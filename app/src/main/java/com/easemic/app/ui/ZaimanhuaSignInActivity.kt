package com.easemic.app.ui

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.easemic.app.AppGraph
import com.easemic.app.feature.providers.ZaimanhuaAccount
import com.easemic.app.feature.providers.ZaimanhuaAccountStore
import com.easemic.app.feature.providers.ZaimanhuaSignInFlow
import com.easemic.app.feature.providers.ZaimanhuaSignInStep
import com.easemic.app.net.SourceHttp
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.theme.PrivateComicLibraryTheme
import com.easemic.app.ui.theme.quietShelfPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** The selective WebView cleanup used by sign-out. It never touches other origins. */
object ZaimanhuaWebSession {
    fun clear(
        cookieManager: CookieManager = CookieManager.getInstance(),
        webStorage: WebStorage = WebStorage.getInstance(),
        onComplete: () -> Unit = {},
    ) {
        webStorage.deleteOrigin(MOBILE_ORIGIN)
        val remaining = AtomicInteger(2)
        fun done() {
            if (remaining.decrementAndGet() == 0) {
                cookieManager.flush()
                onComplete()
            }
        }
        cookieManager.setCookie(
            MOBILE_ORIGIN,
            "token=; Max-Age=0; Path=/",
            android.webkit.ValueCallback { done() },
        )
        cookieManager.setCookie(
            ROOT_ORIGIN,
            "token=; Max-Age=0; Path=/; Domain=.zaimanhua.com",
            android.webkit.ValueCallback { done() },
        )
    }

    private const val MOBILE_ORIGIN = "https://m.zaimanhua.com"
    private const val ROOT_ORIGIN = "https://zaimanhua.com"
}

/**
 * Browser-owned 再漫画 sign-in. The password stays in the source site's page;
 * Easemic only captures and verifies the bearer cookie returned by that page.
 */
class ZaimanhuaSignInActivity : ComponentActivity() {
    private lateinit var flow: ZaimanhuaSignInFlow
    private lateinit var dependencies: Dependencies
    private var webView: WebView? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dependencies = testHooks ?: Dependencies.production(applicationContext)
        flow = ZaimanhuaSignInFlow(mobileOrigin = dependencies.mobileOrigin)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        setContent {
            PrivateComicLibraryTheme {
                SignInContent(
                    onClose = {
                        finished = true
                        finish()
                    },
                    onWebViewCreated = { view ->
                        webView = view
                        cookieManager.setAcceptThirdPartyCookies(view, true)
                        enableSourceWebViewJavaScript(view.settings)
                        view.settings.domStorageEnabled = true
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                if (finished) return
                                feedFlow(url, cookieManager.getCookie(dependencies.mobileOrigin))
                            }
                        }
                        view.loadUrl(dependencies.startUrl)
                    },
                )
            }
        }
        // 再漫画's mobile site can set the token after its last page load (script login, client-side routing),
        // so onPageFinished alone missed it on the phone: the screen stayed open on 再漫画's home page holding a
        // valid token. Keep checking while this screen is open; the flow ignores a token it is already
        // verifying or has judged.
        lifecycleScope.launch {
            while (!finished) {
                delay(COOKIE_CHECK_INTERVAL_MS)
                val view = webView ?: continue
                feedFlow(view.url, cookieManager.getCookie(dependencies.mobileOrigin))
            }
        }
    }

    private fun feedFlow(url: String?, cookieHeader: String?) {
        when (val step = flow.onPageFinished(url, cookieHeader)) {
            ZaimanhuaSignInStep.KeepWaiting -> Unit
            is ZaimanhuaSignInStep.Verify -> verify(step.token)
            is ZaimanhuaSignInStep.Finish -> finishWith(step.account)
        }
    }

    private fun verify(token: String) {
        lifecycleScope.launch {
            val errno = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(dependencies.userInfoUrl)
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    dependencies.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) 1 else {
                            val body = response.body?.string().orEmpty()
                            JSONObject(body).optInt("errno", 1)
                        }
                    }
                }.getOrDefault(1)
            }
            val step = flow.onVerification(token, errno)
            if (step is ZaimanhuaSignInStep.Finish) finishWith(step.account)
        }
    }

    private fun finishWith(account: ZaimanhuaAccount) {
        if (finished) return
        finished = true
        dependencies.accountStore.write(account)
        CookieManager.getInstance().flush()
        setResult(RESULT_OK, android.content.Intent().putExtra(SUCCESS_MESSAGE_EXTRA, "Signed in to 再漫画"))
        finish()
    }

    override fun onDestroy() {
        finished = true
        webView?.let { view ->
            view.stopLoading()
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    data class Dependencies(
        val startUrl: String,
        val mobileOrigin: String,
        val userInfoUrl: String,
        val client: OkHttpClient,
        val accountStore: ZaimanhuaAccountStore,
    ) {
        companion object {
            fun production(context: android.content.Context): Dependencies = Dependencies(
                startUrl = ZaimanhuaSignInFlow.START_URL,
                mobileOrigin = "https://m.zaimanhua.com",
                userInfoUrl = ZaimanhuaSignInFlow.USER_INFO_URL,
                client = SourceHttp.newBuilder().build(),
                accountStore = AppGraph.get(context).zaimanhuaAccountStore,
            )
        }
    }

    companion object {
        const val SUCCESS_MESSAGE_EXTRA = "zaimanhua_sign_in_message"
        private const val COOKIE_CHECK_INTERVAL_MS = 1_000L

        @VisibleForTesting
        internal var testHooks: Dependencies? = null
    }
}

@Composable
private fun SignInContent(
    onClose: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
) {
    val palette = quietShelfPalette()
    Surface(color = palette.surface, modifier = Modifier.fillMaxSize()) {
        // Edge to edge (targetSdk 35+): keep the header clear of the status bar, where Close could not be
        // tapped, and the page clear of the navigation bar.
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Sign in to 再漫画",
                color = palette.ink,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            PclOutlinedButton(onClick = onClose) {
                Text("Close")
            }
        }
        Text(
            "You sign in on 再漫画's own page. Easemic never sees your password; it keeps only the sign-in 再漫画 hands back, on this phone.",
            color = palette.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        )
            }
        AndroidView(
            factory = { context -> WebView(context).also(onWebViewCreated) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        }
    }
}
