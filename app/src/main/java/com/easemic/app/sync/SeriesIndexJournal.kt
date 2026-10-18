package com.easemic.app.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

/** Bucket-scoped write-ahead intent. Production keeps this in filesDir, never the evictable cache. */
class SeriesIndexJournal(private val directory: File? = null, private val clockMs: () -> Long = System::currentTimeMillis) {
    data class Pending(val revision: String, val generation: Int, val collectedAtMs: Long)
    private var memory = JSONObject()

    fun dirtyIds(): Set<String> = synchronized(LOCK) {
        val state = read()
        state.keys().asSequence().filter { state.getJSONObject(it).has("revision") }.toSet()
    }

    fun mark(id: String, generation: Int, collectedAtMs: Long): Pending = synchronized(LOCK) {
        val state = read()
        val row = state.optJSONObject(id) ?: JSONObject()
        val pending = Pending(UUID.randomUUID().toString(), generation, collectedAtMs)
        row.put("revision", pending.revision).put("generation", generation).put("collectedAtMs", collectedAtMs)
        state.put(id, row)
        write(state)
        pending
    }

    fun pending(id: String): Pending? = synchronized(LOCK) { decode(read().optJSONObject(id)) }

    fun claim(id: String, force: Boolean): Pending? = synchronized(LOCK) {
        val state = read()
        val row = state.optJSONObject(id) ?: return@synchronized null
        val pending = decode(row) ?: return@synchronized null
        val now = clockMs()
        if (row.has("attemptMs")) {
            val elapsed = now - row.getLong("attemptMs")
            val alreadyDrained = row.optString("drainedRevision") == pending.revision
            if (elapsed in 0 until WINDOW_MS && (!force || alreadyDrained)) return@synchronized null
        }
        row.put("attemptMs", now)
        if (force) row.put("drainedRevision", pending.revision)
        write(state)
        pending
    }

    fun complete(id: String, revision: String) = synchronized(LOCK) {
        val state = read()
        val row = state.optJSONObject(id) ?: return@synchronized
        if (row.optString("revision") != revision) return@synchronized
        row.remove("revision")
        row.remove("generation")
        row.remove("collectedAtMs")
        write(state)
    }

    private fun decode(row: JSONObject?): Pending? = row?.takeIf { it.has("revision") }?.let {
        Pending(it.getString("revision"), it.getInt("generation"), it.getLong("collectedAtMs"))
    }

    private fun read(): JSONObject {
        val root = directory ?: return JSONObject(memory.toString())
        val file = File(root, "pending.json")
        return if (file.isFile) JSONObject(file.readText()) else JSONObject()
    }

    private fun write(state: JSONObject) {
        val root = directory
        if (root == null) {
            memory = state
            return
        }
        check(root.isDirectory || root.mkdirs()) { "Cannot create series index journal" }
        val target = File(root, "pending.json")
        val staged = File(root, "pending.tmp")
        FileOutputStream(staged).use { out ->
            out.write(state.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val WINDOW_MS = 30_000L
        private val LOCK = Any()
    }
}
