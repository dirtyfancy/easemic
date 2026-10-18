package com.easemic.app.feature.browse

import com.easemic.app.i18n.AppStrings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.easemic.app.feature.reader.AutoScrollSpeed
import com.easemic.app.feature.reader.ReaderFirstPageCache
import com.easemic.app.feature.reader.ReaderLayoutMode
import com.easemic.app.feature.reader.ReaderPageLoadOverlay
import com.easemic.app.feature.reader.ReaderPageLoadProgress
import com.easemic.app.feature.reader.ReaderPagePan
import com.easemic.app.feature.reader.ReaderPageZoom
import com.easemic.app.feature.reader.ReaderSeekOverlay
import com.easemic.app.feature.reader.SpreadPageFloatingBadge
import com.easemic.app.feature.reader.SpreadPageSubSlot
import com.easemic.app.feature.reader.ReaderChapterBoundary
import com.easemic.app.feature.reader.ChapterTransitionCard
import com.easemic.app.feature.reader.ReaderVolumeKeyBus
import com.easemic.app.feature.reader.ReaderVolumePaging
import com.easemic.app.feature.reader.ReaderWebtoonMath
import com.easemic.app.feature.reader.StripVisibleItem
import com.easemic.app.feature.reader.VerticalStripAutoScrollFloatingPill
import com.easemic.app.feature.reader.VerticalStripChapterBanner
import com.easemic.app.feature.reader.VolumeKeyDirection
import com.easemic.app.feature.reader.clampReaderOrdinal
import com.easemic.app.feature.reader.readerInitialPagerPage
import com.easemic.app.feature.reader.keepScreenOn
import com.easemic.app.sync.CloudOpenTrace
import com.easemic.app.sync.DiagLog
import com.easemic.app.ui.components.BrokenImageGlyph
import com.easemic.app.ui.theme.quietShelfPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

fun interface CloudPageFileLoader {
    /** A readable file holding this page's bytes. The caller does not keep those bytes. */
    fun load(ordinal: Int): File
}

data class CloudReaderSession(
    val title: String,
    val pageCount: Int,
    val cacheKey: String,
    val loader: CloudPageFileLoader,
    val focusedLoader: CloudPageFileLoader? = null,
    val focusedMode: String = "WINDOW",
    val initialOrdinal: Int = 0,
    val batchSize: Int = CloudCollectionPageWindow.DEFAULT_BATCH_SIZE,
    /**
     * C34-d: when the session was constructed, i.e. the moment the reader had
     * everything it needed to start fetching. The `first-page` phase is
     * measured from here, so it reports what the owner actually waits for
     * after the open resolves.
     */
    val createdAtMs: Long = System.currentTimeMillis(),
    /** #D17: the opening page, already loading when the session was built ([withOpeningPageStarted]); null in tests. */
    val openingPage: CloudOpeningPage? = null,
    /** Null for gallery sessions; serialized chapters use this to enable transitions. */
    val chapterContext: com.easemic.app.feature.reader.ReaderChapterContext? = null,
    /** Whether this serialized chapter already had persisted remote progress when opened. */
    val hasStoredProgress: Boolean = false,
    val transition: CloudReaderTransitionPresentation? = null,
    val onChapterBoundary: ((ReaderChapterBoundary) -> Unit)? = null,
)

data class CloudReaderTransitionPresentation(
    val startText: String,
    val endText: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

/**
 * Reader that loads pages from the user's R2 bucket (plain GET) — plaintext
 * by default; an opted-in library's pages are sealed at rest and unsealed
 * transparently by CloudLibraryGate/PcloCodec before reaching this screen.
 * Prefetches a batch window around the settled page. Does not use upstream CDN.
 */
@Composable
fun CloudReaderScreen(
    session: CloudReaderSession,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    /**
     * A42 remainder: called when the page the user is actually looking at
     * settles, with the same cadence the LOCAL reader persists with
     * (ReaderScreen's `settledPage == page && pageDisplayed` flow — no extra
     * debounce). Default no-op; the host binds a
     * [RemoteReadingProgressRecorder].
     */
    onProgressSettled: (ordinal: Int, pageCount: Int) -> Unit = { _, _ -> },
    /** #D6: the Settings "Volume keys" switch — this reader never received it, so the keys did nothing here. */
    volumeKeyPagingEnabled: Boolean = true,
    /** #D6: "Manga (RTL)" mirrors the tap zones and the volume keys, same rule as the local and online readers. */
    rtlPagingEnabled: Boolean = false,
    /** Settings → Reading → Keep screen on. */
    keepScreenOnEnabled: Boolean = true,
    /** #D16: shown under a page the cloud refused for the sign-in; null keeps the generic line. */
    signInRejectedLine: String? = null,
    /** Optional explicit chapter context; gallery callers leave this null. */
    chapterContext: com.easemic.app.feature.reader.ReaderChapterContext? = session.chapterContext,
    /** Called when a chapter reader passes its start or end card. */
    onChapterBoundary: ((ReaderChapterBoundary) -> Unit)? = session.onChapterBoundary,
    /** #E114: Reader layout mode (AUTO / VERTICAL_STRIP / PAGED). */
    layoutMode: ReaderLayoutMode = ReaderLayoutMode.AUTO,
    onLayoutModeChange: ((ReaderLayoutMode) -> Unit)? = null,
    onRtlPagingChange: ((Boolean) -> Unit)? = null,
) {
    val palette = quietShelfPalette()
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val pageCount = session.pageCount.coerceAtLeast(1)
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
    var autoDetectedTallStrip by remember(session.cacheKey) { mutableStateOf(false) }
    val stripModeActive = ReaderWebtoonMath.shouldUseVerticalStrip(
        mode = effectiveLayoutMode,
        autoDetectedTallStrip = autoDetectedTallStrip,
    )
    var autoScrollSpeed by remember(session.cacheKey, stripModeActive) { mutableStateOf(AutoScrollSpeed.OFF) }
    var autoScrollTouchPaused by remember(session.cacheKey, stripModeActive) { mutableStateOf(false) }
    val leadingSlotCount = if (session.transition == null) 0 else 1
    val pageSlotCount = pageCount + if (session.transition == null) 0 else 2
    val initialSlot = readerInitialPagerPage(
        initialOrdinal = session.initialOrdinal,
        pageCount = pageCount,
        leadingSlotCount = leadingSlotCount,
    )
    val stripListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialSlot,
    )
    val stripPageAspects = remember(session.cacheKey) { mutableStateMapOf<Int, Float>() }
    val stripPageReady = remember(session.cacheKey) { mutableStateMapOf<Int, Boolean>() }
    val spreadPages = remember(session.cacheKey) { mutableStateMapOf<Int, Boolean>() }
    val spreadSubSlots = remember(session.cacheKey) { mutableStateMapOf<Int, SpreadPageSubSlot>() }
    val pagerState = rememberPagerState(
        initialPage = initialSlot,
        pageCount = { pageSlotCount },
    )
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
    LaunchedEffect(session.cacheKey, session.initialOrdinal, session.transition) {
        val target = readerInitialPagerPage(
            initialOrdinal = session.initialOrdinal,
            pageCount = pageCount,
            leadingSlotCount = leadingSlotCount,
        )
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }
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
            fallbackOrdinal = (pagerState.currentPage - leadingSlotCount).coerceIn(0, pageCount - 1),
        )
    }
    var previousStripMode by remember(session.cacheKey) { mutableStateOf(stripModeActive) }
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
    val pageCache = remember(session.cacheKey) {
        mutableStateMapOf<Int, File>().apply {
            val initOrd = clampReaderOrdinal(session.initialOrdinal, pageCount)
            val cachedPath = ReaderFirstPageCache.shared.resolve("cloud:${session.cacheKey}", initOrd)
            if (cachedPath != null) {
                val cachedFile = File(cachedPath)
                if (cachedFile.isFile && cachedFile.length() > 0L) {
                    put(initOrd, cachedFile)
                }
            }
        }
    }
    val failedPages = remember(session.cacheKey) { mutableStateMapOf<Int, RemotePageFailure?>() }
    var prefetchEpoch by remember(session.cacheKey) { mutableIntStateOf(0) }
    // C42: match the local reader's seed guard (ReaderViewModel seeds
    // lastSettledPage to the opening ordinal). The page the reader OPENS on —
    // whether a resume point or a preview-cell deep link like page 1 — must not
    // overwrite stored progress on its landing settle; only a page the user
    // actually navigates to records. Seeded to the opened ordinal, advanced by
    // the first real settle thereafter.
    var lastRecordedOrdinal by remember(session.cacheKey) {
        mutableIntStateOf(
            if (serializedReaderLandingShouldRecord(chapterContext, session.hasStoredProgress)) {
                -1
            } else {
                clampReaderOrdinal(session.initialOrdinal, pageCount)
            },
        )
    }
    var chromeVisible by remember { mutableStateOf(false) }
    // C34-d: one `first-page` line per opened session — the page the reader
    // LANDED on, not every later settle. Reset with the session's cache key.
    val firstPageTraced = remember(session.cacheKey) { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    // #C45: ONE prefetch pool per reader session. A swipe re-prioritises it
    // (focus); nothing in flight is cancelled or fetched twice. The pool is
    // closed when this session leaves the composition.
    val prefetcher = remember(session.cacheKey) {
        CloudReaderPrefetcher(
            scope = scope,
            load = { session.loader.load(it) },
            loadSettled = session.focusedLoader?.let { focused -> { ordinal -> focused.load(ordinal) } },
            onLoaded = { ordinal, file ->
                if (ordinal == (pagerState.settledPage - leadingSlotCount).coerceIn(0, pageCount - 1) &&
                    firstPageTraced.compareAndSet(false, true)
                ) {
                    check(session.focusedMode == "EXACT" || session.focusedMode == "WINDOW")
                    CloudOpenTrace.log(
                        phase = "first-page",
                        ms = System.currentTimeMillis() - session.createdAtMs,
                        extras = session.firstPageTraceExtras(ordinal),
                    )
                }
                withContext(Dispatchers.Main) {
                    pageCache[ordinal] = file
                    failedPages.remove(ordinal)
                }
            },
            onFailed = { ordinal, error ->
                // #C58: a page the reader could not load was invisible in the
                // diagnostics; record the cause (DiagLog scrubs the message).
                DiagLog.w("CloudOpen", "reader page failed ordinal=$ordinal", error)
                withContext(Dispatchers.Main) {
                    // A62 (spec D3): only the page the user is looking at is
                    // classified; read the CURRENT settled page — the pool
                    // outlives the settle that admitted this ordinal.
                    // #D16: classifyCloudPageFailure names a refused sign-in
                    // its own kind; the line and the Retry come from it.
                    if (ordinal == (pagerState.settledPage - leadingSlotCount).coerceIn(0, pageCount - 1)) {
                        failedPages[ordinal] = classifyCloudPageFailure(error)
                    }
                }
            },
            // #C68: one bounded line per automatic retry — ordinal, attempt
            // number and delay only, no URL/key/message — so a transient
            // hiccup that the pool absorbed on its own is still visible in
            // diagnostics without ever reaching the reader as a failure.
            onRetryScheduled = { ordinal, attempt, delayMs ->
                DiagLog.i("CloudOpen", "reader page retry ordinal=$ordinal attempt=$attempt delayMs=$delayMs")
            },
        )
    }
    DisposableEffect(session.cacheKey) {
        onDispose { prefetcher.close() }
    }
    val latestOnProgressSettledGlobal by rememberUpdatedState(onProgressSettled)
    LaunchedEffect(stripModeActive, stripListState, session.cacheKey) {
        if (!stripModeActive) return@LaunchedEffect
        snapshotFlow {
            val centerOrd = stripCenterOrdinal()
            val ready = stripPageReady[centerOrd] == true
            centerOrd to ready
        }
            .distinctUntilChanged()
            .collect { (centerOrd, ready) ->
                if (ready && centerOrd != lastRecordedOrdinal) {
                    latestOnProgressSettledGlobal(centerOrd, pageCount)
                    lastRecordedOrdinal = centerOrd
                }
            }
    }
    LaunchedEffect(stripModeActive, stripListState, session.cacheKey, prefetchEpoch) {
        if (!stripModeActive) return@LaunchedEffect
        snapshotFlow { stripCenterOrdinal() }
            .distinctUntilChanged()
            .collect { centerOrd ->
                val wanted = CloudCollectionPageWindow.ordinals(
                    settled = centerOrd,
                    pageCount = pageCount,
                    batchSize = session.batchSize,
                )
                pageCache.keys.retainAll(wanted)
                prefetcher.focus(
                    settled = centerOrd,
                    wanted = wanted,
                )
            }
    }
    val pageBy: (Int) -> Unit = pageBy@{ baseDelta ->
        if (stripModeActive) {
            val viewportH = stripListState.layoutInfo.viewportSize.height.takeIf { it > 0 } ?: 1200
            val scrollDelta = viewportH * 0.8f * baseDelta
            scope.launch { stripListState.scrollBy(scrollDelta) }
            return@pageBy
        }
        val current = pagerState.settledPage
        val pageDelta = ReaderVolumePaging.directedDelta(baseDelta, effectiveRtlPaging)
        val realCurrent = current - leadingSlotCount
        val currentSubSlot = spreadSubSlots[realCurrent] ?: SpreadPageSubSlot.FULL
        if (currentSubSlot == SpreadPageSubSlot.LEFT_HALF || currentSubSlot == SpreadPageSubSlot.RIGHT_HALF) {
            val (firstHalf, secondHalf) = ReaderPageZoom.spreadSplitOrder(effectiveRtlPaging)
            if (pageDelta > 0 && currentSubSlot == firstHalf) {
                spreadSubSlots[realCurrent] = secondHalf
                return@pageBy
            }
            if (pageDelta < 0 && currentSubSlot == secondHalf) {
                spreadSubSlots[realCurrent] = firstHalf
                return@pageBy
            }
            if (pageDelta > 0 && currentSubSlot == secondHalf) {
                spreadSubSlots[realCurrent] = SpreadPageSubSlot.FULL
            } else if (pageDelta < 0 && currentSubSlot == firstHalf) {
                spreadSubSlots[realCurrent] = SpreadPageSubSlot.FULL
            }
        }
        if (current == 0 && pageDelta < 0 && chapterContext != null) {
            if (session.transition?.hasPrevious != false) onChapterBoundary?.invoke(ReaderChapterBoundary.START)
            return@pageBy
        }
        if (current == pageSlotCount - 1 && pageDelta > 0 && chapterContext != null) {
            if (session.transition?.hasNext != false) onChapterBoundary?.invoke(ReaderChapterBoundary.END)
            return@pageBy
        }
        val target = ReaderVolumePaging.nextPageIndex(
            current = current,
            delta = pageDelta,
            pageCount = pageSlotCount,
        )
        if (target != current) {
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }
    val latestPageBy by rememberUpdatedState(pageBy)
    if (volumeKeyPagingEnabled) {
        // Volume keys arrive through the Activity (ReaderVolumeKeyBus), not Compose focus — see the bus KDoc.
        DisposableEffect(Unit) {
            val handler: (VolumeKeyDirection) -> Unit = { direction ->
                latestPageBy(
                    if (direction == VolumeKeyDirection.NEXT) {
                        ReaderVolumePaging.pageDeltaForVolumeDown()
                    } else {
                        ReaderVolumePaging.pageDeltaForVolumeUp()
                    },
                )
            }
            ReaderVolumeKeyBus.register(handler)
            onDispose { ReaderVolumeKeyBus.unregister(handler) }
        }
    }
    LaunchedEffect(session.cacheKey, pagerState.settledPage, prefetchEpoch) {
        val settledOrdinal = (pagerState.settledPage - leadingSlotCount)
            .coerceIn(0, pageCount - 1)
        val wanted = CloudCollectionPageWindow.ordinals(
            settled = settledOrdinal,
            pageCount = pageCount,
            batchSize = session.batchSize,
        )
        // #C45 follow-up (finding 2): page files are held for the window only (≤ 24
        // pages), not for every page the session ever showed. The pool forgets the same
        // pages, so a revisit loads them again from the page disk cache. A load already
        // in flight when the window moves still lands (≤ lanes + 1 stray pages) and is
        // trimmed by the next settle. #E27: the map holds files, never the page bytes.
        pageCache.keys.retainAll(wanted)
        prefetcher.focus(
            settled = settledOrdinal,
            wanted = wanted,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .keepScreenOn(keepScreenOnEnabled)
            .testTag("cloud-reader"),
    ) {
        // Test probe (#D6 device test): which page has settled — the pager's own state, no layout involved.
        Box(modifier = Modifier.testTag("cloud-reader-settled-${pagerState.settledPage}"))
        // #E27: how many window pages are loaded. The pager only composes the visible pages.
        Box(modifier = Modifier.testTag("cloud-reader-window-loaded-${pageCache.size}"))
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
                    .testTag("cloud-reader-strip-list"),
            ) {
                items(
                    count = pageSlotCount,
                    key = { slotIndex -> "cloud-strip-slot-$slotIndex" },
                ) { page ->
                    if (session.transition != null && page == 0) {
                        VerticalStripChapterBanner(
                            text = session.transition.startText,
                            actionLabel = if (session.transition.hasPrevious) AppStrings.loadPreviousChapter else null,
                            testTag = "cloud-reader-transition-start",
                            onActivate = {
                                if (session.transition.hasPrevious) {
                                    onChapterBoundary?.invoke(ReaderChapterBoundary.START)
                                }
                            },
                        )
                        return@items
                    }
                    if (session.transition != null && page == pageSlotCount - 1) {
                        VerticalStripChapterBanner(
                            text = session.transition.endText,
                            actionLabel = if (session.transition.hasNext) AppStrings.continueNextChapter else null,
                            testTag = "cloud-reader-transition-end",
                            onActivate = {
                                if (session.transition.hasNext) {
                                    onChapterBoundary?.invoke(ReaderChapterBoundary.END)
                                }
                            },
                        )
                        return@items
                    }
                    val realPage = page - leadingSlotCount
                    val file = pageCache[realPage]
                    val loadFailure = failedPages[realPage]
                    val pageAspect = stripPageAspects[realPage]
                    val pageSpreadSubSlot = spreadSubSlots[realPage] ?: SpreadPageSubSlot.FULL
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
                            .pointerInput(realPage) {
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
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loadFailure != null && file == null -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .padding(24.dp)
                                    .testTag("cloud-reader-page-load-failure"),
                            ) {
                                BrokenImageGlyph(
                                    size = 88.dp,
                                    contentDescription = AppStrings.imageFailedToLoad,
                                    statusCode = loadFailure.statusDigits(),
                                )
                                val refused = loadFailure.kind == RemotePageFailure.Kind.SIGN_IN_REJECTED
                                Text(
                                    if (refused) signInRejectedLine ?: AppStrings.pageFailedTitle else AppStrings.pageFailedTitle,
                                    color = palette.muted,
                                    modifier = Modifier.testTag("cloud-reader-page-failure-text"),
                                )
                                if (!refused) {
                                    OutlinedButton(
                                        onClick = {
                                            failedPages.remove(realPage)
                                            pageCache.remove(realPage)
                                            prefetcher.retry(realPage)
                                            prefetchEpoch += 1
                                        },
                                        modifier = Modifier.testTag("cloud-reader-page-retry"),
                                    ) {
                                        Text(AppStrings.retry)
                                    }
                                }
                            }
                            file == null -> ReaderPageLoadOverlay(
                                ReaderPageLoadProgress.presentation(
                                    imageReady = false,
                                    loadFailed = false,
                                ),
                            )
                            else -> {
                                var pageReady by remember(session.cacheKey, file, realPage, prefetchEpoch) {
                                    mutableStateOf(false)
                                }
                                val missingFileRetried = remember(session.cacheKey, file, realPage) { AtomicBoolean(false) }
                                val painter = rememberAsyncImagePainter(
                                    model = ImageRequest.Builder(context)
                                        .data(file)
                                        .memoryCacheKey("cloud-${session.cacheKey}-$realPage-$prefetchEpoch")
                                        .build(),
                                    onSuccess = {
                                        pageReady = true
                                        stripPageReady[realPage] = true
                                        ReaderFirstPageCache.shared.record(
                                            "cloud:${session.cacheKey}",
                                            realPage,
                                            file.absolutePath,
                                        )
                                    },
                                    onError = {
                                        pageReady = false
                                        stripPageReady[realPage] = false
                                        if (!file.exists() && missingFileRetried.compareAndSet(false, true)) {
                                            failedPages.remove(realPage)
                                            pageCache.remove(realPage)
                                            prefetcher.retry(realPage)
                                            prefetchEpoch += 1
                                        }
                                    },
                                    contentScale = ContentScale.FillWidth,
                                )
                                val intrinsicSize = painter.intrinsicSize
                                LaunchedEffect(file, realPage, prefetchEpoch, intrinsicSize) {
                                    if (intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
                                        val aspect = intrinsicSize.height / intrinsicSize.width
                                        stripPageAspects[realPage] = aspect
                                        spreadPages[realPage] = ReaderPageZoom.isSpreadDimensions(
                                            widthPx = intrinsicSize.width.toInt(),
                                            heightPx = intrinsicSize.height.toInt(),
                                        )
                                    }
                                }
                                Image(
                                    painter = painter,
                                    contentDescription = AppStrings.pageNumber(realPage + 1),
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
                                        }
                                        .testTag("cloud-reader-page-$realPage"),
                                )
                                ReaderPageLoadOverlay(
                                    ReaderPageLoadProgress.presentation(
                                        imageReady = pageReady,
                                        loadFailed = false,
                                    ),
                                    modifier = Modifier.testTag("cloud-reader-page-loading-$realPage"),
                                )
                            }
                        }
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
        ) { page ->
            if (session.transition != null && page == 0) {
                ChapterTransitionCard(
                    text = session.transition.startText,
                    testTag = "cloud-reader-transition-start",
                    forward = false,
                    rtlPagingEnabled = effectiveRtlPaging,
                    onPass = { if (session.transition.hasPrevious) onChapterBoundary?.invoke(ReaderChapterBoundary.START) },
                    onPrevious = { pageBy(ReaderVolumePaging.pageDeltaForVolumeUp()) },
                    onNext = { pageBy(ReaderVolumePaging.pageDeltaForVolumeDown()) },
                )
                return@HorizontalPager
            }
            if (session.transition != null && page == pageSlotCount - 1) {
                ChapterTransitionCard(
                    text = session.transition.endText,
                    testTag = "cloud-reader-transition-end",
                    forward = true,
                    rtlPagingEnabled = effectiveRtlPaging,
                    onPass = { if (session.transition.hasNext) onChapterBoundary?.invoke(ReaderChapterBoundary.END) },
                    onPrevious = { pageBy(ReaderVolumePaging.pageDeltaForVolumeUp()) },
                    onNext = { pageBy(ReaderVolumePaging.pageDeltaForVolumeDown()) },
                )
                return@HorizontalPager
            }
            val realPage = page - leadingSlotCount
            val file = pageCache[realPage]
            val loadFailure = failedPages[realPage]
            val contentAspect = stripPageAspects[realPage]
            val latestContentAspect by rememberUpdatedState(contentAspect)
            val pageSpreadSubSlot = spreadSubSlots[realPage] ?: SpreadPageSubSlot.FULL
            val isSpreadPage = spreadPages[realPage] == true
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .background(Color.Black)
                    .pointerInput(
                        pagerState.settledPage,
                        realPage,
                        contentAspect,
                    ) {
                        if (pagerState.settledPage - leadingSlotCount == realPage) {
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
                                        fitWidth = false,
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
                                            fitWidth = false,
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
                // #C68 addendum: the tap zones are emitted BEFORE the page
                // content below, not after. Compose hit-tests top-down, so
                // with the zones on top (their prior position, after the
                // content) they won every tap everywhere on the page — including
                // on the failure column's Retry button, which sat underneath
                // and clickable but unreachable. With the zones emitted FIRST
                // here, the content drawn on top of them (Retry, the page
                // Image) takes its own taps, and every non-interactive pixel
                // elsewhere (the image, the load overlays) lets the tap fall
                // through to the zone beneath it — the page-turn and
                // chrome-toggle behaviour elsewhere on the page is unchanged.
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .testTag("cloud-reader-zone-previous")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                pageBy(ReaderVolumePaging.pageDeltaForVolumeUp())
                            },
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .testTag("cloud-reader-zone-center")
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
                                            spreadPages[realPage] == true
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
                                                fitWidth = false,
                                                contentAspectHeightOverWidth = spreadAspect,
                                            )
                                        } else {
                                            ReaderPagePan.clampTranslation(
                                                translation = panTranslation,
                                                viewportWidth = size.width.toFloat(),
                                                viewportHeight = size.height.toFloat(),
                                                scale = nextScale,
                                                fitWidth = false,
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
                            .testTag("cloud-reader-zone-next")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                pageBy(ReaderVolumePaging.pageDeltaForVolumeDown())
                            },
                    )
                }
                when {
                    loadFailure != null && file == null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .padding(24.dp)
                            .testTag("cloud-reader-page-load-failure"),
                    ) {
                        // A62 (spec D3): glyph + status digits ("429") for a
                        // CloudHttpException "CLOUD_HTTP_<code>" load failure.
                        BrokenImageGlyph(
                            size = 88.dp,
                            contentDescription = AppStrings.imageFailedToLoad,
                            statusCode = loadFailure.statusDigits(),
                        )
                        val refused = loadFailure.kind == RemotePageFailure.Kind.SIGN_IN_REJECTED
                        Text(
                            // #D16: a refused sign-in says why (and offers no Retry — this
                            // reader still holds the old password, so it cannot work).
                            if (refused) signInRejectedLine ?: AppStrings.pageFailedTitle else AppStrings.pageFailedTitle,
                            color = palette.muted,
                            modifier = Modifier.testTag("cloud-reader-page-failure-text"),
                        )
                        if (!refused) {
                            OutlinedButton(
                                onClick = {
                                    failedPages.remove(realPage)
                                    pageCache.remove(realPage)
                                    // retry() un-lands the ordinal and heads the pending queue;
                                    // the epoch bump re-runs focus(), whose distance order
                                    // supersedes that head placement — harmless, the page is
                                    // still inside `wanted` (the failure column renders on every
                                    // composed page, not only the settled one). retry() stays:
                                    // it is the user's intent stated to the pool. The bump also
                                    // refreshes the painter's memory-cache key.
                                    prefetcher.retry(realPage)
                                    prefetchEpoch += 1
                                },
                                modifier = Modifier.testTag("cloud-reader-page-retry"),
                            ) {
                                Text(AppStrings.retry)
                            }
                        }
                    }
                    file == null -> ReaderPageLoadOverlay(
                        ReaderPageLoadProgress.presentation(
                            imageReady = false,
                            loadFailed = false,
                        ),
                    )
                    else -> {
                        var pageReady by remember(session.cacheKey, file, realPage, prefetchEpoch) {
                            mutableStateOf(false)
                        }
                        // One reload when the cache budget deletes a file the window still points at.
                        val missingFileRetried = remember(session.cacheKey, file, realPage) { AtomicBoolean(false) }
                        // A42 remainder: persist the settled position with the
                        // SAME cadence the local reader uses
                        // (`settledPage == page && pageDisplayed`, no extra
                        // debounce). pageReady is this reader's pageDisplayed.
                        val latestOnProgressSettled by rememberUpdatedState(onProgressSettled)
                        LaunchedEffect(session.cacheKey, pagerState, file, realPage, prefetchEpoch) {
                            snapshotFlow { pagerState.settledPage - leadingSlotCount == realPage && pageReady }
                                .distinctUntilChanged()
                                .collect { isSettledAndDisplayed ->
                                    if (isSettledAndDisplayed && realPage != lastRecordedOrdinal) {
                                        latestOnProgressSettled(realPage, pageCount)
                                        lastRecordedOrdinal = realPage
                                    }
                                }
                        }
                        val painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(context)
                                .data(file)
                                .memoryCacheKey("cloud-${session.cacheKey}-$realPage-$prefetchEpoch")
                                .build(),
                            onSuccess = {
                                pageReady = true
                                stripPageReady[realPage] = true
                                ReaderFirstPageCache.shared.record(
                                    "cloud:${session.cacheKey}",
                                    realPage,
                                    file.absolutePath,
                                )
                            },
                            onError = {
                                pageReady = false
                                stripPageReady[realPage] = false
                                if (!file.exists() && missingFileRetried.compareAndSet(false, true)) {
                                    failedPages.remove(realPage)
                                    pageCache.remove(realPage)
                                    prefetcher.retry(realPage)
                                    prefetchEpoch += 1
                                }
                            },
                            contentScale = ContentScale.Fit,
                        )
                        val intrinsicSize = painter.intrinsicSize
                        LaunchedEffect(file, realPage, prefetchEpoch, intrinsicSize) {
                            if (intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
                                val aspect = intrinsicSize.height / intrinsicSize.width
                                stripPageAspects[realPage] = aspect
                                spreadPages[realPage] = ReaderPageZoom.isSpreadDimensions(
                                    widthPx = intrinsicSize.width.toInt(),
                                    heightPx = intrinsicSize.height.toInt(),
                                )
                                if (ReaderWebtoonMath.isTallStripAspect(aspect)) {
                                    autoDetectedTallStrip = true
                                }
                            }
                        }
                        Image(
                            painter = painter,
                            contentDescription = AppStrings.pageNumber(realPage + 1),
                            contentScale = ContentScale.Fit,
                            // #C45 probe hook: present once this page's file is in
                            // pageCache (the moment the reader stops waiting on it).
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isSettled = pagerState.settledPage - leadingSlotCount == realPage
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
                                }
                                .testTag("cloud-reader-page-$realPage"),
                        )
                        ReaderPageLoadOverlay(
                            ReaderPageLoadProgress.presentation(
                                imageReady = pageReady,
                                loadFailed = false,
                            ),
                            // Present only while this page's image is on screen and not yet decoded.
                            // ReaderPageLoadOverlay returns before applying this modifier once the page is ready.
                            modifier = Modifier.testTag("cloud-reader-page-loading-$realPage"),
                        )
                        if (
                            isSpreadPage &&
                            !chromeVisible &&
                            pagerState.settledPage - leadingSlotCount == realPage
                        ) {
                            SpreadPageFloatingBadge(
                                subSlot = pageSpreadSubSlot,
                                rtl = effectiveRtlPaging,
                                onCycleSplit = {
                                    val (firstHalf, secondHalf) = ReaderPageZoom.spreadSplitOrder(effectiveRtlPaging)
                                    spreadSubSlots[realPage] = when (pageSpreadSubSlot) {
                                        SpreadPageSubSlot.FULL, SpreadPageSubSlot.ROTATED_90 -> firstHalf
                                        firstHalf -> secondHalf
                                        secondHalf -> SpreadPageSubSlot.FULL
                                        else -> SpreadPageSubSlot.FULL
                                    }
                                },
                                onToggleRotate = {
                                    spreadSubSlots[realPage] = if (pageSpreadSubSlot == SpreadPageSubSlot.ROTATED_90) {
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
                    val targetPage = target + leadingSlotCount
                    if (stripModeActive) {
                        scope.launch { stripListState.scrollToItem(targetPage) }
                    } else if (targetPage != pagerState.currentPage) {
                        scope.launch { pagerState.scrollToPage(targetPage) }
                    }
                },
            )
        }
    }
}
