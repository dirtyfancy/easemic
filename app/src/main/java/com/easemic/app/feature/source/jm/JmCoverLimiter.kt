package com.easemic.app.feature.source.jm

import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Coil covers have their own concurrency budget; API and page requests use JmRequestGate. */
internal class JmCoverLimiter(private val domains: JmDomains) {
    private val permits = Semaphore(4, true)
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!domains.owns(JmRole.IMAGE, request.url) ||
            !Regex("/media/albums/[0-9]+_3x4\\.jpg").matches(request.url.encodedPath)) {
            chain.proceed(request)
        } else {
            try {
                while (!permits.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    if (chain.call().isCanceled()) throw IOException("Canceled")
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted", interrupted)
            }
            val released = AtomicBoolean(false)
            fun release() { if (released.compareAndSet(false, true)) permits.release() }
            var originalResponse: Response? = null
            try {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                val response = chain.proceed(request).also { originalResponse = it }
                val body = response.body
                if (body == null) {
                    release()
                    response
                } else {
                    val source = object : ForwardingSource(body.source()) {
                        override fun close() {
                            try { super.close() } finally { release() }
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source(): BufferedSource = source
                    }).build()
                }
            } catch (error: Throwable) {
                runCatching { originalResponse?.close() }
                release()
                throw error
            }
        }
    }
}
