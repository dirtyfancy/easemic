package com.easemic.app.feature.source

import com.easemic.app.feature.detail.model.DefaultSourceDetailProfile
import com.easemic.app.feature.detail.model.SourceDetailProfile
import com.easemic.app.feature.source.jm.JmDetailProfile
import com.easemic.app.feature.source.zaimanhua.ZaimanhuaDetailProfile
import com.easemic.app.importing.ehentai.EhentaiDetailProfile
import com.easemic.app.importing.nhentai.NhentaiDetailProfile

class SourceDetailProfiles(private val entries: Map<String, SourceDetailProfile>) {
    fun profile(namespace: String?): SourceDetailProfile = entries[namespace] ?: DefaultSourceDetailProfile
    fun with(namespace: String, profile: SourceDetailProfile): SourceDetailProfiles = SourceDetailProfiles(entries + (namespace to profile))
}
val builtInDetailProfiles = SourceDetailProfiles(mapOf(
    "ehentai" to EhentaiDetailProfile,
    "exhentai_catalog" to EhentaiDetailProfile,
    "nhentai" to NhentaiDetailProfile,
    "jm" to JmDetailProfile,
    "zaimanhua" to ZaimanhuaDetailProfile,
))
