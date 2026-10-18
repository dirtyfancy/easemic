package com.easemic.app.feature.bridge

import com.easemic.app.sync.GoogleDriveObjectStore
import com.easemic.app.sync.LibraryKeyslot
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.ObjectStore
import com.easemic.app.sync.OneDriveObjectStore
import com.easemic.app.sync.WrongLibraryPasswordException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.easemic.app.i18n.tr

object OneDriveBindCopy {
    val SIGN_IN: String get() = tr("Sign in with Microsoft", "使用 Microsoft 登录", "使用 Microsoft 登入", "Microsoft でサインイン")
    val SIGN_IN_AGAIN: String get() = tr("Sign in with Microsoft again", "再次使用 Microsoft 登录", "再次使用 Microsoft 登入", "Microsoft に再度サインイン")
    val SIGN_IN_FAILED: String get() = tr("Could not sign in to Microsoft. Try again.", "无法登录 Microsoft，请重试。", "無法登入 Microsoft，請重試。", "Microsoft にサインインできません。再試行してください。")
    val SIGN_IN_CANCELLED: String get() = tr("Microsoft sign-in was cancelled.", "Microsoft 登录已取消。", "Microsoft 登入已取消。", "Microsoft のサインインをキャンセルしました。")
    val UNREACHABLE: String get() = tr("Could not reach OneDrive. Check the connection and try again.", "无法访问 OneDrive，请检查连接后重试。", "無法連線 OneDrive，請檢查連線後重試。", "OneDrive に接続できません。接続を確認して再試行してください。")
    val FOREIGN_FOLDER: String get() = tr("This app folder has files from another app or a damaged library.", "此应用文件夹包含其他应用的文件或损坏的书库。", "此應用程式資料夾含有其他應用程式的檔案或損壞的書庫。", "このアプリフォルダには別のアプリのファイル、または破損したライブラリがあります。")
}

object GoogleDriveBindCopy {
    val SIGN_IN_FAILED: String get() = tr("Could not sign in to Google. Try again.", "无法登录 Google，请重试。", "無法登入 Google，請重試。", "Google にサインインできません。再試行してください。")
    val SIGN_IN_CANCELLED: String get() = tr("Google sign-in was cancelled.", "Google 登录已取消。", "Google 登入已取消。", "Google のサインインをキャンセルしました。")
    val UNREACHABLE: String get() = tr("Could not reach Google Drive. Check the connection and try again.", "无法访问 Google Drive，请检查连接后重试。", "無法連線 Google Drive，請檢查連線後重試。", "Google Drive に接続できません。接続を確認して再試行してください。")
    val FOREIGN_FOLDER: String get() = tr("The library folder has files from another app or a damaged library.", "书库文件夹包含其他应用的文件或损坏的书库。", "書庫資料夾含有其他應用程式的檔案或損壞的書庫。", "ライブラリフォルダには別のアプリのファイル、または破損したライブラリがあります。")
}

/** The errors a drive's bind flow can show, in that drive's own words. */
data class DriveBindErrors(
    val signInFailed: String,
    val signInCancelled: String,
    val unreachable: String,
    val foreignFolder: String,
) {
    companion object {
        val ONEDRIVE = DriveBindErrors(
            OneDriveBindCopy.SIGN_IN_FAILED, OneDriveBindCopy.SIGN_IN_CANCELLED, OneDriveBindCopy.UNREACHABLE, OneDriveBindCopy.FOREIGN_FOLDER,
        )
        val GOOGLE_DRIVE = DriveBindErrors(
            GoogleDriveBindCopy.SIGN_IN_FAILED, GoogleDriveBindCopy.SIGN_IN_CANCELLED, GoogleDriveBindCopy.UNREACHABLE,
            GoogleDriveBindCopy.FOREIGN_FOLDER,
        )
    }
}

/** What the sign-in resolved: the drive, its app folder item, and who signed in (shown, never identity). */
data class OneDriveAccount(val driveId: String, val rootItemId: String, val account: String)

/** #C50: what a drive's sign-in resolved, drive-agnostic: the library folder and who signed in. */
data class DriveAccount(val rootId: String, val account: String, val driveId: String = "")

/**
 * #C50: everything that differs between the OAuth drives (OneDrive, Google Drive): their PKCE
 * config, how the signed-in account resolves to a library folder, the store over it, and the
 * one committer call for the binding it produces.
 */
class OAuthDrive<B>(
    val pkce: DrivePkceClient,
    val resolveAccount: (accessToken: String) -> DriveAccount,
    val storeFor: (DriveAccount, DriveTokenSource) -> ObjectStore,
    val bindingFor: (refreshToken: String, DriveAccount) -> B,
    val commit: suspend (B, ByteArray) -> Unit,
    /** A re-sign-in to the bound library: store the new refresh token, touch nothing else. */
    val renewSignIn: suspend (refreshToken: String, DriveAccount) -> Unit = { _, _ -> },
    val errors: DriveBindErrors = DriveBindErrors.ONEDRIVE,
)

object DriveReauthCopy {
    const val OTHER_ACCOUNT: String = "That is a different account. Sign in with the one this library is in, or disconnect first."
}

object OAuthDrives {
    fun oneDrive(
        pkce: DrivePkceClient = DrivePkceClient(DriveOAuthConfigs.ONEDRIVE),
        resolveAccount: (String) -> DriveAccount = { at -> resolveOneDriveAccount(at).let { DriveAccount(it.rootItemId, it.account, it.driveId) } },
        storeFor: (DriveAccount, DriveTokenSource) -> ObjectStore = { a, t -> OneDriveObjectStore(a.rootId, t) },
        commit: suspend (OneDriveBinding, ByteArray) -> Unit,
        renewSignIn: suspend (String, DriveAccount) -> Unit = { _, _ -> },
    ): OAuthDrive<OneDriveBinding> = OAuthDrive(pkce, resolveAccount, storeFor, { rt, a -> OneDriveBinding(rt, a.driveId, a.rootId, a.account) }, commit, renewSignIn)

    fun googleDrive(
        pkce: DrivePkceClient = DrivePkceClient(DriveOAuthConfigs.GOOGLE_DRIVE),
        resolveAccount: (String) -> DriveAccount = ::resolveGoogleDriveAccount,
        storeFor: (DriveAccount, DriveTokenSource) -> ObjectStore = { a, t -> GoogleDriveObjectStore(a.rootId, t) },
        commit: suspend (GoogleDriveBinding, ByteArray) -> Unit,
        renewSignIn: suspend (String, DriveAccount) -> Unit = { _, _ -> },
    ): OAuthDrive<GoogleDriveBinding> = OAuthDrive(
        pkce, resolveAccount, storeFor, { rt, a -> GoogleDriveBinding(rt, a.rootId, a.account) }, commit, renewSignIn,
        errors = DriveBindErrors.GOOGLE_DRIVE,
    )
}

data class OneDriveBindUiState(
    val step: DriveBindStep = DriveBindStep.FORM,
    val busy: Boolean = false,
    val error: String? = null,
    val account: String? = null,
    val libraryPassword: String = "",
    val libraryConfirm: String = "",
    val passwordError: String? = null,
) {
    override fun toString(): String =
        "OneDriveBindUiState(step=$step, busy=$busy, error=$error, account=$account, libraryPassword=<redacted>, " +
            "libraryConfirm=<redacted>, passwordError=$passwordError)"
}

/**
 * #C50 (spec §5.2): Sign in with Microsoft (Custom Tab, PKCE) → resolve the app folder →
 * a library there asks for its password, an empty one asks for a new password, anything
 * else is refused. Always encrypted; nothing is persisted until [commit] runs with a DEK.
 */
class OAuthDriveBindPresenter<B>(
    private val drive: OAuthDrive<B>,
    private val openAuthorization: (String) -> Unit,
) {
    private val pkce = drive.pkce
    private val errors = drive.errors
    private val _states = MutableStateFlow(OneDriveBindUiState())
    val states: StateFlow<OneDriveBindUiState> = _states.asStateFlow()
    val state: OneDriveBindUiState get() = _states.value

    private var session: DrivePkceSession? = null
    private var candidate: DriveAccount? = null
    /** Non-null during "sign in again": the library folder the new sign-in must resolve to. */
    private var reauthRootId: String? = null
    private var candidateStoreInstance: ObjectStore? = null
    private var lockJson: String? = null

    internal fun pendingSessionForTest(): DrivePkceSession? = session

    fun onLibraryPasswordChange(value: String) = _states.update { it.copy(libraryPassword = value, passwordError = null) }
    fun onLibraryConfirmChange(value: String) = _states.update { it.copy(libraryConfirm = value, passwordError = null) }

    fun reset() {
        _states.value = OneDriveBindUiState()
        session = null
        clearCandidate()
    }

    /** The bound library's sign-in expired: sign in again to the same folder; no password, no rebuild. */
    fun onSignInAgain(expectedRootId: String) {
        reauthRootId = expectedRootId
        startSignIn()
    }

    fun onSignIn() {
        reauthRootId = null
        startSignIn()
    }

    private fun startSignIn() {
        if (state.busy) return
        val begun = pkce.begin()
        session = begun
        _states.update { it.copy(error = null) }
        openAuthorization(begun.authorizationUri)
    }

    fun onRedirectFailure(error: String) {
        if (session == null) return
        session = null
        _states.update {
            it.copy(busy = false, error = if (error == "access_denied") errors.signInCancelled else errors.signInFailed)
        }
    }

    suspend fun onRedirect(code: String, returnedState: String) {
        val pending = session ?: return
        if (state.busy) return
        session = null
        _states.update { it.copy(busy = true, error = null) }
        val tokens = withContext(Dispatchers.IO) { runCatching { pkce.exchange(code, returnedState, pending) }.getOrNull() }
        if (tokens !is DriveOAuthResult.Success) {
            _states.update { it.copy(busy = false, error = errors.signInFailed) }
            return
        }
        val account = withContext(Dispatchers.IO) { runCatching { drive.resolveAccount(tokens.tokens.accessToken) }.getOrNull() }
        if (account == null) {
            _states.update { it.copy(busy = false, error = errors.unreachable) }
            return
        }
        reauthRootId?.let { expected ->
            reauthRootId = null
            if (account.rootId != expected) {
                _states.update { it.copy(busy = false, error = DriveReauthCopy.OTHER_ACCOUNT) }
                return
            }
            try {
                drive.renewSignIn(tokens.tokens.refreshToken, account)
            } catch (cancelled: CancellationException) {
                _states.update { it.copy(busy = false) }
                throw cancelled
            } catch (_: Exception) {
                _states.update { it.copy(busy = false, error = errors.unreachable) }
                return
            }
            _states.value = OneDriveBindUiState(step = DriveBindStep.BOUND, account = account.account)
            return
        }
        var refreshToken = tokens.tokens.refreshToken
        val source = DriveTokenSource(
            loadRefreshToken = { refreshToken },
            saveRefreshTokenSync = { refreshToken = it },
            refresh = pkce::refresh,
        ).also { it.seed(tokens.tokens) }
        val store = drive.storeFor(account, source)
        when (val probe = withContext(Dispatchers.IO) { probeDriveLibrary(store) }) {
            is DriveProbe.HasLibrary -> hold(account, { refreshToken }, store, probe.lockJson, DriveBindStep.UNLOCK_PASSWORD)
            DriveProbe.Empty -> hold(account, { refreshToken }, store, null, DriveBindStep.CREATE_PASSWORD)
            DriveProbe.Newer -> _states.update { it.copy(busy = false, error = NewerCloudFormatException.MESSAGE) }
            DriveProbe.Foreign -> _states.update { it.copy(busy = false, error = errors.foreignFolder) }
            DriveProbe.WrongCredentials -> _states.update { it.copy(busy = false, error = errors.signInFailed) }
            DriveProbe.Unreachable -> _states.update { it.copy(busy = false, error = errors.unreachable) }
        }
    }

    private var currentRefreshToken: () -> String = { "" }

    suspend fun onCreateLibrary() {
        val current = state
        val store = candidateStoreInstance ?: return
        if (current.busy || current.step != DriveBindStep.CREATE_PASSWORD) return
        if (current.libraryPassword.isEmpty()) return passwordFail(LibraryLockCopy.PASSWORD_REQUIRED)
        if (current.libraryPassword != current.libraryConfirm) return passwordFail(LibraryLockCopy.PASSWORDS_DONT_MATCH)
        _states.update { it.copy(busy = true, passwordError = null) }
        val created = try {
            withContext(Dispatchers.IO) { writeFirstLibraryLock(store, current.libraryPassword.toCharArray()) }
        } catch (cancelled: CancellationException) {
            _states.update { it.copy(busy = false) }
            throw cancelled
        } catch (_: Exception) {
            return passwordFail(LibraryLockCopy.OPERATION_FAILED)
        }
        when (created) {
            LockCreateResult.NotEmpty -> {
                clearCandidate()
                _states.update { it.copy(busy = false, step = DriveBindStep.FORM, error = errors.foreignFolder) }
            }
            is LockCreateResult.Created -> finish(created.dek)
        }
    }

    suspend fun onUnlockLibrary() {
        val current = state
        val lock = lockJson ?: return
        if (current.busy || current.step != DriveBindStep.UNLOCK_PASSWORD) return
        if (current.libraryPassword.isEmpty()) return passwordFail(LibraryLockCopy.PASSWORD_REQUIRED)
        _states.update { it.copy(busy = true, passwordError = null) }
        val dek = try {
            withContext(Dispatchers.Default) { LibraryKeyslot.unlock(current.libraryPassword.toCharArray(), lock) }
        } catch (cancelled: CancellationException) {
            _states.update { it.copy(busy = false) }
            throw cancelled
        } catch (_: WrongLibraryPasswordException) {
            return passwordFail(LibraryLockCopy.WRONG_PASSWORD)
        } catch (_: NewerCloudFormatException) {
            return passwordFail(NewerCloudFormatException.MESSAGE)
        } catch (_: Exception) {
            return passwordFail(LibraryLockCopy.OPERATION_FAILED)
        }
        finish(dek)
    }

    fun onCancelPasswordStep() {
        if (state.busy) return
        clearCandidate()
        _states.value = OneDriveBindUiState()
    }

    private suspend fun finish(dek: ByteArray) {
        val account = candidate ?: return
        try {
            drive.commit(drive.bindingFor(currentRefreshToken(), account), dek)
        } catch (cancelled: CancellationException) {
            _states.update { it.copy(busy = false) }
            throw cancelled
        } catch (_: Exception) {
            return passwordFail(LibraryLockCopy.OPERATION_FAILED)
        }
        clearCandidate()
        _states.value = OneDriveBindUiState(step = DriveBindStep.BOUND, account = account.account)
    }

    private fun hold(account: DriveAccount, refreshToken: () -> String, store: ObjectStore, lock: String?, step: DriveBindStep) {
        candidate = account
        currentRefreshToken = refreshToken
        candidateStoreInstance = store
        lockJson = lock
        _states.update {
            it.copy(busy = false, step = step, account = account.account, libraryPassword = "", libraryConfirm = "", passwordError = null)
        }
    }

    private fun clearCandidate() {
        candidate = null
        candidateStoreInstance = null
        lockJson = null
        currentRefreshToken = { "" }
    }

    private fun passwordFail(message: String) = _states.update { it.copy(busy = false, passwordError = message) }
}

/** The OneDrive presenter keeps its name and constructor for the callers that have it. */
@Suppress("FunctionName")
fun OneDriveBindPresenter(
    pkce: DrivePkceClient = DrivePkceClient(DriveOAuthConfigs.ONEDRIVE),
    openAuthorization: (String) -> Unit,
    resolveAccount: (accessToken: String) -> OneDriveAccount = ::resolveOneDriveAccount,
    candidateStore: (OneDriveAccount, DriveTokenSource) -> ObjectStore = { account, tokens -> OneDriveObjectStore(account.rootItemId, tokens) },
    commit: suspend (OneDriveBinding, ByteArray) -> Unit,
): OAuthDriveBindPresenter<OneDriveBinding> = OAuthDriveBindPresenter(
    OAuthDrives.oneDrive(
        pkce = pkce,
        resolveAccount = { at -> resolveAccount(at).let { DriveAccount(it.rootItemId, it.account, it.driveId) } },
        storeFor = { a, t -> candidateStore(OneDriveAccount(a.driveId, a.rootId, a.account), t) },
        commit = commit,
    ),
    openAuthorization,
)

/** Drive: the pclRoot folder (found or created) and who signed in. Blocking. */
fun resolveGoogleDriveAccount(accessToken: String): DriveAccount {
    val tokens = DriveTokenSource(loadRefreshToken = { null }, saveRefreshTokenSync = {}, refresh = { error("seeded") })
        .also { it.seed(DriveOAuthTokens(accessToken, "", 3600)) }
    val root = GoogleDriveObjectStore.ensureRoot(tokens)
    val about = OkHttpClient().newCall(
        Request.Builder().url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)")
            .header("Authorization", "Bearer $accessToken").build(),
    ).execute().use { r -> if (r.code in 200..299) JSONObject(r.body!!.string()) else null }
    return DriveAccount(rootId = root, account = about?.optJSONObject("user")?.optString("emailAddress").orEmpty())
}

/** Graph: the app folder (created on first access) and who signed in. Blocking. */
fun resolveOneDriveAccount(accessToken: String): OneDriveAccount {
    val client = OkHttpClient()
    fun get(path: String): JSONObject = client.newCall(
        Request.Builder().url("${OneDriveObjectStore.GRAPH_BASE}$path").header("Authorization", "Bearer $accessToken").build(),
    ).execute().use { response ->
        check(response.code in 200..299) { "CLOUD_HTTP_${response.code}" }
        JSONObject(response.body!!.string())
    }
    val root = get("/me/drive/special/approot?\$select=id,parentReference")
    val me = runCatching { get("/me?\$select=userPrincipalName,mail") }.getOrNull()
    return OneDriveAccount(
        driveId = root.getJSONObject("parentReference").getString("driveId"),
        rootItemId = root.getString("id"),
        account = me?.optString("mail")?.takeIf { it.isNotBlank() } ?: me?.optString("userPrincipalName").orEmpty(),
    )
}
