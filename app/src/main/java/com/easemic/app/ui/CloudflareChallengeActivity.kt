package com.easemic.app.ui

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.easemic.app.AppGraph
import com.easemic.app.feature.providers.NhentaiCookie
import com.easemic.app.feature.providers.hasCfClearance
import com.easemic.app.feature.providers.parseCfClearance
import com.easemic.app.importing.nhentai.NhentaiClient
import com.easemic.app.work.DownloadPumpWorker
import kotlinx.coroutines.launch

/** Shared browser setting used by source-owned WebViews. */
internal fun enableSourceWebViewJavaScript(settings: WebSettings) {
    settings.javaScriptEnabled = true
}

/**
 * One-tap Cloudflare-challenge solve for nhentai (T2a). Hosts a plain
 * [WebView] on [NhentaiClient.DEFAULT_API_ORIGIN] under the WebView's OWN
 * default (real-browser) User-Agent — Cloudflare flags non-browser UAs, so a
 * `cf_clearance` minted under any other UA would be rejected right back; this
 * activity deliberately never touches `settings.userAgentString`.
 *
 * Once the challenge clears, [CookieManager] carries a `cf_clearance` cookie
 * for the origin. That value, paired with the exact UA that solved it (the
 * cookie is UA-bound, see [NhentaiCookie]), is persisted via
 * [AppGraph.nhentaiCookieStore]; the pump's parked ABUSE_STOP job(s) then
 * have their resume window cleared
 * ([com.easemic.app.feature.download.RoomDownloadJobStore.clearResumeWindowForAbuseStop])
 * so the very next kick treats them as runnable — a plain kick alone would
 * not, since the parked job stays outside the 1h resume window otherwise —
 * and finally the pump is kicked so it immediately resends the fresh cookie.
 *
 * Launched only internally (in-app banner tap, or the pump's "Solve"
 * notification action) — no intent-filter, `exported="false"` in the
 * manifest.
 */
class CloudflareChallengeActivity : ComponentActivity() {
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        enableSourceWebViewJavaScript(webView.settings)
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (resolved) return
                val cookieString = cookieManager.getCookie(NhentaiClient.DEFAULT_API_ORIGIN)
                if (hasCfClearance(cookieString)) {
                    resolved = true
                    onChallengeSolved(cookieString, webView.settings.userAgentString)
                }
            }
        }

        webView.loadUrl("${NhentaiClient.DEFAULT_API_ORIGIN}/")
    }

    private fun onChallengeSolved(cookieString: String?, userAgent: String) {
        val clearance = parseCfClearance(cookieString) ?: return
        CookieManager.getInstance().flush()

        val appGraph = AppGraph.get(applicationContext)
        appGraph.nhentaiCookieStore.write(
            NhentaiCookie(
                cfClearance = clearance,
                userAgent = userAgent,
                expiresAtMs = System.currentTimeMillis() + COOKIE_TTL_MS,
            ),
        )

        // The store write above is a plain (synchronous) SharedPreferences
        // write, but clearing the parked job(s)' resume window is a Room
        // suspend call — it must land BEFORE the kick below, or the pump's
        // very next tick would still see the stale resume window and skip
        // them straight back over. Both run on lifecycleScope so finish()
        // only happens once they're done.
        lifecycleScope.launch {
            appGraph.downloadJobStore.clearResumeWindowForAbuseStop()
            DownloadPumpWorker.kick(applicationContext)
            Toast.makeText(
                this@CloudflareChallengeActivity,
                "Cloudflare check solved — resuming downloads",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    companion object {
        private const val COOKIE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
