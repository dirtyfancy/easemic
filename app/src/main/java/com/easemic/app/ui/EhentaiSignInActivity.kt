package com.easemic.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.easemic.app.AppGraph
import com.easemic.app.feature.providers.EhentaiSignInFlow
import com.easemic.app.feature.providers.EhentaiSignInStep
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.ui.SharedPreferencesMap

/**
 * W7: the E-Hentai WebView sign-in — the owner's one-and-only session entry
 * point. Modeled on [CloudflareChallengeActivity]: plain [WebView] under the
 * engine's OWN default User-Agent (Cloudflare flags non-browser UAs, and a
 * cookie minted under any other UA would be rejected right back), JS on,
 * cookies accepted.
 *
 * The user types their credentials into the real forums login page
 * ([EhentaiSignInFlow.START_URL]); the app never sees or stores the password,
 * only the cookies CookieManager harvests. The page-load pipeline is
 * [EhentaiSignInFlow]:
 *  - identity cookies appear on e-hentai.org → the flow issues
 *    [EhentaiSignInStep.ProbeExhentai]; this activity loads it once (the
 *    server mints or refuses `igneous` on that visit);
 *  - the probe page finishing → the body length is read first (via
 *    [WebView.evaluateJavascript]: the 2026-09-07 measurement showed a
 *    refused account gets a 200 with a zero-length body, so the body is the
 *    deciding evidence and must be known BEFORE the flow judges), then fed
 *    to the flow exactly once;
 *  - [EhentaiSignInStep.Finish] → persist session + verdict via
 *    [com.easemic.app.feature.providers.EhentaiAccountStore.writeSession],
 *    turn the ExHentai source's opt-in on for a grant ("enabled by default on
 *    first grant" — [ExhentaiEnableStore.setEnabled] re-checks access
 *    itself), flush CookieManager, finish() back to Settings (whose sibling
 *    row recomposes from the store on ON_RESUME).
 *
 * Launched only internally (Settings ExHentai nav row) — no intent-filter,
 * `exported="false"` in the manifest.
 */
class EhentaiSignInActivity : ComponentActivity() {
    private val flow = EhentaiSignInFlow()
    private var finished = false

    /**
     * The probe page's body evidence, or null while unread. The flow maps a
     * null body to "empty" at Finish time, so this MUST be set before the
     * flow is fed the probe page load — otherwise a granted account would be
     * judged NoAccess on the timing of the JS read alone.
     */
    private var probeBody: String? = null
    private var probeReadInFlight = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (finished) return
                onSignInPageFinished(view, url)
            }
        }

        webView.loadUrl(EhentaiSignInFlow.START_URL)
    }

    private fun onSignInPageFinished(view: WebView, url: String?) {
        // The probe page load is held until its body length is known: the
        // flow's verdict outranks cookies by body evidence, and feeding it
        // early (body still null) would score a granted session as a
        // refusal. Every other page load goes straight to the flow.
        if (isProbeUrl(url)) {
            if (probeReadInFlight) {
                // A re-entrant page-finished while the JS read is still
                // pending must not jump the queue with a null body.
                return
            }
            if (probeBody == null) {
                probeReadInFlight = true
                view.evaluateJavascript(BODY_LENGTH_JS) { result ->
                    probeReadInFlight = false
                    if (finished) return@evaluateJavascript
                    probeBody = bodyEvidenceFrom(result)
                    feedFlow(view, url)
                }
                return
            }
        }
        feedFlow(view, url)
    }

    private fun feedFlow(view: WebView, url: String?) {
        val cookieManager = CookieManager.getInstance()
        val step = flow.onPageFinished(
            url = url,
            ehentaiCookie = cookieManager.getCookie(EHENTAI_ORIGIN),
            exhentaiCookie = cookieManager.getCookie(EhentaiSignInFlow.EXHENTAI_PROBE_URL),
            body = probeBody,
        )
        when (step) {
            EhentaiSignInStep.KeepWaiting -> Unit
            is EhentaiSignInStep.ProbeExhentai -> {
                // Stale body evidence from an earlier probe attempt must not
                // leak into the fresh one.
                probeBody = null
                // #8: a retry probe waits a beat so exhentai.org can finish
                // minting igneous before we read the result again; the first
                // probe loads immediately (delayMs == 0).
                if (step.delayMs > 0L) {
                    view.postDelayed({
                        if (!finished) view.loadUrl(step.url)
                    }, step.delayMs)
                } else {
                    view.loadUrl(step.url)
                }
            }
            is EhentaiSignInStep.Finish -> {
                finished = true
                onSignInFinished(step)
            }
        }
    }

    /**
     * The flow's probe URL family. Mirrors [EhentaiSignInFlow]'s own private
     * check: the ex root (plus trailing-slash form). Any other redirect
     * target stays on the direct path — the flow ignores it anyway
     * ([EhentaiSignInStep.KeepWaiting]) until the probe itself lands.
     */
    private fun isProbeUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return false
        return value.startsWith(EhentaiSignInFlow.EXHENTAI_PROBE_URL) ||
            "$value/" == EhentaiSignInFlow.EXHENTAI_PROBE_URL
    }

    /**
     * Maps the JS result ("12345", "0", "-1", or "null") to the body string
     * the flow judges by length: any positive length becomes a non-empty
     * marker; zero / missing body / unreadable stays null, which the flow
     * scores as the empty body it looks like.
     */
    private fun bodyEvidenceFrom(result: String?): String? =
        result?.removeSurrounding("\"")?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { BODY_NON_EMPTY_MARKER }

    private fun onSignInFinished(step: EhentaiSignInStep.Finish) {
        CookieManager.getInstance().flush()
        val graph = AppGraph.get(applicationContext)
        graph.ehentaiAccountStore.writeSession(step.cookies, step.verdict)
        val prefs = SharedPreferencesMap.from(applicationContext)
        ExhentaiEnableStore(prefs, graph.ehentaiAccountStore).setEnabled(true)
        finish()
    }

    companion object {
        private const val EHENTAI_ORIGIN = "https://e-hentai.org"
        private const val BODY_LENGTH_JS =
            "document.body == null ? -1 : document.body.innerText.length"
        private const val BODY_NON_EMPTY_MARKER = "1"
    }
}