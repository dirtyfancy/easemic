package com.easemic.app.feature.download

/**
 * User-visible label for the Cloudflare-challenge banner/notification. Only
 * nhentai has a WebView solve flow, so when the source is enabled its real
 * name is surfaced instead of an internal code name; when it is disabled the
 * banner is not shown at all and this label is never rendered.
 */
fun cfChallengeBannerSourceLabel(nhentaiEnabled: Boolean): String =
    if (nhentaiEnabled) "nhentai" else "source"
