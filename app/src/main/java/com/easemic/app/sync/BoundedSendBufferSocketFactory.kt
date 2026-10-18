package com.easemic.app.sync

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/**
 * #C53 round 2: a delegating [SSLSocketFactory] that caps the RAW socket's `SO_SNDBUF` at
 * [requestBytes] before TLS is layered on it. `HttpURLConnection` exposes no socket; Android's
 * `HttpsURLConnection` connects a plain socket first and then asks this factory to wrap it
 * (`createSocket(Socket, host, port, autoClose)`), which is the one place the app can touch the
 * option. The kernel doubles the request, so after `write` returns at most
 * [R2ObjectStore.SEND_BUFFER_BOUND_BYTES] can still be unsent — a KNOWN bound, which is what the
 * post-body wait and the progress watchdog need (a phone's default 4 MiB buffer swallowed whole
 * pages without one blocking write, so "no progress" meant nothing there).
 *
 * ONE shared instance ([R2ObjectStore.boundedSendBufferFactory]) so the platform connection pool
 * keeps reusing sockets (okhttp's `Address` equality includes the factory, so a socket created
 * under any other factory can never be handed to us).
 *
 * **Throughput cost — not negligible, and corrected by round-2 audit B5.** `sk_sndbuf` bounds
 * `sk_wmem_queued`, which is accounted in skb *truesize* (not payload) and also covers the
 * retransmit queue, so the in-flight payload ceiling is materially below 256 KiB — typically
 * 50–65 % of it. That is roughly **1.3–1.6 MB/s per lane at 100 ms RTT (≈ 8–10 MB/s across six
 * lanes)** and **≈ 450–570 KB/s per lane at 300 ms RTT (≈ 2.7–3.4 MB/s across six)**. The owner's
 * best measured uplink is 3.5 MB/s, so on a fast, high-RTT path (CN → R2/Wasabi) this bound CAN
 * bind: a 10–25 % peak-throughput regression there is a real possibility, not a theoretical one.
 * The lab MinIO leg is plain `http://` and never sees this factory, so only the UNTHROTTLED R2 and
 * bunny legs can detect it.
 *
 * Two forward traps. (1) The guarantee is exact only for the **wrap** overload, where a raw socket
 * is bounded before the delegate layers TLS on it; the other overloads bound the socket the
 * delegate RETURNS, which on the JDK owns its own fd (fine) but on Conscrypt may be a wrapper whose
 * routing of `setSendBufferSize` is implementation-defined. Android only ever uses the wrap
 * overload. The seventh overload, `createSocket(Socket, InputStream, Boolean)`, is deliberately NOT
 * delegated: it is a server-side API (for sniffers that already consumed the ClientHello) that this
 * client stack never calls, and on this build's Kotlin classpath (AGP 9.3.1 / Kotlin 2.4.10,
 * compileSdk 37) it is not resolvable on `SSLSocketFactory` at all — `override` reports "overrides
 * nothing", and declared without `override` the delegating call does not compile, although the method
 * is public and non-synthetic in `platforms/android-37.{0,1}/android.jar`, in
 * `core-for-system-modules.jar` and in AGP's own `android-…-api.jar`. It therefore keeps the base
 * class's `UnsupportedOperationException` — exactly the behaviour before this class existed, and
 * unreachable for any caller compiled against the same classpath. (2) The platform
 * `HttpURLConnection` is HTTP/1.1-only, so six upload lanes are six
 * sockets and each gets its own bound; if a future Android served it over h2 they would collapse
 * onto ONE bounded socket and the aggregate ceiling would become `bound / RTT` in total.
 *
 * The delegate is snapshotted once (see [R2ObjectStore.boundedSendBufferFactory]): a later
 * `HttpsURLConnection.setDefaultSSLSocketFactory(...)` — debug pinning, a test harness — would not
 * be picked up.
 */
class BoundedSendBufferSocketFactory(
    private val delegate: SSLSocketFactory,
    /**
     * #C53 round-2 audit B1: injectable so a TEST can ask for a size no platform defaults to and assert the
     * readback EXACTLY. Production always takes the default — [R2ObjectStore.postBodyWaitMs] sizes the tail from
     * the CONSTANTS, so a non-default request here would under-state the real bound (a safe direction, but
     * test-only regardless).
     */
    private val requestBytes: Long = R2ObjectStore.SEND_BUFFER_REQUEST_BYTES,
) : SSLSocketFactory() {
    private val refused = AtomicBoolean(false)

    /**
     * #C53 round-2 audit B3: sticky, set the first time the platform refuses `SO_SNDBUF`. Callers AND it into the
     * "is this socket bounded" fact ([R2ObjectStore.isBounded]) so a refusing kernel degrades to round 1's longer,
     * safer window instead of to a 10 s cliff on a backlog nobody bounded. Never reset: one refusal is enough to
     * stop believing the bound for the rest of the process.
     */
    val boundRefused: Boolean get() = refused.get()

    /**
     * Sets the option, or records that the platform would not take it. Unguarded this would throw out of
     * `createSocket` for EVERY verb (`open()` installs this factory on GET/GET-file/GET-text/DELETE too), so a
     * platform that systematically refuses would kill the whole cloud transport rather than degrade it. A refusal
     * is logged once per factory — the exception CLASS only, never its message, which on some stacks carries the
     * endpoint host.
     *
     * Re-review R1: a failure is only a REFUSAL if the socket is still open. `setSendBufferSize` also throws
     * `SocketException("Socket is closed")` on a socket one of this store's two watchdogs closed under us
     * mid-connect, and latching on that would switch the whole round-2 mechanism off for the life of the
     * process — silently, on exactly the weak links this ticket targets. A closed socket is neither a refusal
     * nor a log line.
     *
     * Re-review R3: the handler lives INSIDE the `runCatching`, because `DiagLog.w` also drives
     * `DiagLog.fileSink` — a throwing sink would otherwise re-open through the logging path the very failure
     * mode this guard exists to close. Nothing in here can throw, for any verb.
     */
    private fun bound(socket: Socket): Socket = socket.apply {
        runCatching {
            try {
                sendBufferSize = requestBytes.toInt()
            } catch (refusal: Throwable) {
                if (!isClosed && refused.compareAndSet(false, true)) {
                    DiagLog.w("CloudPut", "SO_SNDBUF refused: ${refusal.javaClass.simpleName}")
                }
            }
        }
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(bound(s), host, port, autoClose)
    override fun createSocket(): Socket = bound(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = bound(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        bound(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = bound(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        bound(delegate.createSocket(address, port, localAddress, localPort))
}
