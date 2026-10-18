package com.easemic.app.ui

/** Where [AppRoute.Sources] should return to when the user navigates back. */
internal enum class SourcesReturnTo {
    SETTINGS,
    LIBRARY,
}

internal sealed interface AppRoute {
    data object Library : AppRoute
    data class Sources(val returnTo: SourcesReturnTo = SourcesReturnTo.SETTINGS) : AppRoute
    data object Browse : AppRoute
    data object Activity : AppRoute
    data object History : AppRoute
    data object Settings : AppRoute
    data object OpdsCatalogs : AppRoute
    data object BackupRestore : AppRoute
    data object DownloadQueue : AppRoute
    data object CloudSyncQueue : AppRoute
    data object BridgeR2Bind : AppRoute
    data class Detail(val itemId: Long) : AppRoute
    data class Reader(
        val itemId: Long,
        val initialOrdinal: Int? = null,
        val readingUnitId: Long? = null,
        val chapterId: String? = null,
    ) : AppRoute
}

/** The destination selected when leaving a reader route. */
internal enum class ReaderBackTarget {
    DETAIL,
    SERIALIZED_DETAIL,
}

/**
 * A saved serialized chapter reader has no durable navigation stack of its
 * own. Keep the route that was underneath its detail while the reader is
 * open; an absent route is the cold-start/process-death fallback.
 */
internal fun serializedReaderBackRoute(origin: AppRoute?): AppRoute = when (origin) {
    AppRoute.Browse -> AppRoute.Browse
    AppRoute.History -> AppRoute.History
    AppRoute.Library -> AppRoute.Library
    else -> AppRoute.Library
}

/**
 * Chapter readers reopen the serialized series detail whenever that detail is
 * resolvable — the collect bookmark when one exists, or the local row's own
 * facts for a #E73 download-only series (which writes no bookmark). Gallery
 * readers and rows with no serialized identity use the ordinary detail route.
 */
internal fun readerBackTarget(route: AppRoute.Reader, serializedDetailAvailable: Boolean): ReaderBackTarget =
    if (route.chapterId != null && serializedDetailAvailable) {
        ReaderBackTarget.SERIALIZED_DETAIL
    } else {
        ReaderBackTarget.DETAIL
    }

/**
 * Which bottom-bar tab (if any) is highlighted while [this] route is showing.
 *
 * A40 (owner bug backlog): [AppRoute.Detail] is a pushed Library screen, not a
 * modal one — the five tabs stay visible with Library selected, so a comic
 * opened from the shelf is not a one-way trip that only Back escapes. Browse's
 * own detail is a separate overlay (`remoteDetailItem`) on top of
 * [AppRoute.Browse] and already kept the bar, so this mapping cannot mislabel a
 * Browse-originated screen.
 *
 * Null means "no bar at all":
 * - [AppRoute.Reader] — reading is immersive by intent.
 * - Every Settings sub-route ([AppRoute.OpdsCatalogs],
 *   [AppRoute.BackupRestore], [AppRoute.DownloadQueue], [AppRoute.CloudSyncQueue],
 *   [AppRoute.BridgeR2Bind], [AppRoute.Sources]) — pushed screens reached from a
 *   row and returned from with Back; a highlighted "More" under them is noise.
 *
 * Lives here rather than in PrivateComicLibraryApp.kt so it (and [AppRoute])
 * can be unit-tested — a file-private declaration is unreachable from any test.
 */
internal fun AppRoute.toBottomBarDestination(): AppBottomBarDestination? = when (this) {
    AppRoute.Library -> AppBottomBarDestination.LIBRARY
    AppRoute.Browse -> AppBottomBarDestination.BROWSE
    AppRoute.Activity -> AppBottomBarDestination.ACTIVITY
    AppRoute.History -> AppBottomBarDestination.HISTORY
    AppRoute.Settings -> AppBottomBarDestination.MORE
    is AppRoute.Detail -> AppBottomBarDestination.LIBRARY
    AppRoute.OpdsCatalogs,
    AppRoute.BackupRestore,
    AppRoute.DownloadQueue,
    AppRoute.CloudSyncQueue,
    AppRoute.BridgeR2Bind,
    is AppRoute.Sources,
    is AppRoute.Reader,
    -> null
}
