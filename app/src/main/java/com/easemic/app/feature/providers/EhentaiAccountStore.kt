package com.easemic.app.feature.providers

import com.easemic.app.ui.PreferenceMap

/**
 * On-device E-Hentai / ExHentai session. Never sent to Easemic
 * servers, and deliberately excluded from the cloud backup envelope.
 *
 * [cookieHeader] stays the persisted representation so every existing consumer
 * (notably `EhentaiClient.fromAccount`) keeps working unchanged, but it is now
 * always a *normalized* header rebuilt from parsed fields rather than whatever
 * the user pasted. [sessionCookies] is the structured view used to answer the
 * question the blob could not: is `igneous` present?
 *
 * [useExhentai] is no longer a user-facing toggle. ExHentai is a first-class
 * source now, and the origin follows proven access — [exhentaiAccess] being
 * [ExhentaiAccessVerdict.Granted] is the only thing that turns it on.
 */
data class EhentaiAccount(
    val cookieHeader: String = "",
    val useExhentai: Boolean = false,
    val exhentaiAccess: ExhentaiAccessVerdict = ExhentaiAccessVerdict.SignedOut,
) {
    fun normalizedCookie(): String? =
        cookieHeader.trim().takeIf { it.isNotEmpty() }

    fun hasSession(): Boolean = sessionCookies().isSignedIn()

    /** Structured view of [cookieHeader]. */
    fun sessionCookies(): EhentaiSessionCookies = parseEhentaiSessionCookies(cookieHeader)

    /**
     * The exhentai.org origin is used only on a proven grant. The legacy
     * [useExhentai] flag is honoured as a second condition, never as a
     * substitute — a user cannot toggle their way into access that the server
     * has not issued.
     */
    fun effectiveUseExhentai(): Boolean = exhentaiAccess.allowsExhentaiSource()
}

class EhentaiAccountStore(
    private val prefs: PreferenceMap,
) {
    fun read(): EhentaiAccount {
        // Parse-then-renormalize: a legacy pasted blob (or an even older
        // pre-rename value) becomes named fields here, which is the whole
        // migration. Nobody is logged out by the upgrade.
        val raw = SourceKeyMigration.readMigrated(prefs, KEY_COOKIE, LegacyKeys.EHENTAI_COOKIE, null)
        val cookies = parseEhentaiSessionCookies(raw)
        val header = cookies.cookieHeader().orEmpty()
        val legacyToggle = SourceKeyMigration.readMigrated(
            prefs,
            KEY_USE_EXHENTAI,
            LegacyKeys.EHENTAI_USE_EXHENTAI,
            null,
        ) == TRUE_VALUE

        val stored = parseExhentaiAccessVerdict(prefs.getString(KEY_EXHENTAI_ACCESS, null))
        val verdict = when {
            !cookies.isSignedIn() -> ExhentaiAccessVerdict.SignedOut
            // A stored Granted is only credible while igneous is still there.
            // Cookies expire independently of this record, so a grant whose
            // igneous has gone must drop back to Unknown and be re-probed
            // rather than silently pointing the app at a site that will now
            // hand back an empty body.
            stored == ExhentaiAccessVerdict.Granted && !cookies.hasExhentaiGrant() ->
                ExhentaiAccessVerdict.Unknown
            stored == ExhentaiAccessVerdict.SignedOut -> ExhentaiAccessVerdict.Unknown
            else -> stored
        }

        return EhentaiAccount(
            cookieHeader = header,
            useExhentai = legacyToggle,
            exhentaiAccess = verdict,
        )
    }

    /** Persists a harvested session together with the probe's verdict. */
    fun writeSession(cookies: EhentaiSessionCookies, verdict: ExhentaiAccessVerdict) {
        write(
            EhentaiAccount(
                cookieHeader = cookies.cookieHeader().orEmpty(),
                useExhentai = verdict.allowsExhentaiSource(),
                exhentaiAccess = verdict,
            ),
        )
    }

    fun write(account: EhentaiAccount) {
        val cookies = parseEhentaiSessionCookies(account.cookieHeader)
        val header = cookies.cookieHeader().orEmpty()
        val verdict = if (cookies.isSignedIn()) account.exhentaiAccess else ExhentaiAccessVerdict.SignedOut
        prefs.putString(KEY_COOKIE, header)
        // The legacy toggle is its own persisted fact, not a projection of the
        // verdict: it round-trips what was written so an upgraded install keeps
        // the user's old preference readable. It no longer *grants* anything —
        // effectiveUseExhentai() consults the verdict alone — so writing it
        // through is safe, and deriving it from the verdict instead would
        // silently rewrite a fact the caller did not ask to change. A signed-out
        // record has no preference to remember.
        prefs.putString(
            KEY_USE_EXHENTAI,
            if (cookies.isSignedIn() && account.useExhentai) TRUE_VALUE else FALSE_VALUE,
        )
        prefs.putString(KEY_EXHENTAI_ACCESS, verdict.storageValue)
    }

    fun clear() {
        write(EhentaiAccount())
    }

    companion object {
        private const val KEY_COOKIE = EhentaiStoreKeys.EHENTAI_COOKIE_KEY
        private const val KEY_USE_EXHENTAI = EhentaiStoreKeys.EHENTAI_USE_EXHENTAI_KEY
        private const val KEY_EXHENTAI_ACCESS = EhentaiStoreKeys.EXHENTAI_ACCESS_KEY
        private const val TRUE_VALUE = "true"
        private const val FALSE_VALUE = "false"
    }
}
