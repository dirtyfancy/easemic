package com.easemic.app.sync

/** Magic-byte sniffing so plaintext cloud objects carry honest extensions and MIME types. */
enum class ImageContentType(val extension: String, val mime: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp"),
    UNKNOWN("bin", "application/octet-stream"),
    ;

    companion object {
        fun sniff(bytes: ByteArray): ImageContentType = when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> PNG
            bytes.size >= 6 && bytes.copyOfRange(0, 3).contentEquals("GIF".toByteArray()) -> GIF
            bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> WEBP
            else -> UNKNOWN
        }
    }
}
