package com.easemic.app.feature.reader

data class ReaderPageLoadPresentation(
    val visible: Boolean,
    val determinate: Boolean = false,
    val fraction: Float = 0f,
    val label: String? = null,
)

object ReaderPageLoadProgress {
    const val TEST_TAG = "reader-page-load-progress"
    const val PERCENT_TAG = "reader-page-load-percent"
    private const val ONE_MEGABYTE = 1_048_576L

    val Hidden = ReaderPageLoadPresentation(visible = false)

    fun presentation(
        imageReady: Boolean,
        loadFailed: Boolean,
        receivedBytes: Long = 0L,
        contentLength: Long = -1L,
    ): ReaderPageLoadPresentation {
        if (imageReady || loadFailed) return Hidden
        if (contentLength > 0L) {
            val fraction = (receivedBytes.toFloat() / contentLength.toFloat())
                .coerceIn(0f, 1f)
            val percent = ((receivedBytes * 100L) / contentLength).coerceIn(0L, 100L)
            val label = if (contentLength >= ONE_MEGABYTE) {
                val recvMb = String.format(java.util.Locale.US, "%.1f", receivedBytes.toDouble() / ONE_MEGABYTE.toDouble())
                val totalMb = String.format(java.util.Locale.US, "%.1f", contentLength.toDouble() / ONE_MEGABYTE.toDouble())
                "$percent% · $recvMb / $totalMb MB"
            } else {
                "$percent%"
            }
            return ReaderPageLoadPresentation(
                visible = true,
                determinate = true,
                fraction = fraction,
                label = label,
            )
        }
        return ReaderPageLoadPresentation(visible = true, determinate = false)
    }
}
