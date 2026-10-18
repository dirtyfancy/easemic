package com.easemic.app.feature.browse

/** Detail actions that must stay tappable while Collect / download run in the background. */
object RemoteDetailActionEnablement {
    fun readOnlineEnabled(
        hasAcquisition: Boolean,
        sourceFamily: DetailSourceFamily = DetailSourceFamily.FRONT_INDEX,
        hasPageStream: Boolean = false,
    ): Boolean = when (sourceFamily) {
        DetailSourceFamily.OPDS -> hasPageStream
        DetailSourceFamily.FRONT_INDEX -> hasAcquisition
    }

    fun collectEnabled(): Boolean = true

    fun localDownloadEnabled(primaryEnabled: Boolean): Boolean = primaryEnabled

    fun localDownloadCancelEnabled(): Boolean = true

    fun uncollectEnabled(): Boolean = true

    fun syncToCloudEnabled(hasAcquisition: Boolean): Boolean = hasAcquisition

    fun cloudReadEnabled(hasCallback: Boolean, hasAcquisition: Boolean): Boolean =
        hasCallback && hasAcquisition

    fun discardEnabled(discardInFlight: Boolean): Boolean = !discardInFlight
}
