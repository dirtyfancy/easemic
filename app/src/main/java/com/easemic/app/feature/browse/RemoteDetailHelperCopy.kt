package com.easemic.app.feature.browse

import com.easemic.app.i18n.tr

/**
 * English Detail helpers, shown for every cloud provider. "Cloud" means the user's own storage (S3, Cloudflare R2,
 * WebDAV, OneDrive or Google Drive), not a service we run. #D19: name the cloud in the general, never "R2" or
 * "bucket" — those read as false on a WebDAV, OneDrive or Google Drive library.
 */
object RemoteDetailHelperCopy {
    fun serialized(presence: ResourcePresence, cloudBound: Boolean, keepOnlyCloud: Boolean): String = when (presence) {
        ResourcePresence.TOMBSTONED -> "This series is in your cloud storage's recycle bin for 30 days. Collect restores it."
        ResourcePresence.CLOUD -> "Read uses saved chapters on this phone first, then your cloud copy."
        else -> "Collect follows this series and saves available chapters. New chapters are saved as they appear. " + when {
            !cloudBound -> "Chapters are saved on this phone."
            keepOnlyCloud -> "Saved chapters go to your cloud; the phone copy is removed after upload."
            else -> "Saved chapters go to your cloud and stay on this phone."
        }
    }
    const val CLOUD_COLLECTION =
        "This comic is in your cloud storage, and Read uses the copy on this phone first. Download saves a copy to this phone; canceling the collection moves the cloud copy to a 30-day recycle bin."
    const val BROWSE_COLLECT =
        "Collect saves this comic to your cloud storage and keeps the pages on this phone unless \"Keep only the cloud copy\" is on; with no storage connected, it saves a bookmark. Download saves a copy to this phone without uploading it."
    const val BOOKMARKED =
        "This comic is collected. Tap Collect again to cancel the collection; Download saves a copy to this phone."
    const val TOMBSTONED =
        "This comic is in your cloud storage's recycle bin for 30 days. Collect restores it; Download saves a copy to this phone."
    const val OPDS_LIBRARY =
        "Collect saves this comic to your cloud storage and keeps the pages on this phone unless \"Keep only the cloud copy\" is on; with no storage connected, it saves a bookmark. Read streams pages from the server only if it supports page streaming; Download saves a copy to this phone."

    /** Display translation for the stable English copy keys above. */
    fun display(raw: String): String = when (raw) {
        CLOUD_COLLECTION -> tr(raw,
            "这部漫画已在云存储中，阅读会优先使用本机副本。下载会将副本保存到本机；取消收藏会将云端副本移入 30 天回收站。",
            "這部漫畫已在雲端儲存中，閱讀會優先使用本機副本。下載會將副本儲存到本機；取消收藏會將雲端副本移入 30 天回收站。",
            "このマンガはクラウドストレージにあり、読むときは端末のコピーを優先します。ダウンロードは端末にコピーを保存し、コレクションを解除するとクラウドのコピーを30日間ごみ箱に移します。")
        BROWSE_COLLECT -> tr(raw,
            "收藏会将这部漫画保存到云存储，并在本机保留页面（除非开启“仅保留云端副本”）；未连接存储时只保存收藏标记。下载会保存本机副本，不会上传。",
            "收藏會將這部漫畫儲存到雲端儲存，並在本機保留頁面（除非開啟「僅保留雲端副本」）；未連接儲存空間時只儲存收藏標記。下載會儲存本機副本，不會上傳。",
            "コレクションするとこのマンガをクラウドストレージに保存し、「クラウドのコピーのみ残す」が有効でなければ端末にもページを残します。ストレージ未接続時はブックマークだけ保存します。ダウンロードは端末に保存し、アップロードしません。")
        BOOKMARKED -> tr(raw,
            "这部漫画已收藏。再次点击“收藏”可取消收藏；“下载”会保存本机副本。",
            "這部漫畫已收藏。再次點擊「收藏」可取消收藏；「下載」會儲存本機副本。",
            "このマンガはコレクション済みです。「コレクション」をもう一度タップすると解除し、「ダウンロード」は端末にコピーを保存します。")
        TOMBSTONED -> tr(raw,
            "这部漫画在云存储的回收站中保留 30 天。收藏可恢复它；下载会保存本机副本。",
            "這部漫畫在雲端儲存的回收站中保留 30 天。收藏可恢復它；下載會儲存本機副本。",
            "このマンガはクラウドストレージのごみ箱に30日間あります。「コレクション」で復元でき、「ダウンロード」は端末にコピーを保存します。")
        OPDS_LIBRARY -> tr(raw,
            "收藏会将这部漫画保存到云存储，并在本机保留页面（除非开启“仅保留云端副本”）；未连接存储时只保存收藏标记。只有服务器支持页面流式读取时，“阅读”才会从服务器读取；“下载”会保存本机副本。",
            "收藏會將這部漫畫儲存到雲端儲存，並在本機保留頁面（除非開啟「僅保留雲端副本」）；未連接儲存空間時只儲存收藏標記。只有伺服器支援頁面串流讀取時，「閱讀」才會從伺服器讀取；「下載」會儲存本機副本。",
            "コレクションするとこのマンガをクラウドストレージに保存し、「クラウドのコピーのみ残す」が有効でなければ端末にもページを残します。ストレージ未接続時はブックマークだけ保存します。サーバーがページストリーミングに対応する場合のみ「読む」でサーバーから読み込み、「ダウンロード」は端末にコピーを保存します。")
        else -> raw
    }

    /** Translation for the serialized-series helper body, whose copy depends on state. */
    fun displaySerialized(presence: ResourcePresence, cloudBound: Boolean, keepOnlyCloud: Boolean): String {
        val raw = serialized(presence, cloudBound, keepOnlyCloud)
        return when (presence) {
            ResourcePresence.TOMBSTONED -> tr(raw,
                "这部作品在云存储的回收站中保留 30 天。收藏可恢复它。",
                "這部作品在雲端儲存的回收站中保留 30 天。收藏可恢復它。",
                "このシリーズはクラウドストレージのごみ箱に30日間あります。「コレクション」で復元できます。")
            ResourcePresence.CLOUD -> tr(raw,
                "阅读会优先使用本机保存的章节，然后使用云端副本。",
                "閱讀會優先使用本機儲存的章節，然後使用雲端副本。",
                "読むときは端末に保存したチャプターを優先し、その後クラウドのコピーを使います。")
            else -> {
                val ending = when {
                    !cloudBound -> tr("Chapters are saved on this phone.", "章节保存在本机。", "章節儲存在本機。", "チャプターは端末に保存されます。")
                    keepOnlyCloud -> tr("Saved chapters go to your cloud; the phone copy is removed after upload.", "保存的章节会上传到云端；上传后会删除本机副本。", "儲存的章節會上傳到雲端；上傳後會刪除本機副本。", "保存したチャプターはクラウドに送られ、アップロード後に端末のコピーを削除します。")
                    else -> tr("Saved chapters go to your cloud and stay on this phone.", "保存的章节会上传到云端，并保留在本机。", "儲存的章節會上傳到雲端，並保留在本機。", "保存したチャプターはクラウドに送られ、端末にも残ります。")
                }
                tr("Collect follows this series and saves available chapters. New chapters are saved as they appear. ", "收藏会关注这部作品并保存可用章节。新章节出现后会自动保存。 ", "收藏會追蹤這部作品並儲存可用章節。新章節出現後會自動儲存。 ", "コレクションするとこのシリーズを追跡し、利用できるチャプターを保存します。新しいチャプターは追加され次第保存します。 ") + ending
            }
        }
    }
}
