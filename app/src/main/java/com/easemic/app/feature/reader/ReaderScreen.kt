package com.easemic.app.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.Disposable
import com.easemic.app.storage.PageImageRequest
import com.easemic.app.storage.PageOpenException
import com.easemic.app.storage.PageOpenFailure
import com.easemic.app.storage.buildPageImageRequest
import com.easemic.app.storage.pageImageMemoryCacheKey
import com.easemic.app.model.GrantKind
import com.easemic.app.model.PageRef
import com.easemic.app.ui.components.BrokenImageGlyph
import com.easemic.app.i18n.AppStrings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onSettledPage: (PageRef) -> Unit,
    onPageOpenFailed: (PageRef, ReaderPageFailure) -> Unit,
    onBack: () -> Unit,
    // S4b/T-storage-overlay: distinct from [onBack] -- every "Back to Library" affordance
    // must land on the Library route, not wherever the platform back stack happens to have
    // pushed the reader from (usually Detail). Defaults to [onBack] so callers with nowhere
    // else to send the user keep their previous behavior.
    onExitToLibrary: () -> Unit = onBack,
    imageLoader: ImageLoader? = null,
    keepScreenOnEnabled: Boolean = true,
    volumeKeyPagingEnabled: Boolean = true,
    rtlPagingEnabled: Boolean = false,
    fitWidthEnabled: Boolean = false,
    // S4b/G8: nullable so the recovery CTA can honestly fall back to "Back to Library"
    // when the host has nowhere to send the user (previews, tests, embedded readers).
    onOpenSources: (() -> Unit)? = null,
    onFilesGone: () -> Unit = {},
    transition: ReaderTransitionPresentation? = null,
    onOpenChapter: (LocalChapterTarget) -> Unit = {},
    layoutMode: ReaderLayoutMode = ReaderLayoutMode.AUTO,
    onLayoutModeChange: ((ReaderLayoutMode) -> Unit)? = null,
    onRtlPagingChange: ((Boolean) -> Unit)? = null,
) {
    when (state) {
        ReaderUiState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is ReaderUiState.Unavailable -> when (state.reason) {
            ReaderUnavailableReason.FILES_GONE -> {
                // A30 §2.4: absence is silent. There is nothing to reauthorize and
                // nothing to retry -- leave, and let the shelf state it once.
                // Review LOW-3: still draw a plain background instead of leaving
                // this frame empty/transparent while the effect above navigates
                // away -- there is no page to show, but there is no reason to
                // flash through to whatever was behind the reader either.
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                LaunchedEffect(Unit) { onFilesGone() }
            }
            // S4b/G2: an ACCESS problem, not a missing file. Say so, and point at the one
            // surface that fixes it; never claim the comic left the phone.
            ReaderUnavailableReason.ACCESS_LOST -> AccessLostReader(
                onExitToLibrary = onExitToLibrary,
                onOpenFolders = onOpenSources,
            )
        }
        is ReaderUiState.Failed -> FailedReader(state, onBack, onExitToLibrary, onOpenSources)
        is ReaderUiState.Ready -> ReadyReader(
            ready = state,
            onSettledPage = onSettledPage,
            onPageOpenFailed = onPageOpenFailed,
            onExitToLibrary = onExitToLibrary,
            imageLoader = imageLoader,
            keepScreenOnEnabled = keepScreenOnEnabled,
            volumeKeyPagingEnabled = volumeKeyPagingEnabled,
            rtlPagingEnabled = rtlPagingEnabled,
            fitWidthEnabled = fitWidthEnabled,
            onOpenSources = onOpenSources,
            transition = transition,
            onOpenChapter = onOpenChapter,
            layoutMode = layoutMode,
            onLayoutModeChange = onLayoutModeChange,
            onRtlPagingChange = onRtlPagingChange,
        )
    }
}

@Composable
private fun ReadyReader(
    ready: ReaderUiState.Ready,
    onSettledPage: (PageRef) -> Unit,
    onPageOpenFailed: (PageRef, ReaderPageFailure) -> Unit,
    onExitToLibrary: () -> Unit,
    imageLoader: ImageLoader?,
    keepScreenOnEnabled: Boolean,
    volumeKeyPagingEnabled: Boolean,
    rtlPagingEnabled: Boolean,
    fitWidthEnabled: Boolean,
    onOpenSources: (() -> Unit)?,
    transition: ReaderTransitionPresentation?,
    onOpenChapter: (LocalChapterTarget) -> Unit,
    layoutMode: ReaderLayoutMode,
    onLayoutModeChange: ((ReaderLayoutMode) -> Unit)?,
    onRtlPagingChange: ((Boolean) -> Unit)?,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pageCount = ready.pages.size.coerceAtLeast(1)
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
    var autoDetectedTallStrip by remember(ready.readingUnitId) { mutableStateOf(false) }
    val stripModeActive = ReaderWebtoonMath.shouldUseVerticalStrip(
        mode = effectiveLayoutMode,
        autoDetectedTallStrip = autoDetectedTallStrip,
    )
    var autoScrollSpeed by remember(ready.readingUnitId, stripModeActive) { mutableStateOf(AutoScrollSpeed.OFF) }
    var autoScrollTouchPaused by remember(ready.readingUnitId, stripModeActive) { mutableStateOf(false) }
    val leadingSlotCount = if (transition == null) 0 else 1
    val pageSlotCount = ready.pages.size + if (transition == null) 0 else 2
    val initialPage = readerInitialPagerPage(
        initialOrdinal = ready.initialOrdinal,
        pageCount = ready.pages.size,
        leadingSlotCount = leadingSlotCount,
    )
    val stripListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage,
    )
    val stripPageAspects = remember(ready.readingUnitId) { mutableStateMapOf<Int, Float>() }
    val stripPageReady = remember(ready.readingUnitId) { mutableStateMapOf<Int, Boolean>() }
    val spreadPages = remember(ready.readingUnitId) { mutableStateMapOf<Int, Boolean>() }
    val spreadSubSlots = remember(ready.readingUnitId) { mutableStateMapOf<Int, SpreadPageSubSlot>() }
    // The window's real size in px (split screen / freeform included), not the Configuration's screen dp.
    val containerSize = LocalWindowInfo.current.containerSize
    val decodeTarget = remember(containerSize) {
        ReaderPageDecodeTarget.pixels(widthPx = containerSize.width, heightPx = containerSize.height)
    }
    val resolvedImageLoader = imageLoader ?: SingletonImageLoader.get(context)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageSlotCount },
    )
    LaunchedEffect(transition != null, ready.readingUnitId, ready.initialOrdinal) {
        if (transition != null && pagerState.currentPage != initialPage) {
            pagerState.scrollToPage(initialPage)
        }
    }
    val stripCenterOrdinal: () -> Int = {
        val visiblePages = stripListState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val pageIdx = info.index - leadingSlotCount
            if (pageIdx in ready.pages.indices) {
                StripVisibleItem(pageOrdinal = pageIdx, offsetPx = info.offset, sizePx = info.size)
            } else {
                null
            }
        }
        ReaderWebtoonMath.centerLinePageOrdinal(
            visibleItems = visiblePages,
            viewportHeightPx = stripListState.layoutInfo.viewportSize.height,
            fallbackOrdinal = (pagerState.currentPage - leadingSlotCount).coerceIn(0, pageCount - 1),
        )
    }
    var previousStripMode by remember(ready.readingUnitId) { mutableStateOf(stripModeActive) }
    LaunchedEffect(stripModeActive) {
        if (stripModeActive != previousStripMode) {
            previousStripMode = stripModeActive
            if (stripModeActive) {
                val targetOrdinal = (pagerState.currentPage - leadingSlotCount).coerceIn(0, pageCount - 1)
                stripListState.scrollToItem(targetOrdinal + leadingSlotCount)
            } else {
                val centerOrdinal = stripCenterOrdinal()
                pagerState.scrollToPage(centerOrdinal + leadingSlotCount)
            }
        }
    }
    LaunchedEffect(stripModeActive, autoScrollSpeed, autoScrollTouchPaused, density.density) {
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
                density = density.density,
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
    LaunchedEffect(stripModeActive, stripListState, ready.pages) {
        if (!stripModeActive) return@LaunchedEffect
        snapshotFlow {
            val centerOrd = stripCenterOrdinal()
            val isReady = stripPageReady[centerOrd] == true
            centerOrd to isReady
        }
            .distinctUntilChanged()
            .collect { (centerOrd, isReady) ->
                if (isReady && centerOrd in ready.pages.indices) {
                    onSettledPage(ready.pages[centerOrd])
                }
            }
    }
    val scope = rememberCoroutineScope()
    var chromeVisible by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(ReaderPageZoom.FIT_SCALE) }
    var panTranslation by remember { mutableStateOf(ReaderPagePan.resetTranslation()) }
    // S4b/T-storage-overlay: which block owns [ReaderUiState.Ready.problem] is a pure
    // function of state we already have -- the settled ordinal plus the problem's own page
    // -- so no one has to write state from composition to work it out. Read inline, the
    // way every other settled-page read in this file does -- and via [currentPage], a
    // plain MutableState, because [PagerState.settledPage] is itself a derivedStateOf the
    // pager mutates outside composition when the page count changes mid-recomposition.
    val settledPage = ready.pages.getOrNull(pagerState.currentPage - leadingSlotCount)
    val settledPageOwnsProblem = settledPage != null &&
        ready.inPlaceProblemFor(settledPage) != null
    val problemBannerVisible = ready.problem != null && !settledPageOwnsProblem
    val latestZoomScale by rememberUpdatedState(zoomScale)
    val latestPanTranslation by rememberUpdatedState(panTranslation)
    fun moveByDirectedDelta(delta: Int) {
        if (stripModeActive) {
            val viewportH = stripListState.layoutInfo.viewportSize.height.takeIf { it > 0 } ?: 1200
            val scrollDelta = viewportH * 0.8f * delta
            scope.launch { stripListState.scrollBy(scrollDelta) }
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
            transition.previous?.let(onOpenChapter)
            return
        }
        if (transition != null && current == pageSlotCount - 1 && delta > 0) {
            transition.next?.let(onOpenChapter)
            return
        }
        val target = ReaderVolumePaging.nextPageIndex(
            current = current,
            delta = delta,
            pageCount = pagerState.pageCount,
        )
        if (target != current) {
            scope.launch { pagerState.animateScrollToPage(target) }
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
    val prefetchDisposables = remember { mutableListOf<Disposable>() }
    LaunchedEffect(pagerState, ready.pages, decodeTarget) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                zoomScale = ReaderPageZoom.FIT_SCALE
                panTranslation = ReaderPagePan.resetTranslation()
                prefetchDisposables.forEach(Disposable::dispose)
                prefetchDisposables.clear()
                val realSettled = settled - leadingSlotCount
                if (realSettled !in ready.pages.indices) return@collect
                val keep = ReaderPagePreload.keepOrdinals(realSettled, ready.pages.size)
                ready.pages.forEachIndexed { index, page ->
                    if (index !in keep) {
                        resolvedImageLoader.memoryCache?.remove(
                            pageImageMemoryCacheKey(page),
                        )
                    }
                }
                ReaderPagePreload.adjacentOrdinals(realSettled, ready.pages.size)
                    .forEach { ordinal ->
                        prefetchDisposables += resolvedImageLoader.enqueue(
                            buildPageImageRequest(
                                context,
                                ready.pages[ordinal],
                                memoryCacheEnabled = true,
                                targetWidthPx = decodeTarget?.first,
                                targetHeightPx = decodeTarget?.second,
                            ),
                        )
                    }
            }
    }
    DisposableEffect(Unit) {
        onDispose { prefetchDisposables.forEach(Disposable::dispose) }
    }

    val volumeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        volumeFocusRequester.requestFocus()
    }
    // Owner bug 2026-09-10: hardware volume paging is driven from the Activity
    // (ReaderVolumeKeyBus), not Compose focus — see the bus KDoc. Register a
    // pager-paging handler while this reader is composed and the setting is on.
    val latestRtl by rememberUpdatedState(effectiveRtlPaging)
    if (volumeKeyPagingEnabled) {
        DisposableEffect(Unit) {
            val handler: (VolumeKeyDirection) -> Unit = { direction ->
                val base = if (direction == VolumeKeyDirection.NEXT) {
                    ReaderVolumePaging.pageDeltaForVolumeDown()
                } else {
                    ReaderVolumePaging.pageDeltaForVolumeUp()
                }
                moveByDirectedDelta(ReaderVolumePaging.directedDelta(base, latestRtl))
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
                moveByDirectedDelta(
                    ReaderVolumePaging.directedDelta(baseDelta, effectiveRtlPaging),
                )
                true
            },
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
                    .testTag("reader-strip-list"),
            ) {
                items(
                    count = pageSlotCount,
                    key = { slotIndex -> "local-strip-slot-$slotIndex" },
                ) { pageIndex ->
                    if (transition != null && pageIndex == 0) {
                        VerticalStripChapterBanner(
                            text = transition.startText,
                            actionLabel = if (transition.previous != null) AppStrings.loadPreviousChapter else null,
                            testTag = "reader-transition-start",
                            onActivate = { transition.previous?.let(onOpenChapter) },
                        )
                        return@items
                    }
                    if (transition != null && pageIndex == pageSlotCount - 1) {
                        VerticalStripChapterBanner(
                            text = transition.endText,
                            actionLabel = if (transition.next != null) AppStrings.continueNextChapter else null,
                            testTag = "reader-transition-end",
                            onActivate = { transition.next?.let(onOpenChapter) },
                        )
                        return@items
                    }
                    val realPageIndex = pageIndex - leadingSlotCount
                    val page = ready.pages[realPageIndex]
                    val imageRequest = buildPageImageRequest(
                        context,
                        page,
                        memoryCacheEnabled = true,
                        targetWidthPx = decodeTarget?.first,
                        targetHeightPx = decodeTarget?.second,
                    )
                    val imageIdentity = imageRequest.data as PageImageRequest
                    var pageDisplayed by remember(imageIdentity) { mutableStateOf(false) }
                    var pageFailure by remember(imageIdentity) {
                        mutableStateOf<ReaderPageFailure?>(null)
                    }
                    var failureReported by remember(imageIdentity) { mutableStateOf(false) }
                    val painter = key(imageIdentity) {
                        rememberAsyncImagePainter(
                            model = imageRequest,
                            imageLoader = resolvedImageLoader,
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                    LaunchedEffect(painter, imageIdentity) {
                        painter.state.collect { state ->
                            when (state) {
                                is AsyncImagePainter.State.Success -> {
                                    if (state.result.request.data == imageIdentity) {
                                        pageDisplayed = true
                                        stripPageReady[realPageIndex] = true
                                        pageFailure = null
                                        failureReported = false
                                    }
                                }
                                is AsyncImagePainter.State.Error -> {
                                    if (state.result.request.data == imageIdentity) {
                                        pageDisplayed = false
                                        stripPageReady[realPageIndex] = false
                                        pageFailure = state.result.throwable.toReaderPageFailure()
                                        if (!failureReported) {
                                            failureReported = true
                                            onPageOpenFailed(page, pageFailure!!)
                                        }
                                    }
                                }
                                AsyncImagePainter.State.Empty,
                                is AsyncImagePainter.State.Loading,
                                -> Unit
                            }
                        }
                    }
                    val intrinsicSize = painter.intrinsicSize
                    LaunchedEffect(imageIdentity, intrinsicSize) {
                        if (intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
                            val aspect = intrinsicSize.height / intrinsicSize.width
                            stripPageAspects[realPageIndex] = aspect
                            spreadPages[realPageIndex] = ReaderPageZoom.isSpreadDimensions(
                                widthPx = intrinsicSize.width.toInt(),
                                heightPx = intrinsicSize.height.toInt(),
                            )
                        }
                    }
                    val pageAspect = stripPageAspects[realPageIndex]
                    val pageSpreadSubSlot = spreadSubSlots[realPageIndex] ?: SpreadPageSubSlot.FULL
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
                            .pointerInput(realPageIndex) {
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
                                contentDescription = AppStrings.pageOf(realPageIndex + 1, ready.pages.size)
                                this[ReaderPageZoom.SCALE_SEMANTICS_KEY] = zoomScale
                                this[ReaderPagePan.TRANSLATION_SEMANTICS_KEY] = panTranslation
                                onClick {
                                    chromeVisible = !chromeVisible
                                    true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painter,
                            contentDescription = null,
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
                                imageReady = pageDisplayed,
                                loadFailed = pageFailure != null,
                            ),
                        )
                    }
                }
            }
        } else {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 2,
            reverseLayout = effectiveRtlPaging,
            userScrollEnabled = ReaderPageZoom.pagerUserScrollEnabled(zoomScale),
        ) { pageIndex ->
            if (transition != null && pageIndex == 0) {
                ChapterTransitionCard(
                    text = transition.startText,
                    testTag = "reader-transition-start",
                    forward = false,
                    rtlPagingEnabled = effectiveRtlPaging,
                    onPass = { transition.previous?.let(onOpenChapter) },
                    onPrevious = goToPrevious,
                    onNext = goToNext,
                )
                return@HorizontalPager
            }
            if (transition != null && pageIndex == pageSlotCount - 1) {
                ChapterTransitionCard(
                    text = transition.endText,
                    testTag = "reader-transition-end",
                    forward = true,
                    rtlPagingEnabled = effectiveRtlPaging,
                    onPass = { transition.next?.let(onOpenChapter) },
                    onPrevious = goToPrevious,
                    onNext = goToNext,
                )
                return@HorizontalPager
            }
            val realPageIndex = pageIndex - leadingSlotCount
            val page = ready.pages[realPageIndex]
            val imageRequest = buildPageImageRequest(
                context,
                page,
                memoryCacheEnabled = true,
                targetWidthPx = decodeTarget?.first,
                targetHeightPx = decodeTarget?.second,
            )
            val imageIdentity = imageRequest.data as PageImageRequest
            var pageDisplayed by remember(imageIdentity) { mutableStateOf(false) }
            var pageFailure by remember(imageIdentity) {
                mutableStateOf<ReaderPageFailure?>(null)
            }
            var failureReported by remember(imageIdentity) { mutableStateOf(false) }
            LaunchedEffect(pagerState, imageIdentity) {
                snapshotFlow {
                    pagerState.settledPage - leadingSlotCount == realPageIndex && pageDisplayed
                }
                    .distinctUntilChanged()
                    .collect { isSettledAndDisplayed ->
                        if (isSettledAndDisplayed) {
                            onSettledPage(page)
                            // Keep the settled page in memory cache; outside-
                            // window eviction happens in the preload effect.
                        }
                    }
            }
            LaunchedEffect(pagerState, imageIdentity, pageFailure) {
                snapshotFlow {
                    ReaderPageFailureReporting.shouldReportOpenFailure(
                        realPageIndex,
                        pagerState.settledPage - leadingSlotCount,
                    ) && pageFailure != null
                }
                    .distinctUntilChanged()
                    .collect { shouldReport ->
                        val failure = pageFailure
                        if (shouldReport && failure != null && !failureReported) {
                            failureReported = true
                            onPageOpenFailed(page, failure)
                        }
                    }
            }
            var contentAspect by remember(imageIdentity) { mutableStateOf<Float?>(null) }
            // One painter per page image. Handing an EXISTING painter a new model (a new revision of this
            // ordinal) makes Coil set its input and restart() inside composition; with a warm memory cache
            // that load completes synchronously and writes the painter's own state from composition while
            // the loader writes it too -- "Unsupported concurrent change during composition" (5/6 runs of
            // ReaderScreenTest's revision swap on the emulator, 2026-09-27). A fresh painter starts its
            // load in onRemembered, after composition.
            val painter = key(imageIdentity) {
                rememberAsyncImagePainter(
                    model = imageRequest,
                    imageLoader = resolvedImageLoader,
                    contentScale = ReaderPageContentScale.forFitWidth(fitWidthEnabled),
                )
            }
            // A warm memory cache (or a tiny page on an idle device) lets Coil resolve the
            // request inside the very composition that remembers the painter, so writing
            // these states from onSuccess/onError wrote them FROM composition -- while the
            // next revision's load wrote the same objects from the loader. That is exactly
            // the "Unsupported concurrent change during composition" the emulator hit. The
            // painter's own state is the source of truth; this effect is its single
            // writer, and an effect body never runs during composition.
            LaunchedEffect(painter, imageIdentity) {
                painter.state.collect { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> {
                            // Ignore a terminal state left over from the previous
                            // revision: this painter instance outlives the model swap.
                            if (state.result.request.data == imageIdentity) {
                                pageDisplayed = true
                                pageFailure = null
                                failureReported = false
                            }
                        }
                        is AsyncImagePainter.State.Error -> {
                            if (state.result.request.data == imageIdentity) {
                                pageDisplayed = false
                                contentAspect = null
                                pageFailure = state.result.throwable.toReaderPageFailure()
                            }
                        }
                        AsyncImagePainter.State.Empty,
                        is AsyncImagePainter.State.Loading,
                        -> Unit
                    }
                }
            }
            val intrinsicSize = painter.intrinsicSize
            LaunchedEffect(imageIdentity, intrinsicSize) {
                val aspect = if (intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
                    intrinsicSize.height / intrinsicSize.width
                } else {
                    null
                }
                contentAspect = aspect
                if (aspect != null) {
                    stripPageAspects[realPageIndex] = aspect
                    spreadPages[realPageIndex] = ReaderPageZoom.isSpreadDimensions(
                        widthPx = intrinsicSize.width.toInt(),
                        heightPx = intrinsicSize.height.toInt(),
                    )
                    if (ReaderWebtoonMath.isTallStripAspect(aspect)) {
                        autoDetectedTallStrip = true
                    }
                }
            }
            val latestContentAspect by rememberUpdatedState(contentAspect)
            val latestFitWidthEnabled by rememberUpdatedState(fitWidthEnabled)
            val fitWidthLayoutAspect = contentAspect
            val pageSpreadSubSlot = spreadSubSlots[realPageIndex] ?: SpreadPageSubSlot.FULL
            val isSpreadPage = spreadPages[realPageIndex] == true
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .background(Color.Black)
                    .pointerInput(
                        pagerState.settledPage,
                        pageIndex,
                        fitWidthEnabled,
                        contentAspect,
                    ) {
                        if (pagerState.settledPage - leadingSlotCount == realPageIndex) {
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
                    }
                    .semantics {
                        contentDescription = AppStrings.pageOf(realPageIndex + 1, ready.pages.size)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (
                    ReaderPageTapZones.interactive(
                        realPageIndex,
                        pagerState.settledPage - leadingSlotCount,
                    )
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("reader-zone-previous")
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
                                .fillMaxHeight()
                                .testTag("reader-zone-center")
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
                                                spreadPages[realPageIndex] == true
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
                                .fillMaxHeight()
                                .testTag("reader-zone-next")
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = AppStrings.nextPage,
                                    onClick = goToNext,
                                ),
                        )
                    }
                }
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ReaderPageContentScale.forFitWidth(fitWidthEnabled),
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
                            val isSettled = pagerState.settledPage - leadingSlotCount == realPageIndex
                            val pageZoomScale = if (isSettled) {
                                zoomScale
                            } else {
                                ReaderPageZoom.FIT_SCALE
                            }
                            val (subScale, subOffset) = if (isSettled && pageZoomScale == ReaderPageZoom.FIT_SCALE) {
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
                            scaleX = pageZoomScale * subScale * rotScale
                            scaleY = pageZoomScale * subScale * rotScale
                            translationX = (if (isSettled) panTranslation.x else 0f) + subOffset.x
                            translationY = (if (isSettled) panTranslation.y else 0f) + subOffset.y
                            rotationZ = if (isSettled && pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90) 90f else 0f
                        },
                )
                if (
                    isSpreadPage &&
                    !chromeVisible &&
                    pagerState.settledPage - leadingSlotCount == realPageIndex
                ) {
                    SpreadPageFloatingBadge(
                        subSlot = pageSpreadSubSlot,
                        rtl = effectiveRtlPaging,
                        onCycleSplit = {
                            val (firstHalf, secondHalf) = ReaderPageZoom.spreadSplitOrder(effectiveRtlPaging)
                            spreadSubSlots[realPageIndex] = when (pageSpreadSubSlot) {
                                SpreadPageSubSlot.FULL, SpreadPageSubSlot.ROTATED_90 -> firstHalf
                                firstHalf -> secondHalf
                                secondHalf -> SpreadPageSubSlot.FULL
                                else -> SpreadPageSubSlot.FULL
                            }
                        },
                        onToggleRotate = {
                            spreadSubSlots[realPageIndex] = if (pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90) {
                                SpreadPageSubSlot.FULL
                            } else {
                                SpreadPageSubSlot.ROTATED_90
                            }
                        },
                    )
                }
                ReaderPageLoadOverlay(
                    ReaderPageLoadProgress.presentation(
                        imageReady = pageDisplayed,
                        loadFailed = pageFailure != null,
                    ),
                )
                // S4b/T-storage-overlay: this IS the recovery block for a
                // retryable-in-place failure (A30 acceptance: exactly one block, one
                // "Try again", one "Back to Library"). It states the reader-wide problem
                // when that problem is about this settled page -- the top banner steps
                // aside for exactly that case -- and otherwise whatever this page's own
                // load reported.
                val recoveryFailure = ready.inPlaceProblemFor(page)
                    ?: pageFailure?.takeIf { it.isRetryableInPlace() }
                if (
                    recoveryFailure != null &&
                    pagerState.settledPage - leadingSlotCount == realPageIndex
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        // A62 (spec D2/D1): the one broken-image glyph, above the message.
                        // Every image-failure surface (local corrupted page, online 429,
                        // failed cover) speaks this same shape.
                        BrokenImageGlyph(
                            size = 88.dp,
                            contentDescription = AppStrings.imageFailedToLoad,
                        )
                        Text(
                            ReaderGrantLossRecovery.userMessage(recoveryFailure),
                            color = Color.White.copy(alpha = 0.72f),
                        )
                        ReaderGrantLossRecovery.secondaryHint(recoveryFailure)?.let { hint ->
                            Text(hint, color = Color.White.copy(alpha = 0.72f))
                        }
                        Button(
                            onClick = {
                                pageDisplayed = false
                                pageFailure = null
                                failureReported = false
                                painter.restart()
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = AppStrings.retryPage },
                        ) {
                            Text(ReaderPageFailureCopy.retryLabel())
                        }
                        // Never two ways out in one tree: while the top banner is up it is
                        // describing a different failure, and it owns the exit.
                        if (!problemBannerVisible) {
                            BackToLibraryButton(onExitToLibrary)
                        }
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
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // S4b/T-storage-overlay: when the settled page's own retry block is already
            // stating this problem, that block IS the recovery block -- drawing this one
            // too would be the duplicate overlay the emulator caught.
            ready.problem?.takeIf { problemBannerVisible }?.let { problem ->
                Text(
                    text = ReaderGrantLossRecovery.userMessage(problem.failure),
                    color = Color.White,
                )
                ProblemRecoveryAction(
                    failure = problem.failure,
                    onExitToLibrary = onExitToLibrary,
                    onOpenFolders = onOpenSources,
                    contentColor = Color.White,
                )
            }
            if (ready.isIncomplete) {
                Text(
                    text = ReaderIncompleteCopy.title(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    // A36: a catalog download has no watched folder, so the
                    // rescan instruction would be one the user cannot carry
                    // out. Say what actually happened instead.
                    text = if (ready.isCatalogDownload) {
                        ReaderIncompleteCopy.downloadHint()
                    } else {
                        ReaderIncompleteCopy.hint()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
                if (!ready.isCatalogDownload) {
                    TextButton(
                        onClick = { onOpenSources?.invoke() },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(
                            text = ReaderIncompleteCopy.cta(),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
        if (chromeVisible) {
            val activeOrdinal = if (stripModeActive) {
                stripCenterOrdinal().coerceIn(0, (ready.pages.size - 1).coerceAtLeast(0))
            } else {
                (pagerState.currentPage - leadingSlotCount)
                    .coerceIn(0, (ready.pages.size - 1).coerceAtLeast(0))
            }
            val activeSubSlot = spreadSubSlots[activeOrdinal] ?: SpreadPageSubSlot.FULL
            ReaderSeekOverlay(
                currentPage0Based = activeOrdinal,
                pageCount = ready.pages.size,
                rtl = effectiveRtlPaging,
                onToggleRtl = toggleRtlPaging,
                chapterLabel = transition?.chapterLabel,
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
                        scope.launch { stripListState.scrollToItem(targetSlot) }
                    } else if (targetSlot != pagerState.currentPage) {
                        scope.launch { pagerState.scrollToPage(targetSlot) }
                    }
                },
            )
        }
    }
}

@Composable
private fun FailedReader(
    state: ReaderUiState.Failed,
    onBack: () -> Unit,
    onExitToLibrary: () -> Unit,
    onOpenFolders: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onBack,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = AppStrings.back },
        ) {
            Text(AppStrings.back)
        }
        Text(state.title)
        Text(ReaderGrantLossRecovery.userMessage(state.problem.failure))
        ProblemRecoveryAction(
            failure = state.problem.failure,
            onExitToLibrary = onExitToLibrary,
            onOpenFolders = onOpenFolders,
        )
    }
}

/**
 * A30 §2.2 / §2.4 (S4b/G2 + G8): the folder's grant went away while this comic was open.
 * One statement, one instruction, one way out -- and no claim about the files themselves.
 */
@Composable
private fun AccessLostReader(
    onExitToLibrary: () -> Unit,
    onOpenFolders: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = ReaderGrantLossRecovery.userMessage(ReaderPageFailure.REAUTHORIZE),
            color = Color.White,
            modifier = Modifier.testTag(READER_ACCESS_LOST_TAG),
        )
        ProblemRecoveryAction(
            failure = ReaderPageFailure.REAUTHORIZE,
            onExitToLibrary = onExitToLibrary,
            onOpenFolders = onOpenFolders,
            contentColor = Color.White,
        )
    }
}

@Composable
private fun ProblemRecoveryAction(
    failure: ReaderPageFailure,
    onExitToLibrary: () -> Unit,
    onOpenFolders: (() -> Unit)?,
    contentColor: Color = Color.Unspecified,
) {
    ReaderGrantLossRecovery.secondaryHint(failure)?.let { hint ->
        Text(text = hint, color = contentColor)
    }
    val openFolders = onOpenFolders.takeIf {
        ReaderGrantLossRecovery.usesFolderRecovery(failure)
    }
    if (openFolders != null) {
        // Single CTA: the Folders screen is where access is repaired (A30 §2.2).
        Button(
            onClick = openFolders,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = ReaderGrantLossRecovery.FOLDERS_LABEL },
        ) {
            Text(ReaderGrantLossRecovery.FOLDERS_LABEL)
        }
        return
    }
    BackToLibraryButton(onExitToLibrary)
}

/** Test tag for the access-lost statement (A30 §2.4 acceptance). */
const val READER_ACCESS_LOST_TAG = "reader-access-lost-line"

@Composable
private fun BackToLibraryButton(
    onExitToLibrary: () -> Unit,
    label: String = ReaderGrantLossRecovery.PRIMARY_LABEL,
) {
    Button(
        onClick = onExitToLibrary,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label },
    ) {
        Text(label)
    }
}

private fun Throwable.toReaderPageFailure(): ReaderPageFailure =
    when ((this as? PageOpenException)?.failure) {
        PageOpenFailure.REAUTHORIZE -> ReaderPageFailure.REAUTHORIZE
        PageOpenFailure.PROVIDER_UNAVAILABLE -> ReaderPageFailure.PROVIDER_UNAVAILABLE
        PageOpenFailure.CONTENT_GONE -> ReaderPageFailure.FILES_GONE
        PageOpenFailure.SOURCE_CHANGED,
        PageOpenFailure.STALE_PAGE,
        -> ReaderPageFailure.SOURCE_CHANGED
        PageOpenFailure.INSUFFICIENT_SPACE -> ReaderPageFailure.INSUFFICIENT_SPACE
        PageOpenFailure.UNSUPPORTED_PAGE -> ReaderPageFailure.UNSUPPORTED_PAGE
        null -> ReaderPageFailure.RENDER_FAILED
    }

/**
 * S4b/T-storage-overlay: the failure that [page]'s own recovery block must state, when the
 * reader-wide [ReaderUiState.Ready.problem] is about that very page and can be retried
 * where the page is. Pure, so the page block and the top-of-screen banner agree on which
 * of them owns a failure without either writing state from composition.
 */
private fun ReaderUiState.Ready.inPlaceProblemFor(page: PageRef): ReaderPageFailure? =
    problem
        ?.takeIf { it.failure.isRetryableInPlace() && it.page.sameContentIdentity(page) }
        ?.failure

private fun ReaderPageFailure.isRetryableInPlace(): Boolean =
    this == ReaderPageFailure.PROVIDER_UNAVAILABLE ||
        this == ReaderPageFailure.RENDER_FAILED ||
        // A62 (spec D2): a corrupted page's slot survived import; its bytes failed the
        // decode once. The Retry re-attempts that decode -- which can honestly succeed
        // after an app/codec update -- so the page-level block (glyph + Retry) owns this
        // failure and the reader-wide banner steps aside for exactly this page, like any
        // other in-place failure.
        this == ReaderPageFailure.UNSUPPORTED_PAGE
