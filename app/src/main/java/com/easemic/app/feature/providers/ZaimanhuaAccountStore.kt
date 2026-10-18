package com.easemic.app.feature.providers

import com.easemic.app.ui.PreferenceMap

object ZaimanhuaStoreKeys {
    const val TOKEN = "zaimanhua_token_v1"
    const val EXP_SECONDS = "zaimanhua_token_exp_v1"
}

data class ZaimanhuaAccount(
    val token: String = "",
    val expSeconds: Long = 0L,
) {
    fun isSignedIn(nowMs: Long = System.currentTimeMillis()): Boolean =
        token.isNotBlank() && expSeconds > nowMs / 1000L && expSeconds - nowMs / 1000L > DAY_SECONDS

    override fun toString(): String =
        "ZaimanhuaAccount(expSeconds=$expSeconds, signedIn=${isSignedIn()})"
}

private const val DAY_SECONDS = 24L * 60L * 60L

class ZaimanhuaAccountStore(
    private val prefs: PreferenceMap,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun read(): ZaimanhuaAccount {
        val token = prefs.getString(ZaimanhuaStoreKeys.TOKEN, null).orEmpty()
        val exp = prefs.getString(ZaimanhuaStoreKeys.EXP_SECONDS, null)?.toLongOrNull() ?: 0L
        val account = ZaimanhuaAccount(token, exp)
        return account.takeIf { it.isSignedIn(clock()) } ?: ZaimanhuaAccount()
    }

    fun write(account: ZaimanhuaAccount) {
        prefs.putString(ZaimanhuaStoreKeys.TOKEN, account.token)
        prefs.putString(ZaimanhuaStoreKeys.EXP_SECONDS, account.expSeconds.toString())
    }

    fun clear() {
        prefs.putString(ZaimanhuaStoreKeys.TOKEN, "")
        prefs.putString(ZaimanhuaStoreKeys.EXP_SECONDS, "0")
    }
}
