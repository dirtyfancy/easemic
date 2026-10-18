package com.easemic.app.i18n

/**
 * #E124: Centralized 4-locale UI strings (`en`, `zh-Hans`, `zh-Hant`, `ja`)
 * across Navigation, Library, Updates, Browse, Filter sheets, Detail, Reader,
 * Activity, History, Settings, Cloud Storage / S3 / WebDAV / OAuth, Backup &
 * Restore, and In-App Updates.
 */
object AppStrings {
    // ── Language Picker (matches expo-app) ───────────────────────────────────
    val languageSection: String
        get() = tr("Language", "语言", "語言", "言語")
    val appLanguageLabel: String
        get() = tr("App language", "应用语言", "應用程式語言", "アプリの言語")
    val chooseAppLanguageDesc: String
        get() = tr("Choose app language", "选择应用语言", "選擇應用程式語言", "アプリの言語を選択")

    fun languageOptionLabel(pref: LanguagePreference, lang: AppLanguage = AppI18n.currentLanguage): String =
        when (pref) {
            LanguagePreference.SYSTEM -> tr("Follow system", "跟随系统", "跟隨系統", "システムに従う", lang)
            LanguagePreference.ENGLISH -> "English"
            LanguagePreference.SIMPLIFIED_CHINESE -> "简体中文"
            LanguagePreference.TRADITIONAL_CHINESE -> "繁體中文"
            LanguagePreference.JAPANESE -> "日本語"
        }

    // ── Bottom Navigation Bar ────────────────────────────────────────────────
    val navLibrary: String
        get() = tr("Library", "书库", "書庫", "ライブラリ")
    val navBrowse: String
        get() = tr("Browse", "浏览", "瀏覽", "ブラウズ")
    val navActivity: String
        get() = tr("Activity", "传输", "傳輸", "アクティビティ")
    val navHistory: String
        get() = tr("History", "历史", "歷史", "履歴")
    val navMore: String
        get() = tr("More", "更多", "更多", "その他")
    fun navigateTo(label: String): String = tr("Navigate to $label", "前往$label", "前往$label", "$label に移動")
    val libraryNewChapters: String get() = tr("Library, new chapters", "书库，有新章节", "書庫，有新章節", "ライブラリ、新しいチャプター")

    // ── Common Actions ───────────────────────────────────────────────────────
    val back: String
        get() = tr("Back", "返回", "返回", "戻る")
    val cancel: String
        get() = tr("Cancel", "取消", "取消", "キャンセル")
    val confirm: String
        get() = tr("Confirm", "确认", "確認", "確認")
    val done: String
        get() = tr("Done", "完成", "完成", "完了")
    val save: String
        get() = tr("Save", "保存", "儲存", "保存")
    val clear: String
        get() = tr("Clear", "清除", "清除", "クリア")
    val retry: String
        get() = tr("Retry", "重试", "重試", "再試行")
    val dismiss: String
        get() = tr("Dismiss", "忽略", "忽略", "閉じる")
    val delete: String
        get() = tr("Delete", "删除", "刪除", "削除")
    val open: String
        get() = tr("Open", "打开", "開啟", "開く")
    val signIn: String
        get() = tr("Sign in", "登录", "登入", "サインイン")
    val signOut: String
        get() = tr("Sign out", "退出登录", "登出", "サインアウト")
    val edit: String
        get() = tr("Edit", "编辑", "編輯", "編集")
    val setKey: String
        get() = tr("Set key", "设置密钥", "設定金鑰", "キーを設定")
    val none: String
        get() = tr("None", "无", "無", "なし")
    val copied: String
        get() = tr("Copied", "已复制", "已複製", "コピーしました")

    // ── Library & Updates ────────────────────────────────────────────────────
    val updatesTab: String
        get() = tr("Updates", "更新", "更新", "更新")
    val noComicsYet: String
        get() = tr("No comics yet.", "书库还没有漫画。", "書庫還沒有漫畫。", "まだマンガがありません。")
    val emptyLibraryHint: String
        get() = tr(
            "Import a folder from this phone — image folders, CBZ files, Mihon or EhViewer downloads are all recognized.",
            "从本机导入文件夹——支持图片文件夹、CBZ 压缩包以及 Mihon 或 EhViewer 下载目录。",
            "從本機匯入資料夾——支援圖片資料夾、CBZ 壓縮檔以及 Mihon 或 EhViewer 下載目錄。",
            "この端末からフォルダを読み込みます。画像フォルダ、CBZファイル、Mihon・EhViewerのダウンロードに対応しています。",
        )
    val importFolder: String
        get() = tr("Import folder", "导入文件夹", "匯入資料夾", "フォルダを読み込む")
    val noComicsMatchFilters: String
        get() = tr("No comics match these filters", "没有符合筛选条件的漫画", "沒有符合篩選條件的漫畫", "条件に一致するマンガがありません")
    val clearFilters: String
        get() = tr("Clear filters", "清除筛选", "清除篩選", "フィルタをクリア")
    fun nothingMatchesQuery(query: String): String =
        tr(
            "Nothing matches \"$query\"",
            "没有匹配“$query”的结果",
            "沒有符合「$query」的結果",
            "「$query」に一致する結果はありません",
        )
    fun inYourLibraryCount(count: Int): String =
        tr(
            "$count in your library",
            "书库中共 $count 部",
            "書庫中共 $count 部",
            "ライブラリ内 $count 件",
        )
    val filterTitle: String
        get() = tr("Filter", "筛选", "篩選", "フィルタ")
    val filterAllComics: String
        get() = tr("All comics", "全部漫画", "全部漫畫", "すべてのマンガ")
    val filterCollected: String
        get() = tr("Collected", "已收藏", "已收藏", "コレクション済み")
    val filterDownloaded: String
        get() = tr("Downloaded", "已下载", "已下載", "ダウンロード済み")
    val filterImported: String
        get() = tr("Imported", "本地导入", "本機匯入", "インポート済み")
    val noNewChapters: String
        get() = tr("No new chapters.", "暂无新章节。", "暫無新章節。", "新しい話はありません。")

    // ── Browse ───────────────────────────────────────────────────────────────
    val browseTitle: String
        get() = tr("Browse", "浏览", "瀏覽", "ブラウズ")
    val addCatalogSource: String
        get() = tr("+ Add a catalog source", "+ 添加书源目录", "+ 新增書源目錄", "+ カタログソースを追加")
    val currentSourceUnreachable: String
        get() = tr("Current source unreachable.", "当前图源暂时无法连接。", "目前圖源暫時無法連線。", "現在のソースに接続できません。")
    val noSeriesMatchFilters: String
        get() = tr("No series match these filters", "没有符合筛选条件的作品", "沒有符合篩選條件的作品", "条件に一致する作品がありません")
    val resetFilters: String
        get() = tr("Reset filters", "重置筛选", "重設篩選", "フィルタをリセット")
    val noMatches: String
        get() = tr("No matches.", "没有找到匹配结果。", "沒有找到符合結果。", "一致する結果がありません。")
    val tryDifferentSearchOrFilter: String
        get() = tr(
            "Try a different search or clear filters.",
            "请尝试其他搜索词或清除筛选条件。",
            "請嘗試其他搜尋詞或清除篩選條件。",
            "別のキーワードで検索するか、フィルタをクリアしてください。",
        )
    val nothingToShowYet: String
        get() = tr("Nothing to show yet.", "这里还没有内容。", "這裡還沒有內容。", "まだ表示するものがありません。")
    val connectCatalogInSettings: String
        get() = tr(
            "Connect a catalog in Settings when you are ready.",
            "可在设置中启用或连接书源。",
            "可在設定中啟用或連接書源。",
            "設定からカタログを接続してください。",
        )
    fun searchInSource(sourceName: String): String =
        tr(
            "Search in $sourceName",
            "在 $sourceName 中搜索",
            "在 $sourceName 中搜尋",
            "$sourceName で検索",
        )
    val statusHealthy: String
        get() = tr("Healthy", "正常", "正常", "正常")
    val statusChecking: String
        get() = tr("Checking…", "正在检查…", "正在檢查…", "確認中…")
    val statusUnreachable: String
        get() = tr("Unreachable", "无法连接", "無法連線", "接続不可")

    // ── Detail & Chapters ────────────────────────────────────────────────────
    val readAction: String
        get() = tr("Read", "阅读", "閱讀", "読む")
    val showMore: String
        get() = tr("Show more", "展开", "展開", "もっと見る")
    val showLess: String
        get() = tr("Show less", "收起", "收起", "閉じる")
    val chaptersHeading: String
        get() = tr("Chapters", "章节", "章節", "チャプター")
    val pagesHeading: String
        get() = tr("Pages", "页数", "頁數", "ページ")
    val previewHeading: String
        get() = tr("PAGES · PREVIEW", "页面预览", "頁面預覽", "ページ・プレビュー")
    val whatCollectDoes: String
        get() = tr("What Collect does ›", "了解「收藏」的作用 ›", "了解「收藏」的作用 ›", "「コレクション」の仕組み ›")
    val whatCollectAndDownloadDo: String
        get() = tr("What Collect and Download do ›", "了解「收藏」与「下载」的区别 ›", "了解「收藏」與「下載」的區別 ›", "「コレクション」と「ダウンロード」の違い ›")
    fun showAllTags(total: Int): String =
        tr("Show all $total tags ⌄", "展开全部 $total 个标签 ⌄", "展開全部 $total 個標籤 ⌄", "全 $total 個のタグを表示 ⌄")
    val showFewerTags: String
        get() = tr("Show fewer ⌃", "收起标签 ⌃", "收起標籤 ⌃", "タグを折りたたむ ⌃")
    fun showAllChapters(total: Int): String =
        tr("Show all $total", "显示全部 $total 话", "顯示全部 $total 話", "全 $total 話を表示")
    val showFewerChapters: String
        get() = tr("Show fewer", "收起部分章节", "收起部分章節", "一部のみ表示")

    // ── History ──────────────────────────────────────────────────────────────
    val historyTitle: String
        get() = tr("History", "历史", "歷史", "履歴")
    val historySubtitle: String
        get() = tr(
            "Comics you opened. Not your Library.",
            "你打开过的漫画记录（独立于书库收藏）。",
            "你打開過的漫畫紀錄（獨立於書庫收藏）。",
            "開いたマンガの履歴（ライブラリとは別です）。",
        )
    val historyEmptyTitle: String
        get() = tr("No visits yet.", "还没有浏览记录。", "還沒有瀏覽紀錄。", "まだ閲覧履歴がありません。")
    val historyEmptySubtitle: String
        get() = tr(
            "Open a comic to start your list.",
            "打开任意漫画后会在这里显示。",
            "打開任意漫畫後會在這裡顯示。",
            "マンガを開くとここに履歴が表示されます。",
        )

    // ── Activity ─────────────────────────────────────────────────────────────
    val activityTitle: String
        get() = tr("Activity", "传输", "傳輸", "アクティビティ")
    val activityEmptyTitle: String
        get() = tr("Nothing running.", "当前没有正在进行的任务。", "目前沒有正在進行的任務。", "実行中のタスクはありません。")
    val activityEmptySubtitle: String
        get() = tr(
            "Downloads, imports and cloud sync appear here while they run.",
            "下载、本地导入与云库同步任务运行时会显示在这里。",
            "下載、本機匯入與雲端同步任務執行時會顯示在這裡。",
            "ダウンロード、インポート、クラウド同期の進行状況がここに表示されます。",
        )
    val activityWaiting: String
        get() = tr("Waiting", "排队等待中", "排隊等待中", "待機中")
    val activityFinishing: String
        get() = tr("Uploading · finishing", "正在上传 · 即将完成", "正在上傳 · 即將完成", "アップロード中 · 仕上げ中")
    val activityRetryingLine: String
        get() = tr("retrying · resumes automatically", "正在重试 · 将自动继续", "正在重試 · 將自動繼續", "再試行中 · 自動で再開します")
    val activityPausedLine: String
        get() = tr("paused · resumes automatically", "已暂停 · 将自动继续", "已暫停 · 將自動繼續", "一時停止中 · 自動で再開します")
    val activityBackgroundPausedLine: String
        get() = tr("Paused while in background · resuming", "后台运行受限已暂停 · 正在恢复", "背景執行受限已暫停 · 正在恢復", "バックグラウンドで一時停止 · 再開中")
    val activityFailedLine: String
        get() = tr("failed · tap Retry", "失败 · 点击重试", "失敗 · 點擊重試", "失敗 · 再試行をタップ")
    val activityUnboundLine: String
        get() = tr("waiting · connect cloud storage", "等待中 · 请先连接云存储", "等待中 · 請先連接雲端儲存", "待機中 · クラウドストレージを接続してください")
    val activityReconnectLine: String
        get() = tr("failed · reconnect cloud storage", "失败 · 请重新连接云存储", "失敗 · 請重新連接雲端儲存", "失敗 · クラウドストレージに再接続してください")
    val activityQuotaFullLine: String
        get() = tr("failed · cloud storage is full", "失败 · 云存储空间已满", "失敗 · 雲端儲存空間已滿", "失敗 · クラウドの空き容量がありません")
    val activityChooseFolderAgain: String
        get() = tr("Choose folder again", "重新选择文件夹", "重新選擇資料夾", "フォルダを再選択")
    val activityImportFinished: String
        get() = tr("Import finished", "导入已完成", "匯入已完成", "インポート完了")
    fun activityNeedsAttentionHeader(count: Int): String =
        tr("Needs attention · $count", "需要处理 · $count", "需要處理 · $count", "要確認 · $count")
    fun activityDownloadingHeader(count: Int): String =
        tr("Downloading · $count", "正在下载 · $count", "正在下載 · $count", "ダウンロード中 · $count")
    fun activitySyncingHeader(count: Int): String =
        tr("Syncing to cloud · $count", "正在同步到云库 · $count", "正在同步至雲端 · $count", "クラウドへ同期中 · $count")
    fun activityImportingHeader(count: Int): String =
        tr("Importing · $count", "正在导入 · $count", "正在匯入 · $count", "インポート中 · $count")
    fun activityPageProgress(ready: Int, expected: Int): String =
        tr("page $ready of $expected", "第 $ready / $expected 页", "第 $ready / $expected 頁", "$ready / $expected ページ")

    // ── Settings ─────────────────────────────────────────────────────────────
    val settingsTitle: String
        get() = tr("Settings", "设置", "設定", "設定")
    val cloudLibraryTitle: String
        get() = tr("Cloud library", "云端书库", "雲端書庫", "クラウドライブラリ")
    val cloudReleaseTitle: String
        get() = tr("Keep only the cloud copy", "上传后释放本机空间", "上傳後釋放本機空間", "クラウドのコピーのみ残す")
    val cloudReleaseSubtitle: String
        get() = tr(
            "After a comic is in your cloud library, remove the copy on this phone.",
            "漫画成功上传到云库后，自动清理本机缓存副本以节省空间。",
            "漫畫成功上傳至雲端書庫後，自動清理本機快取副本以節省空間。",
            "クラウドライブラリへの保存が完了したあと、この端末のコピーを削除します。",
        )
    val typesafeTitle: String
        get() = tr("TypeSafe AI (Jev) ad filter", "TypeSafe AI (Jev) 尾页去广告", "TypeSafe AI (Jev) 尾頁去廣告", "TypeSafe AI (Jev) 広告フィルタ")
    val typesafeSubtitleUnconfigured: String
        get() = tr(
            "Not configured. Add a TypeSafe API key to trim trailing sponsor ads from extraneous-ads comics on cloud upload.",
            "未配置。填入 TypeSafe API 密钥可在上传带 extraneous ads 标签的漫画时自动剔除尾页商业广告。",
            "未設定。填入 TypeSafe API 金鑰可在上傳帶 extraneous ads 標籤的漫畫時自動剔除尾頁商業廣告。",
            "未設定。TypeSafe APIキーを設定すると、extraneous ads タグ付きマンガのクラウド保存時に末尾の広告を自動除外します。",
        )
    val typesafeDialogBody: String
        get() = tr(
            "When connected to a cloud library, galleries tagged with extraneous ads are scanned from the last page backward using TypeSafe (jev-latest) and a shared 64-bit dHash fingerprint cache. Commercial sponsor ads are trimmed while comic pages and scanlation credits are kept.",
            "连接云库后，带有 extraneous ads 标签的画廊在上传时会从末页往前扫描（使用 TypeSafe jev-latest 与共享 64 位 dHash 指纹库），自动裁掉商业赞助广告，同时完整保留漫画正文与汉化组尾页。",
            "連接雲端書庫後，帶有 extraneous ads 標籤的畫廊在上傳時會從末頁往前掃描（使用 TypeSafe jev-latest 與共享 64 位元 dHash 指紋庫），自動裁掉商業贊助廣告，同時完整保留漫畫正文與漢化組尾頁。",
            "クラウドライブラリ接続時、extraneous ads タグ付きギャラリーを末尾からTypeSafe (jev-latest) と64ビットdHashキャッシュでスキャンし、商業広告のみを除外して本編とクレジットページを保持します。",
        )
    val apiKeyLabel: String
        get() = tr("API key", "API 密钥", "API 金鑰", "APIキー")
    val sourcesGroupLabel: String
        get() = tr("Sources", "漫画源", "漫畫源", "ソース")
    val comicSourcesTitle: String
        get() = tr("Comic sources", "在线漫画源", "線上漫畫源", "コミックソース")
    fun activeSourcesCountLabel(count: Int): String =
        tr("$count active", "已启用 $count 个", "已啟用 $count 個", "$count 件有効")
    val serializedMangaGroupLabel: String
        get() = tr("Serialized manga", "连载漫画", "連載漫畫", "連載マンガ")
    val otherCatalogsGroupLabel: String
        get() = tr("Other catalogs", "其他画廊", "其他畫廊", "その他のカタログ")
    val zaimanhuaSignedIn: String
        get() = tr("Signed in", "已登录", "已登入", "サインイン済み")
    fun zaimanhuaSignedInWithExpiry(expiry: String): String =
        tr("Signed in · $expiry", "已登录 · $expiry", "已登入 · $expiry", "サインイン済み · $expiry")
    val zaimanhuaSignedOutSubtitle: String
        get() = tr(
            "Not signed in. Signing in shows the full latest list and chapters that need an account.",
            "未登录。登录后可查看完整最新列表及需要账号的章节。",
            "未登入。登入後可查看完整最新列表及需要帳號的章節。",
            "未サインイン。サインインすると最新の全一覧やアカウント限定チャプターを表示できます。",
        )
    val jmBrowseSubtitle: String
        get() = tr("Show in Browse · no account needed", "在浏览页显示 · 免登录", "在瀏覽頁顯示 · 免登入", "ブラウズに表示 · アカウント不要")
    val opdsLibrariesTitle: String
        get() = tr("OPDS libraries", "OPDS 书库", "OPDS 書庫", "OPDS ライブラリ")
    val foldersOnPhoneTitle: String
        get() = tr("Folders on this phone", "本机文件夹", "本機資料夾", "この端末のフォルダ")
    val storageOnPhoneGroupLabel: String
        get() = tr("Storage on this phone", "本机存储与缓存", "本機儲存與快取", "この端末のストレージ")
    val clearCacheAction: String
        get() = tr("Clear cache", "清理缓存", "清除快取", "キャッシュを消去")
    fun ofCacheLabel(budgetMb: Int): String {
        val formatted = when (budgetMb) {
            1024 -> "1 GB"
            2048 -> "2 GB"
            else -> "$budgetMb MB"
        }
        return tr("of $formatted cache", "/ $formatted 缓存上限", "/ $formatted 快取上限", "/ $formatted キャッシュ上限")
    }
    val cacheRestartNote: String
        get() = tr(
            "New limit applies next time you open the app.",
            "新的缓存上限将在下次启动应用时生效。",
            "新的快取上限將在下次啟動應用程式時生效。",
            "新しい上限は次回アプリ起動時から適用されます。",
        )
    val readingGroupLabel: String
        get() = tr("Reading", "阅读与显示", "閱讀與顯示", "読書と表示")
    val libraryLayoutTitle: String
        get() = tr("Library layout", "书库布局", "書庫佈局", "ライブラリの表示形式")
    val layoutGrid: String
        get() = tr("Grid", "网格", "網格", "グリッド")
    val layoutList: String
        get() = tr("List", "列表", "列表", "リスト")
    val keepScreenOnTitle: String
        get() = tr("Keep screen on", "阅读时保持屏幕常亮", "閱讀時保持螢幕常亮", "読書中は画面を消灯しない")
    val volumeKeysTurnPagesTitle: String
        get() = tr("Volume keys turn pages", "音量键翻页", "音量鍵翻頁", "音量キーでページめくり")
    val rtlPagingTitle: String
        get() = tr("Right-to-left (manga)", "从右往左翻页（日漫模式）", "從右往左翻頁（日漫模式）", "右から左へめくる（マンガ形式）")
    val downloadsGroupLabel: String
        get() = tr("Downloads", "下载与后台", "下載與背景", "ダウンロード")
    val maxConcurrentTransfersTitle: String
        get() = tr("Parallel downloads & uploads", "同时下载与上传数", "同時下載與上傳數", "同時ダウンロード・アップロード数")
    val checkFollowedSeriesInBgTitle: String
        get() = tr("Check followed series in the background", "后台检查已收藏连载更新", "背景檢查已收藏連載更新", "フォロー中の連載をバックグラウンドで確認")
    val onlyOnWifiTitle: String
        get() = tr("Only on Wi-Fi", "仅在 Wi-Fi 下检查", "僅在 Wi-Fi 下檢查", "Wi-Fi 接続時のみ")
    val backgroundReliabilityTitle: String
        get() = tr("Background reliability", "后台运行保障", "背景執行保障", "バックグラウンド動作の安定性")
    val backgroundAllGood: String
        get() = tr("All good", "正常", "正常", "問題なし")
    val backgroundExplanation: String
        get() = tr(
            "Without notification permission the system may stop downloads and uploads when the screen is off.",
            "若未开启通知权限或未允许后台运行，锁屏后系统可能会中断下载与云端上传。",
            "若未開啟通知權限或未允許背景執行，鎖定螢幕後系統可能會中斷下載與雲端上傳。",
            "通知の許可やバッテリー最適化の除外がない場合、画面消灯時にダウンロードやアップロードが停止されることがあります。",
        )
    val grantNotificationsTitle: String
        get() = tr("Notifications", "通知权限", "通知權限", "通知")
    val grantNotificationsSubtitle: String
        get() = tr(
            "Off — background downloads and uploads may be silently stopped.",
            "未开启 — 后台下载与上传可能会被系统静默终止。",
            "未開啟 — 背景下載與上傳可能會被系統靜默終止。",
            "オフ — バックグラウンドの転送が停止される可能性があります。",
        )
    val batteryOptimizationTitle: String
        get() = tr("Battery optimization", "电池优化白名单", "電池最佳化白名單", "バッテリー最適化")
    val batteryOptimizationSubtitle: String
        get() = tr(
            "Battery optimization may pause downloads and uploads in the background.",
            "省电限制可能会暂停后台下载与上传任务。",
            "省電限制可能會暫停背景下載與上傳任務。",
            "バッテリー最適化によりバックグラウンド転送が一時停止されることがあります。",
        )
    val turnOnAction: String
        get() = tr("Turn on", "去开启", "去開啟", "オンにする")
    val allowAction: String
        get() = tr("Allow", "允许", "允許", "許可する")
    val thisPhoneGroupLabel: String
        get() = tr("This phone", "本机与通用", "本機與一般", "この端末と一般")
    val moveToNewPhoneTitle: String
        get() = tr("Move to a new phone", "迁移到新手机（备份与恢复）", "遷移至新手機（備份與還原）", "新しい端末へ移行（バックアップと復元）")

    // ── In-App Update (#E123 + #E124) ────────────────────────────────────────
    fun updateAvailableTitle(versionName: String): String =
        tr(
            "Easemic $versionName is available",
            "发现新版本 Easemic $versionName",
            "發現新版本 Easemic $versionName",
            "新しいバージョン Easemic $versionName が利用可能です",
        )
    val updateAction: String
        get() = tr("Update", "立即更新", "立即更新", "今すぐ更新")
    val installNowAction: String
        get() = tr("Install now", "立即安装", "立即安裝", "今すぐインストール")
    val laterAction: String
        get() = tr("Later", "稍后", "稍後", "あとで")
    val allowInSettingsAction: String
        get() = tr("Allow in Settings", "去设置开启", "去設定開啟", "設定で許可する")
    val browserDownloadAction: String
        get() = tr("Download in browser", "浏览器下载", "瀏覽器下載", "ブラウザでダウンロード")
    val verifyingUpdatePackage: String
        get() = tr("Verifying update package…", "正在校验安装包…", "正在校驗安裝包…", "更新パッケージを検証中…")
    val updatePermissionMessage: String
        get() = tr(
            "Allow \"Install unknown apps\" for Easemic to finish updating. Return here and the installer will open automatically.",
            "需要允许「安装未知应用」才能完成更新。授权后返回即可自动唤起安装。",
            "需要允許「安裝未知應用程式」才能完成更新。授權後返回即可自動喚起安裝。",
            "更新を完了するには「不明なアプリのインストール」を許可してください。許可後に戻ると自動でインストーラーが開きます。",
        )
    val updateDownloadFailed: String
        get() = tr(
            "Download did not finish. Retry or download in your browser.",
            "下载未完成，请重试或使用浏览器下载。",
            "下載未完成，請重試或使用瀏覽器下載。",
            "ダウンロードが完了しませんでした。再試行するかブラウザからダウンロードしてください。",
        )
    val updateVerificationFailed: String
        get() = tr(
            "The downloaded update package could not be verified. Please retry.",
            "安装包校验未通过，请重试。",
            "安裝包校驗未通過，請重試。",
            "ダウンロードしたパッケージを検証できませんでした。再試行してください。",
        )
    val updateRowSubtitle: String
        get() = tr(
            "See what's new and install it.",
            "查看更新内容并直接安装。",
            "查看更新內容並直接安裝。",
            "更新内容を確認してインストールします。",
        )
    val checkForUpdatesSwitchTitle: String
        get() = tr("Check for updates", "自动检查更新", "自動檢查更新", "アップデートを確認")
    val checkForUpdatesSwitchSubtitle: String
        get() = tr(
            "Once a day, Easemic asks easemic.com for a newer version. It sends nothing about you or your library.",
            "每天最多向 easemic.com 查询一次新版本，不会发送任何关于你或书库的信息。",
            "每天最多向 easemic.com 查詢一次新版本，不會傳送任何關於你或書庫的資訊。",
            "1日1回 easemic.com に新しいバージョンを確認します。ユーザーやライブラリの情報は一切送信されません。",
        )
    fun downloadingProgressLabel(percent: Int, received: String, total: String): String =
        tr(
            "Downloading $percent% ($received / $total)",
            "正在下载 $percent%（$received / $total）",
            "正在下載 $percent%（$received / $total）",
            "ダウンロード中 $percent%（$received / $total）",
        )
    fun downloadingIndeterminateLabel(received: String): String =
        tr(
            "Downloading $received…",
            "正在下载 $received…",
            "正在下載 $received…",
            "ダウンロード中 $received…",
        )

    // ── Remaining UI copy (#E131) ────────────────────────────────────────────
    // These entries cover the display-only copy that was previously kept in
    // feature-local copy objects. Persisted markers and diagnostic strings stay
    // in their original English form and are translated only by their renderers.
    val ok: String get() = tr("OK", "确定", "確定", "OK")
    val close: String get() = tr("Close", "关闭", "關閉", "閉じる")
    val notNow: String get() = tr("Not now", "暂不", "暫不", "今はしない")
    val gotIt: String get() = tr("Got it", "知道了", "知道了", "了解")
    val selectAll: String get() = tr("Select all", "全选", "全選", "すべて選択")
    val previousPage: String get() = tr("Previous page", "上一页", "上一頁", "前のページ")
    val nextPage: String get() = tr("Next page", "下一页", "下一頁", "次のページ")
    val page: String get() = tr("Page", "第", "第", "ページ")
    fun pageOf(current: Int, total: Int): String =
        tr("Page $current of $total", "第 $current / $total 页", "第 $current / $total 頁", "$current / $total ページ")
    fun pageNumber(current: Int): String = tr("Page $current", "第 $current 页", "第 $current 頁", "$current ページ")
    val toggleReaderControls: String get() = tr("Toggle reader controls", "显示/隐藏阅读控制", "顯示／隱藏閱讀控制", "読書コントロールを表示／非表示")
    val imageFailedToLoad: String get() = tr("Image failed to load", "图片加载失败", "圖片載入失敗", "画像を読み込めません")
    val coverFailedToLoad: String get() = tr("Cover failed to load", "封面加载失败", "封面載入失敗", "表紙を読み込めません")
    val loadingPage: String get() = tr("Loading page", "正在加载页面", "正在載入頁面", "ページを読み込み中")
    val retryPage: String get() = tr("Retry page", "重试页面", "重試頁面", "ページを再試行")
    val readerPageCounter: String get() = tr("Reader page counter", "阅读器页码", "閱讀器頁碼", "リーダーのページ番号")
    val readerPageSeek: String get() = tr("Reader page seek", "跳转阅读页", "跳轉閱讀頁", "ページ移動")
    val readerSeekCurrentPage: String get() = tr("Reader seek current page", "当前阅读页", "目前閱讀頁", "現在のページ")
    val readerSeekTotalPages: String get() = tr("Reader seek total pages", "总页数", "總頁數", "総ページ数")
    val coverFor: String get() = tr("Cover for", "封面：", "封面：", "表紙：")
    fun coverForTitle(title: String): String = tr("Cover for $title", "${coverFor}$title", "${coverFor}$title", "${coverFor}$title")
    fun coverForTitlePages(title: String, pages: String): String =
        tr("Cover for $title, $pages", "${coverFor}$title，$pages", "${coverFor}$title，$pages", "${coverFor}$title、$pages")
    val searchLibrary: String get() = tr("Search library", "搜索书库", "搜尋書庫", "ライブラリを検索")
    val filterLibrary: String get() = tr("Filter library", "筛选书库", "篩選書庫", "ライブラリを絞り込む")
    val filterActive: String get() = tr("Filter active", "筛选已启用", "篩選已啟用", "フィルタ適用中")
    val closeSearch: String get() = tr("Close search", "关闭搜索", "關閉搜尋", "検索を閉じる")
    val importFolderA11y: String get() = tr("Import folder", "导入文件夹", "匯入資料夾", "フォルダを読み込む")
    val importingFolder: String get() = tr("Importing folder", "正在导入文件夹", "正在匯入資料夾", "フォルダを読み込み中")
    val libraryLoading: String get() = tr("Library loading", "书库加载中", "書庫載入中", "ライブラリを読み込み中")
    fun moreOptionsFor(name: String): String = tr("More options for $name", "$name 的更多选项", "$name 的更多選項", "$name のその他のオプション")
    val fastScroller: String get() = tr("Fast scroller", "快速滚动条", "快速捲動列", "高速スクロール")
    val openSource: String get() = tr("Open source", "打开图源", "開啟圖源", "ソースを開く")
    fun openSourceNamed(name: String): String = tr("Open source $name", "打开图源 $name", "開啟圖源 $name", "ソース $name を開く")
    val backToSources: String get() = tr("Back to sources", "返回图源列表", "返回圖源列表", "ソース一覧に戻る")
    fun filterSource(name: String): String = tr("Filter $name", "筛选 $name", "篩選 $name", "$name を絞り込む")
    fun removeFilter(label: String): String = tr("Remove filter $label", "移除筛选条件 $label", "移除篩選條件 $label", "フィルタ $label を削除")
    val clearSearch: String get() = tr("Clear search", "清除搜索", "清除搜尋", "検索をクリア")
    val retryLoadMore: String get() = tr("Retry load more", "重试加载更多", "重試載入更多", "さらに読み込む操作を再試行")
    val addCatalogSourceA11y: String get() = tr("Add a catalog source", "添加书源目录", "新增書源目錄", "カタログソースを追加")

    // Browse and serialized chapter filters.
    val filters: String get() = tr("Filters", "筛选条件", "篩選條件", "フィルタ")
    val includeTags: String get() = tr("Include tags", "包含标签", "包含標籤", "含めるタグ")
    val excludeTags: String get() = tr("Exclude tags", "排除标签", "排除標籤", "除外するタグ")
    val minimumRating: String get() = tr("Minimum rating", "最低评分", "最低評分", "最低評価")
    val timeJump: String get() = tr("Time jump", "时间跳转", "時間跳轉", "期間ジャンプ")
    val uploadedWithin: String get() = tr("Uploaded within", "上传时间范围", "上傳時間範圍", "アップロード期間")
    val onlyTorrents: String get() = tr("Only torrents", "仅 Torrent", "僅 Torrent", "Torrent のみ")
    val includeExpunged: String get() = tr("Include expunged galleries", "包括已删除画廊", "包括已刪除畫廊", "削除済みギャラリーを含める")
    val includeExpungedChip: String get() = tr("Include expunged", "含已删除", "含已刪除", "削除済みを含む")
    val addTagsToInclude: String get() = tr("Add tags to include…", "添加要包含的标签…", "新增要包含的標籤…", "含めるタグを追加…")
    val addTagsToExclude: String get() = tr("Add tags to exclude…", "添加要排除的标签…", "新增要排除的標籤…", "除外するタグを追加…")
    val femaleIncludePlaceholder: String get() = tr("female:sole female or tag…", "female:sole female 或标签…", "female:sole female 或標籤…", "female:sole female またはタグ…")
    val femaleExcludePlaceholder: String get() = tr("female:ntr or tag to exclude…", "female:ntr 或要排除的标签…", "female:ntr 或要排除的標籤…", "female:ntr または除外するタグ…")
    val addEhentaiIncludeTag: String get() = tr("Add E-Hentai include tag", "添加 E-Hentai 包含标签", "新增 E-Hentai 包含標籤", "E-Hentai の含めるタグを追加")
    val addEhentaiExcludeTag: String get() = tr("Add E-Hentai exclude tag", "添加 E-Hentai 排除标签", "新增 E-Hentai 排除標籤", "E-Hentai の除外タグを追加")
    val addIncludeTag: String get() = tr("Add include tag", "添加包含标签", "新增包含標籤", "含めるタグを追加")
    val addExcludeTag: String get() = tr("Add exclude tag", "添加排除标签", "新增排除標籤", "除外するタグを追加")
    val minimumPages: String get() = tr("Minimum pages", "最少页数", "最少頁數", "最小ページ数")
    val maximumPages: String get() = tr("Maximum pages", "最多页数", "最多頁數", "最大ページ数")
    val addLanguageTag: String get() = tr("Add language tag", "添加语言标签", "新增語言標籤", "言語タグを追加")
    val now: String get() = tr("Now", "现在", "現在", "現在")
    fun atLeastPages(n: Int): String = tr("≥ $n pages", "≥ $n 页", "≥ $n 頁", "≥ $n ページ")
    fun atMostPages(n: Int): String = tr("≤ $n pages", "≤ $n 页", "≤ $n 頁", "≤ $n ページ")
    fun uploadedWithinDays(days: Int): String = tr("Uploaded ≤ ${days}d", "上传不超过 ${days} 天", "上傳不超過 ${days} 天", "${days}日以内にアップロード")
    fun hideCategory(category: String): String = tr("Hide: $category", "隐藏：$category", "隱藏：$category", "非表示：$category")
    fun jumpTo(label: String): String = tr("Jump: $label", "跳转：$label", "跳轉：$label", "ジャンプ：$label")
    val browseFiltersPauseWhileSearching: String get() = tr("Browse filters pause while searching.", "搜索时暂时停用浏览筛选。", "搜尋時暫時停用瀏覽篩選。", "検索中はブラウズのフィルタを一時停止します。")
    val categoryPauseWhileSearching: String get() = tr("Category pauses while searching.", "搜索时暂时停用分类。", "搜尋時暫時停用分類。", "検索中はカテゴリを一時停止します。")
    val timeRangeMostViewed: String get() = tr("Time range applies to Most viewed.", "时间范围仅适用于“浏览最多”。", "時間範圍僅適用於「瀏覽最多」。", "期間は「閲覧数順」に適用されます。")
    val albumMatchFiltersDoNotApply: String get() = tr("Album match · filters do not apply.", "专辑匹配 · 筛选条件不适用。", "專輯符合 · 篩選條件不適用。", "アルバム一致 · フィルタは適用されません。")
    fun noScopeResults(scope: String, query: String): String = tr("No $scope results. Showing all results for $query.", "没有 $scope 结果。显示 $query 的全部结果。", "沒有 $scope 結果。顯示 $query 的全部結果。", "$scope の結果がありません。$query の全結果を表示します。")
    val membersOnlyNotSaved: String get() = tr("Members only · not saved", "仅限会员 · 未保存", "僅限會員 · 未儲存", "会員限定 · 未保存")
    val serializedSignInHint: String get() = tr("These chapters need a 再漫画 account.", "这些章节需要再漫画账号。", "這些章節需要再漫畫帳號。", "このチャプターには再漫画アカウントが必要です。")
    val authorSearch: String get() = tr("Author search", "作者搜索", "作者搜尋", "作者検索")
    val tagSearch: String get() = tr("Tag search", "标签搜索", "標籤搜尋", "タグ検索")
    val signedOutZaimanhuaNotice: String get() = tr("Signed out, 再漫画 shows an older update list.", "已退出登录，再漫画会显示较旧的更新列表。", "已登出，再漫畫會顯示較舊的更新列表。", "サインアウト中は再漫画の古い更新一覧が表示されます。")

    // Cloud, backup, providers, and bind screens.
    val cloudStorageSaved: String get() = tr("Cloud storage saved on this device.", "云存储已保存到本机。", "雲端儲存已儲存至本機。", "クラウドストレージをこの端末に保存しました。")
    val connectCloudflare: String get() = tr("Connect Cloudflare", "连接 Cloudflare", "連接 Cloudflare", "Cloudflare に接続")
    val r2NotEnabled: String get() = tr("R2 is not enabled on this Cloudflare account.", "此 Cloudflare 账号未启用 R2。", "此 Cloudflare 帳號未啟用 R2。", "この Cloudflare アカウントでは R2 が有効になっていません。")
    val connectCloudflareNotSetup: String get() = tr("Connect Cloudflare is not set up on this build.", "此版本未配置 Connect Cloudflare。", "此版本未設定 Connect Cloudflare。", "このビルドでは Connect Cloudflare が設定されていません。")
    val connectCloudflareFailed: String get() = tr("Could not connect to Cloudflare. Try again.", "无法连接 Cloudflare，请重试。", "無法連接 Cloudflare，請重試。", "Cloudflare に接続できません。再試行してください。")
    val disconnectedBucketUntouched: String get() = tr("Disconnected. This bucket was not touched.", "已断开连接，未改动此存储桶。", "已中斷連線，未變更此儲存桶。", "接続を解除しました。このバケットは変更していません。")
    val connectCloudflareOrPaste: String get() = tr("Connect Cloudflare or paste S3 credentials.", "连接 Cloudflare 或粘贴 S3 凭据。", "連接 Cloudflare 或貼上 S3 憑證。", "Cloudflare に接続するか S3 認証情報を貼り付けてください。")
    val currentPassword: String get() = tr("Current password", "当前密码", "目前密碼", "現在のパスワード")
    val newPassword: String get() = tr("New password", "新密码", "新密碼", "新しいパスワード")
    val confirmNewPassword: String get() = tr("Confirm new password", "确认新密码", "確認新密碼", "新しいパスワードを確認")
    val confirmPassword: String get() = tr("Confirm password", "确认密码", "確認密碼", "パスワードを確認")
    val restoreAction: String get() = tr("Restore", "恢复", "還原", "復元")
    val libraryPassword: String get() = tr("Library password", "书库密码", "書庫密碼", "ライブラリのパスワード")
    val useAutoR2Hint: String get() = tr("Use auto for Cloudflare R2, or enter the bucket region.", "Cloudflare R2 可使用自动，或输入存储桶区域。", "Cloudflare R2 可使用自動，或輸入儲存桶區域。", "Cloudflare R2 は自動にするか、バケットのリージョンを入力してください。")
    val r2Endpoint: String get() = tr("R2 endpoint", "R2 端点", "R2 端點", "R2 エンドポイント")
    val s3Region: String get() = tr("S3 region", "S3 区域", "S3 區域", "S3 リージョン")
    val accessKey: String get() = tr("Access Key", "访问密钥", "存取金鑰", "アクセスキー")
    val r2AccessKeyDescription: String get() = tr("R2 access key", "R2 访问密钥", "R2 存取金鑰", "R2 アクセスキー")
    val r2Secret: String get() = tr("R2 secret", "R2 密钥", "R2 密鑰", "R2 シークレット")
    val r2Bucket: String get() = tr("R2 bucket", "R2 存储桶", "R2 儲存桶", "R2 バケット")
    val cloudPickerHeadline: String get() = tr("Your own cloud.\nNot ours.", "你的云端，由你掌控。\n不是我们的。", "你的雲端，由你掌控。\n不是我們的。", "あなた自身のクラウド。\n私たちのものではありません。")
    val cloudPickerSubtitle: String get() = tr("Pick where your comics live. One place per library.", "选择漫画存放的位置。每个书库使用一个位置。", "選擇漫畫存放的位置。每個書庫使用一個位置。", "マンガの保存場所を選びます。ライブラリごとに1か所です。")
    val chooseStorage: String get() = tr("Which storage should I choose?", "应该选择哪种存储？", "應該選擇哪種儲存空間？", "どのストレージを選べばよいですか？")
    val choosingStorage: String get() = tr("Choosing a storage place", "选择存储位置", "選擇儲存位置", "保存場所を選択")
    val s3Storage: String get() = tr("S3 storage", "S3 存储", "S3 儲存", "S3 ストレージ")
    val quickApiSetup: String get() = tr("Quick setup with API key", "使用 API 密钥快速设置", "使用 API 金鑰快速設定", "API キーで簡単設定")
    val customS3Credentials: String get() = tr("Custom S3 credentials", "自定义 S3 凭据", "自訂 S3 憑證", "カスタム S3 認証情報")
    val cloudDrives: String get() = tr("Cloud drives", "云端硬盘", "雲端硬碟", "クラウドドライブ")
    val signInWithGoogleSentence: String get() = tr("Sign in with Google.", "使用 Google 登录。", "使用 Google 登入。", "Google でサインインします。")
    val driveAlwaysEncrypted: String get() = tr("Drive libraries are always encrypted with a password you set.", "云端硬盘书库始终使用你设置的密码加密。", "雲端硬碟書庫一律使用你設定的密碼加密。", "ドライブのライブラリは設定したパスワードで常に暗号化されます。")
    val backupScroll: String get() = tr("Backup scroll", "备份页面滚动区域", "備份頁面捲動區域", "バックアップのスクロール")
    val moveToNewPhone: String get() = tr("Move to a new phone", "迁移到新手机", "遷移至新手機", "新しい端末へ移行")
    val measuring: String get() = tr("Measuring…", "正在计算…", "正在計算…", "計測中…")
    val onThisPhone: String get() = tr("On this phone", "在本机", "在本機", "この端末")
    val saveBackup: String get() = tr("Save a backup", "保存备份", "儲存備份", "バックアップを保存")
    val chooseWhereToSave: String get() = tr("Choose where to save", "选择保存位置", "選擇儲存位置", "保存先を選択")
    val onNewPhone: String get() = tr("On the new phone", "在新手机上", "在新手機上", "新しい端末")
    val restoreFromBackup: String get() = tr("Restore from a backup", "从备份恢复", "從備份還原", "バックアップから復元")
    val chooseBackupFile: String get() = tr("Choose a backup file", "选择备份文件", "選擇備份檔案", "バックアップファイルを選択")
    val thisBackupHas: String get() = tr("This backup has", "此备份包含", "此備份包含", "このバックアップには")
    val collectionAndProgressOnly: String get() = tr("Collection and progress only", "仅收藏和进度", "僅收藏與進度", "コレクションと進捗のみ")
    val backupSmallHint: String get() = tr("A few KB — comics come back from the cloud or the source", "只有几 KB——漫画会从云端或图源恢复", "只有幾 KB——漫畫會從雲端或圖源還原", "数 KB — マンガはクラウドまたはソースから戻ります")
    val fileCheckRestore: String get() = tr("File · Check · Restore", "文件 · 检查 · 恢复", "檔案 · 檢查 · 還原", "ファイル · 確認 · 復元")
    fun backupContents(count: Int, size: String): String = tr("$count · $size, plus collection and progress", "$count · $size，另含收藏和进度", "$count · $size，另含收藏與進度", "$count · $size、コレクションと進捗を含む")
    val noDownloadedComics: String get() = tr("No downloaded comics yet", "还没有下载的漫画", "還沒有下載的漫畫", "ダウンロード済みのマンガはありません")
    val setBackupPassword: String get() = tr("Set a backup password", "设置备份密码", "設定備份密碼", "バックアップパスワードを設定")
    val enterBackupPassword: String get() = tr("Enter the backup password", "输入备份密码", "輸入備份密碼", "バックアップパスワードを入力")
    val setPasswordForFile: String get() = tr("Set a password for this file", "为此文件设置密码", "為此檔案設定密碼", "このファイルのパスワードを設定")
    val backupPasswordIrrecoverable: String get() = tr("You need it to restore. It cannot be recovered.", "恢复时需要此密码，无法找回。", "還原時需要此密碼，無法找回。", "復元に必要です。失くしても復元できません。")
    val enterPassword: String get() = tr("Enter a password.", "请输入密码。", "請輸入密碼。", "パスワードを入力してください。")
    val passwordsDoNotMatch: String get() = tr("Passwords do not match.", "两次密码不一致。", "兩次密碼不一致。", "パスワードが一致しません。")
    val useAtLeastSix: String get() = tr("Use at least 6 characters.", "请至少使用 6 个字符。", "請至少使用 6 個字元。", "6文字以上で入力してください。")
    val wrongBackupPassword: String get() = tr("Wrong password, or this is not a backup file.", "密码错误，或这不是备份文件。", "密碼錯誤，或這不是備份檔案。", "パスワードが違うか、バックアップファイルではありません。")
    val backupDamaged: String get() = tr("This backup file is damaged or incomplete.", "此备份文件已损坏或不完整。", "此備份檔案已損壞或不完整。", "このバックアップファイルは破損または不完全です。")
    val backupNewerApp: String get() = tr("This backup was made by a newer version of the app.", "此备份由更新版本的应用创建。", "此備份由較新版本的應用程式建立。", "このバックアップは新しいバージョンのアプリで作成されています。")
    val notEnoughFreeSpace: String get() = tr("Not enough free space on this phone for these comics.", "本机没有足够空间保存这些漫画。", "本機沒有足夠空間儲存這些漫畫。", "この端末にはこれらのマンガを保存する空き容量がありません。")
    val couldNotWriteBackup: String get() = tr("Could not write the backup.", "无法写入备份。", "無法寫入備份。", "バックアップを書き込めませんでした。")
    val backupRestored: String get() = tr("Backup restored.", "备份已恢复。", "備份已還原。", "バックアップを復元しました。")
    val importFailed: String get() = tr("Import failed", "导入失败", "匯入失敗", "インポートに失敗しました")
    val couldNotReadBackup: String get() = tr("Could not read backup", "无法读取备份", "無法讀取備份", "バックアップを読み込めませんでした")

    // Folder, queue, cloud-library and reader status copy.
    val foldersOnThisPhone: String get() = tr("Folders on this phone", "本机文件夹", "本機資料夾", "この端末のフォルダ")
    val foldersSubtitle: String get() = tr("Comics inside these folders show on the shelf. Files are never changed.", "这些文件夹中的漫画会显示在书架上。文件不会被修改。", "這些資料夾中的漫畫會顯示在書架上。檔案不會被修改。", "これらのフォルダ内のマンガを本棚に表示します。ファイルは変更しません。")
    val noFoldersYet: String get() = tr("No folders yet.", "还没有文件夹。", "還沒有資料夾。", "フォルダはまだありません。")
    val importFolderWatch: String get() = tr("Import a folder to watch it for comics.", "导入文件夹后会自动查找漫画。", "匯入資料夾後會自動尋找漫畫。", "フォルダを読み込むとマンガを自動で探します。")
    val rescanNow: String get() = tr("Rescan now", "立即重新扫描", "立即重新掃描", "今すぐ再スキャン")
    val stopWatching: String get() = tr("Stop watching", "停止监视", "停止監視", "監視を停止")
    fun stoppedWatching(name: String): String = tr("Stopped watching $name", "已停止监视 $name", "已停止監視 $name", "$name の監視を停止しました")
    fun moreOptions(name: String): String = tr("More options for $name", "$name 的更多选项", "$name 的更多選項", "$name のその他のオプション")
    val cloudSyncQueue: String get() = tr("Cloud sync queue", "云端同步队列", "雲端同步佇列", "クラウド同期キュー")
    val noCloudSyncQueue: String get() = tr("No cloud sync in queue.", "云端同步队列为空。", "雲端同步佇列為空。", "クラウド同期キューは空です。")
    val cloudSyncQueueSubtitle: String get() = tr("Collects that are uploading to your cloud storage appear here.", "正在上传到云存储的收藏会显示在这里。", "正在上傳至雲端儲存的收藏會顯示在這裡。", "クラウドストレージにアップロード中のコレクションをここに表示します。")
    val waiting: String get() = tr("Waiting", "等待中", "等待中", "待機中")
    val fetching: String get() = tr("Fetching", "正在获取", "正在擷取", "取得中")
    val uploading: String get() = tr("Uploading", "正在上传", "正在上傳", "アップロード中")
    val failed: String get() = tr("Failed", "失败", "失敗", "失敗")
    val pagesUnknown: String get() = tr("Pages unknown", "页数未知", "頁數未知", "頁数不明")
    val checkingCloud: String get() = tr("Checking cloud", "正在检查云端", "正在檢查雲端", "クラウドを確認中")
    val downloadQueue: String get() = tr("Download queue", "下载队列", "下載佇列", "ダウンロードキュー")
    val noDownloadsQueue: String get() = tr("No downloads in queue.", "下载队列为空。", "下載佇列為空。", "ダウンロードキューは空です。")
    val downloadsQueueSubtitle: String get() = tr("Waiting and active downloads. Finished and failed leave this list.", "等待中和进行中的下载。完成或失败后会从这里移除。", "等待中與進行中的下載。完成或失敗後會從這裡移除。", "待機中と実行中のダウンロード。完了・失敗したものはここから消えます。")
    val incomplete: String get() = tr("Incomplete", "未完成", "未完成", "未完了")
    val rescanFolderHint: String get() = tr("Rescan its folder to look for missing pages.", "重新扫描文件夹以查找缺失页面。", "重新掃描資料夾以尋找遺失頁面。", "フォルダを再スキャンして不足ページを探します。")
    val sourceRestGone: String get() = tr("The source says the rest is gone.", "图源表示其余页面已不存在。", "圖源表示其餘頁面已不存在。", "ソースでは残りのページがないと報告されています。")
    val backToLibrary: String get() = tr("Back to Library", "返回书库", "返回書庫", "ライブラリに戻る")
    val previousChapter: String get() = tr("Previous chapter", "上一章", "上一章", "前のチャプター")
    val nextChapter: String get() = tr("Next chapter", "下一章", "下一章", "次のチャプター")
    val cycleSpreadViewLabel: String get() = tr("Cycle spread view", "切换双页显示", "切換雙頁顯示", "見開き表示を切り替え")
    val toggleReadingDirectionLabel: String get() = tr("Toggle reading direction", "切换阅读方向", "切換閱讀方向", "読む方向を切り替え")
    val cycleAutoScrollSpeedLabel: String get() = tr("Cycle auto-scroll speed", "切换自动滚动速度", "切換自動捲動速度", "自動スクロール速度を切り替え")
    val switchReadingModeLabel: String get() = tr("Switch reading mode", "切换阅读模式", "切換閱讀模式", "読書モードを切り替え")
    val splitDoublePageLabel: String get() = tr("Split double-page spread", "拆分双页", "拆分雙頁", "見開きページを分割")
    val stopAutoScroll: String get() = tr("Stop auto-scroll", "停止自动滚动", "停止自動捲動", "自動スクロールを停止")
    fun autoScrollActive(status: String): String = tr("Auto-scroll active ($status)", "自动滚动中（$status）", "自動捲動中（$status）", "自動スクロール中（$status）")
    val deleteForever: String get() = tr("Delete forever", "永久删除", "永久刪除", "完全に削除")
    val deleteForeverConfirm: String get() = tr("Tap again to delete forever.", "再次点击以永久删除。", "再次點擊以永久刪除。", "もう一度タップすると完全に削除します。")
    fun recentlyDeleted(count: Int): String = tr("Recently deleted · $count", "最近删除 · $count", "最近刪除 · $count", "最近削除 · $count")
    val deletedRecently: String get() = tr("Deleted recently", "最近删除", "最近刪除", "最近削除")

    // Updates, activity and notifications.
    val today: String get() = tr("Today", "今天", "今天", "今日")
    val yesterday: String get() = tr("Yesterday", "昨天", "昨天", "昨日")
    val newChapters: String get() = tr("New chapters", "新章节", "新章節", "新しいチャプター")
    val newChaptersSaved: String get() = tr("New chapters saved", "新章节已保存", "新章節已儲存", "新しいチャプターを保存しました")
    val newChaptersFound: String get() = tr("New chapters found", "发现新章节", "發現新章節", "新しいチャプターがあります")
    val needsAttention: String get() = tr("Needs attention", "需要处理", "需要處理", "要確認")
    val chooseFolderAgain: String get() = tr("Choose folder again", "重新选择文件夹", "重新選擇資料夾", "フォルダを再選択")
    val cloudCheckFailedRetry: String get() = tr("Cloud check failed · tap Retry", "云端检查失败 · 点击重试", "雲端檢查失敗 · 點擊重試", "クラウド確認に失敗 · 再試行をタップ")
    val retryingAutomatically: String get() = tr("retrying · resumes automatically", "正在重试 · 将自动继续", "正在重試 · 將自動繼續", "再試行中 · 自動で再開します")
    val pausedAutomatically: String get() = tr("paused · resumes automatically", "已暂停 · 将自动继续", "已暫停 · 將自動繼續", "一時停止中 · 自動で再開します")
    fun importingCount(count: Int): String = tr("Importing · $count", "正在导入 · $count", "正在匯入 · $count", "インポート中 · $count")
    fun addedComics(count: Int, folder: String): String = tr("Added $count ${if (count == 1) "comic" else "comics"} from $folder", "已从 $folder 添加 $count 部漫画", "已從 $folder 新增 $count 部漫畫", "$folder から $count 件のマンガを追加")
    val accessLostChooseFolder: String get() = tr("Access lost · Choose folder again", "访问权限丢失 · 请重新选择文件夹", "存取權限遺失 · 請重新選擇資料夾", "アクセス権が失われました · フォルダを再選択してください")
    val storageDidNotRespond: String get() = tr("Storage did not respond", "存储未响应", "儲存空間沒有回應", "ストレージが応答しません")
    val folderChanged: String get() = tr("Folder changed since the last scan", "文件夹自上次扫描后已改变", "資料夾自上次掃描後已變更", "前回のスキャン以降にフォルダが変更されています")
    val archiveCouldNotOpen: String get() = tr("An archive could not be opened safely", "无法安全打开压缩包", "無法安全開啟壓縮檔", "アーカイブを安全に開けませんでした")
    val notEnoughSpaceImport: String get() = tr("Not enough space to import", "空间不足，无法导入", "空間不足，無法匯入", "空き容量が足りずインポートできません")
    val scanStoppedBySystem: String get() = tr("The scan was stopped by the system", "扫描被系统停止", "掃描被系統停止", "スキャンがシステムによって停止されました")
    val scanCouldNotFinish: String get() = tr("The scan could not finish", "扫描未能完成", "掃描未能完成", "スキャンを完了できませんでした")
    val tapCloudflareChallenge: String get() = tr("Tap to solve the Cloudflare challenge and resume downloads", "点击完成 Cloudflare 验证并恢复下载", "點擊完成 Cloudflare 驗證並恢復下載", "タップして Cloudflare チャレンジを解決し、ダウンロードを再開")
    val backgroundSync: String get() = tr("Background sync", "后台同步", "背景同步", "バックグラウンド同期")
    val syncingToCloud: String get() = tr("Syncing to cloud", "正在同步到云端", "正在同步至雲端", "クラウドへ同期中")
    val importingCloudLibrary: String get() = tr("Importing cloud library", "正在导入云端书库", "正在匯入雲端書庫", "クラウドライブラリを読み込み中")

    val importing: String get() = tr("Importing…", "正在导入…", "正在匯入…", "インポート中…")
    fun importingProgress(done: Int, total: Int): String = tr("Importing · $done/$total", "正在导入 · $done/$total", "正在匯入 · $done/$total", "インポート中 · $done/$total")
    val sourceChangedOnPhone: String get() = tr("This comic changed on the phone.", "本机上的漫画已发生变化。", "本機上的漫畫已發生變化。", "この端末のマンガが変更されています。")
    val accessLost: String get() = tr("Access lost", "访问权限已失效", "存取權已失效", "アクセス権が失われました")
    val storageNotResponding: String get() = tr("Storage is not responding.", "存储无响应。", "儲存空間沒有回應。", "ストレージが応答していません。")
    val filesGoneFromPhone: String get() = tr("This comic's files are no longer on the phone.", "这部漫画的文件已不在本机。", "這部漫畫的檔案已不在本機。", "このマンガのファイルは端末にありません。")
    val insufficientTemporarySpace: String get() = tr("There is not enough temporary space to open this archive.", "临时空间不足，无法打开此压缩包。", "暫存空間不足，無法開啟此壓縮檔。", "このアーカイブを開くための一時領域が不足しています。")
    val unsupportedPageFormat: String get() = tr("This page format is not supported.", "不支持此页面格式。", "不支援此頁面格式。", "このページ形式には対応していません。")
    val pageCouldNotRender: String get() = tr("This page could not be rendered. Try again.", "无法渲染此页面，请重试。", "無法呈現此頁面，請重試。", "このページを表示できませんでした。再試行してください。")
    val folderAccessLost: String get() = tr("Access to this folder was lost. Open Folders on this phone to choose it again.", "此文件夹的访问权限已失效。打开“本机文件夹”重新选择。", "此資料夾的存取權已失效。開啟「本機資料夾」重新選擇。", "このフォルダへのアクセス権が失われました。「この端末のフォルダ」を開いて選び直してください。")
    val rescanThenOpen: String get() = tr("Rescan its folder, then open it again.", "重新扫描文件夹后再打开。", "重新掃描資料夾後再開啟。", "フォルダを再スキャンしてから、もう一度開いてください。")
    val tryAgainMoment: String get() = tr("Try again in a moment.", "请稍后重试。", "請稍後重試。", "しばらくしてから再試行してください。")
    val freeDeviceSpace: String get() = tr("Free device space, then open it again.", "释放设备空间后再打开。", "釋放裝置空間後再開啟。", "端末の空き容量を増やしてから開いてください。")
    val openAnotherLibraryPage: String get() = tr("Open another page from your library.", "从书库打开其他页面。", "從書庫開啟其他頁面。", "ライブラリから別のページを開いてください。")
    val tryAgain: String get() = tr("Try again", "重试", "重試", "再試行")
    val pageFailedTitle: String get() = tr("Couldn't load this page.", "无法加载此页面。", "無法載入此頁面。", "このページを読み込めませんでした。")
    val loadPreviousChapter: String get() = tr("Load previous chapter", "加载上一章", "載入上一章", "前のチャプターを読み込む")
    val continueNextChapter: String get() = tr("Continue to next chapter", "继续下一章", "繼續下一章", "次のチャプターへ進む")
    val spreadFull: String get() = tr("Spread · Full", "双页 · 完整", "跨頁 · 完整", "見開き · 全体")
    val autoScrollOff: String get() = tr("Auto-scroll · Off", "自动滚动 · 关闭", "自動捲動 · 關閉", "自動スクロール · オフ")
    fun autoScrollSpeed(speed: String, hold: Boolean): String = tr("Auto $speed${if (hold) " · Hold" else ""}", "自动滚动 $speed${if (hold) " · 按住" else ""}", "自動捲動 $speed${if (hold) " · 按住" else ""}", "自動スクロール $speed${if (hold) " · 長押し" else ""}")
    fun readerMode(auto: Boolean, strip: Boolean): String = when {
        auto && strip -> tr("Auto · Strip", "自动 · 长条", "自動 · 長條", "自動 · ストリップ")
        auto -> tr("Auto · Paged", "自动 · 分页", "自動 · 分頁", "自動 · ページ")
        strip -> tr("Strip", "长条", "長條", "ストリップ")
        else -> tr("Paged", "分页", "分頁", "ページ")
    }
    fun cycleSpreadView(label: String): String = tr("Cycle spread view ($label)", "切换双页视图（$label）", "切換跨頁檢視（$label）", "見開き表示を切り替え（$label）")
    fun toggleReadingDirection(dir: String): String = tr("Toggle reading direction ($dir)", "切换阅读方向（$dir）", "切換閱讀方向（$dir）", "読む方向を切り替え（$dir）")
    fun cycleAutoScrollSpeed(label: String): String = tr("Cycle auto-scroll speed ($label)", "切换自动滚动速度（$label）", "切換自動捲動速度（$label）", "自動スクロール速度を切り替え（$label）")
    fun switchReadingMode(label: String): String = tr("Switch reading mode ($label)", "切换阅读模式（$label）", "切換閱讀模式（$label）", "読み方を切り替え（$label）")
    fun splitDoublePage(label: String): String = tr("Split double-page spread ($label)", "拆分双页（$label）", "拆分跨頁（$label）", "見開きを分割（$label）")
    val openFolderAgain: String get() = tr("Open folder again", "重新打开文件夹", "重新開啟資料夾", "フォルダをもう一度開く")
    val folderWaitingStorage: String get() = tr("Waiting for storage", "等待存储", "等待儲存空間", "ストレージを待機中")
    fun scanningFound(count: Int): String = tr("Scanning… $count found", "正在扫描…已找到 $count 部", "正在掃描…已找到 $count 部", "スキャン中…$count 件")
    fun comicCount(count: Int): String = tr(if (count == 1) "1 comic" else "$count comics", if (count == 1) "1 部漫画" else "$count 部漫画", if (count == 1) "1 部漫畫" else "$count 部漫畫", "$count 件のマンガ")
    val justNow: String get() = tr("just now", "刚刚", "剛剛", "たった今")
    fun minutesAgo(n: Long): String = tr("$n min ago", "$n 分钟前", "$n 分鐘前", "$n 分前")
    fun hoursAgo(n: Long): String = tr(if (n == 1L) "1 hour ago" else "$n hours ago", "$n 小时前", "$n 小時前", "$n 時間前")
    fun daysAgo(n: Long): String = tr(if (n == 1L) "1 day ago" else "$n days ago", "$n 天前", "$n 天前", "$n 日前")
    val notScannedYet: String get() = tr("not scanned yet", "尚未扫描", "尚未掃描", "まだスキャンされていません")
    fun scannedAt(relative: String): String = tr("scanned $relative", "已扫描 $relative", "已掃描 $relative", "$relative にスキャン")
    fun importState(raw: String): String = when (raw) {
        "Checking access" -> tr("Checking access", "正在检查访问权限", "正在檢查存取權限", "アクセスを確認中")
        "Ready" -> tr("Ready", "就绪", "就緒", "準備完了")
        "Ready for review" -> tr("Ready for review", "等待审核", "準備審核", "確認待ち")
        "Paused" -> tr("Paused", "已暂停", "已暫停", "一時停止")
        "Complete" -> tr("Complete", "已完成", "已完成", "完了")
        "Partially successful" -> tr("Partially successful", "部分成功", "部分成功", "一部成功")
        "Needs reauthorization" -> tr("Needs reauthorization", "需要重新授权", "需要重新授權", "再認証が必要")
        "Failed" -> tr("Failed", "失败", "失敗", "失敗")
        "Removed" -> tr("Removed", "已移除", "已移除", "削除済み")
        else -> raw
    }
    fun discovering(count: Int?): String = if (count != null && count > 0) tr("Discovering · $count processed", "正在发现 · 已处理 $count", "正在探索 · 已處理 $count", "検出中 · $count 件処理") else tr("Discovering", "正在发现", "正在探索", "検出中")
    fun importingStatus(done: Int, total: Int?, processed: Boolean = false): String = when {
        total != null && total > 0 -> tr("Importing · $done / $total", "正在导入 · $done / $total", "正在匯入 · $done / $total", "インポート中 · $done / $total")
        processed -> tr("Importing · $done processed", "正在导入 · 已处理 $done", "正在匯入 · 已處理 $done", "インポート中 · $done 件処理")
        else -> tr("Importing", "正在导入", "正在匯入", "インポート中")
    }
    fun itemsProcessed(done: Int, total: Int?): String = if (total != null && total > 0) tr("$done / $total items processed", "已处理 $done / $total 项", "已處理 $done / $total 項", "$done / $total 件処理") else tr("$done items processed", "已处理 $done 项", "已處理 $done 項", "$done 件処理")
    fun pageCount(count: Int): String = tr(if (count == 1) "1 page" else "$count pages", "$count 页", "$count 頁", "$count ページ")
    fun chapterCount(count: Int): String = tr(if (count == 1) "1 chapter" else "$count chapters", "$count 章", "$count 章", "$count チャプター")
    fun chapterStateText(raw: String): String = when {
        raw == "Members only · not saved" -> membersOnlyNotSaved
        raw == "Failed · tap to retry" -> tr(raw, "失败 · 点击重试", "失敗 · 點擊重試", "失敗 · タップして再試行")
        raw == "Waiting" -> tr(raw, "等待中", "等待中", "待機中")
        raw == "Downloaded" -> tr(raw, "已下载", "已下載", "ダウンロード済み")
        raw == "Downloading" -> tr(raw, "正在下载", "正在下載", "ダウンロード中")
        raw == "On this phone" -> onThisPhone
        raw == "In cloud" -> tr(raw, "在云端", "在雲端", "クラウド内")
        raw == "Unavailable" -> tr(raw, "不可用", "無法使用", "利用不可")
        raw == "Source unavailable · showing saved chapters" -> tr(raw, "图源无法连接 · 显示已保存章节", "圖源無法連線 · 顯示已儲存章節", "ソースに接続できません · 保存済みチャプターを表示")
        raw.startsWith("Saved · p.") -> tr(raw, raw.replace("Saved · p.", "已保存 · 第 ").replace("/", " / ") + " 页", raw.replace("Saved · p.", "已儲存 · 第 ").replace("/", " / ") + " 頁", raw.replace("Saved · p.", "保存済み · ").replace("/", " / ") + " ページ")
        raw.startsWith("Removed at source · saved copy kept") -> tr(raw, "图源已下架 · 已保留保存副本", "圖源已下架 · 已保留儲存副本", "ソースで削除 · 保存済みコピーあり")
        raw == "Removed at source" -> tr(raw, "图源已下架", "圖源已下架", "ソースで削除")
        else -> raw
    }
}
