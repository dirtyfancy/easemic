package com.easemic.app.feature.providers

/**
 * The E-Hentai / ExHentai session, kept as named fields rather than one opaque
 * `Cookie:` blob.
 *
 * The blob form (what the old paste field stored under
 * [EhentaiStoreKeys.EHENTAI_COOKIE_KEY]) cannot answer the one question the
 * ExHentai source has to ask on every launch: *is `igneous` present?* Without
 * that, a session that is signed in but has no ExHentai permission is
 * indistinguishable from a healthy one, and the user gets an empty gallery
 * instead of the truth. Named fields make the missing-or-stale `igneous` case
 * detectable, so the app can re-probe instead of guessing.
 *
 * Field set mirrors what EhViewer's `EhCookieStore` tracks:
 *  - [memberId] / [passHash] — the login itself; both present means signed in.
 *  - [igneous] — ExHentai's per-account access grant, issued by exhentai.org on
 *    first visit *only* to accounts that carry the permission. The literal
 *    value `mystery` is a refusal, not a grant (see [hasExhentaiGrant]).
 *  - [sk] — session key the main site rotates; carried through so a harvested
 *    session is not immediately invalidated.
 *  - [hathPerks] — affects image resolution/limits; harmless to carry.
 *  - [nw] — Content Warning acknowledgement. Never harvested as state, only
 *    added at send time via [withContentWarningBypass].
 *  - [yay] — `yay=louder` is what exhentai.org sets *instead of* `igneous` for
 *    an account it will not serve; retained purely as evidence for the access
 *    verdict.
 */
data class EhentaiSessionCookies(
    val memberId: String = "",
    val passHash: String = "",
    val igneous: String = "",
    val sk: String = "",
    val hathPerks: String = "",
    val nw: String = "",
    val yay: String = "",
) {
    /** Both login cookies present. Says nothing about ExHentai access. */
    fun isSignedIn(): Boolean = memberId.isNotBlank() && passHash.isNotBlank()

    /**
     * A real ExHentai access grant. `igneous=mystery` is exhentai.org's way of
     * saying "no" while still setting the cookie, so it is explicitly excluded
     * — treating it as a grant is how a no-access account ends up staring at an
     * empty gallery.
     */
    fun hasExhentaiGrant(): Boolean =
        igneous.isNotBlank() && !igneous.equals(IGNEOUS_REFUSAL, ignoreCase = true)

    /** Cookie header value, or null when there is nothing worth sending. */
    fun cookieHeader(): String? {
        val parts = buildList {
            fun add(name: String, value: String) {
                if (value.isNotBlank()) add("$name=$value")
            }
            add(COOKIE_MEMBER_ID, memberId)
            add(COOKIE_PASS_HASH, passHash)
            add(COOKIE_IGNEOUS, igneous)
            add(COOKIE_SK, sk)
            add(COOKIE_HATH_PERKS, hathPerks)
            add(COOKIE_NW, nw)
            add(COOKIE_YAY, yay)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * Copy carrying `nw=1`, which suppresses the Content Warning interstitial.
     * Kept off the stored record: acknowledging the warning is a property of a
     * request, not of the account.
     */
    fun withContentWarningBypass(): EhentaiSessionCookies =
        if (nw.isNotBlank()) this else copy(nw = "1")

    /**
     * Overlays [fresh] on top of this record, field by field, with a non-blank
     * fresh value winning. A WebView cookie read is scoped to one domain, so
     * the exhentai.org read returns `igneous`/`yay` but none of the forum login
     * cookies — a plain replace would throw the login away.
     */
    fun mergedWith(fresh: EhentaiSessionCookies): EhentaiSessionCookies =
        EhentaiSessionCookies(
            memberId = fresh.memberId.ifBlank { memberId },
            passHash = fresh.passHash.ifBlank { passHash },
            igneous = fresh.igneous.ifBlank { igneous },
            sk = fresh.sk.ifBlank { sk },
            hathPerks = fresh.hathPerks.ifBlank { hathPerks },
            nw = fresh.nw.ifBlank { nw },
            yay = fresh.yay.ifBlank { yay },
        )

    companion object {
        const val COOKIE_MEMBER_ID = "ipb_member_id"
        const val COOKIE_PASS_HASH = "ipb_pass_hash"
        const val COOKIE_IGNEOUS = "igneous"
        const val COOKIE_SK = "sk"
        const val COOKIE_HATH_PERKS = "hath_perks"
        const val COOKIE_NW = "nw"
        const val COOKIE_YAY = "yay"

        /** Value exhentai.org issues to sessions it refuses to serve. */
        const val IGNEOUS_REFUSAL = "mystery"
    }
}

/**
 * Parses a `Cookie:` header value or an Android `CookieManager.getCookie()`
 * result — both use the same `name=value; name=value` shape — into named
 * fields. Unknown cookies are dropped, a later duplicate wins, and anything
 * unparseable yields an empty (signed-out) record rather than an exception:
 * this runs on a user-pasted string and on WebView output, neither of which is
 * trustworthy enough to throw over.
 */
fun parseEhentaiSessionCookies(raw: String?): EhentaiSessionCookies {
    val text = raw?.trim().orEmpty().removePrefix("Cookie:").removePrefix("cookie:")
    if (text.isBlank()) return EhentaiSessionCookies()
    var cookies = EhentaiSessionCookies()
    text.split(';').forEach { pair ->
        val separator = pair.indexOf('=')
        if (separator <= 0) return@forEach
        val name = pair.substring(0, separator).trim().lowercase()
        val value = pair.substring(separator + 1).trim().trim('"')
        if (value.isEmpty()) return@forEach
        cookies = when (name) {
            EhentaiSessionCookies.COOKIE_MEMBER_ID -> cookies.copy(memberId = value)
            EhentaiSessionCookies.COOKIE_PASS_HASH -> cookies.copy(passHash = value)
            EhentaiSessionCookies.COOKIE_IGNEOUS -> cookies.copy(igneous = value)
            EhentaiSessionCookies.COOKIE_SK -> cookies.copy(sk = value)
            EhentaiSessionCookies.COOKIE_HATH_PERKS -> cookies.copy(hathPerks = value)
            EhentaiSessionCookies.COOKIE_NW -> cookies.copy(nw = value)
            EhentaiSessionCookies.COOKIE_YAY -> cookies.copy(yay = value)
            else -> cookies
        }
    }
    return cookies
}
