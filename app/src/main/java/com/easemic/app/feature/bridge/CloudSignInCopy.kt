package com.easemic.app.feature.bridge

import com.easemic.app.sync.CloudSignIn
import com.easemic.app.i18n.tr

/** #D16 copy — spec `2026-09-30-d16-drive-rejects-saved-password-design.md` §3. */
object CloudSignInCopy {
    val BANNER_TITLE: String get() = tr("Cloud sign-in rejected — Queued tiles stay as hollow clouds and keep reading via the official source. Downloaded tiles are unaffected.", "云端登录被拒绝——排队中的卡片会显示为空心云朵，仍可通过官方图源阅读。已下载的卡片不受影响。", "雲端登入被拒絕——排隊中的卡片會顯示為空心雲朵，仍可透過官方圖源閱讀。已下載的卡片不受影響。", "クラウドのサインインが拒否されました。キュー待ちのタイルは空の雲として表示され、公式ソースで読み続けられます。端末にダウンロード済みのタイルには影響しません。")
    val BANNER_HINT: String get() = tr("Reconnect in More → Cloud library.", "请在“更多 → 云端书库”中重新连接。", "請在「更多 → 雲端書庫」中重新連線。", "「その他 → クラウドライブラリ」から再接続してください。")
    val WEBDAV_SENTENCE: String get() = tr("Your drive rejected the saved password. Enter the new one in More → Cloud library.", "网盘拒绝了已保存的密码，请在“更多 → 云端书库”中输入新密码。", "雲端硬碟拒絕了已儲存的密碼，請在「更多 → 雲端書庫」中輸入新密碼。", "ドライブが保存済みのパスワードを拒否しました。「その他 → クラウドライブラリ」で新しいパスワードを入力してください。")
    val DRIVE_SENTENCE: String get() = tr("Your drive sign-in expired. Sign in again in More → Cloud library.", "网盘登录已过期，请在“更多 → 云端书库”中重新登录。", "雲端硬碟登入已過期，請在「更多 → 雲端書庫」中重新登入。", "ドライブのサインイン期限が切れました。「その他 → クラウドライブラリ」で再度サインインしてください。")
    val CLOUD_SENTENCE: String get() = tr("Your cloud sign-in expired. Sign in again in More → Cloud library.", "云端登录已过期，请在“更多 → 云端书库”中重新登录。", "雲端登入已過期，請在「更多 → 雲端書庫」中重新登入。", "クラウドのサインイン期限が切れました。「その他 → クラウドライブラリ」で再度サインインしてください。")
    val WEBDAV_STATUS: String get() = tr("WebDAV · password rejected", "WebDAV · 密码被拒绝", "WebDAV · 密碼遭拒", "WebDAV · パスワード拒否")
    val CARD_BODY: String get() = tr("Uploads and cloud reading are paused. Your comics and library password are untouched.", "上传和云端阅读已暂停。漫画和书库密码未受影响。", "上傳與雲端閱讀已暫停。漫畫與書庫密碼未受影響。", "アップロードとクラウド閲覧を一時停止しています。マンガとライブラリのパスワードは変更されていません。")
    val PAN123_STEPS: String get() = tr("123pan can void an app password without notice; the old one will not work again. In the 123pan app open 我的 → 第三方挂载 → WebDAV 授权管理, delete the old app, tap 添加应用, and enter the new password here.", "123pan 可能会在不通知的情况下使应用密码失效，旧密码无法再次使用。请在 123pan 应用中打开 我的 → 第三方挂载 → WebDAV 授权管理，删除旧应用，点击 添加应用，然后在这里输入新密码。", "123pan 可能會在不通知的情況下使應用程式密碼失效，舊密碼無法再次使用。請在 123pan 應用程式中開啟 我的 → 第三方掛載 → WebDAV 授權管理，刪除舊應用程式，點擊 添加应用，然後在這裡輸入新密碼。", "123pan ではアプリパスワードが予告なく無効になり、古いものは再利用できません。123pan アプリで 我的 → 第三方挂载 → WebDAV 授权管理 を開き、古いアプリを削除して 添加应用 をタップし、新しいパスワードをここに入力してください。")
    val RENEW_ACTION: String get() = tr("Enter the new password", "输入新密码", "輸入新密碼", "新しいパスワードを入力")
    val OTHER_LIBRARY: String get() = tr("This folder no longer holds this library. Disconnect it, then connect again.", "此文件夹不再存放此书库。请断开连接后重新连接。", "此資料夾不再存放此書庫。請中斷連線後重新連線。", "このフォルダにはこのライブラリがありません。接続を解除して再接続してください。")
    val RECENTLY_DELETED: String get() = tr("Recently deleted is unavailable until you reconnect.", "重新连接前无法查看最近删除。", "重新連線前無法查看最近刪除。", "再接続するまで最近削除した項目は利用できません。")
    val UPLOAD_LINE: String get() = tr("failed · cloud sign-in rejected · reconnect in More → Cloud library", "失败 · 云端登录被拒绝 · 请在“更多 → 云端书库”中重新连接", "失敗 · 雲端登入被拒絕 · 請在「更多 → 雲端書庫」中重新連線", "失敗 · クラウドのサインイン拒否 · 「その他 → クラウドライブラリ」で再接続")
    val CHECK_LINE: String get() = tr("Cloud check failed · cloud sign-in rejected · reconnect in More → Cloud library", "云端检查失败 · 云端登录被拒绝 · 请在“更多 → 云端书库”中重新连接", "雲端檢查失敗 · 雲端登入被拒絕 · 請在「更多 → 雲端書庫」中重新連線", "クラウド確認に失敗 · クラウドのサインイン拒否 · 「その他 → クラウドライブラリ」で再接続")
    val COLLECT_TOAST: String get() = tr("Collected (bookmark only). Cloud sign-in rejected — reconnect in More → Cloud library.", "已收藏（仅书签）。云端登录被拒绝，请在“更多 → 云端书库”中重新连接。", "已收藏（僅書籤）。雲端登入被拒絕，請在「更多 → 雲端書庫」中重新連線。", "コレクションしました（ブックマークのみ）。クラウドのサインインが拒否されました。「その他 → クラウドライブラリ」で再接続してください。")
    val DOWNLOAD_REASON: String get() = tr("cloud sign-in rejected", "云端登录被拒绝", "雲端登入被拒絕", "クラウドのサインインが拒否されました")
    val READ_FAILED: String get() = tr("Couldn't open the cloud pages. Try again.", "无法打开云端页面，请重试。", "無法開啟雲端頁面，請重試。", "クラウドのページを開けませんでした。再試行してください。")
    val ONLINE_READ_FAILED: String get() = tr("Online read failed", "在线阅读失败", "線上閱讀失敗", "オンライン閲覧に失敗しました")

    fun sentence(kind: BoundKind?): String = when (kind) {
        BoundKind.WEBDAV -> WEBDAV_SENTENCE
        BoundKind.ONEDRIVE, BoundKind.GOOGLE_DRIVE -> DRIVE_SENTENCE
        else -> CLOUD_SENTENCE
    }

    /** The status line a connected card (and the More row) shows for a refused sign-in; null where the kind has none. */
    fun status(kind: BoundKind?): String? = when (kind) {
        BoundKind.WEBDAV -> WEBDAV_STATUS
        BoundKind.ONEDRIVE, BoundKind.GOOGLE_DRIVE -> DriveExpiredCopy.status(checkNotNull(kind))
        else -> null
    }

    /** The detail line when opening a comic's cloud pages failed: the verdict, or plain words — never a transport code. */
    fun readFailure(error: Throwable, kind: BoundKind?): String = when {
        CloudSignIn.rejected(error) -> sentence(kind)
        error.message.orEmpty().startsWith("CLOUD_") -> READ_FAILED
        else -> error.message ?: READ_FAILED
    }

    /**
     * The detail line when an online read failed. A cloud comic's online read (Continue's fallback) asks the cloud for
     * its source first, so its cloud failures read like [readFailure]; any other failure keeps its own words.
     */
    fun onlineReadFailure(error: Throwable, kind: BoundKind?): String = when {
        CloudSignIn.rejected(error) -> sentence(kind)
        error.message.orEmpty().startsWith("CLOUD_") -> READ_FAILED
        else -> error.message ?: ONLINE_READ_FAILED
    }
}
