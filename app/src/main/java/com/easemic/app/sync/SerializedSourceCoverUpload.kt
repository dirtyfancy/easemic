package com.easemic.app.sync

import com.easemic.app.feature.importing.SerializedSourceCoverFiles
import java.io.File

/** The same validated content address is used by the graph and both cloud upload paths. */
internal data class SerializedSourceCoverUpload(val key: String, val type: ImageContentType) {
    companion object {
        fun fromFile(seriesCloudId: String, file: File): SerializedSourceCoverUpload? {
            val id = seriesCloudId.trim()
            if (id.isEmpty() || !file.isFile || file.length() <= 0L ||
                file.length() > SerializedSourceCoverFiles.MAX_SOURCE_COVER_BYTES
            ) return null
            val type = file.inputStream().use { input ->
                val header = ByteArray(16)
                val count = input.read(header)
                ImageContentType.sniff(if (count <= 0) ByteArray(0) else header.copyOf(count))
            }
            if (type == ImageContentType.UNKNOWN) return null
            return SerializedSourceCoverUpload(
                CloudCollectionKeys.seriesCoverKey(id, AwsSigV4.sha256HexOfFile(file), type.extension),
                type,
            )
        }
    }
}

/** A matching mirror key avoids resolving or contacting the cloud object store. */
internal fun serializedSourceCoverRepairNeeded(currentObjectKey: String?, localObjectKey: String?): Boolean =
    localObjectKey != null && currentObjectKey != localObjectKey
