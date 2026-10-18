package com.easemic.app.feature.history

import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.ui.PreferenceMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical dedup key for a history row: the same gallery reached via
 * different surfaces (Browse feed id, Activity-row acquisition-url-as-id,
 * a cloud-shelf copy's upstream source) must collapse to one row. Never
 * dedups by title — two different sources with the same title stay two
 * entries.
 */
private val KNOWN_SERIALIZED_HISTORY_PROVIDERS = setOf("zaimanhua", "jm")

fun HistoryEntry.isSerializedHistory(): Boolean =
    serialized || providerId in KNOWN_SERIALIZED_HISTORY_PROVIDERS

private fun serializedHistoryKey(entry: HistoryEntry): String =
    "serialized:${entry.providerId.length}:${entry.providerId}:${entry.id.length}:${entry.id}"

fun HistoryEntry.historyDedupKey(): String =
    if (isSerializedHistory()) {
        serializedHistoryKey(this)
    } else {
        galleryDedupKey(upstreamAcquisitionUrl) ?: galleryDedupKey(acquisitionUrl) ?: id
    }

fun historyScopedKey(entry: HistoryEntry): String =
    if (entry.isSerializedHistory()) {
        serializedHistoryKey(entry)
    } else {
        "gallery:${entry.historyDedupKey()}"
    }

/**
 * A21: the newest visit wins every field, except that a null or blank
 * [HistoryEntry.coverUrl] / a null [HistoryEntry.pageCount] never overwrites
 * a value an earlier visit of the same gallery (same [historyDedupKey], any
 * surface id) already knew. Root cause: a cloud card opened before its thumb
 * was cached recorded `coverUrl = null` (or, in some paths, `""`) and stayed
 * that way forever.
 *
 * Coordinator must-fix follow-up: a blank [HistoryEntry.coverUrl] is
 * normalized to null even on a FIRST visit (no [previous] to fall back to)
 * — otherwise the literal empty string gets persisted to the stored JSON
 * (defeating [record]'s "omit an unknown coverUrl" invariant) and only
 * looks clean because [BrowseHistoryStore.read]'s own
 * `optString(...).takeIf { isNotBlank() }` happens to mask it back to null
 * on the way out.
 */
fun mergeHistoryEntry(incoming: HistoryEntry, previous: HistoryEntry?): HistoryEntry {
    if (previous == null) {
        return incoming.copy(
            coverUrl = incoming.coverUrl?.takeIf { it.isNotBlank() },
            upstreamAcquisitionUrl = incoming.upstreamAcquisitionUrl?.takeIf { it.isNotBlank() },
        )
    }
    return incoming.copy(
        coverUrl = incoming.coverUrl?.takeIf { it.isNotBlank() } ?: previous.coverUrl,
        pageCount = incoming.pageCount ?: previous.pageCount,
        // A35: a visit recorded before A21 existed carries no upstream URL.
        // Letting it overwrite a known one would both lose the gallery link
        // and flip the row's historyDedupKey back to the "cloud:<uuid>"
        // fallback, re-opening the duplicate-id hole this merge closes.
        upstreamAcquisitionUrl = incoming.upstreamAcquisitionUrl?.takeIf { it.isNotBlank() }
            ?: previous.upstreamAcquisitionUrl,
        lastReadChapterId = incoming.lastReadChapterId ?: previous.lastReadChapterId,
        lastReadChapterLabel = incoming.lastReadChapterLabel ?: previous.lastReadChapterLabel,
        lastReadPageOrdinal = incoming.lastReadPageOrdinal ?: previous.lastReadPageOrdinal,
    )
}

/**
 * A35: collapse stored rows that describe the same gallery.
 *
 * Two rows are the same gallery when their [historyDedupKey]s match OR their
 * [HistoryEntry.id]s match. Matching on the dedup key alone was not enough:
 * a cloud-shelf item visited both before and after A21 introduced
 * [HistoryEntry.upstreamAcquisitionUrl] stores two rows under one id
 * (`cloud_collection:<uuid>`) whose keys differ — the newer resolves to
 * `nhentai://<gid>`, the older falls back to `cloud:<uuid>`. `distinctBy`
 * on the key kept both, and `HistoryScreen`'s `LazyColumn(key = { it.id })`
 * then threw `IllegalArgumentException: Key ... was already used`.
 *
 * [rows] must be newest-first. The newest row of a gallery wins every field
 * except those [mergeHistoryEntry] backfills from the older rows, so an
 * `upstreamAcquisitionUrl` / `coverUrl` / `pageCount` only an older visit
 * knew survives the collapse.
 *
 * The relation is closed transitively: an absorbed row claims BOTH of its
 * halves for the winning slot, not just the half it matched on. The pre-A21
 * twin is often the only bridge between a gallery's other two surfaces (the
 * post-A21 cloud row keyed `nhentai://<gid>` and the acquisition-url-as-id
 * reopen row keyed `cloud:<uuid>` share nothing), so dropping its unmatched
 * half would leave that gallery on two rows for some visit orders.
 *
 * Postcondition (the invariant `HistoryScreen` depends on): the result has
 * unique ids AND unique dedup keys.
 */
private fun historyIdentityAliases(entry: HistoryEntry): List<String> =
    if (entry.isSerializedHistory()) {
        listOf(historyScopedKey(entry))
    } else {
        listOf(entry.id, entry.historyDedupKey())
    }

internal fun collapseHistoryRows(rows: List<HistoryEntry>): List<HistoryEntry> {
    // Slot index per gallery; null marks a slot folded into an earlier one.
    val slots = mutableListOf<HistoryEntry?>()
    val slotByKey = mutableMapOf<String, Int>()

    // Every id and every dedup key ever seen for a gallery must keep pointing
    // at that gallery's slot -- including the half an absorbed row did NOT
    // match on, which is what makes the relation transitive.
    fun claim(slot: Int, entry: HistoryEntry) {
        historyIdentityAliases(entry).forEach { slotByKey[it] = slot }
    }

    for (row in rows) {
        val matches = historyIdentityAliases(row).mapNotNull(slotByKey::get)
            .distinct()
            .sorted()
        if (matches.isEmpty()) {
            slots.add(row)
            claim(slots.lastIndex, row)
            continue
        }
        // Lowest slot == the newest gallery row seen so far; it absorbs the rest.
        val target = matches.first()
        for (other in matches.drop(1)) {
            val victim = slots[other] ?: continue
            slots[target] = mergeHistoryEntry(slots[target]!!, victim)
            slots[other] = null
            slotByKey.filterValues { it == other }.keys.toList()
                .forEach { slotByKey[it] = target }
        }
        slots[target] = mergeHistoryEntry(slots[target]!!, row)
        claim(target, row)
        claim(target, slots[target]!!)
    }
    return slots.filterNotNull()
}

/** Tiny prefs-backed visit log for Browse remote Detail opens. */
class BrowseHistoryStore(
    private val prefs: PreferenceMap,
    private val maxEntries: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun read(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val parsed = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        HistoryEntry(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            secondaryLine = obj.optString("secondary"),
                            coverUrl = obj.optString("coverUrl").takeIf { it.isNotBlank() },
                            acquisitionUrl = obj.optString("acquisitionUrl").takeIf { it.isNotBlank() },
                            providerId = obj.optString("providerId", ""),
                            serialized = obj.optBoolean("serialized", false) ||
                                obj.optString("providerId", "") in KNOWN_SERIALIZED_HISTORY_PROVIDERS,
                            pageStreamUrl = obj.optString("pageStreamUrl").takeIf { it.isNotBlank() },
                            pageCount = if (obj.has("pageCount")) obj.optInt("pageCount") else null,
                            sourceName = obj.optString("sourceName", ""),
                            visitedAtEpochMs = if (obj.has("visitedAt")) obj.optLong("visitedAt") else null,
                            upstreamAcquisitionUrl = obj.optString("upstreamAcquisitionUrl")
                                .takeIf { it.isNotBlank() },
                            lastReadChapterId = obj.optString("lastReadChapterId")
                                .takeIf { it.isNotBlank() },
                            lastReadChapterLabel = obj.optString("lastReadChapterLabel")
                                .takeIf { it.isNotBlank() },
                            lastReadPageOrdinal = if (obj.has("lastReadPageOrdinal")) {
                                obj.optInt("lastReadPageOrdinal")
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
            // List is newest-first (record() prepends). Collapsing keeps the
            // newest row per gallery and backfills the fields only an older
            // visit knew. Cleans up legacy phones with duplicate rows for one
            // gallery without needing an explicit migration step.
            collapseHistoryRows(parsed)
                // Belt and braces: collapseHistoryRows already guarantees
                // unique ids, but HistoryScreen's LazyColumn crashes the app
                // if that ever stops holding, so never hand it a repeat.
                .distinctBy(::historyScopedKey)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(entry: HistoryEntry) {
        val stamped = if (entry.visitedAtEpochMs == null) {
            entry.copy(visitedAtEpochMs = clock())
        } else {
            entry
        }
        val key = stamped.historyDedupKey()
        val existing = read()
        // A35: the row being replaced may match on the id alone — a post-A21
        // visit of a cloud item carries an upstream URL its stored pre-A21
        // row lacks, so their dedup keys differ while their ids are equal.
        // Matching on the key alone left the old row in place and prepended a
        // second row with the same id (the LazyColumn duplicate-key crash).
        val (superseded, rest) = existing.partition {
            it.historyDedupKey() == key ||
                (!stamped.isSerializedHistory() && !it.isSerializedHistory() && it.id == stamped.id)
        }
        // `superseded` is newest-first, so each fold backfills only what is
        // still unknown, from the most recent visit that knew it.
        // Seeded with the previous-less merge so a blank coverUrl / upstream URL
        // is normalized to null even on a first-ever visit (never persisted as "").
        val merged = superseded.fold(mergeHistoryEntry(stamped, null)) { acc, previous ->
            mergeHistoryEntry(acc, previous)
        }
        val current = rest.toMutableList()
        current.add(0, merged)
        val trimmed = current.take(maxEntries)
        val array = JSONArray()
        trimmed.forEach { item ->
            val obj = JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("secondary", item.secondaryLine)
            item.coverUrl?.let { obj.put("coverUrl", it) }
            item.acquisitionUrl?.let { obj.put("acquisitionUrl", it) }
            if (item.providerId.isNotBlank()) {
                obj.put("providerId", item.providerId)
            }
            if (item.isSerializedHistory()) {
                obj.put("serialized", true)
            }
            item.pageStreamUrl?.let { obj.put("pageStreamUrl", it) }
            item.pageCount?.let { obj.put("pageCount", it) }
            if (item.sourceName.isNotBlank()) {
                obj.put("sourceName", item.sourceName)
            }
            item.visitedAtEpochMs?.let { obj.put("visitedAt", it) }
            item.upstreamAcquisitionUrl?.let { obj.put("upstreamAcquisitionUrl", it) }
            item.lastReadChapterId?.let { obj.put("lastReadChapterId", it) }
            item.lastReadChapterLabel?.let { obj.put("lastReadChapterLabel", it) }
            item.lastReadPageOrdinal?.let { obj.put("lastReadPageOrdinal", it) }
            array.put(obj)
        }
        prefs.putString(KEY, array.toString())
    }

    fun exportRaw(): String = prefs.getString(KEY, null) ?: "[]"

    fun importRaw(json: String) {
        prefs.putString(KEY, json.ifBlank { "[]" })
    }

    companion object {
        private const val KEY = "browse_history_v1"
    }
}
