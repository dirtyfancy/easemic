package com.easemic.app.sync

import com.easemic.app.feature.download.GalleryIdentityV1
import java.security.MessageDigest

/**
 * #C52: production logs must never carry an acquisition URL, a
 * `canonicalItemId`, or a non-official `providerNamespace` verbatim. An OPDS
 * id IS a whole acquisition URL, and a catalog's own auth key can live in
 * that URL's path — a real lab Kavita OPDS key already leaked into evidence
 * logcats this way. [redactAcquisition] and [redactIdentity] are the only
 * sanctioned way to put either value into a `DiagLog`/`Log` line. See
 * `LogRedactionSourceGuardTest` for the source-level guard that pins every
 * call site under `app/src/main/java` to use these instead of interpolating
 * the raw fields.
 */

/**
 * `"len=<n> h8=<8-hex sha256 prefix>"` for [url], or the fixed `"len=0 h8=-"`
 * when [url] is null or blank. Deterministic (same [url] -> same result) so
 * two log lines about the same comic still correlate, but this only hides
 * WHICH comic for a non-enumerable id space (an OPDS URL carrying a ~40-char
 * key); for e-hentai/nhentai the id space is enumerable and `len`+`h8`
 * identify the gallery in seconds -- the secret this protects is the catalog
 * KEY, the per-comic privacy is best-effort correlation hygiene, and the
 * sink scrub in `DiagLog` is the layer that makes the key unleakable.
 */
fun redactAcquisition(url: String?): String {
    if (url.isNullOrBlank()) return "len=0 h8=-"
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    val h8 = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(8)
    return "len=${url.length} h8=$h8"
}

/**
 * `"ns=<namespace> item=<redacted>"` for [identity]. An official namespace
 * ([GalleryIdentityV1.EHENTAI], [GalleryIdentityV1.NHENTAI],
 * [GalleryIdentityV1.CLOUD_COLLECTION]) is one of three fixed, non-secret
 * strings and is printed verbatim; every other namespace — an OPDS catalog's
 * is `opds:<configId>`, a locally generated id that is not itself secret, but
 * this line stays a length only so it can never be relied on to carry
 * namespace identity across catalogs — collapses to `nsLen=<n>`. The item id
 * always goes through [redactAcquisition], since an OPDS `canonicalItemId` is
 * itself a whole acquisition URL.
 */
fun redactIdentity(identity: GalleryIdentityV1): String {
    val namespace = identity.providerNamespace
    val ns = when (namespace) {
        GalleryIdentityV1.EHENTAI, GalleryIdentityV1.NHENTAI, GalleryIdentityV1.CLOUD_COLLECTION -> namespace
        else -> "nsLen=${namespace.length}"
    }
    return "ns=$ns item=${redactAcquisition(identity.canonicalItemId)}"
}

/**
 * The namespace half of [redactIdentity], for call sites that only ever hold
 * the bare `providerNamespace: String` (e.g. [com.easemic.app.feature.download.SourcePacingGate],
 * which paces per-source and has no [GalleryIdentityV1] to hand): an official
 * namespace is printed verbatim, every other one collapses to `nsLen=<n>`.
 */
fun redactNamespace(namespace: String): String = when (namespace) {
    GalleryIdentityV1.EHENTAI, GalleryIdentityV1.NHENTAI, GalleryIdentityV1.CLOUD_COLLECTION -> namespace
    else -> "nsLen=${namespace.length}"
}
