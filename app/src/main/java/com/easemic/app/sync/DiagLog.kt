package com.easemic.app.sync

import android.util.Log
import com.easemic.app.importing.opds.OpdsCatalogClient

/**
 * Android Log plus the on-device trace file (#C45 D4). Install the sink once from AppGraph.
 *
 * #C52 round 3 (review REJECT B2/B3/H4): this is a **sink-level scrub**, not just
 * call-site hygiene. Every message, and every throwable's message (including up to
 * 3 levels of its `cause` chain, see [w]/[e]), is passed through
 * [OpdsCatalogClient.redactUrl] (which also strips the `_api_opds_`-mangled on-disk
 * form) before it reaches [android.util.Log] or [fileSink].
 *
 * #C52 round 4 (re-review R1) — what this scrub does and does NOT cover. The
 * round-3 wording claimed it made the catalog key "unleakable even from a call site
 * that forgot to redact"; that was false and is corrected here. The scrub covers
 * exactly two things: calls that go through [DiagLog], and the on-device trace file
 * (which has [fileSink] as its single writer, installed once in `AppGraph`). A call
 * site that reaches for [android.util.Log] directly bypasses it completely and hands
 * its message and its throwable to Android's Log unscrubbed. What keeps those call
 * sites honest is SOURCE GUARDS, not this class:
 * `LogRedactionSourceGuardTest.noProductionOrProbeLogLineInterpolatesAnAcquisitionUrlItemIdNamespaceOrPath`
 * (content: no raw acquisition URL / item id / namespace / path in any log line) and
 * `LogRedactionSourceGuardTest.noThreeArgLogWarnOrErrorCallInProductionBypassesDiagLogsScrub`
 * (routing: the 3-argument `Log.w/e(tag, msg, throwable)` form is banned in
 * `app/src/main`, since that is the one shape whose leak this class cannot see).
 * Cost of the scrub itself is a small, bounded number of extra regex passes per call
 * (µs-scale), which no [DiagLog] call site is anywhere near a rate to notice.
 *
 * #C52 round 5 (round-4 review LOW-4): the round-4 wording named
 * [com.easemic.app.feature.download.SourcePacingGate] as this
 * scrub's highest-frequency caller. It is not a caller at all — it imports and calls
 * [android.util.Log] directly (`SourcePacingGate.kt:3, 96, 99`), so its two
 * `PageFetch` lines per paced page fetch (the app's highest-rate log lines) never
 * reach this scrub, and never reach [fileSink] either — they are logcat-only by
 * design (#C17 device leg), and their namespace is redacted at the call site with
 * [redactNamespace].
 */
object DiagLog {
    @Volatile var fileSink: ((String) -> Unit)? = null

    fun i(tag: String, msg: String) {
        val safeMsg = OpdsCatalogClient.redactUrl(msg)
        Log.i(tag, safeMsg)
        fileSink?.invoke("$tag $safeMsg")
    }

    fun d(tag: String, msg: String) {
        val safeMsg = OpdsCatalogClient.redactUrl(msg)
        Log.d(tag, safeMsg)
        fileSink?.invoke("$tag $safeMsg")
    }

    /**
     * #C52 round 3 B3: never hand the raw [Throwable] to `Log.w(tag, msg, t)` — Android's
     * Log prints `t`'s own message/stack verbatim, and an OPDS throw site (e.g. a
     * `FileNotFoundException` on a mangled acquisition path) can carry the raw catalog
     * key in that message. Log the redacted class+message (plus up to 3 levels of its
     * `cause` chain, #C52 round 4 R7 — a "Caused by" chain is exactly where a wrapped
     * `IOException` re-surfaces the same raw path/URL one level down) as one line, then
     * the stack FRAMES only as a second line — a `ClassName.method(File.java:123)` frame
     * never carries a URL, so the trace stays diagnosable without the raw message riding
     * along. A throwable call to [w]/[e] therefore emits exactly two logcat entries.
     */
    fun w(tag: String, msg: String, t: Throwable? = null) {
        val safeMsg = OpdsCatalogClient.redactUrl(msg)
        val safeT = t?.let { safeThrowableSummary(it) }
        if (t == null) {
            Log.w(tag, safeMsg)
        } else {
            Log.w(tag, "$safeMsg $safeT")
            Log.w(tag, t.stackTrace.joinToString("\n") { frame -> "  at $frame" })
        }
        fileSink?.invoke("$tag $safeMsg" + (safeT?.let { " $it" } ?: ""))
    }

    /** #C52 round 3 B3: same reasoning as [w]. */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val safeMsg = OpdsCatalogClient.redactUrl(msg)
        val safeT = t?.let { safeThrowableSummary(it) }
        if (t == null) {
            Log.e(tag, safeMsg)
        } else {
            Log.e(tag, "$safeMsg $safeT")
            Log.e(tag, t.stackTrace.joinToString("\n") { frame -> "  at $frame" })
        }
        fileSink?.invoke("$tag $safeMsg" + (safeT?.let { " $it" } ?: ""))
    }

    /**
     * `"ClassName: redactedMessage"` for [t], then the same shape for up to 3 levels
     * of its `cause` chain, each prefixed `" Caused by: "` -- #C52 round 4 R7: a
     * wrapped exception's cause is exactly where a raw acquisition URL/path can
     * re-surface one level down (e.g. an `IOException` wrapping the
     * `FileNotFoundException` that names the mangled on-disk path), and the
     * pre-round-4 code dropped the cause chain entirely, silently losing that
     * diagnostic. Capped at 3 levels so a pathological cause cycle/depth cannot
     * blow up a single log line.
     */
    private fun safeThrowableSummary(t: Throwable): String {
        val head = "${t.javaClass.simpleName}: ${OpdsCatalogClient.redactUrl(t.message ?: "")}"
        val chain = StringBuilder()
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 3) {
            chain.append(" Caused by: ")
                .append(cause.javaClass.simpleName)
                .append(": ")
                .append(OpdsCatalogClient.redactUrl(cause.message ?: ""))
            cause = cause.cause
            depth += 1
        }
        return head + chain
    }
}
