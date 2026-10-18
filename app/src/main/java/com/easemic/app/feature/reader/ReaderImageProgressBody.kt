package com.easemic.app.feature.reader

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal class ReaderImageProgressBody(
    private val delegate: ResponseBody,
    private val onProgress: (receivedBytes: Long, contentLength: Long) -> Unit,
) : ResponseBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun source() = object : ForwardingSource(delegate.source()) {
        private var totalBytesRead = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            val length = contentLength()
            if (read == -1L) {
                onProgress(totalBytesRead, length)
            } else {
                totalBytesRead += read
                onProgress(totalBytesRead, length)
            }
            return read
        }
    }.buffer()
}

internal class ReaderImageProgressTag(val key: String)

internal fun tagWatchedRequest(
    request: Request,
    tracker: ReaderImageProgressTracker,
): Request {
    if (request.tag(ReaderImageProgressTag::class.java) != null) return request
    val key = ReaderImageProgressTracker.canonicalKey(request.url.toString())
    if (!tracker.isWatched(key)) return request
    return request.newBuilder()
        .tag(ReaderImageProgressTag::class.java, ReaderImageProgressTag(key))
        .build()
}

internal fun wrapTaggedProgressBody(
    response: Response,
    tracker: ReaderImageProgressTracker,
): Response {
    val tag = response.request.tag(ReaderImageProgressTag::class.java) ?: return response
    val body = response.body ?: return response
    if (!tracker.isWatched(tag.key)) return response
    return response.newBuilder()
        .body(
            ReaderImageProgressBody(body) { receivedBytes, contentLength ->
                tracker.report(tag.key, receivedBytes, contentLength)
            },
        )
        .build()
}

internal fun readerImageProgressAppInterceptor(
    tracker: ReaderImageProgressTracker,
): Interceptor = Interceptor { chain ->
    chain.proceed(tagWatchedRequest(chain.request(), tracker))
}

internal fun readerImageProgressNetworkInterceptor(
    tracker: ReaderImageProgressTracker,
): Interceptor = Interceptor { chain ->
    wrapTaggedProgressBody(chain.proceed(chain.request()), tracker)
}

internal fun wrapProgressBody(
    response: Response,
    tracker: ReaderImageProgressTracker,
    url: String,
): Response {
    val body = response.body ?: return response
    if (!tracker.isWatched(url)) return response
    return response.newBuilder()
        .body(
            ReaderImageProgressBody(body) { receivedBytes, contentLength ->
                tracker.report(url, receivedBytes, contentLength)
            },
        )
        .build()
}
