package com.easemic.app.feature.download

/**
 * A36 review M2 — the one question every Detail surface actually wants to ask
 * about a download job: **does it own pages on this phone right now?**
 *
 * It used to be spelled `state == FINISH` in four separate places
 * (`DetailSwitchState.from`, `CloudDetailReadPreference.preferLocal`,
 * `DetailDownloadControl.present`, and the Detail's delete guard). That was
 * true while a SEALED job never had a Library row. A36 made it false: the GONE
 * truncation wind-down of an `intentLocal` job commits its k pages as an
 * INCOMPLETE row, so the shelf shows a readable comic while every FINISH-only
 * check still calls it "Not downloaded" — offers Read ONLINE against the
 * source that just 404'd twice, and puts "Delete download?" behind a glyph
 * that says the comic was never downloaded.
 *
 * A SEALED job needs BOTH extra facts:
 *  - [intentLocal]: a cloud-only fetch's pages were never meant for the shelf.
 *  - [hasLibraryRow]: the commit is what makes them reachable. Intent alone
 *    can be true for a job whose commit never happened (or whose row was
 *    deleted), and claiming "on device" for that would be the same lie in the
 *    other direction.
 *
 * FINISH needs neither — it is the commit, by definition.
 */
fun downloadJobOwnsLocalPages(
    state: DownloadJobState?,
    intentLocal: Boolean = false,
    hasLibraryRow: Boolean = false,
): Boolean = when (state) {
    DownloadJobState.FINISH -> true
    DownloadJobState.SEALED -> intentLocal && hasLibraryRow
    else -> false
}

/**
 * [downloadJobOwnsLocalPages] for a whole job row, which carries its own
 * intent. Deliberately a distinct name rather than an overload: `(null,
 * hasLibraryRow = …)` would be ambiguous between the two, and the compiler
 * would only say so at the call site that got it wrong.
 */
fun jobOwnsLocalPages(job: DownloadJob?, hasLibraryRow: Boolean): Boolean =
    downloadJobOwnsLocalPages(
        state = job?.state,
        intentLocal = job?.intentLocal == true,
        hasLibraryRow = hasLibraryRow,
    )
