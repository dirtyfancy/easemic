package com.easemic.app.feature.browse

/**
 * A16: pure policy for whether a Recently-deleted row's Restore/Delete-forever
 * button should be enabled. Disabled while a restore/delete for [id] is
 * already in flight so a slow network round-trip doesn't invite duplicate
 * taps (previously each tap fired a fresh, indistinguishable request).
 */
fun recentlyDeletedButtonEnabled(id: String, busyIds: Set<String>): Boolean = id !in busyIds
