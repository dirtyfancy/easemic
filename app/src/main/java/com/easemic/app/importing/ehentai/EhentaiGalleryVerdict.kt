package com.easemic.app.importing.ehentai

/**
 * What an E-Hentai response body actually says about a gallery's existence
 * (#C14). The owner asked for this to be pinned by evidence rather than
 * reasoned about: "if a source really died, retrying it is a problem, but if it
 * did not die and we skip it forever, that is wrong too."
 *
 * Three facts were measured on 2026-09-10 (14 gentle probes, >=3s apart, no
 * credentials, ExHentai untouched) and two of them overturn what the code
 * previously assumed:
 *
 *  1. **E-Hentai does not use 404 for an absent gallery.** A nonexistent gid
 *     answers HTTP **200** with a 172-byte plain-text sentinel. The old
 *     repeat-404 rule was therefore unreachable for e-h, and a genuinely dead
 *     gallery was classified as jitter and retried forever.
 *  2. **`expunged` is not death.** An expunged gallery serves 200 with a real
 *     title and a complete image section; EHWiki says expunging only removes it
 *     from default search. So the expunged marker outranks every other signal
 *     in the body — otherwise we permanently skip a gallery the site still
 *     hands over.
 *  3. Wording varies, so it must not be hardcoded. EhViewer (pinned at
 *     `b9729e94`) hardcodes no removal literal at all: it lifts the error text
 *     structurally out of `div.d` (`detail.rs:476` ->
 *     `GalleryUnavailable(String)`) precisely because the phrasing changes. We
 *     do the same and carry the text, and we deliberately do NOT claim an
 *     unrecognized phrasing means death.
 */
sealed class EhentaiGalleryVerdict {
    /** Real gallery markup, or an explicit expunged marker: alive either way. */
    data object Alive : EhentaiGalleryVerdict()

    /**
     * The verified `Gallery not found. …` sentinel. Note its own wording asks
     * the caller to wait ("you may have to wait a short while before it becomes
     * available … might also have been reverted"), so a single sighting must be
     * retried; only a repeat may seal.
     */
    data object NotFound : EhentaiGalleryVerdict()

    /**
     * The removal notice this codebase has recognized since Task 8, plus (C14,
     * 2026-09-16) the reference fork's two long-shipped takedown literals
     * ("This gallery is unavailable" — copyright/DMCA — and "This gallery is
     * pining for the fjords." — the legacy gone page). Removal-class:
     * seal-on-sight, but the download loop confirms with an authoritative
     * gdata/nh-v2 liveness veto before actually sealing (C14 D3), so a false
     * positive on a live gallery is caught.
     */
    data object Removed : EhentaiGalleryVerdict()

    /**
     * The offensive / content-warning interstitial (C14). NOT a death: it is a
     * viewing gate the account has not cleared a content type for. Non-terminal
     * — never GONE, so a gallery the account merely cannot see is not skipped
     * forever.
     */
    data object ContentGated : EhentaiGalleryVerdict()

    /**
     * A stale or wrong gallery token (`Key missing, or incorrect key
     * provided.`, 39 B). The gallery is alive; the token needs re-resolving.
     * This used to be documented as a permanent "bad key" death, which would
     * have skipped live galleries forever.
     */
    data object TokenStale : EhentaiGalleryVerdict()

    /**
     * Text lifted out of e-h's error `div` that matches no verified sentinel —
     * a copyright/DMCA notice, for instance, whose exact wording we could not
     * sample. Carries [text] so a human can read what e-h said. Deliberately
     * not a death: acting permanently on unproven wording is the failure mode
     * the owner named.
     */
    data class UnavailableUnconfirmed(val text: String) : EhentaiGalleryVerdict()

    /** No gallery markup and no server verdict: blank, login wall, or unknown. */
    data object NoVerdict : EhentaiGalleryVerdict()
}

/** The verified 172-byte body an absent gid serves, as HTTP 200. */
const val EHENTAI_GALLERY_NOT_FOUND_SENTINEL = "Gallery not found."

/** The verified 39-byte body a wrong gallery token serves. */
const val EHENTAI_KEY_MISSING_SENTINEL = "Key missing, or incorrect key provided."

private const val EHENTAI_REMOVED_SENTINEL = "This gallery has been removed"

/**
 * C14 (2026-09-16): the reference fork `xiaojieonly/Ehviewer_CN_SXJ`'s
 * long-shipped takedown literals (facts, GPLv3 — matched structurally, no code
 * copied). "This gallery is unavailable" is the fork's copyright/DMCA prefix
 * (the one recorded takedown page reads "This gallery is unavailable due to a
 * copyright claim by <rightsholder>. …"); "This gallery is pining for the
 * fjords." is e-h's legacy gone page.
 */
private val EHENTAI_TAKEDOWN_SENTINELS = listOf(
    "This gallery is unavailable",
    "This gallery is pining for the fjords.",
)

/**
 * C14 audit (2026-09-16): the subset of removal-class wording that is an
 * EXPLICIT takedown — unambiguous enough to seal without the liveness oracle
 * ([isEhentaiExplicitTakedownWording]). The bare "This gallery is unavailable"
 * prefix is deliberately NOT here: it still judges [EhentaiGalleryVerdict.Removed]
 * (the owner's rule — unfetchable pages are a takedown), but an unrecorded
 * "unavailable …" variant (a regional or temporary notice, say) keeps the gdata
 * veto in front of the seal so it can never permanently seal a gallery the site
 * still serves.
 */
private val EHENTAI_EXPLICIT_TAKEDOWN_SENTINELS = listOf(
    "This gallery is unavailable due to a copyright",
    "This gallery is pining for the fjords.",
    EHENTAI_REMOVED_SENTINEL,
)

/**
 * C14: the offensive/content-warning gate marker (the fork's OFFENSIVE_STRING).
 * A viewing gate, not a takedown.
 */
private const val EHENTAI_OFFENSIVE_SENTINEL =
    "And if you choose to ignore this warning, you lose all rights to complain about it in the future."

private val EHENTAI_EXPUNGED_MARKERS = listOf("No (Expunged)", "\"expunged\":true", "\"expunged\": true")

/** Pulls the text out of e-h's error `div`, the way EhViewer does. */
private val ERROR_DIV_REGEX = Regex(
    "<div[^>]*class=\"d\"[^>]*>(.*?)</div>",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

private val TAG_REGEX = Regex("<[^>]+>")

/**
 * Reads an e-h gallery response body and says what it means. Pure and
 * stateless: no network, no time, no retry counting — the caller decides what
 * to do with the verdict.
 *
 * Order is load-bearing. The expunged marker is checked first because an
 * expunged gallery's own page can carry unavailability-flavoured wording while
 * still serving every page. Gallery markup (`id="gn"`) comes next, so a real
 * gallery whose tags or description merely mention one of these phrases is
 * never mistaken for a verdict about itself. [EhentaiGalleryVerdict.ContentGated]
 * is checked before the removal wording so a content-warning page that also
 * quotes a takedown phrase stays a gate (retried with `nw=session`) and never
 * becomes an oracle-free seal.
 */
fun judgeEhentaiGalleryBody(body: String): EhentaiGalleryVerdict {
    if (EHENTAI_EXPUNGED_MARKERS.any { body.contains(it, ignoreCase = true) }) {
        return EhentaiGalleryVerdict.Alive
    }
    if (body.contains("id=\"gn\"", ignoreCase = true)) {
        return EhentaiGalleryVerdict.Alive
    }
    if (body.contains(EHENTAI_GALLERY_NOT_FOUND_SENTINEL, ignoreCase = true)) {
        return EhentaiGalleryVerdict.NotFound
    }
    if (body.contains(EHENTAI_KEY_MISSING_SENTINEL, ignoreCase = true)) {
        return EhentaiGalleryVerdict.TokenStale
    }
    if (body.contains(EHENTAI_OFFENSIVE_SENTINEL, ignoreCase = true)) {
        return EhentaiGalleryVerdict.ContentGated
    }
    if (body.contains(EHENTAI_REMOVED_SENTINEL, ignoreCase = true) ||
        EHENTAI_TAKEDOWN_SENTINELS.any { body.contains(it, ignoreCase = true) }
    ) {
        return EhentaiGalleryVerdict.Removed
    }
    val errorText = ERROR_DIV_REGEX.find(body)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(TAG_REGEX, " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (errorText != null) {
        return EhentaiGalleryVerdict.UnavailableUnconfirmed(errorText)
    }
    return EhentaiGalleryVerdict.NoVerdict
}

/**
 * C14 audit (2026-09-16): does [body] judge [EhentaiGalleryVerdict.Removed] on
 * one of the EXPLICIT takedown literals (the recorded copyright page, the
 * legacy "pining for the fjords" page, "has been removed")? A `true` here is
 * what lets the download loop seal without consulting the liveness oracle; the
 * broader "This gallery is unavailable" prefix judges Removed but answers
 * `false`, so it stays oracle-vetoed. All alive guards still win first.
 */
fun isEhentaiExplicitTakedownWording(body: String): Boolean =
    judgeEhentaiGalleryBody(body) == EhentaiGalleryVerdict.Removed &&
        EHENTAI_EXPLICIT_TAKEDOWN_SENTINELS.any { body.contains(it, ignoreCase = true) }
