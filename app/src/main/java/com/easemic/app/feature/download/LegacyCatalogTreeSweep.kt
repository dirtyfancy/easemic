package com.easemic.app.feature.download

import java.io.File

/**
 * Re-review r2 BLOCKING-1 (2026-09-20): #C20 HIGH-1 gave every non-official
 * catalog tree a namespace level (`<root>/<ns>/<item>/r<rev>`). Trees written
 * by an older build sit at the legacy shape (`<root>/<item>/r<rev>`) and are
 * NEVER MOVED — the Library's `pages.documentUri` rows are absolute `file://`
 * paths into them, and round 2's move broke exactly those rows (the reader
 * opened the stored URI, got FILES_GONE, and the shelf entry vanished).
 *
 * What a legacy tree needs is a lifecycle, not a new address. Readers resolve
 * both shapes ([resolveLocalCatalogPagesForIngest]); the delete verbs stay
 * namespaced-only; and this sweep, once per start with the whole picture
 * (every job row + every Library page directory), reclaims a legacy revision
 * dir that nothing references any more: no LIVE Library page row (current
 * revision, location not UNAVAILABLE) lives inside it, no job row other than a
 * NONE one derives it (legacy OR namespaced derivation), and it is not an
 * ancestor of anything referenced (an OPDS entry id can be literally `r1`,
 * which puts a `<ns>/r1` item level at the depth this walk inspects).
 * Marker-free (#C13: a burnt seed is a bug that hides); a run with nothing to
 * do says nothing.
 *
 * Only the `opds-downloads` root is walked: the three official roots never
 * had a legacy shape (their trees are two-level by design).
 *
 * @return the number of legacy revision dirs reclaimed.
 */
fun reclaimOrphanLegacyCatalogTrees(
    filesDir: File,
    jobs: List<DownloadJob>,
    referencedPageDirectories: Set<File>,
    log: (String) -> Unit = {},
): Int {
    val root = File(filesDir, "opds-downloads").absoluteFile
    if (!root.isDirectory) return 0
    val referenced = HashSet<File>(referencedPageDirectories.map { it.absoluteFile })
    // r3 BLOCKING-1: a NONE row does not vouch. Five verbs write NONE (cancel,
    // discardCloudOnlyJob, dismissAttentionRow(FAILED), deleteFinished,
    // releaseLocalPagesAfterCloud) and none removes the row. A NONE row is a
    // finished attempt: whatever it still owns on the shelf is vouched for by
    // the LIVE page rows in [referencedPageDirectories] (dismissAttentionRow
    // deliberately keeps both), and what it does NOT own is exactly the legacy
    // orphan this sweep exists to take — the delete verbs unlink only the
    // NAMESPACED tree (deleteCatalogDownloadRevision). While NONE rows vouched,
    // a deleted legacy comic's tree was vetoed for ever (r4 re-review M1).
    jobs.filter { it.state != DownloadJobState.NONE }.forEach { job ->
        referenced += catalogDownloadRevisionDirectory(filesDir, job.storageIdentity, job.downloadRevision).absoluteFile
        legacyCatalogDownloadRevisionDirectory(filesDir, job.storageIdentity, job.downloadRevision)?.let { referenced += it.absoluteFile }
    }
    fun isReferencedOrAncestor(candidate: File): Boolean {
        val prefix = candidate.path + File.separator
        return referenced.any { it == candidate || it.path.startsWith(prefix) }
    }
    val revisionName = Regex("r\\d+")
    var reclaimed = 0
    var failed = 0
    // Depth 2 only: `<root>/<X>/r<N>`. A namespaced tree's depth-2 entry is an
    // `<item>` level, which is a candidate here only when the id looks like a
    // revision — and then it is an ancestor of the live tree, so it is kept.
    root.listFiles()?.filter { it.isDirectory }?.forEach { level ->
        level.listFiles()?.filter { it.isDirectory && revisionName.matches(it.name) }?.forEach { candidate ->
            val dir = candidate.absoluteFile
            if (isReferencedOrAncestor(dir)) return@forEach
            runCatching {
                if (dir.deleteRecursively()) {
                    reclaimed += 1
                    // Bounded to the one `<X>` level; the root is never a candidate.
                    level.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
                } else {
                    failed += 1
                }
            }.onFailure { failed += 1 }
        }
    }
    // Lengths and counts only — an item level's name IS the acquisition URL.
    if (reclaimed > 0 || failed > 0) log("legacy catalog tree sweep reclaimed=$reclaimed failed=$failed rows=${jobs.size}")
    return reclaimed
}
