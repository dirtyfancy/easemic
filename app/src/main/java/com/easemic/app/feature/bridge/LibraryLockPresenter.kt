package com.easemic.app.feature.bridge

import com.easemic.app.sync.CloudCollectionKeys
import com.easemic.app.sync.CloudLibraryGate
import com.easemic.app.sync.CloudSignIn
import com.easemic.app.sync.DamagedLibraryLockException
import com.easemic.app.sync.LibraryKeyslot
import com.easemic.app.sync.LibraryLockState
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.ObjectStore
import com.easemic.app.sync.StaleCloudBindingException
import com.easemic.app.sync.WrongLibraryPasswordException
import com.easemic.app.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Keyslot screen copy, locked by accountless spec §4 / Orange Field spec §5. */
object LibraryLockCopy {
    val FORGET_WARNING: String get() = tr("If you forget this password, this library cannot be opened. We cannot reset it.", "如果忘记此密码，将无法打开书库。我们无法重置密码。", "如果忘記此密碼，將無法開啟書庫。我們無法重設密碼。", "このパスワードを忘れるとライブラリを開けません。リセットはできません。")
    val CREATE_ACTION: String get() = tr("Turn on encryption", "开启加密", "開啟加密", "暗号化を有効にする")
    val LOCKED_PROMPT: String get() = tr("This library is encrypted. Enter its password.", "此书库已加密，请输入密码。", "此書庫已加密，請輸入密碼。", "このライブラリは暗号化されています。パスワードを入力してください。")
    val UNLOCK_ACTION: String get() = tr("Unlock", "解锁", "解鎖", "ロックを解除")
    val WRONG_PASSWORD: String get() = tr("Incorrect password.", "密码错误。", "密碼錯誤。", "パスワードが違います。")
    val PASSWORD_REQUIRED: String get() = tr("Password required.", "需要密码。", "需要密碼。", "パスワードが必要です。")
    val PASSWORDS_DONT_MATCH: String get() = tr("Passwords do not match.", "两次密码不一致。", "兩次密碼不一致。", "パスワードが一致しません。")
    val BUCKET_NOT_EMPTY: String get() = tr("This bucket is no longer empty, so its mode is already decided.", "此存储桶已不为空，模式已经确定。", "此儲存桶已不為空，模式已確定。", "このバケットは空ではないため、モードは決定済みです。")
    val CHANGE_PASSWORD: String get() = tr("Change password", "更改密码", "變更密碼", "パスワードを変更")
    val CHANGE_SUBMIT: String get() = tr("Save new password", "保存新密码", "儲存新密碼", "新しいパスワードを保存")
    val PASSWORD_CHANGED: String get() = tr("Password changed.", "密码已更改。", "密碼已變更。", "パスワードを変更しました。")
    val STATUS_ENCRYPTED_SUFFIX: String get() = tr(" · encrypted", " · 已加密", " · 已加密", " · 暗号化済み")
    val OPERATION_FAILED: String get() = tr("Cloud operation failed. Try again.", "云端操作失败，请重试。", "雲端操作失敗，請重試。", "クラウド操作に失敗しました。再試行してください。")
}

enum class LibraryLockMode { HIDDEN, LOCKED, ENCRYPTED }

/** Events the bind screen's lock sections send up to [LibraryLockPresenter]. */
sealed interface LibraryLockEvent {
    data class UnlockPasswordChanged(val value: String) : LibraryLockEvent
    data object UnlockTapped : LibraryLockEvent
    data object ChangeExpandToggled : LibraryLockEvent
    data class OldPasswordChanged(val value: String) : LibraryLockEvent
    data class NewPasswordChanged(val value: String) : LibraryLockEvent
    data class NewConfirmChanged(val value: String) : LibraryLockEvent
    data object ChangeTapped : LibraryLockEvent
}

data class LibraryLockUiState(
    val mode: LibraryLockMode = LibraryLockMode.HIDDEN,
    val busy: Boolean = false,
    val unlockPassword: String = "",
    val changeExpanded: Boolean = false,
    val oldPassword: String = "",
    val newPassword: String = "",
    val newConfirm: String = "",
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    /** #D16: the drive refused the saved sign-in — the card says so; no error line under it. */
    val signInRejected: Boolean = false,
)

/**
 * Drives the bind screen's encryption sections. All bucket IO goes through
 * [rawStore] (the ungated store — the lock object must be readable while
 * locked). [onLibraryUsable] fires when content becomes readable (unlock) so
 * the app can reload the cloud shelf.
 */
class LibraryLockPresenter(
    private val gate: CloudLibraryGate,
    private val dekStore: BridgeDeviceLibraryKeyStore,
    private val rawStore: () -> ObjectStore?,
    private val onLibraryUsable: () -> Unit = {},
) {
    private val _states = MutableStateFlow(LibraryLockUiState())
    val states: StateFlow<LibraryLockUiState> = _states.asStateFlow()
    val state: LibraryLockUiState get() = _states.value

    fun onUnlockPasswordChange(value: String) = clearMessagesAnd { it.copy(unlockPassword = value) }
    fun onOldPasswordChange(value: String) = clearMessagesAnd { it.copy(oldPassword = value) }
    fun onNewPasswordChange(value: String) = clearMessagesAnd { it.copy(newPassword = value) }
    fun onNewConfirmChange(value: String) = clearMessagesAnd { it.copy(newConfirm = value) }

    fun onChangeExpandToggle() {
        _states.update {
            it.copy(
                changeExpanded = !it.changeExpanded,
                oldPassword = "",
                newPassword = "",
                newConfirm = "",
                errorMessage = null,
                infoMessage = null,
            )
        }
    }

    /**
     * Precondition: any bind replacement must gate.reset() + dekStore.clear()
     * first (the App's onBindReplaced does) — the gate memoizes the previous
     * bucket's state. #C64: bind replacement brackets the durable rewrite
     * with gate.beginSwitch()/finishSwitch().
     */
    suspend fun refresh(bound: Boolean) {
        // #C64: generation BEFORE the binding — a commit landing between the
        // read and rawStore() (or the probe itself) yields a dead wrapper.
        val born = gate.generation()
        val store = rawStore()
        if (!bound || store == null) {
            _states.value = LibraryLockUiState()
            return
        }
        _states.update { it.copy(busy = true) }
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                // #C57: an empty bucket's mode is chosen at bind time, never here.
                when (gate.probe(store, born)) {
                    LibraryLockState.UNLOCKED -> LibraryLockMode.ENCRYPTED
                    LibraryLockState.LOCKED -> LibraryLockMode.LOCKED
                    else -> LibraryLockMode.HIDDEN
                }
            }
        }
        _states.update { current ->
            resolved.fold(
                onSuccess = { mode -> LibraryLockUiState(mode = mode, infoMessage = current.infoMessage) },
                onFailure = { error ->
                    // #C64: the bind that made this probe stale re-triggers its
                    // own refresh — stay quiet rather than surface a transient error.
                    if (error is StaleCloudBindingException) {
                        current.copy(busy = false)
                    } else {
                        LibraryLockUiState(
                            mode = LibraryLockMode.HIDDEN,
                            // #D16: the drive refused the saved sign-in — the card says so; no error line under it.
                            signInRejected = CloudSignIn.rejected(error),
                            errorMessage = when {
                                error is NewerCloudFormatException -> NewerCloudFormatException.MESSAGE
                                error is DamagedLibraryLockException -> DamagedLibraryLockException.MESSAGE
                                // #C50 + #D16: the card already says "sign-in expired" / "password rejected".
                                CloudSignIn.rejected(error) -> null
                                // Transport codes (CLOUD_HTTP_500, …) are for logs, not people.
                                error.message.orEmpty().startsWith("CLOUD_") -> LibraryLockCopy.OPERATION_FAILED
                                else -> error.message ?: LibraryLockCopy.OPERATION_FAILED
                            },
                        )
                    }
                },
            )
        }
    }

    suspend fun unlock() {
        if (state.busy) return
        // #C64: generation BEFORE the binding — a commit landing between the
        // read and rawStore() must not let this unlock cache the OLD bucket's key.
        val born = gate.generation()
        val store = rawStore() ?: return
        val password = state.unlockPassword
        if (password.isEmpty()) {
            _states.update { it.copy(errorMessage = LibraryLockCopy.PASSWORD_REQUIRED, infoMessage = null) }
            return
        }
        _states.update { it.copy(busy = true, errorMessage = null, infoMessage = null) }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val lockJson = String(
                    store.download(CloudCollectionKeys.lockKey()),
                    Charsets.UTF_8,
                )
                val dek = LibraryKeyslot.unlock(password.toCharArray(), lockJson)
                // #C64: unlock the gate first — a stale unlock must not overwrite
                // the device's single DEK slot with the old bucket's key.
                if (!gate.unlockedWith(dek, born)) throw StaleCloudBindingException()
                dekStore.save(dek)
            }
        }
        _states.update { current ->
            outcome.fold(
                onSuccess = {
                    current.copy(
                        busy = false,
                        mode = LibraryLockMode.ENCRYPTED,
                        unlockPassword = "",
                        signInRejected = false,
                    )
                },
                onFailure = { error ->
                    current.copy(
                        busy = false,
                        // #D16: the drive refused the saved sign-in — the card says so, not a code.
                        signInRejected = CloudSignIn.rejected(error),
                        errorMessage = when {
                            error is WrongLibraryPasswordException -> LibraryLockCopy.WRONG_PASSWORD
                            error is NewerCloudFormatException -> NewerCloudFormatException.MESSAGE
                            error is DamagedLibraryLockException -> DamagedLibraryLockException.MESSAGE
                            error is StaleCloudBindingException -> LibraryLockCopy.OPERATION_FAILED
                            CloudSignIn.rejected(error) -> null
                            else -> error.message ?: LibraryLockCopy.OPERATION_FAILED
                        },
                    )
                },
            )
        }
        if (state.mode == LibraryLockMode.ENCRYPTED) onLibraryUsable()
    }

    suspend fun changePassword() {
        if (state.busy) return
        val store = rawStore() ?: return
        val newPassword = state.newPassword
        if (state.oldPassword.isEmpty() || newPassword.isEmpty()) {
            _states.update { it.copy(errorMessage = LibraryLockCopy.PASSWORD_REQUIRED, infoMessage = null) }
            return
        }
        if (newPassword != state.newConfirm) {
            _states.update { it.copy(errorMessage = LibraryLockCopy.PASSWORDS_DONT_MATCH, infoMessage = null) }
            return
        }
        _states.update { it.copy(busy = true, errorMessage = null, infoMessage = null) }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val lockJson = String(
                    store.download(CloudCollectionKeys.lockKey()),
                    Charsets.UTF_8,
                )
                val newLock = LibraryKeyslot.changePassword(
                    oldPassword = state.oldPassword.toCharArray(),
                    newPassword = newPassword.toCharArray(),
                    lockJson = lockJson,
                )
                val code = store.upload(
                    CloudCollectionKeys.lockKey(),
                    newLock.toByteArray(Charsets.UTF_8),
                    ObjectStore.JSON_MIME,
                )
                require(code in 200..299) { "CLOUD_HTTP_$code" }
            }
        }
        _states.update { current ->
            outcome.fold(
                onSuccess = {
                    current.copy(
                        busy = false,
                        changeExpanded = false,
                        oldPassword = "",
                        newPassword = "",
                        newConfirm = "",
                        infoMessage = LibraryLockCopy.PASSWORD_CHANGED,
                        signInRejected = false,
                    )
                },
                onFailure = { error ->
                    current.copy(
                        busy = false,
                        // #D16: the drive refused the saved sign-in — the card says so, not a code.
                        signInRejected = CloudSignIn.rejected(error),
                        errorMessage = when {
                            error is WrongLibraryPasswordException -> LibraryLockCopy.WRONG_PASSWORD
                            error is NewerCloudFormatException -> NewerCloudFormatException.MESSAGE
                            error is DamagedLibraryLockException -> DamagedLibraryLockException.MESSAGE
                            CloudSignIn.rejected(error) -> null
                            else -> error.message ?: LibraryLockCopy.OPERATION_FAILED
                        },
                    )
                },
            )
        }
    }

    private fun clearMessagesAnd(edit: (LibraryLockUiState) -> LibraryLockUiState) {
        _states.update { edit(it).copy(errorMessage = null, infoMessage = null) }
    }
}
