package com.easemic.app.net

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * Order-only DNS preference for source transfers (#E19): IPv4 first, IPv6 second, never dropping
 * an address. OkHttp 4.12 tries the addresses in lookup order with no fast-fallback between
 * families, so the order is the only supported steering mechanism — a connected-but-slow pipe
 * stays with the #E15 stall floor and the slow-node switch.
 *
 * The preference is keyed on the current network's handle (`Network.getNetworkHandle`), so what
 * one network taught never leaks onto the next. The only evidence that counts toward preferring
 * IPv6 on the current network is, within one call, an IPv4 `connectFailed` followed by a
 * successful IPv6 `connectEnd` (controller review 1): failures on IPv4-only hosts — every offline
 * H@H node — never count, and any successful IPv4 connection resets the count. After
 * [failureThreshold] pieces of evidence the lookups put IPv6 first. [onNetworkLost] and a handle
 * change clear the state; with no handle provider attached (JVM tests, early startup) the
 * delegate's order survives.
 */
class FamilyPreferenceDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val currentHandle: () -> Long? = { null },
    private val failureThreshold: Int = 3,
) : Dns {
    private var handleProvider: (() -> Long?)? = null
    private var stateHandle: Long? = null
    private var ipv6Evidence = 0
    private var preferIpv6ForCurrent = false

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        // No handle and no provider: JVM default and early startup. Sorting here would invent an
        // IPv4 preference before any network exists. A provider that returns null is not this case.
        if (keepDelegateOrder()) return addresses
        // sortedWith is stable, so within one family the delegate's order survives.
        return if (preferIpv6()) {
            addresses.sortedWith(compareBy { it is Inet4Address })
        } else {
            addresses.sortedWith(compareBy { it is Inet6Address })
        }
    }

    /** Evidence that IPv4 is broken on this network while IPv6 works. Test seam for the listener. */
    @Synchronized internal fun recordIpv4BrokenIpv6Working(handle: Long?) {
        if (handle == null) return
        adoptHandle(handle)
        if (++ipv6Evidence >= failureThreshold) preferIpv6ForCurrent = true
    }

    /** A successful IPv4 connection proves IPv4 is not broken here; it resets the count. */
    @Synchronized internal fun recordIpv4Working(handle: Long?) {
        if (handle == null) return
        adoptHandle(handle)
        ipv6Evidence = 0
    }

    @Synchronized fun onNetworkLost(handle: Long) {
        if (handle == stateHandle) {
            ipv6Evidence = 0
            preferIpv6ForCurrent = false
        }
    }

    /** Replaces the constructor handle source once the app knows the live network (Task 6 wiring). */
    @Synchronized fun attachHandleProvider(provider: () -> Long?) {
        handleProvider = provider
    }

    internal fun connectEventListener(): EventListener = object : EventListener() {
        private var ipv4AttemptFailed = false

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            cause: IOException,
        ) {
            if (inetSocketAddress.address is Inet4Address) ipv4AttemptFailed = true
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) {
            if (inetSocketAddress.address is Inet4Address) {
                ipv4AttemptFailed = false
                recordIpv4Working(currentNetworkHandle())
            } else if (ipv4AttemptFailed) {
                ipv4AttemptFailed = false
                recordIpv4BrokenIpv6Working(currentNetworkHandle())
            }
        }
    }

    fun okHttpClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .dns(this)
        .eventListenerFactory(EventListener.Factory { connectEventListener() })

    /** True only when nothing has identified a network. An attached provider wins, even if it returns null. */
    @Synchronized private fun keepDelegateOrder(): Boolean =
        handleProvider == null && currentHandle() == null

    @Synchronized private fun preferIpv6(): Boolean {
        adoptHandle(currentNetworkHandle())
        return preferIpv6ForCurrent
    }

    @Synchronized private fun currentNetworkHandle(): Long? {
        val provider = handleProvider
        // `provider?.let { it() } ?: currentHandle()` treats a null return as "no provider".
        return if (provider != null) provider() else currentHandle()
    }

    @Synchronized private fun adoptHandle(handle: Long?) {
        if (handle != stateHandle) {
            stateHandle = handle
            ipv6Evidence = 0
            preferIpv6ForCurrent = false
        }
    }
}
