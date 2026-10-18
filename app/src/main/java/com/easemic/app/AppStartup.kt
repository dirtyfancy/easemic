package com.easemic.app

internal suspend fun validateThenObserveWork(
    validatePendingGrants: suspend () -> Unit,
    observeWork: suspend () -> Unit,
) {
    validatePendingGrants()
    observeWork()
}
