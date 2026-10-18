package com.easemic.app.sync

import org.json.JSONArray
import org.json.JSONObject

data class ReadingProgressBackupEntry(
    val title: String,
    val pageCount: Int,
    val progressOrdinal: Int,
)

object ReadingProgressBackup {
    fun encode(entries: List<ReadingProgressBackupEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("title", entry.title)
                    .put("pageCount", entry.pageCount)
                    .put("progressOrdinal", entry.progressOrdinal),
            )
        }
        return array.toString()
    }

    fun decode(json: String): List<ReadingProgressBackupEntry> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        ReadingProgressBackupEntry(
                            title = obj.getString("title"),
                            pageCount = obj.getInt("pageCount"),
                            progressOrdinal = obj.getInt("progressOrdinal"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
