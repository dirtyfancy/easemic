package com.easemic.app.sync

/**
 * #E1: the highest version of each cloud object this app understands. Two phones can share one library, so an older
 * app meets objects a newer app wrote; it must stop and say so, never call the library damaged or repair it.
 * Raise a number only together with the code that reads the new version.
 */
object CloudFormatVersions {
    const val PAGE_MANIFEST = 5
    const val KEYSLOT = 1
    const val INDEX_MANIFEST = 2
    const val SERIES_MANIFEST = 1
    const val CHAPTER_META = 1
}

class NewerCloudFormatException(what: String, version: Int) : Exception("$what v$version is newer than this app") {
    companion object {
        const val MESSAGE: String =
            "This library was updated by a newer version of Easemic. Update the app on this phone to keep using it."
    }
}
