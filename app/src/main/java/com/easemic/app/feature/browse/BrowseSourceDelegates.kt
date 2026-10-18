package com.easemic.app.feature.browse

import com.easemic.app.i18n.tr

import com.easemic.app.feature.browse.BrowseFeedRepository.Companion.ehentaiAcquisitionUrl
import com.easemic.app.feature.browse.BrowseFeedRepository.Companion.nhentaiAcquisitionUrl
import com.easemic.app.feature.browse.BrowseFeedRepository.Companion.resolveHref
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.EXHENTAI_UNAUTHORIZED_DETAIL
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.OpdsProviderConfig
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.ehentai.EhentaiException
import com.easemic.app.importing.ehentai.EhentaiListEntry
import com.easemic.app.importing.nhentai.NhentaiClient
import com.easemic.app.importing.nhentai.NhentaiException
import com.easemic.app.importing.nhentai.NhentaiListEntry
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.importing.opds.OpdsCatalogException
import com.easemic.app.importing.opds.OpdsEntry
import com.easemic.app.importing.opds.OpdsFeed
import com.easemic.app.importing.opds.OpenSearchDescription
import java.util.concurrent.ConcurrentHashMap

// #E2 Slice 2: Browse branch bodies of [BrowseFeedRepository], moved here unchanged so the source wrappers
// (feature/source) own them while Browse moves to the registry.

/** The OPDS Browse branch: a provider's root/"Recently Added" feed, server-side search, drill-in feeds and paging. */
internal class OpdsBrowseSource(
    private val providers: () -> List<OpdsProviderConfig>,
    private val client: OpdsCatalogClient,
) {
    /**
     * Resolved OpenSearch templates, keyed by provider id + description URL.
     * See [searchTemplate]; [NO_SEARCH_TEMPLATE] marks "this catalog has none".
     */
    private val searchTemplates = ConcurrentHashMap<String, String>()

    fun browse(
        prefs: BrowsePrefs,
        query: String,
        continuation: String? = null,
        explicitFeedUrl: String? = null,
        explicitProviderId: String? = null,
    ): BrowseFeedResult {
        if (continuation != null) return loadOpdsContinuation(prefs, query, continuation)
        if (explicitFeedUrl != null) {
            val provider = providers().firstOrNull { it.id == explicitProviderId }
                ?: providers().firstOrNull()
                ?: return browseOk(emptyList())
            return try {
                val needle = query.trim()
                val page = when {
                    explicitFeedUrl == provider.catalogUrl -> loadProvider(provider, query)
                    // #10b: a query typed while the user is drilled INTO a
                    // subsection has to reach the server too. Client-filtering
                    // the one page the user happens to be looking at reported
                    // "no matches" for terms the catalog finds instantly.
                    needle.isNotEmpty() ->
                        trySearchFromRoot(provider, needle)
                            ?: loadFeedUrl(provider, explicitFeedUrl, query)
                    else -> loadFeedUrl(provider, explicitFeedUrl, query)
                }
                browseOk(page.items, page.nextContinuation, page.totalItems)
            } catch (error: OpdsCatalogException) {
                BrowseFeedResult.Error(error.message ?: "OPDS_ERROR")
            } catch (_: Exception) {
                BrowseFeedResult.Error("OPDS_ERROR")
            }
        }
        val provider = providers().firstOrNull { it.id == prefs.sourceId }
            ?: return browseOk(emptyList())
        return try {
            val page = loadProvider(provider, query)
            browseOk(page.items, page.nextContinuation, page.totalItems)
        } catch (error: OpdsCatalogException) {
            BrowseFeedResult.Error(error.message ?: "OPDS_ERROR")
        } catch (_: Exception) {
            BrowseFeedResult.Error("OPDS_ERROR")
        }
    }

    private fun loadOpdsContinuation(
        prefs: BrowsePrefs,
        query: String,
        continuation: String,
    ): BrowseFeedResult {
        if (BrowseContinuation.isOpdsSearchNext(continuation)) {
            val searchProviderId = BrowseContinuation.parseOpdsSearchProviderId(continuation)
                ?: return browseOk(emptyList())
            val searchHref = BrowseContinuation.parseOpdsSearchNextHref(continuation)
                ?: return browseOk(emptyList())
            val searchProvider =
                providers().firstOrNull { it.id == searchProviderId && it.id == prefs.sourceId }
                    ?: return browseOk(emptyList())
            return try {
                val page = mapSearchFeed(searchProvider, searchHref, fetch(searchProvider, searchHref))
                browseOk(page.items, page.nextContinuation, page.totalItems)
            } catch (error: OpdsCatalogException) {
                BrowseFeedResult.Error(error.message ?: "OPDS_ERROR")
            } catch (_: Exception) {
                BrowseFeedResult.Error("OPDS_ERROR")
            }
        }
        val providerId = BrowseContinuation.parseOpdsProviderId(continuation)
            ?: return browseOk(emptyList())
        val href = BrowseContinuation.parseOpdsNextHref(continuation)
            ?: return browseOk(emptyList())
        // A token outliving its source (provider deleted, or the user switched
        // shelves mid-scroll) must end the scroll rather than fetch a stranger.
        val provider = providers().firstOrNull { it.id == providerId && it.id == prefs.sourceId }
            ?: return browseOk(emptyList())
        return try {
            val page = loadFeedUrl(provider, href, query)
            browseOk(page.items, page.nextContinuation)
        } catch (error: OpdsCatalogException) {
            BrowseFeedResult.Error(error.message ?: "OPDS_ERROR")
        } catch (_: Exception) {
            BrowseFeedResult.Error("OPDS_ERROR")
        }
    }

    /** One OPDS page: the entries it mapped, plus a token for the page after it. */
    private data class MappedOpdsPage(
        val items: List<BrowseFeedItem>,
        val nextContinuation: String?,
        val totalItems: Int? = null,
    )

    private fun loadProvider(provider: OpdsProviderConfig, query: String): MappedOpdsPage {
        val root = fetch(provider, provider.catalogUrl)
        if (query.trim().isNotEmpty()) {
            val searched = trySearch(provider, root, query.trim())
            if (searched != null) return searched
        }
        val recent = followRecentlyAddedIfPresent(provider, root)
        val feedUrl = recent?.first ?: provider.catalogUrl
        val feed = recent?.second ?: root
        return loadFeedUrl(provider, feedUrl, query, prefetched = feed)
    }

    /**
     * #10: run the catalog's real OpenSearch query instead of filtering one
     * "Recently Added" page by title. Returns null (caller falls back to the
     * browse path) when the feed advertises no search link or its description
     * has no usable {searchTerms} template — a missing-search catalog never
     * throws.
     */
    private fun trySearch(
        provider: OpdsProviderConfig,
        root: OpdsFeed,
        query: String,
    ): MappedOpdsPage? {
        val searchHref = root.searchLink()?.href ?: return null
        val descriptionUrl = resolveHref(provider.catalogUrl, searchHref)
        val template = searchTemplate(provider, descriptionUrl) ?: return null
        val resolved = resolveHref(descriptionUrl, OpenSearchDescription.applyTemplate(template, query))
        return mapSearchFeed(provider, resolved, fetch(provider, resolved))
    }

    /**
     * #10b: the same server-side search as [loadProvider], reached from a
     * subsection — the root has to be fetched first because that is where the
     * `rel="search"` link lives. Returns null (caller keeps the client-side
     * filter) when the root is unreachable or the catalog advertises no
     * search: a drilled-in user must never lose their page to a search the
     * catalog can't run.
     */
    private fun trySearchFromRoot(
        provider: OpdsProviderConfig,
        query: String,
    ): MappedOpdsPage? {
        val root = runCatching { fetch(provider, provider.catalogUrl) }.getOrNull() ?: return null
        return trySearch(provider, root, query)
    }

    /**
     * The catalog's `{searchTerms}` template, memoized per provider. Search
     * runs per keystroke, so refetching the OpenSearch description each time
     * would add a request per character typed.
     *
     * A completed fetch is cached either way — a document with no usable
     * template caches as [NO_SEARCH_TEMPLATE] so a search-less catalog is
     * asked exactly once. A *thrown* fetch is deliberately NOT cached, so a
     * transient network blip can't disable search for the rest of the
     * process.
     *
     * Keyed by provider id + description URL: [PrivateComicLibraryApp] builds
     * the repository inside `remember(opdsProviders, …)`, so editing a
     * provider already yields a fresh repository with an empty cache; the URL
     * in the key makes that independent of how the caller holds the instance.
     */
    private fun searchTemplate(provider: OpdsProviderConfig, descriptionUrl: String): String? {
        val key = "${provider.id}\n$descriptionUrl"
        searchTemplates[key]?.let { cached -> return cached.ifEmpty { null } }
        val fetched = runCatching {
            client.fetchOpenSearchTemplate(
                url = descriptionUrl,
                username = provider.username.ifBlank { null },
                password = provider.password.ifBlank { null },
            )
        }.getOrElse { return null }
        searchTemplates[key] = fetched ?: NO_SEARCH_TEMPLATE
        return fetched
    }

    /**
     * Maps a server-search results feed. Its `next` is tagged as a search
     * continuation so the load-more path does NOT re-apply the client-side
     * title filter to already-searched results.
     */
    private fun mapSearchFeed(
        provider: OpdsProviderConfig,
        feedUrl: String,
        feed: OpdsFeed,
    ): MappedOpdsPage {
        val items = feed.entries.map { entry -> mapEntry(provider, feedUrl, entry) }
        val next = feed.nextPage()?.href
            ?.let { resolveHref(feedUrl, it) }
            ?.let { BrowseContinuation.opdsSearchNext(provider.id, it) }
        return MappedOpdsPage(items, next, feed.totalResults)
    }

    private fun loadFeedUrl(
        provider: OpdsProviderConfig,
        feedUrl: String,
        query: String,
        prefetched: OpdsFeed? = null,
        clientFilter: Boolean = true,
    ): MappedOpdsPage {
        val feed = prefetched ?: fetch(provider, feedUrl)
        val mapped = feed.entries.map { entry -> mapEntry(provider, feedUrl, entry) }
        // Carry `next` even when the client-side query filters this page empty:
        // the match may sit on a later page, and dropping the token here would
        // end the scroll early.
        val next = feed.nextPage()?.href
            ?.let { resolveHref(feedUrl, it) }
            ?.let { BrowseContinuation.opdsNext(provider.id, it) }
        val needle = query.trim().lowercase()
        val items = if (!clientFilter || needle.isEmpty()) {
            mapped
        } else {
            mapped.filter { item -> item.title.lowercase().contains(needle) }
        }
        return MappedOpdsPage(items, next, feed.totalResults)
    }

    private fun followRecentlyAddedIfPresent(
        provider: OpdsProviderConfig,
        root: OpdsFeed,
    ): Pair<String, OpdsFeed>? {
        // Prefer "Recently Added" over "Recently Updated" when both exist (Kavita).
        val candidates = root.entries.filter { entry ->
            entry.primarySubsection() != null && looksLikeRecentlyAdded(entry)
        }
        val recent = candidates.firstOrNull(::prefersRecentlyAddedEntry)
            ?: candidates.firstOrNull() ?: return null
        val href = recent.primarySubsection()?.href ?: return null
        val url = resolveHref(provider.catalogUrl, href)
        return url to fetch(provider, url)
    }

    private fun fetch(provider: OpdsProviderConfig, url: String): OpdsFeed =
        client.fetchFeed(
            url = url,
            username = provider.username.ifBlank { null },
            password = provider.password.ifBlank { null },
        )

    private fun looksLikeRecentlyAdded(entry: OpdsEntry): Boolean {
        val title = entry.title.lowercase()
        val id = entry.id.orEmpty().lowercase()
        val collapsedId = collapsedOpdsId(entry)
        val href = subsectionHref(entry)
        return title.contains("recent") ||
            title.contains("latest book") ||
            title.contains("latest books") ||
            id.contains("recently-added") ||
            id.contains("recently_added") ||
            id.contains("recently-updated") ||
            collapsedId.contains("recentlyadded") ||
            collapsedId.contains("recentlyupdated") ||
            href.contains("recently-added") ||
            href.contains("recently-updated") ||
            href.contains("recentlyadded") ||
            id.contains("books/latest") ||
            id.endsWith("/latest")
    }

    private fun prefersRecentlyAddedEntry(entry: OpdsEntry): Boolean {
        val title = entry.title.lowercase()
        val id = entry.id.orEmpty().lowercase()
        val collapsedId = collapsedOpdsId(entry)
        val href = subsectionHref(entry)
        return title.contains("recently added") ||
            id.contains("recently-added") ||
            id.contains("recently_added") ||
            collapsedId.contains("recentlyadded") ||
            href.contains("recently-added")
    }

    private fun collapsedOpdsId(entry: OpdsEntry): String =
        entry.id.orEmpty().lowercase().replace("-", "").replace("_", "")

    private fun subsectionHref(entry: OpdsEntry): String =
        entry.primarySubsection()?.href.orEmpty().lowercase()

    private fun mapEntry(
        provider: OpdsProviderConfig,
        baseUrl: String,
        entry: OpdsEntry,
    ): BrowseFeedItem {
        val subsection = entry.primarySubsection()?.href?.let { resolveHref(baseUrl, it) }
        val acquisition = entry.primaryAcquisition()?.href?.let { resolveHref(baseUrl, it) }
        val cover = entry.coverImage()?.href?.let { resolveHref(baseUrl, it) }
        val stream = entry.primaryStream()
        return BrowseFeedItem(
            id = entry.id ?: "${provider.id}:${entry.title}",
            title = entry.title,
            secondaryLine = "",
            providerId = provider.id,
            subsectionUrl = subsection,
            acquisitionUrl = acquisition,
            coverUrl = cover,
            pageCount = stream?.count,
            pageStreamUrl = stream?.href?.let { resolveHref(baseUrl, it) },
            author = entry.author,
            publisher = entry.publisher,
            feedUrl = baseUrl,
        )
    }

    private companion object {
        /** ConcurrentHashMap forbids null values; this stands in for "no template". */
        const val NO_SEARCH_TEMPLATE = ""
    }
}

/** nhentai's Browse branch: latest and search with the per-source filters, and index-page continuation. */
internal class NhentaiBrowseSource(
    private val enabled: () -> Boolean,
    private val client: NhentaiClient,
    private val filterStateFor: (String) -> SourceFilterState,
) {
    fun browse(prefs: BrowsePrefs, query: String, continuation: String?): BrowseFeedResult {
        if (prefs.sourceId != NhentaiEnableStore.SOURCE_ID) return browseOk(emptyList())
        if (continuation != null) {
            if (!enabled()) return browseOk(emptyList())
            val pageIndex = BrowseContinuation.parseIndexPageIndex(continuation)
                ?: return browseOk(emptyList())
            return try {
                val page = loadNhentaiPage(prefs, query, pageIndex)
                browseOk(page.items, page.nextContinuation)
            } catch (error: NhentaiException) {
                BrowseFeedResult.Error(error.message ?: "NHENTAI_ERROR")
            } catch (_: Exception) {
                BrowseFeedResult.Error("NHENTAI_ERROR")
            }
        }

        if (!enabled()) return browseOk(emptyList())
        var nextContinuation: String? = null
        val items = mutableListOf<BrowseFeedItem>()
        runCatching { loadNhentaiPage(prefs, query, pageIndex = 0) }
            .onSuccess { page ->
                items += page.items
                nextContinuation = page.nextContinuation
            }
            .onFailure { error ->
                if (prefs.sourceId == NhentaiEnableStore.SOURCE_ID) {
                    return BrowseFeedResult.Error(
                        (error as? NhentaiException)?.message ?: "NHENTAI_ERROR",
                    )
                }
            }
        return browseOk(items, nextContinuation)
    }

    private data class MappedCatalogPage(
        val items: List<BrowseFeedItem>,
        val nextContinuation: String?,
    )

    private fun loadNhentaiPage(
        prefs: BrowsePrefs,
        query: String,
        pageIndex: Int,
    ): MappedCatalogPage {
        val listQuery = NhentaiQueryMapper.from(filterStateFor(prefs.sourceId), query, pageIndex)
        val page = client.fetchList(listQuery)
        return MappedCatalogPage(
            items = page.entries.map { mapIndexEntry(it) },
            nextContinuation = page.nextPageIndex?.let(BrowseContinuation::indexPage),
        )
    }

    private fun mapIndexEntry(entry: NhentaiListEntry): BrowseFeedItem =
        BrowseFeedItem(
            id = "${NhentaiEnableStore.SOURCE_ID}:${entry.galleryId}",
            title = entry.title,
            secondaryLine = "",
            providerId = NhentaiEnableStore.SOURCE_ID,
            subsectionUrl = null,
            acquisitionUrl = nhentaiAcquisitionUrl(entry.galleryId),
            coverUrl = entry.thumbUrl,
            pageCount = entry.pageCount,
            favorites = entry.favorites,
        )
}

/** E-Hentai's Browse branch: the anonymous front list, filters and front continuation. */
internal class EhentaiBrowseSource(
    private val enabled: () -> Boolean,
    private val client: () -> EhentaiClient,
    private val filterStateFor: (String) -> SourceFilterState,
) {
    fun browse(prefs: BrowsePrefs, query: String, continuation: String?): BrowseFeedResult {
        if (prefs.sourceId != EhentaiEnableStore.SOURCE_ID) return browseOk(emptyList())
        if (continuation != null) {
            if (!enabled()) return browseOk(emptyList())
            val seek = BrowseContinuation.parseFrontNextGid(continuation)
            val pageIndex = BrowseContinuation.parseFrontPageIndex(continuation)
            if (seek == null && pageIndex == null) return browseOk(emptyList())
            return try {
                val page = loadEhentaiPage(
                    prefs = prefs,
                    query = query,
                    pageIndex = pageIndex ?: 0,
                    seekNextGid = seek,
                )
                browseOk(page.items, page.nextContinuation)
            } catch (error: EhentaiException) {
                BrowseFeedResult.Error(error.message ?: "EHENTAI_ERROR")
            } catch (_: Exception) {
                BrowseFeedResult.Error("EHENTAI_ERROR")
            }
        }
        if (!enabled()) return browseOk(emptyList())
        return runCatching { loadEhentaiPage(prefs, query) }
            .fold(
                onSuccess = { page -> browseOk(page.items, page.nextContinuation) },
                onFailure = { error ->
                    BrowseFeedResult.Error((error as? EhentaiException)?.message ?: "EHENTAI_ERROR")
                },
            )
    }

    private data class MappedCatalogPage(
        val items: List<BrowseFeedItem>,
        val nextContinuation: String?,
    )

    private fun loadEhentaiPage(
        prefs: BrowsePrefs,
        query: String,
        pageIndex: Int = 0,
        seekNextGid: Long? = null,
    ): MappedCatalogPage {
        val listQuery = EhentaiQueryMapper.from(
            state = filterStateFor(prefs.sourceId),
            query = query,
            pageIndex = pageIndex,
            seekNextGid = seekNextGid,
        )
        val page = client().fetchList(listQuery)
        val continuation = when {
            page.nextSeekGid != null -> BrowseContinuation.frontNext(page.nextSeekGid)
            page.nextPageIndex != null -> BrowseContinuation.frontPage(page.nextPageIndex)
            else -> null
        }
        return MappedCatalogPage(
            items = page.entries.map { mapFrontEntry(it) },
            nextContinuation = continuation,
        )
    }

    private fun mapFrontEntry(entry: EhentaiListEntry): BrowseFeedItem =
        BrowseFeedItem(
            id = "${EhentaiEnableStore.SOURCE_ID}:${entry.galleryId}",
            title = entry.title,
            secondaryLine = "",
            providerId = EhentaiEnableStore.SOURCE_ID,
            subsectionUrl = null,
            acquisitionUrl = ehentaiAcquisitionUrl(entry.galleryId, entry.token),
            coverUrl = entry.thumbUrl,
            category = entry.category,
            rating = entry.rating,
            postedAge = entry.postedEpochMillis?.let { relativeAgeMillis(it) },
            pageCount = entry.pageCount,
            language = entry.language,
            author = entry.uploader,
        )
}

/** ExHentai's Browse branch: the session origin, filters and front continuation. */
internal class ExhentaiBrowseSource(
    private val enabled: () -> Boolean,
    private val client: () -> EhentaiClient,
    private val filterStateFor: (String) -> SourceFilterState,
) {
    fun browse(prefs: BrowsePrefs, query: String, continuation: String?): BrowseFeedResult {
        if (prefs.sourceId != ExhentaiEnableStore.SOURCE_ID) return browseOk(emptyList())
        if (continuation != null) {
            if (!enabled()) return browseOk(emptyList())
            val seek = BrowseContinuation.parseFrontNextGid(continuation)
            val pageIndex = BrowseContinuation.parseFrontPageIndex(continuation)
            if (seek == null && pageIndex == null) return browseOk(emptyList())
            return try {
                val page = loadExhentaiPage(
                    prefs = prefs,
                    query = query,
                    pageIndex = pageIndex ?: 0,
                    seekNextGid = seek,
                )
                browseOk(page.items, page.nextContinuation)
            } catch (error: EhentaiException) {
                BrowseFeedResult.Error(exhentaiFailureMessage(error))
            } catch (_: Exception) {
                BrowseFeedResult.Error("EXHENTAI_ERROR")
            }
        }
        if (!enabled()) return browseOk(emptyList())
        return runCatching { loadExhentaiPage(prefs, query) }
            .fold(
                onSuccess = { page -> browseOk(page.items, page.nextContinuation) },
                onFailure = { error -> BrowseFeedResult.Error(exhentaiFailureMessage(error)) },
            )
    }

    private data class MappedCatalogPage(
        val items: List<BrowseFeedItem>,
        val nextContinuation: String?,
    )

    /**
     * W7: the exhentai.org listing — same query mapper, same continuation
     * tokens, its own origin-scoped client. A sad-panda refusal (200 + blank
     * body) surfaces from the client as [EhentaiException.ExhentaiUnauthorized]
     * and is translated to the honest auth copy here — never "no galleries".
     */
    private fun loadExhentaiPage(
        prefs: BrowsePrefs,
        query: String,
        pageIndex: Int = 0,
        seekNextGid: Long? = null,
    ): MappedCatalogPage {
        val listQuery = EhentaiQueryMapper.from(
            state = filterStateFor(prefs.sourceId),
            query = query,
            pageIndex = pageIndex,
            seekNextGid = seekNextGid,
        )
        val page = client().fetchList(listQuery)
        val continuation = when {
            page.nextSeekGid != null -> BrowseContinuation.frontNext(page.nextSeekGid)
            page.nextPageIndex != null -> BrowseContinuation.frontPage(page.nextPageIndex)
            else -> null
        }
        return MappedCatalogPage(
            items = page.entries.map { mapExhentaiEntry(it) },
            nextContinuation = continuation,
        )
    }

    /**
     * W7 break #2: an explicit-session failure surfaces as the honest auth
     * detail, never as a generic empty manifest. Every other failure keeps
     * its own classification.
     */
    private fun exhentaiFailureMessage(error: Throwable): String =
        when (error) {
            is EhentaiException.ExhentaiUnauthorized -> EXHENTAI_UNAUTHORIZED_DETAIL
            is EhentaiException -> error.message ?: "EXHENTAI_ERROR"
            else -> "EXHENTAI_ERROR"
        }

    private fun mapExhentaiEntry(entry: EhentaiListEntry): BrowseFeedItem =
        BrowseFeedItem(
            id = "${ExhentaiEnableStore.SOURCE_ID}:${entry.galleryId}",
            title = entry.title,
            secondaryLine = "",
            providerId = ExhentaiEnableStore.SOURCE_ID,
            subsectionUrl = null,
            acquisitionUrl = ehentaiAcquisitionUrl(entry.galleryId, entry.token),
            coverUrl = entry.thumbUrl,
            category = entry.category,
            rating = entry.rating,
            postedAge = entry.postedEpochMillis?.let { relativeAgeMillis(it) },
            pageCount = entry.pageCount,
            language = entry.language,
            author = entry.uploader,
        )
}

/** English-only relative age for the E-Hentai subline (dev stage). */
private fun relativeAgeMillis(postedEpochMillis: Long): String {
    val delta = System.currentTimeMillis() - postedEpochMillis
    val seconds = delta / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        delta < 0 -> tr("just now", "刚刚", "剛剛", "たった今")
        seconds < 60 -> tr("just now", "刚刚", "剛剛", "たった今")
        minutes < 60 -> tr("$minutes min ago", "$minutes 分钟前", "$minutes 分鐘前", "$minutes 分前")
        hours < 24 -> tr("$hours hours ago", "$hours 小时前", "$hours 小時前", "$hours 時間前")
        days < 30 -> tr("$days days ago", "$days 天前", "$days 天前", "$days 日前")
        else -> {
            val months = days / 30
            if (months < 12) tr("$months months ago", "$months 个月前", "$months 個月前", "$months か月前") else tr("${days / 365} years ago", "${days / 365} 年前", "${days / 365} 年前", "${days / 365} 年前")
        }
    }
}

