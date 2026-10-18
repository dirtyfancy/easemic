package com.easemic.app.feature.bridge

import com.easemic.app.sync.CloudCollectionKeys
import com.easemic.app.sync.LibraryKeyslot
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.ObjectStore
import com.easemic.app.sync.WebDavBinding
import com.easemic.app.sync.WebDavObjectStore
import com.easemic.app.sync.WrongLibraryPasswordException
import com.easemic.app.i18n.tr
import com.easemic.app.sync.looksLikeMissingObject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** #C50: what a drive's library folder holds at bind time (spec §5.2). */
sealed interface DriveProbe {
    data class HasLibrary(val lockJson: String) : DriveProbe
    data object Empty : DriveProbe
    /** Files in the folder but no usable `library.lock`: never adopted — drives are always encrypted. */
    data object Foreign : DriveProbe
    data object Newer : DriveProbe
    data object WrongCredentials : DriveProbe
    data object Unreachable : DriveProbe
}

/**
 * One GET for the lock, then (only when it is missing) a look at the top level. Not a
 * whole-library walk: a folder with anything in it besides a lock is "foreign".
 */
fun probeDriveLibrary(store: ObjectStore): DriveProbe = try {
    val lock = try {
        String(store.download(CloudCollectionKeys.lockKey()), Charsets.UTF_8)
    } catch (missing: Exception) {
        if (!looksLikeMissingObject(missing) || isAuthFailure(missing)) throw missing
        null
    }
    when {
        lock != null -> try {
            LibraryKeyslot.validate(lock)
            DriveProbe.HasLibrary(lock)
        } catch (_: NewerCloudFormatException) {
            DriveProbe.Newer
        } catch (_: Exception) {
            DriveProbe.Foreign
        }
        store.listKeys().isEmpty() -> DriveProbe.Empty
        else -> DriveProbe.Foreign
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    if (isAuthFailure(error)) DriveProbe.WrongCredentials else DriveProbe.Unreachable
}

private fun isAuthFailure(error: Exception): Boolean =
    Regex("""CLOUD_HTTP_40[13]""").containsMatchIn(error.message.orEmpty())

object WebDavBindCopy {
    val FIELDS_REQUIRED: String get() = tr("Server, username and password are required.", "服务器、用户名和密码均为必填项。", "伺服器、使用者名稱與密碼皆為必填項。", "サーバー、ユーザー名、パスワードを入力してください。")
    val NOT_HTTPS: String get() = tr("Use an https:// address.", "请使用 https:// 地址。", "請使用 https:// 位址。", "https:// アドレスを使用してください。")
    val WRONG_CREDENTIALS: String get() = tr("The server rejected this username or password.", "服务器拒绝了此用户名或密码。", "伺服器拒絕了此使用者名稱或密碼。", "サーバーがユーザー名またはパスワードを拒否しました。")
    val UNREACHABLE: String get() = tr("Could not reach this server. Check the address.", "无法访问此服务器，请检查地址。", "無法連線此伺服器，請檢查位址。", "このサーバーに接続できません。アドレスを確認してください。")
    val FOREIGN_FOLDER: String get() = tr("This folder has files from another app or a damaged library.", "此文件夹包含其他应用的文件或损坏的书库。", "此資料夾含有其他應用程式的檔案或損壞的書庫。", "このフォルダには別のアプリのファイル、または破損したライブラリがあります。")
    val APP_PASSWORD_HELP: String get() = tr("Use an app password from your drive's settings, not your login password.", "请使用网盘设置中的应用密码，而不是登录密码。", "請使用雲端硬碟設定中的應用程式密碼，而不是登入密碼。", "ログインパスワードではなく、ドライブ設定のアプリパスワードを使用してください。")
    val FOLDER_HELP: String get() = tr("The library goes inside this folder.", "书库将保存在此文件夹中。", "書庫會儲存在此資料夾中。", "ライブラリはこのフォルダ内に保存されます。")
    val NOT_WEBDAV: String get() = tr("No WebDAV folder at this address. Check the server address.", "此地址没有 WebDAV 文件夹，请检查服务器地址。", "此位址沒有 WebDAV 資料夾，請檢查伺服器位址。", "このアドレスに WebDAV フォルダがありません。サーバーアドレスを確認してください。")
    val PAN123_ADDRESS_HINT: String get() = tr("123pan's WebDAV address is https://webdav.123pan.cn/webdav.", "123pan 的 WebDAV 地址是 https://webdav.123pan.cn/webdav。", "123pan 的 WebDAV 位址是 https://webdav.123pan.cn/webdav。", "123pan の WebDAV アドレスは https://webdav.123pan.cn/webdav です。")

    private val PAN123 = Regex("""^([^/]+\.)?123pan\.(cn|com)(:\d+)?(/|$)""", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)

    /** #D15/#D16: whether [address] — a typed URL or a bound label (host, host/folder) — is on 123pan. */
    fun isPan123(address: String): Boolean = PAN123.containsMatchIn(address.trim().replace(SCHEME, ""))

    /** #D15: [NOT_WEBDAV], plus where 123pan's WebDAV lives when the address is on 123pan. */
    fun notWebDav(server: String): String = if (isPan123(server)) "$NOT_WEBDAV $PAN123_ADDRESS_HINT" else NOT_WEBDAV

    /** #D16: [WRONG_CREDENTIALS], plus how to get a new app password when the address is on 123pan. */
    fun wrongCredentials(server: String): String =
        if (isPan123(server)) "$WRONG_CREDENTIALS ${CloudSignInCopy.PAN123_STEPS}" else WRONG_CREDENTIALS
}

enum class WebDavPreset(val label: String, val server: String) {
    NUTSTORE("Nutstore", "https://dav.jianguoyun.com/dav/"),
    PAN123("123pan", "https://webdav.123pan.cn/webdav"),
    OTHER("Other", ""),
}

enum class WebDavField { SERVER, USERNAME, PASSWORD }

/** Where a drive bind stands; shared by WebDAV now and OneDrive / Google Drive later. */
enum class DriveBindStep { FORM, CREATE_PASSWORD, UNLOCK_PASSWORD, BOUND }

data class WebDavBindUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val folder: String = "",
    val step: DriveBindStep = DriveBindStep.FORM,
    val busy: Boolean = false,
    val formError: String? = null,
    val errorField: WebDavField? = null,
    val libraryPassword: String = "",
    val libraryConfirm: String = "",
    val passwordError: String? = null,
    val renewing: Boolean = false,
) {
    override fun toString(): String =
        "WebDavBindUiState(server=$server, username=$username, password=<redacted>, folder=$folder, step=$step, " +
            "busy=$busy, formError=$formError, errorField=$errorField, libraryPassword=<redacted>, " +
            "libraryConfirm=<redacted>, passwordError=$passwordError, renewing=$renewing)"
}

/**
 * #C50 (spec §5.2): the WebDAV bind flow. Credentials → probe the library folder →
 * a library there asks for its password, an empty folder asks for a new one, anything
 * else is refused. There is no "encrypt or not" step: drive libraries are always
 * encrypted (owner 2026-09-18, reaffirmed 2026-09-26). Nothing is persisted until
 * [commit] runs, and it runs only with a DEK in hand.
 */
class WebDavBindPresenter(
    private val candidateStore: (WebDavBinding) -> ObjectStore = { WebDavObjectStore(it) },
    /**
     * Makes the binding durable with its library DEK — in the app,
     * [BridgeR2BindPresenter.commitWebDav], the one place every binding kind is
     * committed (binding lock, gate switch, other kinds cleared, DEK handed over).
     */
    private val commit: suspend (WebDavBinding, ByteArray) -> Unit,
    /**
     * #D15: whether the typed address is a WebDAV folder at all. 123pan's bare host answers every path 404, which the
     * library-folder probe reads as an empty folder (a legitimate first bind), so it offered to create a library there.
     * Asked only when the probe finds nothing, the one case that leads to creating a library. Only a real WebDAV store
     * can ask; test doubles pass.
     */
    private val addressAnswersWebDav: (ObjectStore) -> Boolean = { store ->
        (store as? WebDavObjectStore)?.addressAnswersWebDav() ?: true
    },
    /**
     * #D16: the library key this phone holds. Entering a new password is accepted only when the folder's lock names
     * the same key, so a renewal can never land this phone in another library.
     */
    private val deviceDek: () -> ByteArray? = { null },
) {
    private val _states = MutableStateFlow(WebDavBindUiState())
    val states: StateFlow<WebDavBindUiState> = _states.asStateFlow()
    val state: WebDavBindUiState get() = _states.value

    private var candidate: WebDavBinding? = null
    private var candidateStoreInstance: ObjectStore? = null
    private var lockJson: String? = null

    // #D16: while renewing, server, account and folder are the library's identity — only the password may change.
    fun onServerChange(value: String) = edit { if (it.renewing) it else it.copy(server = value) }
    fun onUsernameChange(value: String) = edit { if (it.renewing) it else it.copy(username = value) }
    fun onPasswordChange(value: String) = edit { it.copy(password = value) }
    fun onFolderChange(value: String) = edit { if (it.renewing) it else it.copy(folder = value) }
    fun onPreset(preset: WebDavPreset) = edit { if (it.renewing) it else it.copy(server = preset.server) }

    /** #D16: the drive refused the saved password — ask only for the new one, for the same server, account and folder. */
    fun onRenew(saved: WebDavBinding) {
        clearCandidate()
        _states.value = WebDavBindUiState(server = saved.baseUrl, username = saved.username, folder = saved.folder, renewing = true)
    }

    fun onLibraryPasswordChange(value: String) = _states.update { it.copy(libraryPassword = value, passwordError = null) }
    fun onLibraryConfirmChange(value: String) = _states.update { it.copy(libraryConfirm = value, passwordError = null) }

    fun reset() {
        _states.value = WebDavBindUiState()
        candidate = null
        candidateStoreInstance = null
        lockJson = null
    }

    suspend fun onConnect() {
        val current = state
        if (current.busy || current.step != DriveBindStep.FORM) return
        val server = current.server.trim()
        if (server.isEmpty() || current.username.isBlank() || current.password.isEmpty()) {
            fail(WebDavBindCopy.FIELDS_REQUIRED, field = null)
            return
        }
        if (!server.startsWith("https://", ignoreCase = true)) {
            fail(WebDavBindCopy.NOT_HTTPS, WebDavField.SERVER)
            return
        }
        val binding = WebDavBinding(
            baseUrl = server,
            username = current.username.trim(),
            password = current.password,
            folder = current.folder.trim().trim('/'),
        )
        _states.update { it.copy(busy = true, formError = null, errorField = null) }
        val store = try {
            candidateStore(binding)
        } catch (_: IllegalArgumentException) {
            fail(WebDavBindCopy.NOT_HTTPS, WebDavField.SERVER)
            return
        }
        // #D16: a renewal never walks the create/unlock steps — the library and its password already exist.
        if (current.renewing) return renew(binding, store)
        when (val probe = withContext(Dispatchers.IO) { probeDriveLibrary(store) }) {
            is DriveProbe.HasLibrary -> hold(binding, store, probe.lockJson, DriveBindStep.UNLOCK_PASSWORD)
            DriveProbe.Empty -> {
                // #D15: an empty (or missing) library folder is also what an address with no WebDAV folder at all
                // looks like; ask the address before offering to create a library there.
                val isWebDav = try {
                    withContext(Dispatchers.IO) { addressAnswersWebDav(store) }
                } catch (cancelled: CancellationException) {
                    _states.update { it.copy(busy = false) }
                    throw cancelled
                } catch (_: Exception) {
                    fail(WebDavBindCopy.UNREACHABLE, WebDavField.SERVER)
                    return
                }
                if (isWebDav) {
                    hold(binding, store, null, DriveBindStep.CREATE_PASSWORD)
                } else {
                    fail(WebDavBindCopy.notWebDav(server), WebDavField.SERVER)
                }
            }
            DriveProbe.Newer -> fail(NewerCloudFormatException.MESSAGE, field = null)
            DriveProbe.Foreign -> fail(WebDavBindCopy.FOREIGN_FOLDER, field = null)
            DriveProbe.WrongCredentials -> fail(WebDavBindCopy.wrongCredentials(server), WebDavField.PASSWORD)
            DriveProbe.Unreachable -> fail(WebDavBindCopy.UNREACHABLE, WebDavField.SERVER)
        }
    }

    suspend fun onCreateLibrary() {
        val current = state
        val binding = candidate ?: return
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
            // Content raced in between the probe and the write: start over, never clobber.
            LockCreateResult.NotEmpty -> {
                clearCandidate()
                _states.update { it.copy(busy = false, step = DriveBindStep.FORM, formError = WebDavBindCopy.FOREIGN_FOLDER) }
            }
            is LockCreateResult.Created -> finish(binding, created.dek)
        }
    }

    suspend fun onUnlockLibrary() {
        val current = state
        val binding = candidate ?: return
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
        finish(binding, dek)
    }

    /** "Cancel": back to the form, nothing saved (a first lock already written stays; the folder then reads as a library). */
    fun onCancelPasswordStep() {
        if (state.busy) return
        clearCandidate()
        _states.update {
            it.copy(step = DriveBindStep.FORM, libraryPassword = "", libraryConfirm = "", passwordError = null)
        }
    }

    /** #D16: commit the new password with this phone's own key, only for the library this phone already holds. */
    private suspend fun renew(binding: WebDavBinding, store: ObjectStore) {
        when (val probe = withContext(Dispatchers.IO) { probeDriveLibrary(store) }) {
            is DriveProbe.HasLibrary -> {
                val dek = deviceDek()
                val lockKey = runCatching { LibraryKeyslot.dekIdFrom(probe.lockJson) }.getOrNull()
                if (dek == null || lockKey != LibraryKeyslot.dekId(dek)) return fail(CloudSignInCopy.OTHER_LIBRARY, field = null)
                finish(binding, dek)
            }
            DriveProbe.Newer -> fail(NewerCloudFormatException.MESSAGE, field = null)
            DriveProbe.WrongCredentials -> fail(WebDavBindCopy.wrongCredentials(binding.baseUrl), WebDavField.PASSWORD)
            DriveProbe.Unreachable -> fail(WebDavBindCopy.UNREACHABLE, WebDavField.SERVER)
            DriveProbe.Empty, DriveProbe.Foreign -> fail(CloudSignInCopy.OTHER_LIBRARY, field = null)
        }
    }

    private suspend fun finish(binding: WebDavBinding, dek: ByteArray) {
        try {
            commit(binding, dek)
        } catch (cancelled: CancellationException) {
            _states.update { it.copy(busy = false) }
            throw cancelled
        } catch (_: Exception) {
            // #D16: the renew form has no password-step error line — a commit failure is a form error there.
            if (state.renewing) return fail(LibraryLockCopy.OPERATION_FAILED, field = null)
            return passwordFail(LibraryLockCopy.OPERATION_FAILED)
        }
        clearCandidate()
        _states.value = WebDavBindUiState(step = DriveBindStep.BOUND, server = binding.baseUrl, username = binding.username, folder = binding.folder)
    }

    private fun hold(binding: WebDavBinding, store: ObjectStore, lock: String?, step: DriveBindStep) {
        candidate = binding
        candidateStoreInstance = store
        lockJson = lock
        _states.update {
            it.copy(busy = false, step = step, libraryPassword = "", libraryConfirm = "", passwordError = null)
        }
    }

    private fun clearCandidate() {
        candidate = null
        candidateStoreInstance = null
        lockJson = null
    }

    private fun edit(change: (WebDavBindUiState) -> WebDavBindUiState) =
        _states.update { if (it.busy) it else change(it).copy(formError = null, errorField = null) }

    private fun fail(message: String, field: WebDavField?) =
        _states.update { it.copy(busy = false, formError = message, errorField = field) }

    private fun passwordFail(message: String) =
        _states.update { it.copy(busy = false, passwordError = message) }
}
