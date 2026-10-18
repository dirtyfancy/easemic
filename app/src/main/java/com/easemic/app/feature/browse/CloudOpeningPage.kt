package com.easemic.app.feature.browse

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #D17: the reader's opening page, requested the moment the session is prepared. The request used to wait for the
 * reader's first composition — 38–273 ms after the Read tap on the emulator, with the network idle meanwhile.
 *
 * Single use: the first [take] of [ordinal] returns this load's file (waiting for it when still in flight) or throws
 * its failure once; every later call, and any other ordinal, returns null and the caller loads as usual.
 */
class CloudOpeningPage private constructor(val ordinal: Int, private val result: CompletableFuture<File>) {
    private val taken = AtomicBoolean(false)

    /** Whether the reader got this page's file from the early start (the `first-page` trace's `early=`). */
    @Volatile
    var handedOver: Boolean = false
        private set

    fun take(ordinal: Int): File? {
        if (ordinal != this.ordinal || !taken.compareAndSet(false, true)) return null
        val file = try {
            result.join()
        } catch (failed: CompletionException) {
            throw failed.cause ?: failed
        }
        handedOver = true
        return file
    }

    companion object {
        fun start(ordinal: Int, executor: Executor, load: CloudPageFileLoader): CloudOpeningPage =
            CloudOpeningPage(ordinal, CompletableFuture.supplyAsync({ load.load(ordinal) }, executor))
    }
}

/**
 * #D17: this session with its opening page already loading on [executor], through the loader the prefetcher uses for
 * the settled page (the focused loader when there is one). Both loaders hand the result to the first load of
 * [CloudReaderSession.initialOrdinal], so the page is downloaded once.
 */
fun CloudReaderSession.withOpeningPageStarted(executor: Executor): CloudReaderSession {
    val window = loader
    val focused = focusedLoader
    val opening = CloudOpeningPage.start(initialOrdinal, executor, focused ?: window)
    return copy(
        loader = CloudPageFileLoader { ordinal -> opening.take(ordinal) ?: window.load(ordinal) },
        focusedLoader = focused?.let { f -> CloudPageFileLoader { ordinal -> opening.take(ordinal) ?: f.load(ordinal) } },
        openingPage = opening,
    )
}

/** #D17: the `first-page` trace's fields; `early=1` when [ordinal]'s file came from the early start. */
fun CloudReaderSession.firstPageTraceExtras(ordinal: Int): String {
    val early = openingPage?.let { it.ordinal == ordinal && it.handedOver } == true
    return "ordinal=$ordinal mode=$focusedMode early=${if (early) 1 else 0}"
}
