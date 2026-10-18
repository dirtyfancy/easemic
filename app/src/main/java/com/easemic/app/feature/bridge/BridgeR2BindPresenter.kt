package com.easemic.app.feature.bridge

import com.easemic.app.sync.ObjectStore
import com.easemic.app.sync.WebDavBinding
import com.easemic.app.i18n.tr
import com.easemic.app.ui.PreferenceMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** English-only copy for the R2 paste screen. */
object BridgeR2BindCopy {
    const val R2_DASHBOARD: String = "https://dash.cloudflare.com/?to=/:account/r2"

    val FIELDS_REQUIRED: String get() = tr("Endpoint, Region, Access Key, Secret, and bucket are required.", "Endpoint、区域、访问密钥、密钥和存储桶均为必填项。", "Endpoint、區域、存取金鑰、密鑰與儲存桶皆為必填項。", "Endpoint、リージョン、アクセスキー、シークレット、バケットをすべて入力してください。")

    val SAVED: String get() = tr("Cloud storage saved on this device.", "云存储已保存到本机。", "雲端儲存已保存在本機。", "クラウドストレージをこの端末に保存しました。")

    val CONNECT_CLOUDFLARE: String get() = tr("Connect Cloudflare", "连接 Cloudflare", "連接 Cloudflare", "Cloudflare に接続")

    val R2_NOT_ENABLED: String get() = tr("R2 is not enabled on this Cloudflare account.", "此 Cloudflare 账号未启用 R2。", "此 Cloudflare 帳號未啟用 R2。", "この Cloudflare アカウントでは R2 が有効になっていません。")

    val CONNECT_NOT_SET_UP: String get() = tr("Connect Cloudflare is not set up on this build.", "此版本未配置 Connect Cloudflare。", "此版本未設定 Connect Cloudflare。", "このビルドでは Connect Cloudflare が設定されていません。")

    val CONNECT_FAILED: String get() = tr("Could not connect to Cloudflare. Try again.", "无法连接 Cloudflare，请重试。", "無法連接 Cloudflare，請重試。", "Cloudflare に接続できません。再試行してください。")

    val CONNECT_CANCELLED: String get() = tr("Cloudflare sign-in was cancelled. Try again or paste credentials.", "Cloudflare 登录已取消。请重试或粘贴凭据。", "Cloudflare 登入已取消。請重試或貼上憑證。", "Cloudflare のサインインをキャンセルしました。再試行するか認証情報を貼り付けてください。")

    val DISCONNECTED: String get() = tr("Disconnected. This bucket was not touched.", "已断开连接，未改动此存储桶。", "已中斷連線，未變更此儲存桶。", "接続を解除しました。このバケットは変更していません。")

    val ENDPOINT_INVALID: String get() = tr("Endpoint must be a full URL, like https://s3.example.com or http://localhost:9000.", "端点必须是完整 URL，例如 https://s3.example.com 或 http://localhost:9000。", "端點必須是完整 URL，例如 https://s3.example.com 或 http://localhost:9000。", "エンドポイントは https://s3.example.com や http://localhost:9000 のような完全な URL にしてください。")

    val BUCKET_UNREACHABLE: String get() = tr("Could not reach this bucket. Check the details and try again.", "无法访问此存储桶，请检查信息后重试。", "無法連線此儲存桶，請檢查資訊後重試。", "このバケットに接続できません。内容を確認して再試行してください。")
}

/** #C50: which kind of place the library is bound to; drives are always encrypted. */
enum class BoundKind { S3, CLOUDFLARE_OAUTH, WEBDAV, ONEDRIVE, GOOGLE_DRIVE }

/** #C50: the bound label of a WebDAV library — host (+ folder), never the account or password. */
object WebDavBoundLabel {
    fun format(binding: WebDavBinding): String {
        val host = runCatching { java.net.URI(binding.baseUrl.trim()).host }.getOrNull() ?: binding.baseUrl.trim()
        val folder = binding.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
        return if (folder.isEmpty()) host else "$host/$folder"
    }
}

/** More → Cloud storage subtitle. Never includes Access Key, Secret or a drive password. */
object BridgeR2StorageSubtitle {
    fun format(bucketLabel: String?, kind: BoundKind? = null, signInRejected: Boolean = false): String {
        val label = bucketLabel?.trim()?.takeIf { it.isNotEmpty() }
        // #D16: a drive that refuses the saved sign-in says so on the row that leads to the fix.
        if (label != null && signInRejected) CloudSignInCopy.status(kind)?.let { return it }
        return when {
            label == null -> tr("Connect Cloudflare or paste S3 credentials.", "连接 Cloudflare 或粘贴 S3 凭据。", "連接 Cloudflare 或貼上 S3 憑證。", "Cloudflare に接続するか S3 認証情報を貼り付けてください。")
            kind == BoundKind.WEBDAV -> tr("WebDAV · $label · Encrypted", "WebDAV · $label · 已加密", "WebDAV · $label · 已加密", "WebDAV · $label · 暗号化済み")
            kind == BoundKind.ONEDRIVE -> tr("OneDrive · $label · Encrypted", "OneDrive · $label · 已加密", "OneDrive · $label · 已加密", "OneDrive · $label · 暗号化済み")
            kind == BoundKind.GOOGLE_DRIVE -> tr("Google Drive · $label · Encrypted", "Google Drive · $label · 已加密", "Google Drive · $label · 已加密", "Google Drive · $label · 暗号化済み")
            else -> tr("Bound to $label", "已连接到 $label", "已連接至 $label", "$label に接続済み")
        }
    }
}

/**
 * Plain libraries: a bound bucket is a usable bucket. Encrypted libraries add
 * a bound-but-locked state, owned by [LibraryLockPresenter] — this state only
 * tracks the binding itself. Readiness here is derived from [boundBucketLabel]
 * alone.
 */
data class BridgeR2BindUiState(
    val endpoint: String = "",
    val region: String = "auto",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val boundBucketLabel: String? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    /** Unbound only: paste fields disclosed. The bound state has no form. */
    val pasteFormExpanded: Boolean = false,
    /** Feeds the bound status line: OAuth vs pasted credentials. */
    val boundViaOAuth: Boolean = false,
    /** #C50: what [boundBucketLabel] names; null while unbound. */
    val boundKind: BoundKind? = null,
    /** Bumped on every successful bind so usage re-measures even when the
     *  bucket label is unchanged (same-name Replace, smoke finding 2). */
    val bindGeneration: Int = 0,
    /**
     * #C57: non-null while a bind to an EMPTY bucket waits for "encrypt or not".
     * Nothing is persisted and the previous binding stays live until it is answered.
     */
    val modeChoice: ModeChoiceUiState? = null,
)

class BridgeR2BindPresenter(
    private val bindingCache: LocalR2BindingCache,
    private val deviceStore: BridgeDeviceR2SecretStore,
    private val oauthStore: BridgeOAuthDeviceSecretStore = BridgeOAuthDeviceSecretStore(MemoryPrefs()),
    /** #C50: the WebDAV binding; every commit clears the kinds it is not. */
    private val webDavStore: BridgeDeviceWebDavStore = BridgeDeviceWebDavStore(MemoryPrefs()),
    private val oneDriveStore: BridgeDeviceOneDriveStore = BridgeDeviceOneDriveStore(MemoryPrefs()),
    private val googleDriveStore: BridgeDeviceGoogleDriveStore = BridgeDeviceGoogleDriveStore(MemoryPrefs()),
    private val pkceSessionStore: BridgeOAuthPkceSessionStore = BridgeOAuthPkceSessionStore(MemoryPrefs()),
    private val pkce: CloudflarePkceClient? = null,
    private val r2Admin: CloudflareR2AdminClient = CloudflareR2AdminClient(),
    private val openAuthorization: (String) -> Unit = {},
    private val clientId: String = CloudflareOAuthConfig.CLIENT_ID,
    /** Committed bind; carries the new library DEK when the user chose encryption (#C57). */
    private val onBindReplaced: (ByteArray?) -> Unit = {},
    private val bindingMutationGate: CloudBindingMutationGate = CloudBindingMutationGate(),
    /** #C64: runs right before a commit rewrites the durable binding; the app wires it to `CloudLibraryGate.beginSwitch()`. */
    private val onBindSwitching: () -> Unit = {},
    /** #C64: the durable rewrite failed after [onBindSwitching]; the app ends the switch (`finishSwitch(null)`). */
    private val onBindSwitchAborted: () -> Unit = {},
    /**
     * C37-j: the device's own location signal (time zone, then SIM/network
     * country) for a fixed-name bucket create. Cloudflare otherwise places a
     * hint-less bucket next to the creator's IP, which a VPN exit fools. Null
     * lets Cloudflare decide; the app wires this to [R2LocationHint.fromDevice].
     */
    private val locationHint: () -> String? = { null },
    /** #C57: transient, never-persisted store that probes a candidate bucket and writes its first lock. */
    private val candidateStore: (BindCandidate) -> ObjectStore = ::defaultCandidateStore,
    /** #E121: single-key auto-provisioner for Bunny.net, Backblaze B2, and Cloudflare R2 API tokens. */
    private val apiKeyProvisioner: S3ApiKeyAutoProvisioner = S3ApiKeyAutoProvisioner(),
) {
    private val _states = MutableStateFlow(BridgeR2BindUiState())
    val states: StateFlow<BridgeR2BindUiState> = _states.asStateFlow()
    val state: BridgeR2BindUiState get() = _states.value
    private var pkceSession: CloudflarePkceSession? = null
    private var pendingCandidate: BindCandidate? = null
    private var pendingStore: ObjectStore? = null

    fun onEndpointChange(value: String) {
        _states.update { it.copy(endpoint = value, errorMessage = null, infoMessage = null) }
    }

    fun onRegionChange(value: String) {
        _states.update { it.copy(region = value, errorMessage = null, infoMessage = null) }
    }

    fun onAccessKeyChange(value: String) {
        _states.update { it.copy(accessKeyId = value, errorMessage = null, infoMessage = null) }
    }

    fun onSecretChange(value: String) {
        _states.update { it.copy(secretAccessKey = value, errorMessage = null, infoMessage = null) }
    }

    fun onBucketChange(value: String) {
        _states.update { it.copy(bucket = value, errorMessage = null, infoMessage = null) }
    }

    fun onPasteFormToggle() {
        _states.update { it.copy(pasteFormExpanded = !it.pasteFormExpanded) }
    }

    fun reset() {
        _states.value = BridgeR2BindUiState()
        pkceSession = null
        pendingCandidate = null
        pendingStore = null
    }

    suspend fun refreshBoundLabel() {
        if (bindingCache.workingBinding() != null) {
            // Working binding is live, so only the label can be stale: derive it
            // from the durable stores without touching the form fields.
            val pasted = deviceStore.load()?.bucket
            val boundBucket = pasted ?: oauthStore.load()?.bucket
            _states.update {
                it.copy(
                    boundBucketLabel = boundBucket,
                    boundViaOAuth = pasted == null && boundBucket != null,
                    boundKind = when {
                        pasted != null -> BoundKind.S3
                        boundBucket != null -> BoundKind.CLOUDFLARE_OAUTH
                        else -> null
                    },
                )
            }
            return
        }
        // restoreFromDevice() reads the same stores and sets boundBucketLabel
        // itself (including the null case), so deriving again would re-read them.
        restoreFromDevice()
    }

    suspend fun restoreFromDevice() {
        val binding = deviceStore.load()
        if (binding != null) {
            bindingCache.cacheWorking(binding)
            _states.update {
                it.copy(
                    endpoint = binding.endpoint,
                    region = binding.region,
                    accessKeyId = "",
                    secretAccessKey = "",
                    bucket = binding.bucket,
                    boundBucketLabel = binding.bucket,
                    boundViaOAuth = false,
                    boundKind = BoundKind.S3,
                    errorMessage = null,
                )
            }
            return
        }
        val oauth = oauthStore.load()
        if (oauth == null) {
            val dav = webDavStore.load()
            val od = if (dav == null) oneDriveStore.load() else null
            val gd = if (dav == null && od == null) googleDriveStore.load() else null
            _states.update {
                it.copy(
                    boundBucketLabel = dav?.let(WebDavBoundLabel::format) ?: od?.account ?: gd?.account,
                    boundViaOAuth = false,
                    boundKind = when {
                        dav != null -> BoundKind.WEBDAV
                        od != null -> BoundKind.ONEDRIVE
                        gd != null -> BoundKind.GOOGLE_DRIVE
                        else -> null
                    },
                )
            }
            return
        }
        _states.update {
            it.copy(
                accessKeyId = "",
                secretAccessKey = "",
                bucket = oauth.bucket,
                boundBucketLabel = oauth.bucket,
                boundViaOAuth = true,
                boundKind = BoundKind.CLOUDFLARE_OAUTH,
                errorMessage = null,
            )
        }
    }

    fun onConnectCloudflare() {
        if (state.busy || state.modeChoice != null) return
        val pkceClient = pkce
        if (pkceClient == null || clientId.isBlank()) {
            _states.update { it.copy(errorMessage = BridgeR2BindCopy.CONNECT_NOT_SET_UP) }
            return
        }
        val session = pkceClient.begin()
        pkceSession = session
        pkceSessionStore.save(session)
        _states.update { it.copy(errorMessage = null, infoMessage = null) }
        openAuthorization(session.authorizationUri)
    }

    fun onOAuthFailure(error: String, description: String?) {
        clearPendingPkce()
        val message = messageForOAuthFailure(error)
        _states.update {
            it.copy(busy = false, errorMessage = message, infoMessage = null)
        }
    }

    suspend fun onOAuthRedirect(code: String, state: String) {
        if (this.state.busy || this.state.modeChoice != null) return
        val pkceClient = pkce
        val session = pkceSession ?: pkceSessionStore.load()
        if (pkceClient == null || clientId.isBlank()) {
            _states.update { it.copy(errorMessage = BridgeR2BindCopy.CONNECT_NOT_SET_UP) }
            return
        }
        if (session == null) {
            if (this.state.boundBucketLabel != null && oauthStore.load() != null) {
                return
            }
            _states.update { it.copy(errorMessage = BridgeR2BindCopy.CONNECT_NOT_SET_UP) }
            return
        }
        _states.update { it.copy(busy = true, errorMessage = null, infoMessage = null) }
        try {
            withContext(Dispatchers.IO) {
                when (val exchanged = pkceClient.exchange(code, state, session)) {
                    is CloudflareOAuthExchangeResult.Failed -> {
                        _states.update {
                            it.copy(busy = false, errorMessage = BridgeR2BindCopy.CONNECT_FAILED)
                        }
                    }
                    is CloudflareOAuthExchangeResult.Success -> {
                        completeOAuthBind(exchanged.tokens)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _states.update {
                it.copy(busy = false, errorMessage = BridgeR2BindCopy.CONNECT_FAILED)
            }
        } finally {
            clearPendingPkce()
            if (this.state.busy) {
                _states.update {
                    it.copy(
                        busy = false,
                        errorMessage = it.errorMessage ?: BridgeR2BindCopy.CONNECT_FAILED,
                    )
                }
            }
        }
    }

    suspend fun onSave() {
        // Check-then-act is safe: no suspension before busy=true lands, and every
        // call comes from the main dispatcher.
        if (state.busy || state.modeChoice != null) return
        val binding = bindingOrNull()
        if (binding == null) {
            _states.update { it.copy(errorMessage = BridgeR2BindCopy.FIELDS_REQUIRED) }
            return
        }
        if (!R2EndpointFormat.isValid(binding.endpoint)) {
            _states.update { it.copy(errorMessage = BridgeR2BindCopy.ENDPOINT_INVALID) }
            return
        }
        _states.update { it.copy(busy = true, errorMessage = null, infoMessage = null) }
        val candidate = BindCandidate.Pasted(binding)
        val store = candidateStore(candidate)
        when (probeIsEmpty(store)) {
            null -> _states.update {
                it.copy(busy = false, errorMessage = BridgeR2BindCopy.BUCKET_UNREACHABLE)
            }
            true -> holdForModeChoice(candidate, store)
            false -> bindingMutationGate.withMutation { commitLocked(candidate, dek = null) }
        }
    }

    fun setPasteFormExpanded(expanded: Boolean) {
        _states.update { it.copy(pasteFormExpanded = expanded, errorMessage = null, infoMessage = null) }
    }

    /**
     * #E121: Auto-detects the provider from [rawApiKey] (or uses [providerOverride]),
     * finds or creates `easemic-comics`, and binds it through the exact same empty-bucket
     * encryption prompt or existing-bucket lock pipeline as [onSave].
     */
    suspend fun onConnectWithApiKey(
        rawApiKey: String,
        providerOverride: S3ApiKeyProvider? = null,
    ): Boolean {
        if (state.busy || state.modeChoice != null) return false
        _states.update { it.copy(busy = true, errorMessage = null, infoMessage = null) }
        val result = try {
            withContext(Dispatchers.IO) {
                apiKeyProvisioner.provision(rawApiKey, providerOverride)
            }
        } catch (cancelled: CancellationException) {
            _states.update { it.copy(busy = false) }
            throw cancelled
        } catch (_: Exception) {
            S3ApiKeyProvisionResult.Failed(S3ApiKeyCopy.NETWORK_ERROR)
        }
        when (result) {
            is S3ApiKeyProvisionResult.Failed -> {
                _states.update { it.copy(busy = false, errorMessage = result.message) }
                return false
            }
            is S3ApiKeyProvisionResult.Success -> {
                val candidate = BindCandidate.Pasted(result.binding)
                val store = candidateStore(candidate)
                when (probeIsEmpty(store)) {
                    null -> {
                        _states.update {
                            it.copy(busy = false, errorMessage = BridgeR2BindCopy.BUCKET_UNREACHABLE)
                        }
                        return false
                    }
                    true -> {
                        holdForModeChoice(candidate, store)
                        return true
                    }
                    false -> {
                        bindingMutationGate.withMutation { commitLocked(candidate, dek = null) }
                        return true
                    }
                }
            }
        }
    }

    fun onModeChoiceEncryptSelected() = updateModeChoice { it.copy(encryptSelected = true, errorMessage = null) }

    fun onModeChoicePasswordChange(value: String) = updateModeChoice { it.copy(password = value, errorMessage = null) }

    fun onModeChoiceConfirmChange(value: String) = updateModeChoice { it.copy(confirm = value, errorMessage = null) }

    /** Back or Cancel: nothing is saved; the previous binding (if any) is untouched. */
    fun onModeChoiceCancel() {
        // A stray tap after a commit closed the dialog must not overwrite "saved".
        val choice = state.modeChoice ?: return
        if (choice.busy) return
        pendingCandidate = null
        pendingStore = null
        _states.update {
            it.copy(modeChoice = null, busy = false, errorMessage = null, infoMessage = ModeChoiceCopy.CANCELLED)
        }
    }

    suspend fun onModeChoiceDontEncrypt() {
        val candidate = pendingCandidate ?: return
        if (state.modeChoice?.busy != false) return
        // Busy BEFORE queueing on the binding lock: the release sweeps hold it
        // across file IO, and the answer must not stay cancellable (or
        // re-tappable) while it waits.
        updateModeChoice { it.copy(busy = true, errorMessage = null) }
        commitHeld(candidate, dek = null)
    }

    suspend fun onModeChoiceTurnOnEncryption() {
        val candidate = pendingCandidate ?: return
        val store = pendingStore ?: return
        val choice = state.modeChoice ?: return
        if (choice.busy || !choice.encryptSelected) return
        if (choice.password.isEmpty()) {
            updateModeChoice { it.copy(errorMessage = LibraryLockCopy.PASSWORD_REQUIRED) }
            return
        }
        if (choice.password != choice.confirm) {
            updateModeChoice { it.copy(errorMessage = LibraryLockCopy.PASSWORDS_DONT_MATCH) }
            return
        }
        updateModeChoice { it.copy(busy = true, errorMessage = null) }
        val outcome = try {
            withContext(Dispatchers.IO) { writeFirstLibraryLock(store, choice.password.toCharArray()) }
        } catch (cancelled: CancellationException) {
            updateModeChoice { it.copy(busy = false) }
            throw cancelled
        } catch (_: Exception) {
            null
        }
        when (outcome) {
            null -> updateModeChoice { it.copy(busy = false, errorMessage = LibraryLockCopy.OPERATION_FAILED) }
            LockCreateResult.NotEmpty -> {
                // Never clobber: content raced in, so its mode is decided. Nothing is
                // saved; saving again takes the non-empty path.
                pendingCandidate = null
                pendingStore = null
                _states.update { it.copy(modeChoice = null, errorMessage = LibraryLockCopy.BUCKET_NOT_EMPTY) }
            }
            is LockCreateResult.Created -> commitHeld(candidate, outcome.dek)
        }
    }

    /** Commits the held [candidate] once the binding lock is ours — unless it was discarded meanwhile. */
    private suspend fun commitHeld(candidate: BindCandidate, dek: ByteArray?) {
        bindingMutationGate.withMutation {
            if (pendingCandidate === candidate) commitLocked(candidate, dek)
        }
    }

    private fun holdForModeChoice(candidate: BindCandidate, store: ObjectStore) {
        pendingCandidate = candidate
        pendingStore = store
        _states.update {
            it.copy(busy = false, errorMessage = null, infoMessage = null, modeChoice = ModeChoiceUiState())
        }
    }

    private fun updateModeChoice(edit: (ModeChoiceUiState) -> ModeChoiceUiState) {
        _states.update { current -> current.modeChoice?.let { current.copy(modeChoice = edit(it)) } ?: current }
    }

    /** True = empty, false = has content, null = could not list it. */
    private suspend fun probeIsEmpty(store: ObjectStore): Boolean? =
        try {
            withContext(Dispatchers.IO) { store.listKeys().isEmpty() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /**
     * The one place a binding becomes durable. The caller holds
     * [bindingMutationGate]; it is a non-reentrant Mutex, so this must never
     * take it again.
     */
    private fun commitLocked(candidate: BindCandidate, dek: ByteArray?) {
        onBindSwitching()
        try {
            when (candidate) {
                is BindCandidate.Pasted -> {
                    deviceStore.save(candidate.binding)
                    bindingCache.cacheWorking(candidate.binding)
                    oauthStore.clear()
                    webDavStore.clear()
                    oneDriveStore.clear()
                    googleDriveStore.clear()
                }
                is BindCandidate.OAuth -> {
                    deviceStore.clear()
                    webDavStore.clear()
                    oneDriveStore.clear()
                    googleDriveStore.clear()
                    // Latest bind wins in-process too: a stale pasted working binding
                    // would keep serving IO for the old bucket while the UI reports
                    // the OAuth one.
                    bindingCache.clearWorking()
                    oauthStore.save(candidate.secrets)
                }
            }
        } catch (failure: Exception) {
            // #C64: never leave the gate mid-switch — every cloud op would fail
            // until the process restarts.
            onBindSwitchAborted()
            throw failure
        }
        pendingCandidate = null
        pendingStore = null
        _states.update {
            it.copy(
                accessKeyId = "",
                secretAccessKey = "",
                busy = false,
                boundBucketLabel = candidate.bucket,
                errorMessage = null,
                infoMessage = BridgeR2BindCopy.SAVED,
                pasteFormExpanded = false,
                boundViaOAuth = candidate is BindCandidate.OAuth,
                boundKind = if (candidate is BindCandidate.OAuth) BoundKind.CLOUDFLARE_OAUTH else BoundKind.S3,
                bindGeneration = it.bindGeneration + 1,
                modeChoice = null,
            )
        }
        onBindReplaced(dek)
    }

    /**
     * #C50: the WebDAV flow's commit — the same lock, #C64 switch and DEK hand-over as
     * [commitLocked], so a drive bind can never race or outlive an S3 / Cloudflare one.
     * [WebDavBindPresenter] only reaches here with a DEK: drive libraries are always
     * encrypted.
     */
    suspend fun commitWebDav(binding: WebDavBinding, dek: ByteArray) {
        commitDrive(
            BoundKind.WEBDAV,
            WebDavBoundLabel.format(binding),
            dek,
            clearOthers = {
                oneDriveStore.clear()
                googleDriveStore.clear()
            },
            save = { webDavStore.save(binding) },
        )
    }

    /** #C50: the OneDrive flow's commit — same discipline as [commitWebDav]. */
    suspend fun commitOneDrive(binding: OneDriveBinding, dek: ByteArray) {
        commitDrive(
            BoundKind.ONEDRIVE,
            binding.account,
            dek,
            clearOthers = {
                webDavStore.clear()
                googleDriveStore.clear()
            },
            save = { oneDriveStore.save(binding) },
        )
    }

    /** #C50: the Google Drive flow's commit — same discipline as [commitWebDav]. */
    suspend fun commitGoogleDrive(binding: GoogleDriveBinding, dek: ByteArray) {
        commitDrive(
            BoundKind.GOOGLE_DRIVE,
            binding.account,
            dek,
            clearOthers = {
                webDavStore.clear()
                oneDriveStore.clear()
            },
            save = { googleDriveStore.save(binding) },
        )
    }

    /**
     * #C50 "sign in again": the bound drive's new refresh token, nothing else — same folder, same
     * library, same DEK, so no gate switch and no mirror wipe. Under the binding lock.
     */
    suspend fun renewOneDriveSignIn(refreshToken: String) = bindingMutationGate.withMutation {
        oneDriveStore.load()?.let { oneDriveStore.save(it.copy(refreshToken = refreshToken)) }
    }

    suspend fun renewGoogleDriveSignIn(refreshToken: String) = bindingMutationGate.withMutation {
        googleDriveStore.load()?.let { googleDriveStore.save(it.copy(refreshToken = refreshToken)) }
    }

    /**
     * Every other kind is erased before the new binding is written, so a failure part-way
     * leaves at most one kind persisted — never two libraries claiming this device.
     */
    private suspend fun commitDrive(
        kind: BoundKind,
        label: String,
        dek: ByteArray,
        clearOthers: () -> Unit,
        save: () -> Unit,
    ) {
        bindingMutationGate.withMutation {
            onBindSwitching()
            try {
                clearOthers()
                deviceStore.clear()
                bindingCache.clearWorking()
                oauthStore.clear()
                save()
            } catch (failure: Exception) {
                onBindSwitchAborted()
                throw failure
            }
            pendingCandidate = null
            pendingStore = null
            _states.update {
                it.copy(
                    busy = false,
                    boundBucketLabel = label,
                    boundViaOAuth = false,
                    boundKind = kind,
                    errorMessage = null,
                    infoMessage = BridgeR2BindCopy.SAVED,
                    pasteFormExpanded = false,
                    bindGeneration = it.bindGeneration + 1,
                    modeChoice = null,
                )
            }
            onBindReplaced(dek)
        }
    }

    fun onDisconnect() {
        bindingMutationGate.withMutationBlocking disconnectMutation@{
            if (state.busy) return@disconnectMutation
            deviceStore.clear()
            oauthStore.clear()
            webDavStore.clear()
            oneDriveStore.clear()
            googleDriveStore.clear()
            pkceSessionStore.clear()
            bindingCache.clearWorking()
            pkceSession = null
            pendingCandidate = null
            pendingStore = null
            _states.value = BridgeR2BindUiState(infoMessage = BridgeR2BindCopy.DISCONNECTED)
        }
    }

    private suspend fun completeOAuthBind(tokens: CloudflareOAuthTokens) {
        when (val accounts = r2Admin.listAccountIds(tokens.accessToken)) {
            is CloudflareR2AdminResult.Failed -> {
                failOAuth(accounts)
                return
            }
            is CloudflareR2AdminResult.Ok -> {
                when (
                    val selected = r2Admin.selectAccountId(tokens.accessToken, accounts.value)
                ) {
                    is CloudflareR2AdminResult.Failed -> {
                        failOAuth(selected)
                        return
                    }
                    is CloudflareR2AdminResult.Ok -> {
                        val accountId = selected.value
                        // Fixed-name policy: always bind PREFERRED_BUCKET (create if
                        // missing). Foreign previous buckets (e.g. agent-mentor-pubs)
                        // migrate on this Replace / re-Connect — never sticky. A create
                        // carries the device-derived location hint (C37-j).
                        val bucketResult =
                            r2Admin.resolveBucket(tokens.accessToken, accountId, locationHint())
                        val bucket = when (bucketResult) {
                            is CloudflareR2AdminResult.Failed -> {
                                failOAuth(bucketResult)
                                return
                            }
                            is CloudflareR2AdminResult.Ok -> bucketResult.value
                        }
                        val candidate = BindCandidate.OAuth(
                            secrets = BridgeOAuthDeviceSecrets(
                                refreshToken = tokens.refreshToken,
                                accountId = accountId,
                                bucket = bucket,
                            ),
                            accessToken = tokens.accessToken,
                        )
                        val store = candidateStore(candidate)
                        // #C57: emptiness is judged by listing, not by "just created" —
                        // an existing-but-empty pcl-comics is asked too.
                        when (probeIsEmpty(store)) {
                            null -> _states.update {
                                it.copy(busy = false, errorMessage = BridgeR2BindCopy.CONNECT_FAILED)
                            }
                            true -> holdForModeChoice(candidate, store)
                            false -> bindingMutationGate.withMutation { commitLocked(candidate, dek = null) }
                        }
                    }
                }
            }
        }
    }

    private fun clearPendingPkce() {
        pkceSession = null
        pkceSessionStore.clear()
    }

    private fun messageForOAuthFailure(error: String): String =
        when (error.trim().lowercase()) {
            "access_denied" -> BridgeR2BindCopy.CONNECT_CANCELLED
            else -> BridgeR2BindCopy.CONNECT_FAILED
        }

    private fun failOAuth(failure: CloudflareR2AdminResult.Failed) {
        val message = if (failure.r2NotEnabled) {
            BridgeR2BindCopy.R2_NOT_ENABLED
        } else {
            BridgeR2BindCopy.CONNECT_FAILED
        }
        _states.update { it.copy(busy = false, errorMessage = message) }
    }

    private fun bindingOrNull(): BridgeR2Binding? {
        val endpoint = state.endpoint.trim()
        val region = state.region.trim()
        val accessKeyId = state.accessKeyId.trim()
        val secretAccessKey = state.secretAccessKey.trim()
        val bucket = state.bucket.trim()
        if (endpoint.isEmpty() || region.isEmpty() || accessKeyId.isEmpty() ||
            secretAccessKey.isEmpty() || bucket.isEmpty()
        ) {
            return null
        }
        return BridgeR2Binding(
            endpoint = endpoint,
            region = region,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            bucket = bucket,
        )
    }

    companion object {
        const val PREFERRED_BUCKET: String = CloudflareR2AdminClient.PREFERRED_BUCKET
    }
}

private class MemoryPrefs : PreferenceMap {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String, default: String?): String? = map[key] ?: default
    override fun putString(key: String, value: String) {
        map[key] = value
    }
}
