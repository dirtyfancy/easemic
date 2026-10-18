package com.easemic.app.sync

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #C45 D4: a 256 KB ring file for the CloudOpen / DownloadPump / CloudSync
 * lines, so a real-world session survives logcat's rollover
 * (`adb shell run-as <pkg> cat files/diag/cloud-trace.log`). One writer
 * thread; a bounded queue that DROPS when full — a diagnostic must never
 * slow the path it observes. Lines carry no credentials (the CloudOpen rule).
 *
 * The caller only reads its clock; the timestamp is FORMATTED on the writer
 * thread. `SimpleDateFormat` is not thread-safe, and six pump lanes logging
 * at once through one instance stamp each other's time onto their lines.
 */
class DiagTraceFile(
    private val dir: File,
    private val capBytes: Long = 256L * 1024,
    queueCapacity: Int = 512,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val atMs: Long, val line: String)

    private val queue = ArrayBlockingQueue<Any>(queueCapacity)
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val live get() = File(dir, "cloud-trace.log")
    private val rotated get() = File(dir, "cloud-trace.log.1")

    init {
        Thread({ drain() }, "diag-trace").apply { isDaemon = true }.start()
    }

    fun append(line: String) {
        queue.offer(Pending(clock(), line)) // drop on full
    }

    /** Tests only: wait until everything queued so far is on disk. */
    fun flushForTest() {
        val latch = CountDownLatch(1)
        queue.put(latch)
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun drain() {
        while (true) {
            val item = queue.take()
            if (item is CountDownLatch) {
                item.countDown()
                continue
            }
            runCatching {
                dir.mkdirs()
                if (live.exists() && live.length() >= capBytes) {
                    rotated.delete()
                    live.renameTo(rotated)
                }
                val pending = item as Pending
                live.appendText("${format.format(Date(pending.atMs))} ${pending.line}\n")
            }
        }
    }
}
