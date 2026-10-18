package com.easemic.app.acceptance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/**
 * Debug-only host so an instrumented test can show an override graph on a real activity.
 * Release builds do not include this class.
 */
class E15ComposeHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = content ?: return finish()
        setContent { content() }
    }

    companion object {
        var content: (@Composable () -> Unit)? = null
    }
}
