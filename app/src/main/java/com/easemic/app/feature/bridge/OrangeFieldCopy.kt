package com.easemic.app.feature.bridge

import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr

/**
 * Screen chrome copy for the Orange Field redesign, locked by spec §3/§4.
 * Behavioral messages (errors, SAVED, DISCONNECTED) stay in [BridgeR2BindCopy].
 */
object OrangeFieldCopy {
    val HEADLINE: String get() = CloudStoragePickerCopy.HEADLINE
    val LEDE: String
        get() = tr(
            "Comics live in an R2 bucket on your Cloudflare account.",
            "漫画存储在你自己的 Cloudflare R2 存储桶中。",
            "漫畫儲存在你自己的 Cloudflare R2 儲存桶中。",
            "マンガはあなた自身の Cloudflare R2 バケットに保存されます。",
        )

    const val STEP_1_NUMERAL: String = "01"
    val STEP_1_TITLE: String
        get() = tr("Enable R2 on Cloudflare", "在 Cloudflare 启用 R2", "在 Cloudflare 啟用 R2", "Cloudflare で R2 を有効化")
    val STEP_1_HELPER: String
        get() = tr("Needs their billing card.", "需在后台绑定支付方式（享 10 GB 免费额度）。", "需在後台綁定付款方式（享 10 GB 免費額度）。", "支払い方法の登録が必要です（10GB無料枠あり）。")
    val STEP_1_LINK: String
        get() = tr("Dashboard ↗", "打开控制台 ↗", "打開控制台 ↗", "ダッシュボード ↗")

    const val STEP_2_NUMERAL: String = "02"
    val STEP_2_TITLE: String
        get() = tr("Connect this device", "连接当前设备", "連接目前裝置", "この端末を接続")
    val STEP_2_HELPER: String
        get() = tr("Sign in with Cloudflare OAuth.", "通过 Cloudflare OAuth 授权登录。", "透過 Cloudflare OAuth 授權登入。", "Cloudflare OAuth でサインインします。")

    const val STEP_3_NUMERAL: String = "03"
    val STEP_3_TITLE: String
        get() = tr("Collect comics to it", "收藏漫画自动上云", "收藏漫畫自動上雲", "マンガをクラウドに保存")
    val STEP_3_HELPER: String
        get() = tr("Read them from any connected device.", "在任意连接同一云库的设备上随时阅读。", "在任意連接同一雲端書庫的裝置上隨時閱讀。", "接続したすべての端末からいつでも読めます。")

    val PASTE_INSTEAD: String
        get() = tr("Paste credentials instead", "改为手动粘贴凭证", "改為手動貼上憑證", "認証情報を直接貼り付ける")

    val CUSTOM_S3_HEADLINE: String
        get() = tr("Custom S3 storage.", "自定义 S3 存储。", "自訂 S3 儲存。", "カスタム S3 ストレージ。")
    val CUSTOM_S3_LEDE: String
        get() = tr(
            "Enter the endpoint, region, keys, and bucket name for any S3-compatible storage.",
            "填写任意 S3 兼容存储的 Endpoint、区域、密钥与存储桶名。",
            "填寫任意 S3 相容儲存的 Endpoint、區域、金鑰與儲存桶名。",
            "任意のS3互換ストレージのエンドポイント、リージョン、キー、バケット名を入力してください。",
        )

    val BOUND_TITLE: String get() = AppStrings.cloudLibraryTitle
    val CONNECTED_OAUTH: String
        get() = tr("Connected via OAuth", "已通过 OAuth 连接", "已透過 OAuth 連接", "OAuth で接続済み")
    val CONNECTED_PASTED: String
        get() = tr("Connected · pasted credentials", "已连接 · 凭证接入", "已連接 · 憑證接入", "接続済み · 認証情報")

    /** Big-numeral placeholder while the first measurement is in flight. */
    const val STAT_EMPTY: String = "—"
}
