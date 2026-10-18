package com.easemic.app.feature.reader

import com.easemic.app.i18n.AppStrings

object ReaderPageFailureCopy {
    fun title(): String = AppStrings.pageFailedTitle

    /** A30 §2.4 (S4b/B4): the same verb the storage banner uses -- one vocabulary. */
    fun retryLabel(): String = AppStrings.tryAgain
}
