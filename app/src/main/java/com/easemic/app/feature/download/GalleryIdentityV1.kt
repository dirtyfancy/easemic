package com.easemic.app.feature.download

/**
 * Stable download identity (v1).
 *
 * Locators (URLs, tokens, titles, mutable query params) are never the primary
 * identity. Cloud chrome Download-from-R2 may use `cloudComicId` as
 * [canonicalItemId] when origin Front/Index/OPDS cannot be resolved.
 * Uniqueness is `(identityVersion, providerNamespace, canonicalItemId)`.
 *
 * Spec: local download queue design §4.1.
 */
data class GalleryIdentityV1(
    val providerNamespace: String,
    val canonicalItemId: String,
    val fallbackUrlDerived: Boolean = false,
) {
    // #C19 audit F11: equality is the persisted contract
    // (identityVersion, providerNamespace, canonicalItemId); the derivation
    // flag is provenance, not identity. Room's UNIQUE index is exactly
    // (identityVersion, providerNamespace, canonicalItemId) —
    // `DownloadEntities.kt`; the flag IS persisted (`fallbackUrlDerived`
    // column) but as a provenance attribute, not part of the key, so one row
    // must be one value in memory too.
    override fun equals(other: Any?): Boolean =
        other is GalleryIdentityV1 &&
            other.providerNamespace == providerNamespace &&
            other.canonicalItemId == canonicalItemId

    override fun hashCode(): Int = 31 * providerNamespace.hashCode() + canonicalItemId.hashCode()

    companion object {
        const val IDENTITY_VERSION: Int = 1
        const val EHENTAI: String = "ehentai"
        const val NHENTAI: String = "nhentai"
        const val ZAIMANHUA: String = "zaimanhua"
        const val JM: String = "jm"
        const val CLOUD_COLLECTION: String = "cloud_collection"

        /** Prefix for OPDS namespaces: `opds:<stableProviderConfigId>`. */
        const val OPDS_PREFIX: String = "opds:"

        /**
         * OPDS namespace: `opds:<stableProviderConfigId>`.
         *
         * IDEMPOTENT (#C19). Callers legitimately hold either shape and both
         * read naturally at their own call site: `BrowseFeedItem.providerId` is
         * a bare config id when the item came from a Browse feed
         * (`OpdsCatalogConnect` mints it as a bare UUID) but a FULL namespace
         * when `detailCollectFeedItem` published it off an existing identity.
         * Prepending unconditionally forked one OPDS library into
         * `opds:<uuid>` and `opds:opds:<uuid>` — the collect-to-cloud tap on a
         * downloaded comic minted a second job row whose pages were nowhere on
         * disk.
         *
         * Applying the rule to a value that already carries it is therefore the
         * identity, not a second wrap. A total function costs nothing here and
         * removes a convention every future caller would have to remember.
         */
        fun opdsNamespace(stableProviderConfigId: String): String =
            if (stableProviderConfigId.startsWith(OPDS_PREFIX)) {
                stableProviderConfigId
            } else {
                "$OPDS_PREFIX$stableProviderConfigId"
            }

        /**
         * Prefix for slice-C2 old-bucket migration namespaces. Scoped per old
         * bucket fingerprint so a job fetching from a retired bucket can never
         * collide with (or be mistaken for) a `cloud_collection` job against
         * the CURRENT bucket, even though both ultimately read a
         * `CloudCollectionStore`.
         */
        const val R2_SOURCE_PREFIX: String = "r2source:"

        /** Old-bucket migration namespace: `r2source:<oldFingerprint>`. */
        fun r2SourceNamespace(oldFingerprint: String): String =
            "$R2_SOURCE_PREFIX$oldFingerprint"
    }
}
