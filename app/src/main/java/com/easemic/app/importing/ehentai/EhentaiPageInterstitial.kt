package com.easemic.app.importing.ehentai

/**
 * E-Hentai gallery-page interstitials (Task 8, owner bug #6): an HTTP 200
 * gallery-page fetch can return HTML that is NOT the gallery markup
 * [EhentaiGalleryParser] expects — an IP ban page, a "gallery removed"
 * notice, a Content Warning screen (bypassable with `nw=session`), or some
 * other page with no recognizable signal at all (blank/login-required/
 * unexpected copy). Previously none of these were detected: the parser's
 * regexes simply found nothing, producing a silently empty plan and a
 * reason-less hard-fail two layers away.
 *
 * Detection is content-based and, per the owner's classification rule
 * (explicit server verdicts only — never inferred from timing, speed, or a
 * single failed connection), keyed on the SAME explicit ban text
 * [classifyFetchFailure][com.easemic.app.feature.download.classifyFetchFailure]
 * already recognizes for e-h, plus the real e-hentai.org removed-gallery
 * copy. [Banned] and [Removed] are surfaced through
 * `EhentaiException.Interstitial` so the existing transport-exception →
 * `classifyFetchFailure` pipeline classifies them uniformly with every other
 * e-h failure path — this is deliberately a single source of truth for the
 * ban/removed text, not a second parallel detector. [EmptyOrLogin] has no
 * server verdict to attach to, so it is never parked — callers route it to
 * `MANIFEST_ERROR` with the page's own text as the detail, so a human can
 * see what e-h actually returned.
 */
sealed class EhentaiInterstitial {
    /** Explicit IP-ban text present — same phrases as the transport-level classifier. */
    data object Banned : EhentaiInterstitial()

    /** Content Warning screen — bypassable with a single `nw=session` re-fetch. */
    data object ContentWarning : EhentaiInterstitial()

    /**
     * An explicit server verdict that the gallery is not there: either the
     * removal notice or the verified `Gallery not found. …` sentinel. See
     * [judgeEhentaiGalleryBody] — permanence is decided downstream, on a
     * repeat, never on one read.
     */
    data object Removed : EhentaiInterstitial()

    /** No gallery markup and no explicit signal — blank/login-required/unexpected page. */
    data object EmptyOrLogin : EhentaiInterstitial()
}

/**
 * Detects an interstitial in a gallery-page response body. Returns null when
 * ordinary gallery markup (`id="gn"` title heading, per
 * [EhentaiGalleryParser]'s own marker) is present — a real gallery page
 * always wins over any incidental text match, so a gallery whose own tags or
 * description happen to mention e.g. "Content Warning" is never
 * misclassified as an interstitial.
 */
fun detectEhentaiInterstitial(html: String): EhentaiInterstitial? {
    if (html.contains("id=\"gn\"", ignoreCase = true)) {
        return null
    }
    if (html.contains("temporarily banned", ignoreCase = true) ||
        html.contains("Your IP address has been", ignoreCase = true)
    ) {
        return EhentaiInterstitial.Banned
    }
    // #C14: existence is decided in exactly one place, so this detector and
    // the transport classifier cannot drift apart on what "gone" looks like.
    // Notably this is what finally lets the verified 200 + "Gallery not
    // found. …" sentinel through — it has no gallery markup and matched none
    // of the phrases this function used to know, so it fell out as
    // EmptyOrLogin and e-h's real absence signal never reached the classifier.
    when (judgeEhentaiGalleryBody(html)) {
        EhentaiGalleryVerdict.NotFound, EhentaiGalleryVerdict.Removed ->
            return EhentaiInterstitial.Removed
        EhentaiGalleryVerdict.Alive -> return null
        // A stale token, unproven unavailability wording and a blank page all
        // lack a verdict we may act on permanently; fall through so Content
        // Warning still wins where it applies and everything else surfaces as
        // an unrecognized page carrying e-h's own text.
        else -> Unit
    }
    return if (html.contains("Content Warning", ignoreCase = true)) {
        EhentaiInterstitial.ContentWarning
    } else {
        EhentaiInterstitial.EmptyOrLogin
    }
}

/**
 * Thrown for [EhentaiInterstitial.EmptyOrLogin] — deliberately NOT an
 * [EhentaiException] subtype. `EhentaiDownloadPageAdapter`'s `loadPlan` only
 * catches [EhentaiException], so this propagates unconverted straight to
 * `DownloadWorkerLoop.routeManifestFailure`'s non-transport fallback, which
 * records it as `MANIFEST_ERROR` — never a QUOTA/ABUSE_STOP park, per the
 * owner's "explicit server verdicts only" rule (there is no server verdict
 * here, just an unrecognized page). [message] is itself the first ~300 chars
 * of the actual response body so the persisted failure detail shows a human
 * what e-h returned.
 */
class EhentaiManifestEmptyException(message: String) : Exception(message)
