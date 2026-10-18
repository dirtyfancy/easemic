package com.easemic.app.importing.metadata

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal class BoundedCborReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readMap(): Value.MapValue {
        val value = readValue(depth = 0)
        if (value !is Value.MapValue) throw CborException("CBOR_TOP_LEVEL", unsupported = true)
        if (offset != bytes.size) throw CborException("CBOR_TRAILING")
        return value
    }

    private fun readValue(depth: Int): Value {
        if (depth > MAX_DEPTH) throw CborException("CBOR_NESTING")
        val header = header()
        return when (header.major) {
            0 -> Value.Unsigned(requireSize(header))
            1 -> Value.Negative
            3 -> Value.Text(readText(requireSize(header)))
            4 -> readArray(header, depth)
            5 -> readMap(header, depth)
            else -> throw CborException("CBOR_UNSUPPORTED", unsupported = true)
        }
    }

    private fun readArray(header: Header, depth: Int): Value.ArrayValue {
        val values = mutableListOf<Value>()
        if (header.indefinite) {
            while (!consumeBreak()) {
                if (values.size >= MAX_COLLECTION) throw CborException("CBOR_COLLECTION_LIMIT")
                values += readValue(depth + 1)
            }
        } else {
            val size = requireSize(header)
            requireCollectionLimit(size)
            repeat(size.toInt()) { values += readValue(depth + 1) }
        }
        return Value.ArrayValue(values)
    }

    private fun readMap(header: Header, depth: Int): Value.MapValue {
        val entries = mutableListOf<Pair<String, Value>>()
        if (header.indefinite) {
            while (!consumeBreak()) {
                if (entries.size >= MAX_COLLECTION) throw CborException("CBOR_COLLECTION_LIMIT")
                entries += readMapEntry(depth)
            }
        } else {
            val size = requireSize(header)
            requireCollectionLimit(size)
            repeat(size.toInt()) { entries += readMapEntry(depth) }
        }
        return Value.MapValue(entries)
    }

    private fun readMapEntry(depth: Int): Pair<String, Value> {
        val keyHeader = header()
        if (keyHeader.major != 3 || keyHeader.indefinite) {
            throw CborException("CBOR_KEY", unsupported = true)
        }
        return readText(requireSize(keyHeader)) to readValue(depth + 1)
    }

    private fun readText(size: Long): String {
        if (size > MAX_STRING_BYTES) throw CborException("CBOR_STRING_LIMIT")
        if (size > bytes.size - offset) throw CborException("CBOR_TRUNCATED")
        val end = offset + size.toInt()
        val slice = ByteBuffer.wrap(bytes, offset, size.toInt())
        offset = end
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(slice)
                .toString()
        } catch (_: CharacterCodingException) {
            throw CborException("CBOR_UTF8")
        }
    }

    private fun header(): Header {
        if (offset >= bytes.size) throw CborException("CBOR_TRUNCATED")
        val first = bytes[offset++].toInt() and 0xff
        val major = first ushr 5
        return when (val additional = first and 0x1f) {
            in 0..23 -> Header(major, additional.toLong())
            24 -> Header(major, readUnsigned(1))
            25 -> Header(major, readUnsigned(2))
            26 -> Header(major, readUnsigned(4))
            27 -> Header(major, readUnsigned(8))
            31 -> {
                if (major != 4 && major != 5) {
                    throw CborException("CBOR_UNSUPPORTED", unsupported = true)
                }
                Header(major, size = null)
            }
            else -> throw CborException("CBOR_UNSUPPORTED", unsupported = true)
        }
    }

    private fun readUnsigned(width: Int): Long {
        if (bytes.size - offset < width) throw CborException("CBOR_TRUNCATED")
        var value = 0L
        repeat(width) {
            value = (value shl 8) or (bytes[offset++].toInt() and 0xff).toLong()
        }
        if (value < 0) throw CborException("CBOR_OVERFLOW")
        return value
    }

    private fun consumeBreak(): Boolean {
        if (offset >= bytes.size) throw CborException("CBOR_TRUNCATED")
        if (bytes[offset].toInt() and 0xff != BREAK) return false
        offset += 1
        return true
    }

    private fun requireSize(header: Header): Long =
        header.size ?: throw CborException("CBOR_UNSUPPORTED", unsupported = true)

    private fun requireCollectionLimit(size: Long) {
        if (size > MAX_COLLECTION) throw CborException("CBOR_COLLECTION_LIMIT")
    }

    private data class Header(val major: Int, val size: Long?) {
        val indefinite: Boolean get() = size == null
    }

    internal sealed interface Value {
        data class Unsigned(val value: Long) : Value
        data class Text(val value: String) : Value
        data object Negative : Value
        data class ArrayValue(val values: List<Value>) : Value
        data class MapValue(val entries: List<Pair<String, Value>>) : Value
    }

    internal class CborException(
        val code: String,
        val unsupported: Boolean = false,
    ) : Exception()

    private companion object {
        const val MAX_STRING_BYTES = 8 * 1024L
        const val MAX_COLLECTION = 100_000L
        const val MAX_DEPTH = 16
        const val BREAK = 0xff
    }
}
