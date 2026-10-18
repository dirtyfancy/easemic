package com.easemic.app.feature.providers

/**
 * Whether this device's session may use exhentai.org.
 *
 * ExHentai access is a per-account, server-side permission. It is not a setting
 * the app can turn on, not a function of account age, and not something a
 * correct request can talk its way into. Measured 2026-09-07 against the
 * owner's own multi-year account (evidence:
 * `docs/superpowers/evidence/2026-09-07-exhentai-account-access.md`):
 * exhentai.org answered **200 with a zero-length body** and set **`yay=louder`**
 * while never issuing `igneous`.
 *
 * So the post-login probe *is* the access check, and there are exactly three
 * honest answers plus "not asked yet". Every one of them is stated to the user
 * as itself — the failure this replaces was an access-less account rendering as
 * a generic empty gallery, which sends the user hunting for a bug in the app.
 */
enum class ExhentaiAccessVerdict(val storageValue: String) {
    /** No usable login. Nothing can be said about ExHentai yet. */
    SignedOut("signed_out"),

    /** Signed in, but never probed. Deliberately not a denial. */
    Unknown("unknown"),

    /** Signed in and exhentai.org refused: no `igneous`, or an empty body. */
    NoExhentaiAccess("no_access"),

    /** Signed in, real `igneous`, and exhentai.org served actual content. */
    Granted("granted"),
    ;

    /** Only a proven grant may expose the ExHentai source. */
    fun allowsExhentaiSource(): Boolean = this == Granted

    /** English, user-facing, and specific about which of the four states this is. */
    fun statusMessage(): String = when (this) {
        SignedOut -> "Not signed in. Sign in to E-Hentai to check ExHentai access."
        Unknown -> "Signed in. ExHentai access has not been checked yet."
        NoExhentaiAccess ->
            "This account does not have ExHentai access. ExHentai grants it " +
                "server-side; the app cannot enable it."
        Granted -> "Signed in with ExHentai access."
    }
}

/**
 * Applies the verdict rules to a cookie set and the result of the exhentai.org
 * probe. [probeBodyLength] is the probe response's body length, or null when no
 * probe has run.
 *
 * Order matters: a zero-length body outranks a real-looking `igneous`, because
 * the measured refusal *does* sometimes carry cookies. Trusting the cookie over
 * the body is precisely how a no-access account gets mistaken for a working one.
 */
fun judgeExhentaiAccess(
    cookies: EhentaiSessionCookies,
    probeBodyLength: Int?,
): ExhentaiAccessVerdict = when {
    !cookies.isSignedIn() -> ExhentaiAccessVerdict.SignedOut
    // Never probed. A stored igneous is not proof the site will serve us — the
    // measured refusal can carry cookies too — so this stays Unknown either way.
    probeBodyLength == null -> ExhentaiAccessVerdict.Unknown
    probeBodyLength <= 0 -> ExhentaiAccessVerdict.NoExhentaiAccess
    !cookies.hasExhentaiGrant() -> ExhentaiAccessVerdict.NoExhentaiAccess
    else -> ExhentaiAccessVerdict.Granted
}

/** Reads back [ExhentaiAccessVerdict.storageValue]; anything unrecognized is [ExhentaiAccessVerdict.Unknown]. */
fun parseExhentaiAccessVerdict(raw: String?): ExhentaiAccessVerdict =
    ExhentaiAccessVerdict.entries.firstOrNull { it.storageValue == raw?.trim() }
        ?: ExhentaiAccessVerdict.Unknown
