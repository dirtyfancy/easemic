package com.easemic.app

/**
 * #C50: the ONE place the product's on-cloud identity is named (owner 2026-09-18: the
 * library folder name is never hardcoded elsewhere and never user-chosen; a rename
 * changes this constant only).
 */
object ProductIdentity {
    /** Root folder of a drive library (WebDAV folder, OneDrive app folder, Google Drive folder). */
    const val LIBRARY_FOLDER_NAME: String = "Easemic"
}
