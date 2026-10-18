package com.easemic.app.importing.opds

import java.io.File
import com.easemic.app.storage.CacheBudget
import com.easemic.app.feature.download.FetchTransportException

/**
 * Fetches one OPDS-PSE page image and caches it locally.
 * Non-2xx or empty body throws so the online reader can show a page error.
 */
object OpdsPsePageFetch {
    fun fetchToCache(
        url: String,
        httpGet: OpdsHttpGet,
        authorizationHeader: String?,
        cacheRoot: File,
        ordinal: Int,
        budget: CacheBudget? = null,
    ): String {
        val response = httpGet.get(
            OpdsHttpRequest(
                url = url,
                authorizationHeader = authorizationHeader,
            ),
        )
        if (response.code !in 200..299) {
            throw FetchTransportException(
                statusCode = response.code,
                resolvedUrl = url,
                bodySample = response.body.copyOf(
                    minOf(response.body.size, FetchTransportException.BODY_SAMPLE_LIMIT),
                ),
                message = "OPDS_HTTP_${response.code}",
            )
        }
        require(response.body.isNotEmpty()) { "OPDS page empty" }
        return OpdsOnlinePageCache.write(
            cacheRoot,
            ordinal,
            response.body,
            pageKey = OpdsOnlinePageCache.pageKey(url),
            budget = budget,
        )
    }
}
