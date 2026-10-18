package com.easemic.app.importing.ehentai

data class EhentaiListPage(
    val entries: List<EhentaiListEntry>,
    val nextPageIndex: Int? = null,
    /** Current e-hentai.org seek token from `?next={gid}` (id=unext / dnext). */
    val nextSeekGid: Long? = null,
)

data class EhentaiListEntry(
    val title: String,
    val galleryId: Long,
    val token: String,
    val thumbUrl: String?,
    /** E-Hentai category label (Doujinshi / Manga / …), when the list row carries it. */
    val category: String? = null,
    /** Average user rating 0–5, when the list row carries it. */
    val rating: Float? = null,
    /** Absolute posted timestamp, epoch millis, when the page carries it. */
    val postedEpochMillis: Long? = null,
    /** Declared page count, when the list row carries it. */
    val pageCount: Int? = null,
    /** #D4: uploader account name, when the list row carries it. */
    val uploader: String? = null,
    /** #D4: the gallery's language (from its `language:` tag, "translated" skipped), when the row shows tags. */
    val language: String? = null,
)

data class EhentaiPreviewThumb(
    val sheetUrl: String,
    val offsetX: Int,
    val width: Int,
    val height: Int,
)

data class EhentaiGalleryPlan(
    val galleryId: Long,
    val token: String,
    val title: String,
    val pageViewPaths: List<String>,
    val tags: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val category: String? = null,
    val language: String? = null,
    val previewThumbs: List<EhentaiPreviewThumb> = emptyList(),
    /** Gallery "Length: N pages" row, when present. */
    val declaredPageCount: Int? = null,
    /** Highest `?p=` thumbnail-grid index linked from this HTML (0 = first grid). */
    val highestThumbPageIndex: Int = 0,
    /** Gallery Posted row interpreted in UTC, in seconds rather than list-row millis. */
    val postedEpochSeconds: Long? = null,
    /** Gallery average rating; null for absent, invalid or not-yet-rated labels. */
    val rating: Float? = null,
)

data class EhentaiPageImage(
    val pageNumber: Int,
    val imageUrl: String,
    /**
     * H@H node-reload token from the page's own "click here if the image
     * fails loading" link (`nl('<token>')` / `?nl=<token>`), when present.
     * Task 9 defect 4 (owner bug backlog): a dead/slow H@H node otherwise
     * keeps resolving to the SAME broken node on every retry — this token
     * lets `FrontDownloadPageAdapter` ask e-hentai for a different node
     * after a few consecutive same-ordinal failures. Parsed by
     * [EhentaiGalleryParser.parsePageImage] via `parseEhentaiReloadToken`.
     */
    val reloadToken: String? = null,
    /**
     * The gallery's `showkey` scraped from a page-view HTML (`var showkey="…"`).
     * With it, every later page of the same gallery can be resolved through the
     * `api.php` `showpage` JSON call instead of a full main-site page view —
     * the load-bearing reason EhViewer downloads fast without tripping the
     * "opening pages too fast" counter (research 2026-09-10). Null when the
     * page came from the API itself or the markup lacked it.
     */
    val showKey: String? = null,
)
