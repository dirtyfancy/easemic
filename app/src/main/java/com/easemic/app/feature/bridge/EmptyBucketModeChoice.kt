package com.easemic.app.feature.bridge

import com.easemic.app.sync.CloudCollectionKeys
import com.easemic.app.sync.CloudLibraryGate
import com.easemic.app.sync.LibraryKeyslot
import com.easemic.app.sync.ObjectStore
import com.easemic.app.sync.R2ObjectStore
import com.easemic.app.sync.R2RestObjectStore
import com.easemic.app.i18n.tr
import com.easemic.app.i18n.AppStrings

/**
 * #C57: a bind that found an EMPTY bucket waits here, in memory only, until the
 * user answers "encrypt or not". Nothing downstream can see it — every uploader
 * keys off a persisted binding, and this is not one yet.
 */
sealed interface BindCandidate {
    val bucket: String

    class Pasted(val binding: BridgeR2Binding) : BindCandidate {
        override val bucket: String get() = binding.bucket
        override fun toString(): String = "Pasted($binding)"
    }

    class OAuth(val secrets: BridgeOAuthDeviceSecrets, val accessToken: String) : BindCandidate {
        override val bucket: String get() = secrets.bucket
        override fun toString(): String = "OAuth($secrets, accessToken=<redacted>)"
    }
}

/**
 * Production transient store for a candidate. Deliberately NOT wrapped by the
 * library gate: it only lists the bucket and writes the first `library.lock`.
 */
fun defaultCandidateStore(candidate: BindCandidate): ObjectStore = when (candidate) {
    is BindCandidate.Pasted -> R2ObjectStore(candidate.binding)
    is BindCandidate.OAuth -> R2RestObjectStore(
        candidate.secrets.accountId,
        candidate.secrets.bucket,
        candidate.accessToken,
    )
}

/** Copy for the mandatory empty-bucket choice (spec §4.4). */
object ModeChoiceCopy {
    val TITLE: String get() = tr("Encrypt this library?", "要加密此书库吗？", "要加密此書庫嗎？", "このライブラリを暗号化しますか？")
    val BODY: String get() = tr("This bucket is empty. Choose now — once comics are uploaded, this can't be changed.", "此存储桶为空，请现在选择——漫画上传后将无法更改。", "此儲存桶為空，請現在選擇——漫畫上傳後將無法變更。", "このバケットは空です。今選択してください。マンガをアップロードすると変更できません。")
    val ENCRYPT_OPTION: String get() = tr("Encrypt with a password", "使用密码加密", "使用密碼加密", "パスワードで暗号化")
    val DONT_ENCRYPT: String get() = tr("Don't encrypt", "不加密", "不加密", "暗号化しない")
    val CANCEL: String get() = AppStrings.cancel
    val CANCELLED: String get() = tr("Not connected. Nothing was saved.", "未连接，未保存任何内容。", "未連接，未儲存任何內容。", "接続されていません。何も保存されませんでした。")
}

/** The dialog's own state; non-null on [BridgeR2BindUiState.modeChoice] while a candidate waits. */
data class ModeChoiceUiState(
    val encryptSelected: Boolean = false,
    val password: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val errorMessage: String? = null,
) {
    override fun toString(): String =
        "ModeChoiceUiState(encryptSelected=$encryptSelected, password=<redacted>, " +
            "confirm=<redacted>, busy=$busy, errorMessage=$errorMessage)"
}

sealed interface LockCreateResult {
    class Created(val dek: ByteArray) : LockCreateResult
    data object NotEmpty : LockCreateResult
}

/**
 * Writes the FIRST `library.lock` into [store], only while the bucket is still
 * empty (never clobber: another device may have raced content in). A non-2xx
 * PUT throws, so a failed write can never be committed as an encrypted bind.
 */
fun writeFirstLibraryLock(store: ObjectStore, password: CharArray): LockCreateResult {
    if (store.listKeys().isNotEmpty()) return LockCreateResult.NotEmpty
    val created = LibraryKeyslot.create(password)
    val code = store.upload(
        CloudCollectionKeys.lockKey(),
        created.lockJson.toByteArray(Charsets.UTF_8),
        ObjectStore.JSON_MIME,
    )
    require(code in 200..299) { "CLOUD_HTTP_$code" }
    return LockCreateResult.Created(created.dek)
}

/**
 * App side of a committed bind (spec §4.8), run synchronously in this order so
 * the first uploader [notifyBound] wakes already sees the new bucket's final
 * gate state: an encrypted bind is UNLOCKED before anything can write, and the
 * previous bucket's DEK never leaks onto the new one. #C64: the commit is
 * bracketed by `gate.beginSwitch()` (presenter, before persisting) and
 * `gate.finishSwitch()` (here) — every wrapper born before the switch is dead
 * by the time this returns.
 */
fun applyBindCommit(
    dek: ByteArray?,
    dekStore: BridgeDeviceLibraryKeyStore,
    gate: CloudLibraryGate,
    notifyBound: () -> Unit,
) {
    try {
        dekStore.clear()
        if (dek != null) dekStore.save(dek)
    } finally {
        // #C64: ends the switch the presenter began before persisting; every
        // store wrapped before it is dead from here on.
        gate.finishSwitch(dek)
    }
    notifyBound()
}
