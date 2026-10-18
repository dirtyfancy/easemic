package com.easemic.app.feature.browse

import com.easemic.app.sync.CloudCollectionStore

/**
 * C41: bytes for one cloud detail preview tile — the per-page grid thumb
 * (uploaded by #C5) and nothing else: for a v3 comic the standalone
 * `thumbs/<ordinal>` object, and since #C47-C (v4) the `NNNN.<ext>` entry of
 * its 24-ordinal `thumbs/pack-NNNN.zip`, which [CloudCollectionStore] fetches
 * once and memoises so the strip's serial walk costs one GET per pack. The
 * full-resolution page object is never read as a preview source or fallback;
 * a missing or unreadable thumb (or pack entry) returns null so the caller
 * can mark that ordinal explicitly missing instead of showing the wrong image.
 */
internal fun cloudPreviewBytes(
    store: CloudCollectionStore,
    cloudComicId: String,
    ordinal: Int,
): ByteArray? = runCatching { store.downloadThumb(cloudComicId, ordinal) }.getOrNull()
