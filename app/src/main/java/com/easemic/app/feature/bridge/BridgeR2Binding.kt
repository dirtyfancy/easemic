package com.easemic.app.feature.bridge

/**
 * Cloudflare R2 credentials the device uses for S3 Put/Get.
 *
 * Secrets live only in the device store. The account row is a non-secret
 * bucket label. [toString] redacts Access Key and Secret so logs cannot
 * leak them.
 */
data class BridgeR2Binding(
    val endpoint: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val bucket: String,
    /** SigV4 region; `auto` preserves R2 and known-endpoint inference. */
    val region: String = "auto",
) {
    override fun toString(): String =
        "BridgeR2Binding(endpoint=$endpoint, accessKeyId=<redacted>, " +
            "secretAccessKey=<redacted>, bucket=$bucket)"
}
