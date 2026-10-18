package com.easemic.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.easemic.app.feature.bridge.CloudflareOAuthCallbackParser
import com.easemic.app.feature.bridge.CloudflareOAuthCallbackResult
import com.easemic.app.feature.bridge.CloudflareOAuthTrampoline
import com.easemic.app.feature.bridge.DriveRedirectParser

/**
 * Owns the custom-scheme VIEW filter so Custom Tabs bounce into the
 * original [MainActivity] instead of a second instance.
 */
class CloudflareOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #C50: a drive sign-in rides the same trampoline, marked with its kind.
        intent?.dataString?.let(DriveRedirectParser::parse)?.let { drive ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    data = intent.data
                },
            )
            finish()
            return
        }
        when (val parsed = intent?.dataString?.let { CloudflareOAuthCallbackParser.parseResult(it) }) {
            is CloudflareOAuthCallbackResult.Success ->
                startActivity(forwardOAuthToMain(parsed.code, parsed.state))
            is CloudflareOAuthCallbackResult.Failure ->
                startActivity(forwardOAuthFailureToMain(parsed.error, parsed.description))
            null -> Unit
        }
        finish()
    }

    private fun forwardOAuthToMain(code: String, state: String): Intent {
        val forward = CloudflareOAuthTrampoline.forwardOAuthToMain(code, state)
        return Intent(this, MainActivity::class.java).apply {
            addFlags(forward.flags)
            forward.extras.forEach { (key, value) -> putExtra(key, value) }
        }
    }

    private fun forwardOAuthFailureToMain(error: String, description: String?): Intent {
        val forward = CloudflareOAuthTrampoline.forwardOAuthFailureToMain(error, description)
        return Intent(this, MainActivity::class.java).apply {
            addFlags(forward.flags)
            forward.extras.forEach { (key, value) -> putExtra(key, value) }
        }
    }
}
