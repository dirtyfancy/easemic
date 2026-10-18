package com.easemic.app.feature.source

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.CloudCollectionSource
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.OpdsProviderConfig
import com.easemic.app.feature.source.zaimanhua.ZAIMANHUA_DISPLAY_NAME

/**
 * The one provider-id → display-name table (#E101). History rows, the Library
 * cloud shelf and any other surface that must name a source read this
 * resolver instead of keeping per-surface copies that drift apart.
 */
fun sourceDisplayNameFor(
    providerId: String,
    opdsProviders: List<OpdsProviderConfig> = emptyList(),
): String = when (providerId) {
    EhentaiEnableStore.SOURCE_ID -> EhentaiEnableStore.DISPLAY_NAME
    ExhentaiEnableStore.SOURCE_ID -> ExhentaiEnableStore.DISPLAY_NAME
    NhentaiEnableStore.SOURCE_ID -> NhentaiEnableStore.DISPLAY_NAME
    JmEnableStore.SOURCE_ID -> JmEnableStore.DISPLAY_NAME
    CloudCollectionSource.SOURCE_ID -> CloudCollectionSource.DISPLAY_NAME
    GalleryIdentityV1.ZAIMANHUA -> ZAIMANHUA_DISPLAY_NAME
    else -> opdsProviders.firstOrNull { it.id == providerId }?.displayName.orEmpty()
}
