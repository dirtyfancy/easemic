package com.easemic.app.feature.detail

import com.easemic.app.feature.reader.ReaderIncompleteCopy

/**
 * A32 §7 — development-locked English copy for a [DetailSubject.Local].
 * Bans: "Unknown tags", "No metadata available", "Synced!", any count that is
 * not a real count, any affordance a local subject cannot perform.
 */
object LocalDetailCopy {
    const val READ = "Read"

    /**
     * A41: both helpers state the same plain fact — the files live on this
     * device. The old "On this phone." / "Downloaded to this phone." were
     * phone-word jargon; the import/download distinction is carried by the
     * Download switch and the shelf badges, not by this line.
     */
    const val IMPORT_HELPER = "Stored on this device."
    const val DOWNLOAD_HELPER = "Stored on this device."
    const val ITEM_GONE = "This comic is no longer in your library."

    /**
     * [progressOrdinal] is the 0-based page the reader stopped on; the label
     * shows the 1-based page a human counts. Nothing read yet ⇒ plain [READ],
     * so the button never claims progress the row does not have.
     */
    fun readLabel(progressOrdinal: Int): String =
        if (progressOrdinal > 0) "Continue · page ${progressOrdinal + 1}" else READ

    /**
     * Meta-line page count. Carried over from the retired local skin's
     * `pageCountText`: a one-page comic reads "1 page", never "1 pages". The
     * count itself is always the row's real page count (spec §7 ban).
     */
    fun pageCountLabel(pageCount: Int): String =
        if (pageCount == 1) "1 page" else "$pageCount pages"

    /**
     * P-a — a catalog download whose job SEALED short of its pinned count (the
     * D1 GONE verdict). [fetched] is the count really on disk, which is also
     * what the meta line reports; [expected] is what the source advertised
     * before it withdrew the rest. A statement, not an alarm: no "!", no
     * "Failed", nothing to retry — the source is what changed, not the app.
     */
    fun truncatedSealLine(fetched: Int, expected: Int): String =
        "$fetched of $expected pages · " +
            ReaderIncompleteCopy.downloadHint().replaceFirstChar { it.lowercase() }
}

/**
 * #C60: the Read label for a comic, whichever detail shows it. The remote
 * detail of a comic already on this device used to say "Read" while its local
 * detail said "Continue · page 8". The same facts the Read route uses decide it:
 * a local copy (non-null [localProgressOrdinal]) is what Read opens, so its
 * progress wins; otherwise the cloud / online reader's progress.
 */
fun detailReadLabel(localProgressOrdinal: Int?, remoteProgressOrdinal: Int?): String =
    LocalDetailCopy.readLabel(localProgressOrdinal ?: remoteProgressOrdinal ?: 0)
