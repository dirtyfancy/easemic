package com.easemic.app.net

import okhttp3.OkHttpClient

/** One seed for every source-transfer client: the family preference Dns plus its connect listener. */
object SourceHttp {
    /** The process-wide preference instance; with no handle attached the JVM default order survives. */
    val dns: FamilyPreferenceDns = FamilyPreferenceDns()

    /**
     * Seeds timeout-agnostic clients: shared Dns + connect-evidence listener, with OkHttp's
     * connection recovery on. When one address fails the next one is tried, and a pooled socket
     * the server has already closed is replaced — both of which HttpURLConnection did for the
     * source clients before the OkHttp port.
     */
    fun newBuilder(): OkHttpClient.Builder = dns.okHttpClientBuilder().retryOnConnectionFailure(true)
}
