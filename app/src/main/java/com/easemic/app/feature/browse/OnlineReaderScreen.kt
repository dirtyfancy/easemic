package com.easemic.app.feature.browse

import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import com.easemic.app.feature.reader.AutoScrollSpeed
import com.easemic.app.feature.reader.ReaderFirstPageCache
import com.easemic.app.feature.reader.ReaderLayoutMode
import com.easemic.app.feature.reader.ReaderWebtoonMath
import com.easemic.app.feature.reader.StripVisibleItem
import com.easemic.app.feature.reader.VerticalStripAutoScrollFloatingPill
import com.easemic.app.feature.reader.VerticalStripChapterBanner
import com.easemic.app.feature.reader.ReaderVolumePaging
import com.easemic.app.feature.reader.VolumeKeyDirection
import com.easemic.app.feature.reader.ReaderVolumeKeyBus
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.foundation.Image
import com.easemic.app.feature.reader.ReaderPageLoadOverlay
import com.easemic.app.feature.reader.ReaderPageLoadProgress
import com.easemic.app.feature.reader.ReaderPagePan
import com.easemic.app.feature.reader.ReaderPageZoom
import com.easemic.app.feature.reader.ReaderPageContentScale
import com.easemic.app.feature.reader.ReaderSeekOverlay
import com.easemic.app.feature.reader.SpreadPageFloatingBadge
import com.easemic.app.feature.reader.SpreadPageSubSlot
import com.easemic.app.feature.reader.ChapterTransitionCard
import com.easemic.app.feature.reader.rememberReaderImageProgress
import com.easemic.app.feature.reader.keepScreenOn
import com.easemic.app.feature.reader.ReaderChapterBoundary
import com.easemic.app.feature.reader.ReaderChapterContext
import com.easemic.app.feature.reader.clampReaderOrdinal
import com.easemic.app.feature.reader.readerInitialPagerPage
import com.easemic.app.feature.download.FollowProgress
import com.easemic.app.ui.components.BrokenImageGlyph
import com.easemic.app.ui.theme.quietShelfPalette
import com.easemic.app.storage.isReadableCacheUri
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class OnlineReaderSession(
    val title: String,
    val imageUrls: List<String>,
    val initialOrdinal: Int = 0,
    /**
     * When [imageUrls] has a blank slot, the online reader calls this to resolve
     * a display URI (READY file:// preferred, else upstream HTTPS). Null = no
     * lazy resolve. Failure must throw — blank success is treated as a page error.
     */
    val resolveBlankPageUrl: (suspend (ordinal: Int) -> String)? = null,
    /**
     * #E15 Task 3b: while [resolveBlankPageUrl] waits on a download lane
     * fetching the same ordinal, polled every 500 ms for the lane's live
     * progress ("Downloading · N%" in the overlay's label slot). Null for
     * sessions that are not download-backed — their overlay stays unchanged.
     */
    val followProgress: ((ordinal: Int) -> FollowProgress?)? = null,
    /** Release any borrowed download-page URIs when this session leaves composition. */
    val onClose: () -> Unit = {},
    /** Optional serialized context; gallery sessions leave this null. */
    val chapterContext: ReaderChapterContext? = null,
    /** Whether this serialized chapter already had persisted remote progress when opened. */
    val hasStoredProgress: Boolean = false,
    /** Optional boundary cards; gallery sessions leave this null. */
    val transition: OnlineReaderTransitionPresentation? = null,
)

internal fun serializedReaderLandingShouldRecord(
    chapterContext: ReaderChapterContext?,
    hasStoredProgress: Boolean,
): Boolean = chapterContext != null && !hasStoredProgress

data class OnlineReaderTransitionPresentation(
    val startText: String,
    val endText: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

/**
 * Ephemeral HTTPS page reader. Does not touch Library or reading progress Room;
 * remote read progress is reported through [onProgressSettled] (A42 remainder:
 * the host persists it in [RemoteReadingProgressStore]).
 */
@Composable
fun OnlineReaderScreen(
    session: OnlineReaderSession,
    onBack: () -> Unit,
    keepScreenOnEnabled: Boolean = true,
    volumeKeyPagingEnabled: Boolean = true,
    rtlPagingEnabled: Boolean = false,
    fitWidthEnabled: Boolean = false,
    /**
     * A62 (spec test 6): injectable so a device test can drive the failure block with a
     * loader whose fetcher throws (e.g. Coil's HttpException(429)) instead of reaching the
     * network. Null = the singleton, exactly like the local reader's seam.
     */
    imageLoader: ImageLoader? = null,
    /** Serialized chapter context; null preserves gallery behavior exactly. */
    chapterContext: ReaderChapterContext? = session.chapterContext,
    /** Optional serialized transition cards; null preserves gallery behavior exactly. */
    transition: OnlineReaderTransitionPresentation? = session.transition,
    /** Called when a serialized reader is asked to move beyond its chapter edge. */
    onChapterBoundary: ((ReaderChapterBoundary) -> Unit)? = null,
    /**
     * A42 remainder: called when the page the user is actually looking at
     * settles, with the same cadence the LOCAL reader persists with
     * (ReaderScreen's `settledPage == page && pageDisplayed` flow — no extra
     * debounce). Default no-op; the host binds a
     * [RemoteReadingProgressRecorder].
     */
    onProgressSettled: (ordinal: Int, pageCount: Int) -> Unit = { _, _ -> },
    /** #E114: Reader layout mode (AUTO / VERTICAL_STRIP / PAGED). */
    layoutMode: ReaderLayoutMode = ReaderLayoutMode.AUTO,
    onLayoutModeChange: ((ReaderLayoutMode) -> Unit)? = null,
    onRtlPagingChange: ((Boolean) -> Unit)? = null,
) {
    // onBack remains for system/gesture exit from the host; no in-reader Back control.
    @Suppress("UNUSED_PARAMETER")
    val unusedOnBack = onBack
    val palette = quietShelfPalette()
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val resolvedImageLoader = imageLoader ?: SingletonImageLoader.get(context)
    val pageCount = session.imageUrls.size.coerceAtLeast(1)
    val sessionCacheKey = remember(session.title, chapterContext?.chapterId, session.imageUrls.size) {
        "online:${session.title}:${chapterContext?.chapterId.orEmpty()}:${session.imageUrls.size}"
    }
    var localRtlPaging by remember(rtlPagingEnabled) { mutableStateOf(rtlPagingEnabled) }
    val effectiveRtlPaging = if (onRtlPagingChange != null) rtlPagingEnabled else localRtlPaging
    val toggleRtlPaging: () -> Unit = {
        val next = !effectiveRtlPaging
        localRtlPaging = next
        onRtlPagingChange?.invoke(next)
    }
    var localLayoutMode by remember(layoutMode) { mutableStateOf(layoutMode) }
    val effectiveLayoutMode = if (onLayoutModeChange != null) layoutMode else localLayoutMode
    val toggleLayoutMode: () -> Unit = {
        val next = effectiveLayoutMode.next()
        localLayoutMode = next
        onLayoutModeChange?.invoke(next)
    }
    var autoDetectedTallStrip by remember(session) { mutableStateOf(false) }
    val stripModeActive = ReaderWebtoonMath.shouldUseVerticalStrip(
        mode = effectiveLayoutMode,
        autoDetectedTallStrip = autoDetectedTallStrip,
    )
    var autoScrollSpeed by remember(session, stripModeActive) { mutableStateOf(AutoScrollSpeed.OFF) }
    var autoScrollTouchPaused by remember(session, stripModeActive) { mutableStateOf(false) }
    val stripListState = rememberLazyListState(
        initialFirstVisibleItemIndex = clampReaderOrdinal(session.initialOrdinal, pageCount) +
            if (transition != null) 1 else 0,
    )
    val stripPageAspects = remember(session) { mutableStateMapOf<Int, Float>() }
    val stripPageReady = remember(session) { mutableStateMapOf<Int, Boolean>() }
    val spreadPages = remember(session) { mutableStateMapOf<Int, Boolean>() }
    val spreadSubSlots = remember(session) { mutableStateMapOf<Int, SpreadPageSubSlot>() }
    val leadingSlotCount = if (transition == null) 0 else 1
    val pageSlotCount = pageCount + if (transition == null) 0 else 2
    val initialPage = readerInitialPagerPage(
        initialOrdinal = session.initialOrdinal,
        pageCount = pageCount,
        leadingSlotCount = leadingSlotCount,
    )
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageSlotCount },
    )
    LaunchedEffect(session, transition) {
        if (pagerState.currentPage != initialPage) pagerState.scrollToPage(initialPage)
        val initialStripIndex = clampReaderOrdinal(session.initialOrdinal, pageCount) + leadingSlotCount
        if (stripListState.firstVisibleItemIndex != initialStripIndex) {
            stripListState.scrollToItem(initialStripIndex)
        }
    }
    var previousStripMode by remember(session) { mutableStateOf(stripModeActive) }
    LaunchedEffect(stripModeActive) {
        if (stripModeActive != previousStripMode) {
            previousStripMode = stripModeActive
            if (stripModeActive) {
                val targetOrdinal = (pagerState.currentPage - leadingSlotCount).coerceIn(0, pageCount - 1)
                stripListState.scrollToItem(targetOrdinal + leadingSlotCount)
            } else {
                val visiblePages = stripListState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                    val pageIdx = info.index - leadingSlotCount
                    if (pageIdx in 0 until pageCount) {
                        StripVisibleItem(pageOrdinal = pageIdx, offsetPx = info.offset, sizePx = info.size)
                    } else {
                        null
                    }
                }
                val centerOrdinal = ReaderWebtoonMath.centerLinePageOrdinal(
                    visibleItems = visiblePages,
                    viewportHeightPx = stripListState.layoutInfo.viewportSize.height,
                    fallbackOrdinal = (pagerState.currentPage - leadingSlotCount).coerceIn(0, pageCount - 1),
                )
                pagerState.scrollToPage(centerOrdinal + leadingSlotCount)
            }
        }
    }
    LaunchedEffect(stripModeActive, autoScrollSpeed, autoScrollTouchPaused, density) {
        if (!stripModeActive || autoScrollSpeed == AutoScrollSpeed.OFF || autoScrollTouchPaused) {
            return@LaunchedEffect
        }
        var lastFrameMs = withFrameMillis { it }
        while (true) {
            val nowMs = withFrameMillis { it }
            val dt = (nowMs - lastFrameMs).coerceAtLeast(1L)
            lastFrameMs = nowMs
            val deltaPx = ReaderWebtoonMath.autoScrollDeltaPx(
                speed = autoScrollSpeed,
                touchPaused = autoScrollTouchPaused,
                density = density,
                frameMillis = dt,
            )
            if (deltaPx > 0f) {
                val consumed = stripListState.scrollBy(deltaPx)
                val lastInfo = stripListState.layoutInfo.visibleItemsInfo.lastOrNull()
                val totalItems = stripListState.layoutInfo.totalItemsCount
                val reachedAbsoluteEnd = totalItems > 0 &&
                    lastInfo != null &&
                    lastInfo.index == totalItems - 1 &&
                    lastInfo.offset + lastInfo.size <= stripListState.layoutInfo.viewportEndOffset &&
                    !stripListState.canScrollForward
                if (consumed == 0f && reachedAbsoluteEnd) {
                    autoScrollSpeed = AutoScrollSpeed.OFF
                    break
                }
            }
        }
    }
    var chromeVisible by remember { mutableStateOf(false) }
    // C42: match the local reader's seed guard — the page the reader OPENS on
    // (resume point or a preview-cell deep link like page 1) must not overwrite
    // stored progress on its landing settle; only a page the user navigates to
    // records. Seeded to the opened ordinal, advanced by the first real settle.
    var lastRecordedOrdinal by remember(session) {
        mutableIntStateOf(
            if (serializedReaderLandingShouldRecord(chapterContext, session.hasStoredProgress)) {
                -1
            } else {
                clampReaderOrdinal(session.initialOrdinal, pageCount)
            },
        )
    }
    var zoomScale by remember { mutableFloatStateOf(ReaderPageZoom.FIT_SCALE) }
    var panTranslation by remember { mutableStateOf(ReaderPagePan.resetTranslation()) }
    val latestZoomScale by rememberUpdatedState(zoomScale)
    val latestPanTranslation by rememberUpdatedState(panTranslation)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect {
                zoomScale = ReaderPageZoom.FIT_SCALE
                panTranslation = ReaderPagePan.resetTranslation()
            }
    }
    val volumeFocusRequester = remember { FocusRequester() }
    val volumeScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        volumeFocusRequester.requestFocus()
    }
    val latestOnProgressSettledGlobal by rememberUpdatedState(onProgressSettled)
    val stripCenterOrdinal: () -> Int = {
        val visiblePages = stripListState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val pageIdx = info.index - leadingSlotCount
            if (pageIdx in 0 until pageCount) {
                StripVisibleItem(pageOrdinal = pageIdx, offsetPx = info.offset, sizePx = info.size)
            } else {
                null
            }
        }
        ReaderWebtoonMath.centerLinePageOrdinal(
            visibleItems = visiblePages,
            viewportHeightPx = stripListState.layoutInfo.viewportSize.height,
            fallbackOrdinal = clampReaderOrdinal(session.initialOrdinal, pageCount),
        )
    }
    LaunchedEffect(stripModeActive, stripListState, session) {
        if (!stripModeActive) return@LaunchedEffect
        snapshotFlow {
            val centerOrd = stripCenterOrdinal()
            val ready = stripPageReady[centerOrd] == true
            centerOrd to ready
        }
            .distinctUntilChanged()
            .collect { (centerOrd, ready) ->
                val initialSinglePageChapter = chapterContext != null && pageCount == 1
                if (ready && (centerOrd != lastRecordedOrdinal || initialSinglePageChapter)) {
                    latestOnProgressSettledGlobal(centerOrd, session.imageUrls.size)
                    lastRecordedOrdinal = centerOrd
                }
            }
    }
    fun moveByDirectedDelta(delta: Int) {
        if (stripModeActive) {
            val viewportH = stripListState.layoutInfo.viewportSize.height.takeIf { it > 0 } ?: 1200
            val scrollDelta = viewportH * 0.8f * delta
            volumeScope.launch { stripListState.scrollBy(scrollDelta) }
            return
        }
        val current = pagerState.settledPage
        val realCurrent = current - leadingSlotCount
        val currentSubSlot = spreadSubSlots[realCurrent] ?: SpreadPageSubSlot.FULL
        if (currentSubSlot == SpreadPageSubSlot.LEFT_HALF || currentSubSlot == SpreadPageSubSlot.RIGHT_HALF) {
            val (firstHalf, secondHalf) = ReaderPageZoom.spreadSplitOrder(effectiveRtlPaging)
            if (delta > 0 && currentSubSlot == firstHalf) {
                spreadSubSlots[realCurrent] = secondHalf
                return
            }
            if (delta < 0 && currentSubSlot == secondHalf) {
                spreadSubSlots[realCurrent] = firstHalf
                return
            }
            if (delta > 0 && currentSubSlot == secondHalf) {
                spreadSubSlots[realCurrent] = SpreadPageSubSlot.FULL
            } else if (delta < 0 && currentSubSlot == firstHalf) {
                spreadSubSlots[realCurrent] = SpreadPageSubSlot.FULL
            }
        }
        if (transition != null && current == 0 && delta < 0) {
            if (transition.hasPrevious) onChapterBoundary?.invoke(ReaderChapterBoundary.START)
            return
        }
        if (transition != null && current == pageSlotCount - 1 && delta > 0) {
            if (transition.hasNext) onChapterBoundary?.invoke(ReaderChapterBoundary.END)
            return
        }
        val target = ReaderVolumePaging.nextPageIndex(
            current = current,
            delta = delta,
            pageCount = pageSlotCount,
        )
        if (target != current) {
            volumeScope.launch { pagerState.animateScrollToPage(target) }
        }
    }
    val goToPrevious: () -> Unit = {
        moveByDirectedDelta(
            ReaderVolumePaging.directedDelta(
                ReaderVolumePaging.pageDeltaForVolumeUp(),
                effectiveRtlPaging,
            ),
        )
    }
    val goToNext: () -> Unit = {
        moveByDirectedDelta(
            ReaderVolumePaging.directedDelta(
                ReaderVolumePaging.pageDeltaForVolumeDown(),
                effectiveRtlPaging,
            ),
        )
    }
    val latestGoToPrevious by rememberUpdatedState(goToPrevious)
    val latestGoToNext by rememberUpdatedState(goToNext)
    // Owner bug 2026-09-10: drive volume paging from the Activity bus (see
    // ReaderVolumeKeyBus) so it works without relying on Compose focus.
    if (volumeKeyPagingEnabled) {
        DisposableEffect(Unit) {
            val handler: (VolumeKeyDirection) -> Unit = { direction ->
                if (direction == VolumeKeyDirection.NEXT) latestGoToNext() else latestGoToPrevious()
            }
            ReaderVolumeKeyBus.register(handler)
            onDispose { ReaderVolumeKeyBus.unregister(handler) }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .keepScreenOn(keepScreenOnEnabled)
            .focusRequester(volumeFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (!volumeKeyPagingEnabled) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val baseDelta = when (event.key) {
                    Key.VolumeUp -> ReaderVolumePaging.pageDeltaForVolumeUp()
                    Key.VolumeDown -> ReaderVolumePaging.pageDeltaForVolumeDown()
                    else -> return@onKeyEvent false
                }
                val pageDelta = ReaderVolumePaging.directedDelta(baseDelta, effectiveRtlPaging)
                moveByDirectedDelta(pageDelta)
                true
            }
            .testTag("online-reader"),
    ) {
        if (stripModeActive) {
            LazyColumn(
                state = stripListState,
                userScrollEnabled = ReaderPageZoom.pagerUserScrollEnabled(zoomScale),
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            autoScrollTouchPaused = true
                            var gestureScale = latestZoomScale
                            var gestureTranslation = latestPanTranslation
                            var transformActive = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointerCount = event.changes.count { it.pressed }
                                if (pointerCount >= 2) {
                                    transformActive = true
                                }
                                if (transformActive || gestureScale > ReaderPageZoom.FIT_SCALE) {
                                    gestureScale = ReaderPageZoom.clampScale(
                                        gestureScale * event.calculateZoom(),
                                    )
                                    val panChange = event.calculatePan()
                                    gestureTranslation = ReaderPagePan.clampTranslation(
                                        translation = gestureTranslation + panChange,
                                        viewportWidth = size.width.toFloat(),
                                        viewportHeight = size.height.toFloat(),
                                        scale = gestureScale,
                                    )
                                    zoomScale = gestureScale
                                    panTranslation = gestureTranslation
                                    if (pointerCount >= 2 || gestureScale > ReaderPageZoom.FIT_SCALE) {
                                        event.changes.forEach { change ->
                                            if (change.positionChanged()) {
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            autoScrollTouchPaused = false
                        }
                    }
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panTranslation.x
                        translationY = panTranslation.y
                    }
                    .testTag("online-reader-strip-list"),
            ) {
                items(
                    count = pageSlotCount,
                    key = { slotIndex -> "strip-slot-$slotIndex" },
                ) { pageIndex ->
                    if (transition != null && pageIndex == 0) {
                        VerticalStripChapterBanner(
                            text = transition.startText,
                            actionLabel = if (transition.hasPrevious) AppStrings.loadPreviousChapter else null,
                            testTag = "online-reader-transition-start",
                            onActivate = {
                                if (transition.hasPrevious) {
                                    onChapterBoundary?.invoke(ReaderChapterBoundary.START)
                                }
                            },
                        )
                        return@items
                    }
                    if (transition != null && pageIndex == pageSlotCount - 1) {
                        VerticalStripChapterBanner(
                            text = transition.endText,
                            actionLabel = if (transition.hasNext) AppStrings.continueNextChapter else null,
                            testTag = "online-reader-transition-end",
                            onActivate = {
                                if (transition.hasNext) {
                                    onChapterBoundary?.invoke(ReaderChapterBoundary.END)
                                }
                            },
                        )
                        return@items
                    }
                    val page = pageIndex - leadingSlotCount
                    val initialSlotUrl = session.imageUrls.getOrNull(page).orEmpty()
                    val cachedFirstPageUrl = remember(sessionCacheKey, page) {
                        if (initialSlotUrl.isBlank()) {
                            ReaderFirstPageCache.shared.resolve(sessionCacheKey, page).orEmpty()
                        } else {
                            ""
                        }
                    }
                    var resolvedUrl by remember(session, page, initialSlotUrl) {
                        mutableStateOf(initialSlotUrl.ifBlank { cachedFirstPageUrl })
                    }
                    var resolveFailed by remember(session, page, initialSlotUrl) {
                        mutableStateOf(false)
                    }
                    var resolveAttempt by remember(session, page) { mutableIntStateOf(0) }
                    LaunchedEffect(page, resolvedUrl, session.resolveBlankPageUrl, resolveAttempt) {
                        if (resolvedUrl.isNotBlank() && !isReadableCacheUri(resolvedUrl)) {
                            resolvedUrl = ""
                            resolveFailed = false
                        }
                        if (resolvedUrl.isNotBlank() || session.resolveBlankPageUrl == null) {
                            return@LaunchedEffect
                        }
                        val resolver = session.resolveBlankPageUrl
                        runCatching { resolver(page) }
                            .onSuccess { uri ->
                                if (uri.isNotBlank()) {
                                    resolvedUrl = uri
                                    resolveFailed = false
                                    ReaderFirstPageCache.shared.record(sessionCacheKey, page, uri)
                                } else {
                                    resolveFailed = true
                                }
                            }
                            .onFailure {
                                resolveFailed = true
                            }
                    }
                    LaunchedEffect(page, session.resolveBlankPageUrl) {
                        val resolver = session.resolveBlankPageUrl ?: return@LaunchedEffect
                        val next = page + 1
                        if (next < session.imageUrls.size &&
                            session.imageUrls.getOrNull(next).orEmpty().isBlank()
                        ) {
                            runCatching { resolver(next) }
                        }
                    }
                    val url = resolvedUrl.takeIf { it.isNotBlank() }
                    val pageAspect = stripPageAspects[page]
                    val pageSpreadSubSlot = spreadSubSlots[page] ?: SpreadPageSubSlot.FULL
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (pageAspect != null && pageAspect > 0f) {
                                    Modifier.aspectRatio(
                                        if (pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90) {
                                            pageAspect
                                        } else {
                                            1f / pageAspect
                                        },
                                    )
                                } else {
                                    Modifier.heightIn(min = 360.dp)
                                },
                            )
                            .clipToBounds()
                            .background(Color.Black)
                            .pointerInput(page) {
                                suspend fun AwaitPointerEventScope.awaitQualifiedTap(): Offset? {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val up = waitForUpOrCancellation() ?: return null
                                    return up.position.takeIf {
                                        (up.position - down.position).getDistance() <= viewConfiguration.touchSlop * 2f
                                    } ?: down.position
                                }
                                awaitEachGesture {
                                    val firstPos = awaitQualifiedTap() ?: return@awaitEachGesture
                                    chromeVisible = !chromeVisible
                                    val secondPos = withTimeoutOrNull(
                                        viewConfiguration.doubleTapTimeoutMillis,
                                    ) {
                                        awaitQualifiedTap()
                                    } ?: return@awaitEachGesture
                                    chromeVisible = !chromeVisible
                                    val prevScale = zoomScale
                                    val nextScale = ReaderPageZoom.nextScale(prevScale)
                                    zoomScale = nextScale
                                    panTranslation = if (prevScale == ReaderPageZoom.FIT_SCALE && nextScale > ReaderPageZoom.FIT_SCALE) {
                                        ReaderPagePan.doubleTapTargetTranslation(
                                            tapOffset = secondPos ?: firstPos,
                                            viewportWidth = size.width.toFloat(),
                                            viewportHeight = size.height.toFloat(),
                                            targetScale = nextScale,
                                            rtl = effectiveRtlPaging,
                                        )
                                    } else {
                                        ReaderPagePan.clampTranslation(
                                            translation = panTranslation,
                                            viewportWidth = size.width.toFloat(),
                                            viewportHeight = size.height.toFloat(),
                                            scale = nextScale,
                                        )
                                    }
                                }
                            }
                            .semantics {
                                contentDescription = AppStrings.toggleReaderControls
                                this[ReaderPageZoom.SCALE_SEMANTICS_KEY] = zoomScale
                                this[ReaderPagePan.TRANSLATION_SEMANTICS_KEY] = panTranslation
                                onClick {
                                    chromeVisible = !chromeVisible
                                    true
                                }
                            }
                            .testTag("online-reader-center-$page"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (url.isNullOrBlank()) {
                            if (resolveFailed) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .padding(24.dp)
                                        .testTag("online-reader-page-resolve-failure"),
                                ) {
                                    BrokenImageGlyph(
                                        size = 88.dp,
                                        contentDescription = AppStrings.imageFailedToLoad,
                                    )
                                    Text(
                                        OnlineReaderPageFailureCopy.title(),
                                        color = palette.muted,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            resolveFailed = false
                                            resolveAttempt += 1
                                        },
                                        modifier = Modifier.testTag("online-reader-page-resolve-retry"),
                                    ) {
                                        Text(OnlineReaderPageFailureCopy.retryLabel())
                                    }
                                }
                            } else if (session.resolveBlankPageUrl != null) {
                                var follow by remember(session, page) { mutableStateOf<FollowProgress?>(null) }
                                LaunchedEffect(session.followProgress, page) {
                                    val tracker = session.followProgress ?: return@LaunchedEffect
                                    while (true) {
                                        follow = tracker(page)
                                        delay(500L)
                                    }
                                }
                                ReaderPageLoadOverlay(
                                    follow?.let(::readerFollowPresentation)
                                        ?: ReaderPageLoadProgress.presentation(
                                            imageReady = false,
                                            loadFailed = false,
                                        ),
                                )
                            } else {
                                Text(
                                    tr("Page unavailable", "页面不可用", "頁面無法使用", "ページを利用できません"),
                                    color = palette.muted,
                                    modifier = Modifier.testTag("online-reader-page-unavailable"),
                                )
                            }
                        } else {
                            var reloadNonce by remember(url, page) { mutableIntStateOf(0) }
                            var loadFailed by remember(url, page, reloadNonce) { mutableStateOf(false) }
                            var loadFailure by remember(url, page, reloadNonce) {
                                mutableStateOf<RemotePageFailure?>(null)
                            }
                            var pageReady by remember(url, page, reloadNonce) { mutableStateOf(false) }
                            val progress = rememberReaderImageProgress(url)
                            val painter = key(url, reloadNonce) {
                                rememberAsyncImagePainter(
                                    model = "$url#$reloadNonce",
                                    imageLoader = resolvedImageLoader,
                                    onSuccess = {
                                        pageReady = true
                                        stripPageReady[page] = true
                                        loadFailed = false
                                        loadFailure = null
                                        ReaderFirstPageCache.shared.record(sessionCacheKey, page, url)
                                    },
                                    onError = { result ->
                                        pageReady = false
                                        stripPageReady[page] = false
                                        loadFailed = true
                                        loadFailure = classifyRemotePageFailure(result.result.throwable)
                                    },
                                    contentScale = ContentScale.FillWidth,
                                )
                            }
                            val intrinsicSize = painter.intrinsicSize
                            LaunchedEffect(url, page, reloadNonce, intrinsicSize) {
                                if (intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
                                    val aspect = intrinsicSize.height / intrinsicSize.width
                                    stripPageAspects[page] = aspect
                                    spreadPages[page] = ReaderPageZoom.isSpreadDimensions(
                                        widthPx = intrinsicSize.width.toInt(),
                                        heightPx = intrinsicSize.height.toInt(),
                                    )
                                }
                            }
                            Image(
                                painter = painter,
                                contentDescription = AppStrings.pageOf(page + 1, pageCount),
                                contentScale = if (pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90) {
                                    ContentScale.Fit
                                } else {
                                    ContentScale.FillWidth
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val (subScale, subOffset) = ReaderPagePan.spreadSubSlotTransform(
                                            subSlot = pageSpreadSubSlot,
                                            viewportWidth = size.width,
                                        )
                                        val rotScale = if (
                                            pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90 &&
                                            size.width > 0f &&
                                            size.height > 0f
                                        ) {
                                            (size.height / size.width).coerceIn(1f, 2.2f)
                                        } else {
                                            1f
                                        }
                                        scaleX = subScale * rotScale
                                        scaleY = subScale * rotScale
                                        translationX = subOffset.x
                                        translationY = subOffset.y
                                        rotationZ = if (pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90) 90f else 0f
                                    },
                            )
                            ReaderPageLoadOverlay(
                                ReaderPageLoadProgress.presentation(
                                    imageReady = pageReady,
                                    loadFailed = loadFailed,
                                    receivedBytes = progress.receivedBytes,
                                    contentLength = progress.contentLength,
                                ),
                            )
                            if (loadFailed) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .padding(24.dp)
                                        .testTag("online-reader-page-load-failure"),
                                ) {
                                    BrokenImageGlyph(
                                        size = 88.dp,
                                        contentDescription = AppStrings.imageFailedToLoad,
                                        statusCode = loadFailure?.statusDigits(),
                                    )
                                    Text(
                                        OnlineReaderPageFailureCopy.title(),
                                        color = palette.muted,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            loadFailed = false
                                            loadFailure = null
                                            reloadNonce += 1
                                        },
                                        modifier = Modifier.testTag("online-reader-page-retry"),
                                    ) {
                                        Text(OnlineReaderPageFailureCopy.retryLabel())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("online-reader-pager"),
                reverseLayout = effectiveRtlPaging,
                userScrollEnabled = ReaderPageZoom.pagerUserScrollEnabled(zoomScale),
            ) { pageIndex ->
            if (transition != null && pageIndex == 0) {
                ChapterTransitionCard(
                    text = transition.startText,
                    testTag = "online-reader-transition-start",
                    forward = false,
                    rtlPagingEnabled = effectiveRtlPaging,
                    onPass = {
                        if (transition.hasPrevious) {
                            onChapterBoundary?.invoke(ReaderChapterBoundary.START)
                        }
                    },
                    onPrevious = goToPrevious,
                    onNext = goToNext,
                )
                return@HorizontalPager
            }
            if (transition != null && pageIndex == pageSlotCount - 1) {
                ChapterTransitionCard(
                    text = transition.endText,
                    testTag = "online-reader-transition-end",
                    forward = true,
                    rtlPagingEnabled = effectiveRtlPaging,
                    onPass = {
                        if (transition.hasNext) {
                            onChapterBoundary?.invoke(ReaderChapterBoundary.END)
                        }
                    },
                    onPrevious = goToPrevious,
                    onNext = goToNext,
                )
                return@HorizontalPager
            }
            val page = pageIndex - leadingSlotCount
            val initialSlotUrl = session.imageUrls.getOrNull(page).orEmpty()
            val cachedFirstPageUrl = remember(sessionCacheKey, page) {
                if (initialSlotUrl.isBlank()) {
                    ReaderFirstPageCache.shared.resolve(sessionCacheKey, page).orEmpty()
                } else {
                    ""
                }
            }
            var resolvedUrl by remember(session, page, initialSlotUrl) {
                mutableStateOf(initialSlotUrl.ifBlank { cachedFirstPageUrl })
            }
            var resolveFailed by remember(session, page, initialSlotUrl) {
                mutableStateOf(false)
            }
            var resolveAttempt by remember(session, page) { mutableIntStateOf(0) }
            LaunchedEffect(page, resolvedUrl, session.resolveBlankPageUrl, resolveAttempt) {
                if (resolvedUrl.isNotBlank() && !isReadableCacheUri(resolvedUrl)) {
                    resolvedUrl = ""
                    resolveFailed = false
                }
                if (resolvedUrl.isNotBlank() || session.resolveBlankPageUrl == null) {
                    return@LaunchedEffect
                }
                val resolver = session.resolveBlankPageUrl
                runCatching { resolver(page) }
                    .onSuccess { uri ->
                        if (uri.isNotBlank()) {
                            resolvedUrl = uri
                            resolveFailed = false
                            ReaderFirstPageCache.shared.record(sessionCacheKey, page, uri)
                        } else {
                            // Spec §4.5: never blank success — surface explicit page error.
                            resolveFailed = true
                        }
                    }
                    .onFailure {
                        resolveFailed = true
                    }
            }
            // Prefetch next blank page while viewing current (failures stay soft until visited).
            LaunchedEffect(page, session.resolveBlankPageUrl) {
                val resolver = session.resolveBlankPageUrl ?: return@LaunchedEffect
                val next = page + 1
                if (next < session.imageUrls.size &&
                    session.imageUrls.getOrNull(next).orEmpty().isBlank()
                ) {
                    runCatching { resolver(next) }
                }
            }
            val url = resolvedUrl.takeIf { it.isNotBlank() }
            var contentAspect by remember(url, page) { mutableStateOf<Float?>(null) }
            val latestContentAspect by rememberUpdatedState(contentAspect)
            val latestFitWidthEnabled by rememberUpdatedState(fitWidthEnabled)
            val fitWidthLayoutAspect = contentAspect
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .background(Color.Black)
                    .pointerInput(
                        pagerState.settledPage,
                        page,
                        fitWidthEnabled,
                        contentAspect,
                    ) {
                        if (pagerState.settledPage - leadingSlotCount == page) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var gestureScale = latestZoomScale
                                var gestureTranslation = latestPanTranslation
                                var panSinceDown = Offset.Zero
                                var transformActive = false
                                do {
                                    val event = awaitPointerEvent()
                                    val pointerCount = event.changes.count { it.pressed }
                                    if (pointerCount >= 2) {
                                        transformActive = true
                                    }
                                    val panChange = event.calculatePan()
                                    panSinceDown += panChange
                                    val overflowMax = ReaderPagePan.maxTranslation(
                                        viewportWidth = size.width.toFloat(),
                                        viewportHeight = size.height.toFloat(),
                                        scale = gestureScale,
                                        fitWidth = latestFitWidthEnabled,
                                        contentAspectHeightOverWidth = latestContentAspect,
                                    )
                                    val pastSlop =
                                        panSinceDown.getDistance() > viewConfiguration.touchSlop
                                    val panStarted = ReaderPagePan.panGestureEnabled(
                                        gestureScale,
                                        overflowMax,
                                    ) && pastSlop
                                    val allowConsume = when {
                                        !panStarted && !transformActive -> false
                                        gestureScale > ReaderPageZoom.FIT_SCALE ||
                                            transformActive -> true
                                        else -> ReaderPagePan.isVerticalDominantPan(panSinceDown)
                                    }
                                    if (allowConsume) {
                                        gestureScale = ReaderPageZoom.clampScale(
                                            gestureScale * event.calculateZoom(),
                                        )
                                        val appliedPan =
                                            if (
                                                !transformActive &&
                                                gestureScale <= ReaderPageZoom.FIT_SCALE
                                            ) {
                                                Offset(0f, panChange.y)
                                            } else {
                                                panChange
                                            }
                                        gestureTranslation = ReaderPagePan.clampTranslation(
                                            translation = gestureTranslation + appliedPan,
                                            viewportWidth = size.width.toFloat(),
                                            viewportHeight = size.height.toFloat(),
                                            scale = gestureScale,
                                            fitWidth = latestFitWidthEnabled,
                                            contentAspectHeightOverWidth = latestContentAspect,
                                        )
                                        zoomScale = gestureScale
                                        panTranslation = gestureTranslation
                                        event.changes.forEach { change ->
                                            if (change.positionChanged()) {
                                                change.consume()
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // #C77 (the #C68 fix, now in this reader too): the tap zones are emitted BEFORE the page
                // content. Compose hit-tests top-down, so zones emitted after the content sat on top and won
                // every tap, including on the failure column's Retry button. Content drawn above them now takes
                // its own taps; every non-interactive pixel still falls through to the zone beneath.
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .testTag("online-reader-zone-previous")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = AppStrings.previousPage,
                                onClick = goToPrevious,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .testTag("online-reader-center-$page")
                            .pointerInput(Unit) {
                            suspend fun AwaitPointerEventScope.awaitQualifiedTap(): Boolean {
                                awaitFirstDown(requireUnconsumed = false)
                                return waitForUpOrCancellation() != null
                            }

                            awaitEachGesture {
                                if (!awaitQualifiedTap()) return@awaitEachGesture
                                chromeVisible = !chromeVisible
                                val secondTap = withTimeoutOrNull(
                                    viewConfiguration.doubleTapTimeoutMillis,
                                ) {
                                    awaitQualifiedTap()
                                } ?: return@awaitEachGesture
                                if (secondTap) {
                                    chromeVisible = !chromeVisible
                                    val prevScale = zoomScale
                                    val spreadAspect = latestContentAspect?.takeIf {
                                        spreadPages[page] == true
                                    }
                                    val nextScale = if (spreadAspect != null) {
                                        ReaderPageZoom.nextScaleForPage(
                                            current = prevScale,
                                            viewportWidth = size.width.toFloat() * 3f,
                                            viewportHeight = size.height.toFloat(),
                                            contentAspectHeightOverWidth = spreadAspect,
                                        )
                                    } else {
                                        ReaderPageZoom.nextScale(prevScale)
                                    }
                                    zoomScale = nextScale
                                    panTranslation = if (
                                        spreadAspect != null &&
                                        prevScale == ReaderPageZoom.FIT_SCALE &&
                                        nextScale > ReaderPageZoom.FIT_SCALE
                                    ) {
                                        ReaderPagePan.doubleTapTargetTranslation(
                                            tapOffset = null,
                                            viewportWidth = size.width.toFloat() * 3f,
                                            viewportHeight = size.height.toFloat(),
                                            targetScale = nextScale,
                                            rtl = effectiveRtlPaging,
                                            fitWidth = latestFitWidthEnabled,
                                            contentAspectHeightOverWidth = spreadAspect,
                                        )
                                    } else {
                                        ReaderPagePan.clampTranslation(
                                            translation = panTranslation,
                                            viewportWidth = size.width.toFloat(),
                                            viewportHeight = size.height.toFloat(),
                                            scale = nextScale,
                                            fitWidth = latestFitWidthEnabled,
                                            contentAspectHeightOverWidth = latestContentAspect,
                                        )
                                    }
                                }
                            }
                        }
                            .semantics {
                                contentDescription = AppStrings.toggleReaderControls
                                this[ReaderPageZoom.SCALE_SEMANTICS_KEY] = zoomScale
                                this[ReaderPagePan.TRANSLATION_SEMANTICS_KEY] = panTranslation
                                onClick {
                                    chromeVisible = !chromeVisible
                                    true
                                }
                            },
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .testTag("online-reader-zone-next")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = AppStrings.nextPage,
                                onClick = goToNext,
                            ),
                    )
                }
                if (url.isNullOrBlank()) {
                    if (resolveFailed) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .padding(24.dp)
                                .testTag("online-reader-page-resolve-failure"),
                        ) {
                            // A62 (spec D3): resolve has no URL yet, so there is no HTTP
                            // status to state -- the glyph alone.
                            BrokenImageGlyph(
                                size = 88.dp,
                                contentDescription = AppStrings.imageFailedToLoad,
                            )
                            Text(
                                OnlineReaderPageFailureCopy.title(),
                                color = palette.muted,
                            )
                            OutlinedButton(
                                onClick = {
                                    resolveFailed = false
                                    resolveAttempt += 1
                                },
                                modifier = Modifier.testTag("online-reader-page-resolve-retry"),
                            ) {
                                Text(OnlineReaderPageFailureCopy.retryLabel())
                            }
                        }
                    } else if (session.resolveBlankPageUrl != null) {
                        // #E15 Task 3b: while the resolve waits on a download lane,
                        // poll the lane's progress into the overlay's existing label
                        // slot; a session without followProgress keeps today's overlay.
                        var follow by remember(session, page) { mutableStateOf<FollowProgress?>(null) }
                        LaunchedEffect(session.followProgress, page) {
                            val tracker = session.followProgress ?: return@LaunchedEffect
                            while (true) {
                                follow = tracker(page)
                                delay(500L)
                            }
                        }
                        ReaderPageLoadOverlay(
                            follow?.let(::readerFollowPresentation)
                                ?: ReaderPageLoadProgress.presentation(
                                    imageReady = false,
                                    loadFailed = false,
                                ),
                        )
                    } else {
                        Text(
                            tr("Page unavailable", "页面不可用", "頁面無法使用", "ページを利用できません"),
                            color = palette.muted,
                            modifier = Modifier.testTag("online-reader-page-unavailable"),
                        )
                    }
                } else {
                    var reloadNonce by remember(url, page) { mutableIntStateOf(0) }
                    var loadFailed by remember(url, page, reloadNonce) { mutableStateOf(false) }
                    var loadFailure by remember(url, page, reloadNonce) {
                        mutableStateOf<RemotePageFailure?>(null)
                    }
                    var pageReady by remember(url, page, reloadNonce) { mutableStateOf(false) }
                    val progress = rememberReaderImageProgress(url)
                    // #C76: a new model (a retry) gets a fresh painter; handing the old one a new model makes Coil
                    // restart it inside composition, and the onSuccess/onError writes below would then run there.
                    val painter = key(url, reloadNonce) { rememberAsyncImagePainter(
                        model = "$url#$reloadNonce",
                        imageLoader = resolvedImageLoader,
                        onSuccess = {
                            pageReady = true
                            stripPageReady[page] = true
                            loadFailed = false
                            loadFailure = null
                            ReaderFirstPageCache.shared.record(sessionCacheKey, page, url)
                        },
                        onError = { result ->
                            pageReady = false
                            stripPageReady[page] = false
                            loadFailed = true
                            loadFailure = classifyRemotePageFailure(result.result.throwable)
                            contentAspect = null
                        },
                        contentScale = ReaderPageContentScale.forFitWidth(fitWidthEnabled),
                    ) }
                    val intrinsicSize = painter.intrinsicSize
                    LaunchedEffect(url, page, reloadNonce, intrinsicSize) {
                        val aspect = if (intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
                            intrinsicSize.height / intrinsicSize.width
                        } else {
                            null
                        }
                        contentAspect = aspect
                        if (aspect != null) {
                            stripPageAspects[page] = aspect
                            spreadPages[page] = ReaderPageZoom.isSpreadDimensions(
                                widthPx = intrinsicSize.width.toInt(),
                                heightPx = intrinsicSize.height.toInt(),
                            )
                            if (ReaderWebtoonMath.isTallStripAspect(aspect)) {
                                autoDetectedTallStrip = true
                            }
                        }
                    }
                    // A42 remainder: persist the settled position with the
                    // SAME cadence the local reader uses (ReaderScreen's
                    // `settledPage == page && pageDisplayed` flow — no extra
                    // debounce). pageReady is the online reader's pageDisplayed.
                    val latestOnProgressSettled by rememberUpdatedState(onProgressSettled)
                    LaunchedEffect(pagerState, url, page, reloadNonce) {
                        snapshotFlow { pagerState.settledPage - leadingSlotCount == page && pageReady }
                            .distinctUntilChanged()
                            .collect { isSettledAndDisplayed ->
                                val initialSinglePageChapter = chapterContext != null && pageCount == 1
                                if (isSettledAndDisplayed &&
                                    (page != lastRecordedOrdinal || initialSinglePageChapter)
                                ) {
                                    latestOnProgressSettled(page, session.imageUrls.size)
                                    lastRecordedOrdinal = page
                                }
                            }
                    }
                    val pageSpreadSubSlot = spreadSubSlots[page] ?: SpreadPageSubSlot.FULL
                    val isSpreadPage = spreadPages[page] == true
                    Box(
                        modifier = Modifier
                            .then(
                                when {
                                    fitWidthEnabled && fitWidthLayoutAspect != null ->
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f / fitWidthLayoutAspect)
                                    else -> Modifier.fillMaxSize()
                                },
                            )
                            .graphicsLayer {
                                val isSettled = pagerState.settledPage - leadingSlotCount == page
                                val pageScale = if (isSettled) {
                                    zoomScale
                                } else {
                                    ReaderPageZoom.FIT_SCALE
                                }
                                val (subScale, subOffset) = if (isSettled && pageScale == ReaderPageZoom.FIT_SCALE) {
                                    ReaderPagePan.spreadSubSlotTransform(
                                        subSlot = pageSpreadSubSlot,
                                        viewportWidth = size.width,
                                    )
                                } else {
                                    1f to Offset.Zero
                                }
                                val rotScale = if (
                                    isSettled &&
                                    pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90 &&
                                    size.width > 0f &&
                                    size.height > 0f
                                ) {
                                    (size.height / size.width).coerceIn(1f, 2.2f)
                                } else {
                                    1f
                                }
                                scaleX = pageScale * subScale * rotScale
                                scaleY = pageScale * subScale * rotScale
                                translationX = (if (isSettled) panTranslation.x else 0f) + subOffset.x
                                translationY = (if (isSettled) panTranslation.y else 0f) + subOffset.y
                                rotationZ = if (isSettled && pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90) 90f else 0f
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painter,
                            contentDescription = AppStrings.pageOf(page + 1, pageCount),
                            contentScale = ReaderPageContentScale.forFitWidth(fitWidthEnabled),
                            modifier = Modifier.fillMaxSize(),
                        )
                        ReaderPageLoadOverlay(
                            ReaderPageLoadProgress.presentation(
                                imageReady = pageReady,
                                loadFailed = loadFailed,
                                receivedBytes = progress.receivedBytes,
                                contentLength = progress.contentLength,
                            ),
                        )
                        if (loadFailed) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .padding(24.dp)
                                    .testTag("online-reader-page-load-failure"),
                            ) {
                                // A62 (spec D3): glyph + the HTTP status digits ("429"),
                                // the EhViewer behavioral reference for quota/blocking
                                // failures. Digits under the glyph, existing copy stays.
                                BrokenImageGlyph(
                                    size = 88.dp,
                                    contentDescription = AppStrings.imageFailedToLoad,
                                    statusCode = loadFailure?.statusDigits(),
                                )
                                Text(
                                    OnlineReaderPageFailureCopy.title(),
                                    color = palette.muted,
                                )
                                OutlinedButton(
                                    onClick = {
                                        loadFailed = false
                                        loadFailure = null
                                        reloadNonce += 1
                                    },
                                    modifier = Modifier.testTag("online-reader-page-retry"),
                                ) {
                                    Text(OnlineReaderPageFailureCopy.retryLabel())
                                }
                            }
                        }
                    }
                    if (
                        isSpreadPage &&
                        !chromeVisible &&
                        pagerState.settledPage - leadingSlotCount == page
                    ) {
                        SpreadPageFloatingBadge(
                            subSlot = pageSpreadSubSlot,
                            rtl = effectiveRtlPaging,
                            onCycleSplit = {
                                val (firstHalf, secondHalf) = ReaderPageZoom.spreadSplitOrder(effectiveRtlPaging)
                                spreadSubSlots[page] = when (pageSpreadSubSlot) {
                                    SpreadPageSubSlot.FULL, SpreadPageSubSlot.ROTATED_90 -> firstHalf
                                    firstHalf -> secondHalf
                                    secondHalf -> SpreadPageSubSlot.FULL
                                    else -> SpreadPageSubSlot.FULL
                                }
                            },
                            onToggleRotate = {
                                spreadSubSlots[page] = if (pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90) {
                                    SpreadPageSubSlot.FULL
                                } else {
                                    SpreadPageSubSlot.ROTATED_90
                                }
                            },
                        )
                    }
                }
            }
            }
        }
        if (stripModeActive && !chromeVisible && autoScrollSpeed != AutoScrollSpeed.OFF) {
            VerticalStripAutoScrollFloatingPill(
                speed = autoScrollSpeed,
                touchPaused = autoScrollTouchPaused,
                onCycleSpeed = { autoScrollSpeed = autoScrollSpeed.next() },
                onStop = { autoScrollSpeed = AutoScrollSpeed.OFF },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 20.dp),
            )
        }
        if (chromeVisible) {
            val activeOrdinal = if (stripModeActive) {
                stripCenterOrdinal().coerceIn(0, pageCount - 1)
            } else {
                (pagerState.currentPage - leadingSlotCount).coerceIn(0, pageCount - 1)
            }
            val activeSubSlot = spreadSubSlots[activeOrdinal] ?: SpreadPageSubSlot.FULL
            ReaderSeekOverlay(
                currentPage0Based = activeOrdinal,
                pageCount = pageCount,
                rtl = effectiveRtlPaging,
                onToggleRtl = toggleRtlPaging,
                chapterLabel = chapterContext?.chapterLabel,
                layoutMode = effectiveLayoutMode,
                verticalStripActive = stripModeActive,
                onToggleLayoutMode = toggleLayoutMode,
                autoScrollSpeed = autoScrollSpeed,
                autoScrollTouchPaused = autoScrollTouchPaused,
                onCycleAutoScrollSpeed = { autoScrollSpeed = autoScrollSpeed.next() },
                currentPageIsSpread = spreadPages[activeOrdinal] == true,
                spreadSubSlot = activeSubSlot,
                onCycleSpreadSubSlot = {
                    spreadSubSlots[activeOrdinal] = ReaderPageZoom.nextSpreadSubSlot(
                        current = activeSubSlot,
                        rtl = effectiveRtlPaging,
                    )
                },
                onSeekToPage0Based = { target ->
                    val targetSlot = target + leadingSlotCount
                    if (stripModeActive) {
                        volumeScope.launch { stripListState.scrollToItem(targetSlot) }
                    } else if (targetSlot != pagerState.currentPage) {
                        volumeScope.launch { pagerState.scrollToPage(targetSlot) }
                    }
                },
            )
        }
    }
}
