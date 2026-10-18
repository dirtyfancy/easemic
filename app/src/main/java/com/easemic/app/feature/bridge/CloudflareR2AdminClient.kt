package com.easemic.app.feature.bridge

import com.easemic.app.sync.R2RestBody
import com.easemic.app.sync.R2RestHttp
import com.easemic.app.sync.R2RestHttpResponse
import com.easemic.app.sync.R2RestObjectStore
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

sealed class CloudflareR2AdminResult<out T> {
    data class Ok<T>(val value: T) : CloudflareR2AdminResult<T>()
    data class Failed(
        val r2NotEnabled: Boolean,
        val message: String = "",
    ) : CloudflareR2AdminResult<Nothing>()
}

/**
 * Cloudflare account/bucket admin for OAuth bind. HTTP is injected so JVM
 * tests never talk to the network.
 */
class CloudflareR2AdminClient(
    private val http: R2RestHttp = R2RestObjectStore.defaultHttp(),
) {
    fun listAccountIds(accessToken: String): CloudflareR2AdminResult<List<String>> {
        val response = http.request("GET", ACCOUNTS_URL, bearer(accessToken), null)
        if (isR2NotEnabled(response)) {
            return CloudflareR2AdminResult.Failed(r2NotEnabled = true, message = body(response))
        }
        if (response.status !in 200..299) {
            return CloudflareR2AdminResult.Failed(r2NotEnabled = false, message = body(response))
        }
        return CloudflareR2AdminResult.Ok(parseIds(body(response), idKey = "id"))
    }

    fun listBucketNames(
        accessToken: String,
        accountId: String,
    ): CloudflareR2AdminResult<List<String>> {
        val response = http.request("GET", bucketsUrl(accountId), bearer(accessToken), null)
        if (isR2NotEnabled(response)) {
            return CloudflareR2AdminResult.Failed(r2NotEnabled = true, message = body(response))
        }
        if (response.status !in 200..299) {
            return CloudflareR2AdminResult.Failed(r2NotEnabled = false, message = body(response))
        }
        return CloudflareR2AdminResult.Ok(parseIds(body(response), idKey = "name"))
    }

    /**
     * Prefer the Cloudflare account that already has [PREFERRED_BUCKET].
     * Otherwise use the first listed account (single or multi — a picker is
     * a follow-up). Never invent a different sticky foreign bucket.
     */
    fun selectAccountId(
        accessToken: String,
        accountIds: List<String>,
    ): CloudflareR2AdminResult<String> {
        val ids = accountIds.map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) {
            return CloudflareR2AdminResult.Failed(
                r2NotEnabled = false,
                message = "no accounts",
            )
        }
        for (id in ids) {
            when (val listed = listBucketNames(accessToken, id)) {
                is CloudflareR2AdminResult.Ok -> {
                    if (PREFERRED_BUCKET in listed.value) {
                        return CloudflareR2AdminResult.Ok(id)
                    }
                }
                is CloudflareR2AdminResult.Failed -> {
                    // Skip unlistable accounts while searching for preferred.
                }
            }
        }
        return CloudflareR2AdminResult.Ok(ids.first())
    }

    /**
     * Always bind the fixed product bucket [PREFERRED_BUCKET]. Create it when
     * missing. Never fall back to `names.firstOrNull()` — a prior foreign
     * label (e.g. agent-mentor-pubs) must migrate on the next OAuth bind.
     */
    fun resolveBucket(
        accessToken: String,
        accountId: String,
        locationHint: String? = null,
    ): CloudflareR2AdminResult<String> {
        when (val listed = listBucketNames(accessToken, accountId)) {
            is CloudflareR2AdminResult.Failed -> return listed
            is CloudflareR2AdminResult.Ok -> {
                if (PREFERRED_BUCKET in listed.value) {
                    return CloudflareR2AdminResult.Ok(PREFERRED_BUCKET)
                }
                return createBucket(accessToken, accountId, PREFERRED_BUCKET, locationHint)
            }
        }
    }

    fun createBucket(
        accessToken: String,
        accountId: String,
        name: String,
        locationHint: String? = null,
    ): CloudflareR2AdminResult<String> {
        val payload = JSONObject().put("name", name).apply {
            if (!locationHint.isNullOrBlank()) put("locationHint", locationHint)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val response = http.request(
            "POST",
            bucketsUrl(accountId),
            bearer(accessToken, contentType = JSON),
            // #C51: the transport's body is a sealed type now; this one is genuinely in memory.
            R2RestBody.Bytes(payload),
        )
        if (isR2NotEnabled(response)) {
            return CloudflareR2AdminResult.Failed(r2NotEnabled = true, message = body(response))
        }
        if (response.status !in 200..299) {
            return CloudflareR2AdminResult.Failed(r2NotEnabled = false, message = body(response))
        }
        val parsed = parseIds(body(response), idKey = "name").firstOrNull() ?: name
        return CloudflareR2AdminResult.Ok(parsed)
    }

    private fun parseIds(raw: String, idKey: String): List<String> {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val result = json.opt("result") ?: return emptyList()
        val array = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("buckets") ?: JSONArray().also { out ->
                val name = result.optString(idKey).trim().ifEmpty {
                    result.optString("name").trim()
                }
                if (name.isNotEmpty()) {
                    out.put(JSONObject().put(idKey, name).put("name", name))
                }
            }
            else -> return emptyList()
        }
        val names = ArrayList<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString(idKey).trim().ifEmpty {
                item.optString("name").trim()
            }
            if (name.isNotEmpty()) names += name
        }
        return names
    }

    private fun isR2NotEnabled(response: R2RestHttpResponse): Boolean {
        if (response.status in 200..299) return false
        val raw = body(response)
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return raw.contains("R2 is not enabled", ignoreCase = true)
        }
        val errors = json.optJSONArray("errors") ?: return raw.contains(
            "R2 is not enabled",
            ignoreCase = true,
        )
        for (i in 0 until errors.length()) {
            val error = errors.optJSONObject(i) ?: continue
            if (error.optInt("code") == R2_NOT_ENABLED_CODE) return true
            if (error.optString("message").contains("R2 is not enabled", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun body(response: R2RestHttpResponse): String =
        String(response.body, StandardCharsets.UTF_8)

    private fun bearer(
        accessToken: String,
        contentType: String? = null,
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["Authorization"] = "Bearer $accessToken"
        if (contentType != null) {
            map["Content-Type"] = contentType
        }
        return map
    }

    private fun bucketsUrl(accountId: String): String =
        "$ACCOUNTS_URL/${accountId.trim()}/r2/buckets"

    companion object {
        const val ACCOUNTS_URL = "https://api.cloudflare.com/client/v4/accounts"
        const val PREFERRED_BUCKET = "pcl-comics"
        private const val JSON = "application/json"
        private const val R2_NOT_ENABLED_CODE = 10042
    }
}
