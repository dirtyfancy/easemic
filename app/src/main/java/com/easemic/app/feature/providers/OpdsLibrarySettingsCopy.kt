package com.easemic.app.feature.providers

import com.easemic.app.i18n.tr

object OpdsLibrarySettingsCopy {
    val title: String get() = tr("OPDS libraries", "OPDS 书库", "OPDS 書庫", "OPDS ライブラリ")
    val connect: String get() = tr("Connect", "连接", "連線", "接続")
    val disconnect: String get() = tr("Disconnect", "断开连接", "中斷連線", "接続解除")
    val couldNotConnect: String get() = tr("Could not connect to this catalog.", "无法连接此目录。", "無法連接此目錄。", "このカタログに接続できませんでした。")
    val alreadyConnected: String get() = tr("This catalog is already connected.", "此目录已连接。", "此目錄已連接。", "このカタログはすでに接続されています。")

    /**
     * One line per distinguishable cause. The server already told us which one
     * it was; collapsing them all into [couldNotConnect] left the owner with
     * no next step, which is how a working Kavita server read as broken.
     *
     * Kavita is called out by name on the address failure because its OPDS key
     * lives in the URL path — pasting the web UI address is the single most
     * likely way to land here, and no amount of retrying fixes it.
     */
    fun connectFailure(failure: OpdsConnectResult.Failure): String = when (failure) {
        is OpdsConnectResult.Unreachable ->
            tr("Nothing answered at this address. Check the host, the port, and that the server is running.", "此地址没有响应。请检查主机、端口以及服务器是否正在运行。", "此位址沒有回應。請檢查主機、連接埠及伺服器是否正在執行。", "このアドレスから応答がありません。ホスト、ポート、サーバーの稼働状況を確認してください。")
        is OpdsConnectResult.AuthRejected ->
            tr("The server rejected this username and password.", "服务器拒绝了此用户名和密码。", "伺服器拒絕了此使用者名稱與密碼。", "サーバーがユーザー名とパスワードを拒否しました。")
        is OpdsConnectResult.NotAFeed ->
            tr("Reached the server, but no catalog at this address. Kavita: use its OPDS URL, not the web address.", "已连接服务器，但此地址没有目录。Kavita 请使用 OPDS 地址，而不是网页地址。", "已連接伺服器，但此位址沒有目錄。Kavita 請使用 OPDS 位址，而不是網頁位址。", "サーバーには接続しましたが、このアドレスにカタログがありません。Kavita ではウェブアドレスではなく OPDS URL を使用してください。")
        is OpdsConnectResult.ServerError ->
            tr("The server answered with an error (${failure.code}). Try again later.", "服务器返回错误（${failure.code}），请稍后重试。", "伺服器回傳錯誤（${failure.code}），請稍後重試。", "サーバーがエラー（${failure.code}）を返しました。後でもう一度お試しください。")
    }

    fun libraryCount(count: Int): String =
        when (count) {
            0 -> tr("No OPDS libraries yet.", "还没有 OPDS 书库。", "還沒有 OPDS 書庫。", "OPDS ライブラリはまだありません。")
            1 -> tr("1 OPDS library.", "1 个 OPDS 书库。", "1 個 OPDS 書庫。", "OPDS ライブラリ 1 件。")
            else -> tr("$count OPDS libraries.", "$count 个 OPDS 书库。", "$count 個 OPDS 書庫。", "OPDS ライブラリ $count 件。")
        }

    /**
     * Settings-row health line. `lastSeenOkEpochMs` is a persisted fact, not
     * a guessed root cause; when it is missing we say so instead of
     * fabricating a recency.
     */
    fun healthLine(config: OpdsProviderConfig, nowEpochMs: Long): String {
        val state = if (config.unreachableSinceEpochMs == null) tr("Healthy", "正常", "正常", "正常") else tr("Unreachable", "无法连接", "無法連線", "接続不可")
        val lastSeen = config.lastSeenOkEpochMs ?: return "$state · ${tr("never seen", "从未连接", "從未連線", "未確認")}"
        return "$state · ${tr("last seen", "上次连接", "上次連線", "最終確認")} ${formatRelativeTime(nowEpochMs - lastSeen)}"
    }

    internal fun formatRelativeTime(deltaMs: Long): String {
        val clamped = deltaMs.coerceAtLeast(0L)
        val minutes = clamped / 60_000L
        val hours = clamped / 3_600_000L
        val days = clamped / 86_400_000L
        val months = days / 30
        return when {
            minutes < 1 -> tr("just now", "刚刚", "剛剛", "たった今")
            minutes < 60 -> tr("$minutes min ago", "$minutes 分钟前", "$minutes 分鐘前", "$minutes 分前")
            hours < 24 -> tr("$hours hours ago", "$hours 小时前", "$hours 小時前", "$hours 時間前")
            days < 30 -> tr("$days days ago", "$days 天前", "$days 天前", "$days 日前")
            months < 12 -> tr("$months months ago", "$months 个月前", "$months 個月前", "$months か月前")
            else -> tr("${days / 365} years ago", "${days / 365} 年前", "${days / 365} 年前", "${days / 365} 年前")
        }
    }
}
