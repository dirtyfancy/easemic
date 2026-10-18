package com.easemic.app.feature.bridge

import com.easemic.app.sync.R2RestBody
import com.easemic.app.sync.R2RestHttp
import com.easemic.app.sync.R2RestHttpResponse
import com.easemic.app.sync.R2RestObjectStore
import com.easemic.app.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Supported single-key / pasted-key object-storage providers that can automatically
 * locate or create the `easemic-comics` bucket and produce a ready [BridgeR2Binding].
 */
enum class S3ApiKeyProvider(
    val displayName: String,
    val shortBadge: String,
    private val hintTitleEn: String,
    private val hintStepsEn: String,
) {
    BUNNY(
        displayName = "Bunny.net",
        shortBadge = "Bunny.net",
        hintTitleEn = "Bunny.net Account API Key",
        hintStepsEn = "Account menu → API key → copy your account API key. Easemic finds or creates the easemic-comics storage zone and connects via Bunny's S3 gateway.",
    ),
    BACKBLAZE_B2(
        displayName = "Backblaze B2",
        shortBadge = "Backblaze B2",
        hintTitleEn = "Backblaze B2 Application Key",
        hintStepsEn = "Dashboard → Application Keys → create an Application Key (or Master Key) and paste keyID:applicationKey (with a colon or on two lines). Easemic finds or creates an easemic-comics bucket.",
    ),
    CLOUDFLARE_R2(
        displayName = "Cloudflare R2",
        shortBadge = "Cloudflare R2",
        hintTitleEn = "Cloudflare R2 API Token",
        hintStepsEn = "Profile → API Tokens → Create Token → Custom token: set Account · Workers R2 Storage · Edit, create it and paste the token.",
    );

    val hintTitle: String
        get() = when (this) {
            BUNNY -> tr(hintTitleEn, "Bunny.net 账户 API Key", "Bunny.net 帳戶 API Key", "Bunny.net アカウント API キー")
            BACKBLAZE_B2 -> tr(hintTitleEn, "Backblaze B2 应用密钥", "Backblaze B2 應用程式金鑰", "Backblaze B2 アプリケーションキー")
            CLOUDFLARE_R2 -> tr(hintTitleEn, "Cloudflare R2 API Token", "Cloudflare R2 API Token", "Cloudflare R2 API トークン")
        }

    val hintSteps: String
        get() = when (this) {
            BUNNY -> tr(
                hintStepsEn,
                "账户菜单 → API key → 复制账户 API key。Easemic 会查找或创建 easemic-comics 存储区域，并通过 Bunny 的 S3 网关连接。",
                "帳戶選單 → API key → 複製帳戶 API key。Easemic 會尋找或建立 easemic-comics 儲存區，並透過 Bunny 的 S3 閘道連線。",
                "アカウントメニュー → API key → アカウント API キーをコピーします。Easemic が easemic-comics ストレージゾーンを検索・作成し、Bunny の S3 ゲートウェイで接続します。",
            )
            BACKBLAZE_B2 -> tr(
                hintStepsEn,
                "控制台 → Application Keys → 创建 Application Key（或 Master Key），然后粘贴 keyID:applicationKey（用冒号分隔或分两行）。Easemic 会查找或创建 easemic-comics 存储桶。",
                "控制台 → Application Keys → 建立 Application Key（或 Master Key），然後貼上 keyID:applicationKey（以冒號分隔或分成兩行）。Easemic 會尋找或建立 easemic-comics 儲存桶。",
                "ダッシュボード → Application Keys → Application Key（または Master Key）を作成し、keyID:applicationKey を貼り付けます（コロン区切りまたは2行）。Easemic が easemic-comics バケットを検索・作成します。",
            )
            CLOUDFLARE_R2 -> tr(
                hintStepsEn,
                "个人资料 → API Tokens → Create Token → Custom token：勾选 Account · Workers R2 Storage · Edit，创建后粘贴 Token。",
                "個人檔案 → API Tokens → Create Token → Custom token：勾選 Account · Workers R2 Storage · Edit，建立後貼上 Token。",
                "プロフィール → API Tokens → Create Token → Custom token：Account · Workers R2 Storage · Edit を設定して作成し、トークンを貼り付けます。",
            )
        }
}

sealed interface S3ApiKeyProvisionResult {
    data class Success(
        val provider: S3ApiKeyProvider,
        val binding: BridgeR2Binding,
        val createdNewBucket: Boolean,
    ) : S3ApiKeyProvisionResult

    data class Failed(val message: String) : S3ApiKeyProvisionResult
}

/**
 * Auto-detects the provider from the pasted API key format, finds an existing
 * `easemic-comics` bucket (or `easemic-comics-<suffix>` on globally namespaced
 * providers like Bunny and Backblaze B2), creates one if none exists, and returns
 * a standard [BridgeR2Binding] ready for [com.easemic.app.sync.R2ObjectStore].
 */
class S3ApiKeyAutoProvisioner(
    private val http: R2RestHttp = R2RestObjectStore.defaultHttp(),
    private val defaultBunnyRegion: String = "SG",
) {
    fun provision(
        rawInput: String,
        selectedProviderOverride: S3ApiKeyProvider? = null,
    ): S3ApiKeyProvisionResult {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.KEY_REQUIRED)
        }
        val provider = detectProvider(cleaned) ?: selectedProviderOverride
            ?: return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.UNRECOGNIZED_KEY)
        return when (provider) {
            S3ApiKeyProvider.BUNNY -> provisionBunny(cleaned)
            S3ApiKeyProvider.BACKBLAZE_B2 -> provisionBackblaze(cleaned)
            S3ApiKeyProvider.CLOUDFLARE_R2 -> provisionCloudflareR2(cleaned)
        }
    }

    private fun provisionBunny(accountApiKey: String): S3ApiKeyProvisionResult {
        val listResp = runCatching {
            http.request(
                method = "GET",
                url = BUNNY_STORAGE_ZONES_URL,
                headers = mapOf(
                    "AccessKey" to accountApiKey,
                    "Accept" to "application/json",
                ),
                body = null,
            )
        }.getOrElse {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.NETWORK_ERROR)
        }
        if (listResp.status == 401 || listResp.status == 403) {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.INVALID_BUNNY_KEY)
        }
        if (listResp.status !in 200..299) {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.PROVIDER_ERROR)
        }
        val zones = parseJsonArray(bodyString(listResp))
            ?: return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.PROVIDER_ERROR)

        val existingZone = findPreferredBunnyZone(zones)
        if (existingZone != null) {
            return S3ApiKeyProvisionResult.Success(
                provider = S3ApiKeyProvider.BUNNY,
                binding = existingZone,
                createdNewBucket = false,
            )
        }

        // Try creating "easemic-comics" first; if globally taken on Bunny, try deterministic account suffix.
        val suffix = sha256Hex(accountApiKey).take(6)
        val candidates = listOf(PREFERRED_BUCKET, "$PREFERRED_BUCKET-$suffix")
        for (candidateName in candidates) {
            val payload = JSONObject()
                .put("Name", candidateName)
                .put("Region", defaultBunnyRegion.uppercase(Locale.US))
                .put("ZoneTier", 0)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            val createResp = runCatching {
                http.request(
                    method = "POST",
                    url = BUNNY_STORAGE_ZONES_URL,
                    headers = mapOf(
                        "AccessKey" to accountApiKey,
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                    ),
                    body = R2RestBody.Bytes(payload),
                )
            }.getOrNull() ?: continue
            if (createResp.status in 200..299) {
                val createdObj = runCatching { JSONObject(bodyString(createResp)) }.getOrNull()
                val binding = createdObj?.let(::bunnyZoneToBinding)
                if (binding != null) {
                    return S3ApiKeyProvisionResult.Success(
                        provider = S3ApiKeyProvider.BUNNY,
                        binding = binding,
                        createdNewBucket = true,
                    )
                }
            }
        }
        return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.CREATE_BUCKET_FAILED)
    }

    private fun findPreferredBunnyZone(zones: JSONArray): BridgeR2Binding? {
        var prefixMatch: BridgeR2Binding? = null
        for (i in 0 until zones.length()) {
            val obj = zones.optJSONObject(i) ?: continue
            if (obj.optBoolean("Deleted", false)) continue
            val name = obj.optString("Name").trim()
            val binding = bunnyZoneToBinding(obj) ?: continue
            if (name.equals(PREFERRED_BUCKET, ignoreCase = true)) {
                return binding
            }
            if (prefixMatch == null && name.lowercase(Locale.US).startsWith("$PREFERRED_BUCKET-")) {
                prefixMatch = binding
            }
        }
        return prefixMatch
    }

    private fun bunnyZoneToBinding(obj: JSONObject): BridgeR2Binding? {
        val name = obj.optString("Name").trim()
        val password = obj.optString("Password").trim()
        val regionRaw = obj.optString("Region").trim().ifEmpty { defaultBunnyRegion }
        if (name.isEmpty() || password.isEmpty()) return null
        val regionLower = regionRaw.lowercase(Locale.US)
        return BridgeR2Binding(
            endpoint = "https://$regionLower-s3.storage.bunnycdn.com",
            region = regionLower,
            accessKeyId = name,
            secretAccessKey = password,
            bucket = name,
        )
    }

    private fun provisionBackblaze(rawInput: String): S3ApiKeyProvisionResult {
        val (keyId, applicationKey) = parseBackblazePair(rawInput)
            ?: return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.INVALID_B2_KEY)
        val basic = Base64.getEncoder().encodeToString(
            "$keyId:$applicationKey".toByteArray(StandardCharsets.UTF_8),
        )
        val authResp = runCatching {
            http.request(
                method = "GET",
                url = B2_AUTHORIZE_URL,
                headers = mapOf(
                    "Authorization" to "Basic $basic",
                    "Accept" to "application/json",
                ),
                body = null,
            )
        }.getOrElse {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.NETWORK_ERROR)
        }
        if (authResp.status == 401 || authResp.status == 403) {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.INVALID_B2_KEY)
        }
        if (authResp.status !in 200..299) {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.PROVIDER_ERROR)
        }
        val authJson = runCatching { JSONObject(bodyString(authResp)) }.getOrNull()
            ?: return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.PROVIDER_ERROR)
        val accountId = authJson.optString("accountId").trim()
        val authToken = authJson.optString("authorizationToken").trim()
        val storageApi = authJson.optJSONObject("apiInfo")?.optJSONObject("storageApi") ?: authJson
        val apiUrl = storageApi.optString("apiUrl").trim().trimEnd('/')
        val s3ApiUrl = storageApi.optString("s3ApiUrl").trim().trimEnd('/')
        if (accountId.isEmpty() || authToken.isEmpty() || apiUrl.isEmpty() || s3ApiUrl.isEmpty()) {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.PROVIDER_ERROR)
        }
        val region = extractB2Region(s3ApiUrl)

        // List existing buckets
        val listPayload = JSONObject().put("accountId", accountId).toString().toByteArray(StandardCharsets.UTF_8)
        val listResp = runCatching {
            http.request(
                method = "POST",
                url = "$apiUrl/b2api/v3/b2_list_buckets",
                headers = mapOf(
                    "Authorization" to authToken,
                    "Content-Type" to "application/json",
                ),
                body = R2RestBody.Bytes(listPayload),
            )
        }.getOrElse {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.NETWORK_ERROR)
        }
        if (listResp.status !in 200..299) {
            return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.PROVIDER_ERROR)
        }
        val bucketsJson = runCatching { JSONObject(bodyString(listResp)).optJSONArray("buckets") }.getOrNull() ?: JSONArray()
        var existingBucketName: String? = null
        var prefixBucketName: String? = null
        for (i in 0 until bucketsJson.length()) {
            val b = bucketsJson.optJSONObject(i) ?: continue
            val name = b.optString("bucketName").trim()
            if (name == PREFERRED_BUCKET) {
                existingBucketName = name
                break
            }
            if (prefixBucketName == null && name.startsWith("$PREFERRED_BUCKET-")) {
                prefixBucketName = name
            }
        }
        var targetBucket = existingBucketName ?: prefixBucketName
        var createdNew = false
        if (targetBucket == null) {
            val suffix = accountId.takeLast(6).lowercase(Locale.US)
            val candidates = listOf(PREFERRED_BUCKET, "$PREFERRED_BUCKET-$suffix")
            for (candidate in candidates) {
                val createPayload = JSONObject()
                    .put("accountId", accountId)
                    .put("bucketName", candidate)
                    .put("bucketType", "allPrivate")
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)
                val createResp = runCatching {
                    http.request(
                        method = "POST",
                        url = "$apiUrl/b2api/v3/b2_create_bucket",
                        headers = mapOf(
                            "Authorization" to authToken,
                            "Content-Type" to "application/json",
                        ),
                        body = R2RestBody.Bytes(createPayload),
                    )
                }.getOrNull() ?: continue
                if (createResp.status in 200..299) {
                    val createdName = runCatching {
                        JSONObject(bodyString(createResp)).optString("bucketName").trim()
                    }.getOrNull().orEmpty().ifEmpty { candidate }
                    targetBucket = createdName
                    createdNew = true
                    break
                }
            }
        }
        val resolvedBucket = targetBucket
            ?: return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.CREATE_BUCKET_FAILED)

        // Master Application Keys (12 hex chars, no "00" prefix) are rejected by B2's S3-compatible
        // endpoint with `InvalidAccessKeyId`. If the user supplied a Master Key, mint or reuse a
        // dedicated S3-compatible Application Key via `b2_create_key`.
        val (s3KeyId, s3AppKey) = if (isB2MasterKeyId(keyId)) {
            mintB2S3Key(apiUrl, authToken, accountId)
                ?: return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.B2_KEY_MINT_FAILED)
        } else {
            keyId to applicationKey
        }

        return S3ApiKeyProvisionResult.Success(
            provider = S3ApiKeyProvider.BACKBLAZE_B2,
            binding = BridgeR2Binding(
                endpoint = s3ApiUrl,
                region = region,
                accessKeyId = s3KeyId,
                secretAccessKey = s3AppKey,
                bucket = resolvedBucket,
            ),
            createdNewBucket = createdNew,
        )
    }

    private fun mintB2S3Key(
        apiUrl: String,
        authToken: String,
        accountId: String,
    ): Pair<String, String>? {
        val capabilities = JSONArray()
            .put("listBuckets")
            .put("listAllBucketNames")
            .put("readBuckets")
            .put("writeBuckets")
            .put("listFiles")
            .put("readFiles")
            .put("writeFiles")
            .put("deleteFiles")
        val payload = JSONObject()
            .put("accountId", accountId)
            .put("keyName", "easemic-s3-key")
            .put("capabilities", capabilities)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val resp = runCatching {
            http.request(
                method = "POST",
                url = "$apiUrl/b2api/v3/b2_create_key",
                headers = mapOf(
                    "Authorization" to authToken,
                    "Content-Type" to "application/json",
                ),
                body = R2RestBody.Bytes(payload),
            )
        }.getOrNull() ?: return null
        if (resp.status !in 200..299) return null
        val json = runCatching { JSONObject(bodyString(resp)) }.getOrNull() ?: return null
        val newKeyId = json.optString("applicationKeyId").trim()
        val newAppKey = json.optString("applicationKey").trim()
        if (newKeyId.isEmpty() || newAppKey.isEmpty()) return null
        return newKeyId to newAppKey
    }

    private fun provisionCloudflareR2(rawToken: String): S3ApiKeyProvisionResult {
        val token = rawToken.removePrefix("Bearer ").trim()
        val admin = CloudflareR2AdminClient(http)
        val accounts = when (val listed = admin.listAccountIds(token)) {
            is CloudflareR2AdminResult.Ok -> listed.value
            is CloudflareR2AdminResult.Failed -> {
                val msg = if (listed.r2NotEnabled) BridgeR2BindCopy.R2_NOT_ENABLED else S3ApiKeyCopy.INVALID_CF_TOKEN
                return S3ApiKeyProvisionResult.Failed(msg)
            }
        }
        val accountId = when (val selected = admin.selectAccountId(token, accounts)) {
            is CloudflareR2AdminResult.Ok -> selected.value
            is CloudflareR2AdminResult.Failed -> return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.INVALID_CF_TOKEN)
        }
        val existingBuckets = when (val b = admin.listBucketNames(token, accountId)) {
            is CloudflareR2AdminResult.Ok -> b.value
            is CloudflareR2AdminResult.Failed -> {
                val msg = if (b.r2NotEnabled) BridgeR2BindCopy.R2_NOT_ENABLED else S3ApiKeyCopy.INVALID_CF_TOKEN
                return S3ApiKeyProvisionResult.Failed(msg)
            }
        }
        val alreadyExists = PREFERRED_BUCKET in existingBuckets
        val bucket = if (alreadyExists) {
            PREFERRED_BUCKET
        } else {
            when (val created = admin.createBucket(token, accountId, PREFERRED_BUCKET)) {
                is CloudflareR2AdminResult.Ok -> created.value
                is CloudflareR2AdminResult.Failed -> return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.CREATE_BUCKET_FAILED)
            }
        }
        val tokenId = verifyCloudflareTokenId(token, accountId)
            ?: return S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.INVALID_CF_TOKEN)
        val secretKey = sha256Hex(token)
        return S3ApiKeyProvisionResult.Success(
            provider = S3ApiKeyProvider.CLOUDFLARE_R2,
            binding = BridgeR2Binding(
                endpoint = "https://$accountId.r2.cloudflarestorage.com",
                region = "auto",
                accessKeyId = tokenId,
                secretAccessKey = secretKey,
                bucket = bucket,
            ),
            createdNewBucket = !alreadyExists,
        )
    }

    private fun verifyCloudflareTokenId(token: String, accountId: String): String? {
        val urls = listOf(
            "https://api.cloudflare.com/client/v4/accounts/$accountId/tokens/verify",
            "https://api.cloudflare.com/client/v4/user/tokens/verify",
        )
        for (url in urls) {
            val resp = runCatching {
                http.request(
                    method = "GET",
                    url = url,
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Accept" to "application/json",
                    ),
                    body = null,
                )
            }.getOrNull() ?: continue
            if (resp.status in 200..299) {
                val id = runCatching {
                    JSONObject(bodyString(resp)).optJSONObject("result")?.optString("id")?.trim()
                }.getOrNull()
                if (!id.isNullOrEmpty()) return id
            }
        }
        return null
    }

    companion object {
        const val PREFERRED_BUCKET: String = "easemic-comics"
        private const val BUNNY_STORAGE_ZONES_URL = "https://api.bunny.net/storagezone"
        private const val B2_AUTHORIZE_URL = "https://api.backblazeb2.com/b2api/v3/b2_authorize_account"

        // Bunny Account API Key: 36-char UUID or 72/73-char double-UUID with hyphens
        private val BUNNY_UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" +
                "(-?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})?$",
        )
        private val CF_TOKEN_REGEX = Regex("^(cfut_|cfat_)[A-Za-z0-9_-]{30,80}$|^[A-Za-z0-9_-]{40}$")

        fun detectProvider(rawInput: String): S3ApiKeyProvider? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null
            if (BUNNY_UUID_REGEX.matches(trimmed)) {
                return S3ApiKeyProvider.BUNNY
            }
            if (parseBackblazePair(trimmed) != null) {
                return S3ApiKeyProvider.BACKBLAZE_B2
            }
            val tokenCandidate = trimmed.removePrefix("Bearer ").trim()
            if (CF_TOKEN_REGEX.matches(tokenCandidate)) {
                return S3ApiKeyProvider.CLOUDFLARE_R2
            }
            return null
        }

        fun parseBackblazePair(rawInput: String): Pair<String, String>? {
            val trimmed = rawInput.trim()
            val parts = when {
                ':' in trimmed -> trimmed.split(':', limit = 2)
                '\n' in trimmed -> trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
                ' ' in trimmed -> trimmed.split(Regex("\\s+"), limit = 2)
                else -> return null
            }
            if (parts.size != 2) return null
            val keyId = parts[0].trim()
            val appKey = parts[1].trim()
            val validKeyId = keyId.matches(Regex("^[0-9a-fA-F]{12,28}$"))
            val validAppKey = appKey.length in 28..48 && appKey.all { !it.isWhitespace() }
            return if (validKeyId && validAppKey) keyId to appKey else null
        }

        /** Backblaze Master Application Keys are 12 hex digits without the `00<region>` prefix of S3-ready keys. */
        internal fun isB2MasterKeyId(keyId: String): Boolean =
            keyId.length == 12 && !keyId.startsWith("000") && !keyId.startsWith("001") &&
                !keyId.startsWith("002") && !keyId.startsWith("003") &&
                !keyId.startsWith("004") && !keyId.startsWith("005")

        internal fun extractB2Region(s3ApiUrl: String): String {
            val host = runCatching { java.net.URI(s3ApiUrl).host }.getOrNull() ?: s3ApiUrl
            val match = Regex("s3\\.([a-z0-9-]+)\\.backblazeb2\\.com", RegexOption.IGNORE_CASE).find(host)
            return match?.groupValues?.getOrNull(1)?.lowercase(Locale.US) ?: "us-west-004"
        }

        internal fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(String.format(Locale.US, "%02x", b))
            }
            return sb.toString()
        }

        private fun bodyString(response: R2RestHttpResponse): String =
            String(response.body, StandardCharsets.UTF_8)

        private fun parseJsonArray(raw: String): JSONArray? =
            runCatching { JSONArray(raw) }.getOrNull()
    }
}

object S3ApiKeyCopy {
    val DIALOG_TITLE: String get() = tr("Connect with API key", "使用 API Key 连接", "使用 API Key 連線", "API キーで接続")
    val DIALOG_SUBTITLE: String get() = tr(
        "Paste an API key below. Easemic detects the provider, looks for easemic-comics, and creates it if needed.",
        "在下方粘贴 API Key。Easemic 会识别服务商，查找 easemic-comics，并在需要时创建它。",
        "在下方貼上 API Key。Easemic 會識別服務商，尋找 easemic-comics，並在需要時建立它。",
        "下に API キーを貼り付けます。Easemic がプロバイダーを判別し、easemic-comics を検索・必要なら作成します。",
    )
    val INPUT_LABEL: String get() = tr("API key or token", "API Key 或 Token", "API Key 或 Token", "API キーまたはトークン")
    val INPUT_PLACEHOLDER: String get() = tr("Bunny Account Key, B2 keyID:appKey, or R2 Token", "Bunny Account Key、B2 keyID:appKey 或 R2 Token", "Bunny Account Key、B2 keyID:appKey 或 R2 Token", "Bunny Account Key、B2 keyID:appKey、または R2 Token")
    val CONNECT_CTA: String get() = tr("Connect easemic-comics", "连接 easemic-comics", "連線 easemic-comics", "easemic-comics に接続")
    val CONNECTING_CTA: String get() = tr("Connecting…", "连接中…", "連線中…", "接続中…")

    val KEY_REQUIRED: String get() = tr("Paste an API key first.", "请先粘贴 API Key。", "請先貼上 API Key。", "先に API キーを貼り付けてください。")
    val UNRECOGNIZED_KEY: String get() = tr(
        "Could not recognize this key format. Select Bunny.net, Backblaze B2, or Cloudflare R2 above.",
        "无法识别此密钥格式。请在上方选择 Bunny.net、Backblaze B2 或 Cloudflare R2。",
        "無法識別此金鑰格式。請在上方選擇 Bunny.net、Backblaze B2 或 Cloudflare R2。",
        "このキー形式を認識できません。上で Bunny.net、Backblaze B2、または Cloudflare R2 を選択してください。",
    )
    val INVALID_BUNNY_KEY: String get() = tr(
        "Bunny.net rejected this Account API Key. Copy it from the account menu → API key.",
        "Bunny.net 拒绝了此 Account API Key。请从账户菜单 → API key 复制。",
        "Bunny.net 拒絕了此 Account API Key。請從帳戶選單 → API key 複製。",
        "Bunny.net がこの Account API キーを拒否しました。アカウントメニュー → API key からコピーしてください。",
    )
    val INVALID_B2_KEY: String get() = tr(
        "Backblaze B2 rejected this key. Paste keyID:applicationKey from Application Keys.",
        "Backblaze B2 拒绝了此密钥。请从 Application Keys 粘贴 keyID:applicationKey。",
        "Backblaze B2 拒絕了此金鑰。請從 Application Keys 貼上 keyID:applicationKey。",
        "Backblaze B2 がこのキーを拒否しました。Application Keys の keyID:applicationKey を貼り付けてください。",
    )
    val INVALID_CF_TOKEN: String get() = tr(
        "Cloudflare rejected this R2 API Token. Check that it has the Account · Workers R2 Storage · Edit permission.",
        "Cloudflare 拒绝了此 R2 API Token。请确认它拥有 Account · Workers R2 Storage · Edit 权限。",
        "Cloudflare 拒絕了此 R2 API Token。請確認它擁有 Account · Workers R2 Storage · Edit 權限。",
        "Cloudflare がこの R2 API トークンを拒否しました。Account · Workers R2 Storage · Edit 権限を確認してください。",
    )
    val B2_KEY_MINT_FAILED: String get() = tr(
        "Could not create an S3-compatible key on Backblaze B2. Create an Application Key directly in Backblaze.",
        "无法在 Backblaze B2 创建 S3 兼容密钥。请直接在 Backblaze 创建 Application Key。",
        "無法在 Backblaze B2 建立 S3 相容金鑰。請直接在 Backblaze 建立 Application Key。",
        "Backblaze B2 に S3 互換キーを作成できませんでした。Backblaze で Application Key を直接作成してください。",
    )
    val CREATE_BUCKET_FAILED: String get() = tr(
        "Connected to your account, but could not create the easemic-comics bucket.",
        "已连接到账户，但无法创建 easemic-comics 存储桶。",
        "已連線至帳戶，但無法建立 easemic-comics 儲存桶。",
        "アカウントには接続しましたが、easemic-comics バケットを作成できませんでした。",
    )
    val NETWORK_ERROR: String get() = tr(
        "Could not reach the storage provider. Check your network and try again.",
        "无法连接存储服务商。请检查网络后重试。",
        "無法連線儲存服務商。請檢查網路後重試。",
        "ストレージプロバイダーに接続できません。ネットワークを確認して再試行してください。",
    )
    val PROVIDER_ERROR: String get() = tr(
        "The storage provider returned an unexpected error. Try again.",
        "存储服务商返回了意外错误。请重试。",
        "儲存服務商返回了意外錯誤。請重試。",
        "ストレージプロバイダーで予期しないエラーが発生しました。再試行してください。",
    )
}
