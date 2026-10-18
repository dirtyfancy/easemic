package com.easemic.app.feature.providers

/** What the host should do after a page load. */
sealed interface EhentaiSignInStep {
    /** Nothing to do; let the user keep interacting with the page. */
    data object KeepWaiting : EhentaiSignInStep

    /**
     * Navigate to [url] to make the server mint (or refuse) `igneous`. The first
     * ex hit after login redirects while `igneous` is being issued, so a
     * not-yet-granted probe is re-loaded after [delayMs] rather than judged a
     * refusal on the spot (#8).
     */
    data class ProbeExhentai(val url: String, val delayMs: Long = 0L) : EhentaiSignInStep

    /** Sign-in resolved. Persist [cookies] with [verdict] and close. */
    data class Finish(
        val cookies: EhentaiSessionCookies,
        val verdict: ExhentaiAccessVerdict,
    ) : EhentaiSignInStep
}

/**
 * The E-Hentai WebView sign-in state machine, mined from FooIbar/EhViewer's
 * `WebViewSignInScreen.kt` and extended with the ExHentai access probe.
 *
 * Adopted from upstream:
 *  - [START_URL] is the forums login page **without** `&CODE=01`. `CODE=01` is
 *    the form-POST endpoint; loading it in a WebView shows no form.
 *  - Success is detected **per page load**, not on a timer. Upstream bridges
 *    Compose's `isLoading` into a flow; we get the same event from
 *    `onPageFinished`, which is what this repo's
 *    [com.easemic.app.ui.CloudflareChallengeActivity]
 *    already does.
 *  - The success predicate is exactly upstream's: `ipb_member_id` **and**
 *    `ipb_pass_hash` present on the e-hentai.org origin.
 *  - Visiting the ex origin once after login is what mints `igneous` (upstream
 *    does this via `getUConfig(SITE_EX)` in `PostLogin.kt`, and even retries it
 *    because the first ex hit redirects while `igneous` is being issued).
 *
 * Where this diverges, and why:
 *  - Upstream *infers* access from whether the ex request succeeds. We read the
 *    two signals directly — `igneous` present and the body non-empty — because
 *    measurement on 2026-09-07 showed a denied account returns HTTP 200 with a
 *    zero-length body and only `yay=louder`. A success/failure check on the
 *    request alone would score that denial as a grant.
 *  - Upstream forces a synthetic Chrome mobile UA to get past Cloudflare. We
 *    leave the WebView's own default UA alone, matching this repo's existing
 *    CF-challenge activity: on Android that default is already a real Chrome
 *    mobile UA, so it clears CF for the same reason without the risk of the
 *    synthetic string drifting from the engine that actually rendered the page.
 *
 * Pure and single-threaded: the host calls [onPageFinished] from the WebView
 * callback on the main thread, one call per page load.
 */
class EhentaiSignInFlow {
    /** How many times the host has been asked to load the ex probe so far. */
    private var probeLoads = 0

    fun onPageFinished(
        url: String?,
        ehentaiCookie: String?,
        exhentaiCookie: String?,
        body: String?,
    ): EhentaiSignInStep {
        val identity = parseEhentaiSessionCookies(ehentaiCookie)
        if (!identity.isSignedIn()) {
            // Either the user has not logged in yet, or the session was lost
            // mid-probe. Both mean: stay on the page and let them continue.
            return EhentaiSignInStep.KeepWaiting
        }

        if (probeLoads == 0) {
            probeLoads = 1
            return EhentaiSignInStep.ProbeExhentai(EXHENTAI_PROBE_URL)
        }

        if (!isProbeUrl(url)) {
            // A page load we did not ask for landed after the probe was
            // issued. Don't re-navigate; wait for the probe itself.
            return EhentaiSignInStep.KeepWaiting
        }

        // The ex origin gets its own cookie read: `igneous` is set on
        // exhentai.org, never on e-hentai.org.
        val probed = parseEhentaiSessionCookies(exhentaiCookie)
        val merged = identity.mergedWith(probed)
        val verdict = judgeExhentaiAccess(cookies = merged, probeBodyLength = body?.length ?: 0)

        if (verdict == ExhentaiAccessVerdict.Granted) {
            return EhentaiSignInStep.Finish(cookies = merged, verdict = verdict)
        }

        // #8: the FIRST ex hit right after login is not the settled post-login
        // page — it redirects (and may serve a blank/challenge body) while the
        // server mints `igneous`. Upstream re-hits ex for exactly this reason.
        // So a not-yet-granted probe is RE-LOADED (after a short delay) up to
        // [MAX_PROBE_LOADS] times before we conclude a genuine refusal. Without
        // this, attempt 1 always closed as NoExhentaiAccess and only a second
        // manual sign-in (igneous already minted) succeeded.
        if (probeLoads < MAX_PROBE_LOADS) {
            probeLoads++
            return EhentaiSignInStep.ProbeExhentai(EXHENTAI_PROBE_URL, delayMs = RETRY_DELAY_MS)
        }

        // Retries exhausted: this is a settled, honest refusal (the owner's
        // measured no-access case — 200 + empty body + yay=louder — lands here).
        return EhentaiSignInStep.Finish(cookies = merged, verdict = verdict)
    }

    private fun isProbeUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return false
        return value.startsWith(EXHENTAI_PROBE_URL) ||
            "$value/" == EXHENTAI_PROBE_URL
    }

    companion object {
        /**
         * No `&CODE=01`: that is the POST target, not the form page.
         */
        const val START_URL = "https://forums.e-hentai.org/index.php?act=Login"

        /** One hit here is what makes the server mint or refuse `igneous`. */
        const val EXHENTAI_PROBE_URL = "https://exhentai.org/"

        /**
         * Total ex probe loads before a not-yet-granted result is taken as a
         * genuine refusal. 1 initial + retries. Kept small so a real no-access
         * account is only delayed a few seconds.
         */
        const val MAX_PROBE_LOADS = 4

        /** Delay before each retry load, giving igneous time to be minted. */
        const val RETRY_DELAY_MS = 1500L
    }
}
