package com.hoathinh3d.donghua.data.source

import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.data.model.CatalogAudioFilter
import com.hoathinh3d.donghua.data.model.CatalogPageResult
import com.hoathinh3d.donghua.data.model.CatalogSortMode
import com.hoathinh3d.donghua.data.model.CatalogStatusFilter
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.FavoriteEpisodeMeta
import com.hoathinh3d.donghua.data.model.GenreItem
import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.model.MovieDetailResult
import com.hoathinh3d.donghua.data.model.ServerGroup
import com.hoathinh3d.donghua.data.model.WpSourceRef
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object MovieRepository {
    private const val CACHE_TTL_MS = 2L * 60L * 60L * 1000L
    private const val CAT_CACHE_TTL_MS = 90L * 1000L

    private data class CacheEntry(val data: Any, val expiresAtMs: Long)

    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> getCached(key: String): T? {
        val hit = memoryCache[key] ?: return null
        return if (hit.expiresAtMs > System.currentTimeMillis()) hit.data as? T else null
    }

    private fun setCached(key: String, data: Any, ttlMs: Long = CACHE_TTL_MS) {
        memoryCache[key] = CacheEntry(data, System.currentTimeMillis() + ttlMs)
    }

    private fun normalizeLeadingGenre(genres: List<GenreItem>): List<GenreItem> {
        if (genres.isEmpty()) return InitialData.GENRES_DATA
        return genres.map { g ->
            if (g.id == "Tất cả" && g.label != "Thể loại phim") g.copy(label = "Thể loại phim") else g
        }
    }

    fun isDeadOrBlankPoster(url: String?): Boolean {
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) return true
        val low = clean.lowercase()
        return low.startsWith("data:image") ||
            low.contains("blank.gif") ||
            low.contains("placeholder") ||
            low.contains("phimimg.com/upload/vod/202")
    }

    fun normalizePosterUrl(rawUrl: String?, baseUrl: String = ""): String {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty() || raw.startsWith("data:image", ignoreCase = true)) return ""
        val low = raw.lowercase()
        if (low.contains("blank.gif") || low.contains("placeholder")) return ""
        return when {
            low.startsWith("http://") || low.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            baseUrl.isNotBlank() -> "${baseUrl.trimEnd('/')}/${raw.trimStart('/')}"
            else -> raw
        }
    }

    fun extractPosterUrl(imgEl: Element?, baseUrl: String): String {
        if (imgEl == null) return ""
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-url", "src")
        for (attr in attrs) {
            val abs = imgEl.absUrl(attr)
            val normAbs = normalizePosterUrl(abs, baseUrl)
            if (normAbs.isNotEmpty() && !isDeadOrBlankPoster(normAbs)) return normAbs
            val raw = imgEl.attr(attr)
            val normRaw = normalizePosterUrl(raw, baseUrl)
            if (normRaw.isNotEmpty() && !isDeadOrBlankPoster(normRaw)) return normRaw
        }
        val srcset = imgEl.attr("data-srcset").ifBlank { imgEl.attr("srcset") }
        if (srcset.isNotBlank()) {
            val firstCandidate = srcset.split(',').firstOrNull()?.trim()?.substringBefore(' ')
            val normSet = normalizePosterUrl(firstCandidate, baseUrl)
            if (normSet.isNotEmpty()) return normSet
        }
        return ""
    }

    fun getOptimizedPosterUrl(url: String, width: Int = 420): String {
        if (isDeadOrBlankPoster(url) || !url.startsWith("http")) return ""
        val low = url.lowercase()
        if (low.contains("wsrv.nl") ||
            low.endsWith(".webp") ||
            low.endsWith(".avif") ||
            low.contains("output=webp") ||
            low.contains("cdn.500phim.net") ||
            low.contains("phimimg.com") ||
            low.contains("yanhh3d.") ||
            low.contains("googleusercontent.com")
        ) {
            return url
        }
        return "https://wsrv.nl/?url=${URLEncoder.encode(url, "UTF-8")}&w=$width&output=webp&q=82"
    }

    fun isDirectStreamServer(server: ServerGroup): Boolean =
        server.episodes.any { it.linkM3u8.isNotBlank() || it.wpServerSv == "hls" }

    /**
     * Phương án 2: Chỉ hiển thị và phát các máy chủ có luồng trực tiếp HLS/MP4
     * trên Media3 ExoPlayer (ẩn hoàn toàn các máy chủ chỉ có Embed).
     */
    fun visibleServerIndices(
        serverGroups: List<ServerGroup>,
        @Suppress("UNUSED_PARAMETER") includeIdx: Int = -1
    ): List<Int> {
        if (serverGroups.isEmpty()) return emptyList()
        return serverGroups.indices.filter { isDirectStreamServer(serverGroups[it]) }
    }

    fun isDubbedServerName(serverName: String?): Boolean {
        val low = (serverName ?: "").lowercase()
        return low.contains("thuyết") ||
            low.contains("thuyet") ||
            low.contains("lồng tiếng") ||
            low.contains("long tieng") ||
            low.contains("dub")
    }

    fun selectPreferredServerIdx(
        serverGroups: List<ServerGroup>,
        preferredAudio: String = "Thuyết Minh"
    ): Int {
        if (serverGroups.isEmpty()) return 0

        data class RankedServer(
            val idx: Int,
            val epCount: Int,
            val hasDirectHls: Boolean,
            val sourceTier: Int,
            val isDubbed: Boolean,
            val isExactThuyetMinh: Boolean
        )

        val indexed = serverGroups.mapIndexed { idx, g ->
            val name = g.serverName
            val lowName = name.lowercase()
            val tier = when {
                lowName.contains("yanhh3d now") -> 3
                lowName.contains("trình phát tích hợp") -> 2
                else -> 1
            }
            RankedServer(
                idx = idx,
                epCount = g.episodes.size,
                hasDirectHls = g.episodes.any { it.linkM3u8.isNotBlank() || it.wpServerSv == "hls" },
                sourceTier = tier,
                isDubbed = isDubbedServerName(name),
                isExactThuyetMinh = lowName.contains("thuyết")
            )
        }

        val wantDubbed = preferredAudio == "Thuyết Minh"
        // Luôn ưu tiên âm thanh mặc định (Thuyết Minh / Lồng Tiếng) trước,
        // trong nhóm đó ưu tiên luồng trực tiếp > nguồn gốc nhiều gương CDN (YanHH3D Now > Trình phát Tích hợp) > nhiều tập nhất.
        val matching = indexed.filter { if (wantDubbed) it.isDubbed else !it.isDubbed }
        val pool = matching.ifEmpty { indexed }

        val sorted = pool.sortedWith(
            Comparator { a, b ->
                if (b.hasDirectHls != a.hasDirectHls) return@Comparator if (b.hasDirectHls) 1 else -1
                if (b.sourceTier != a.sourceTier) return@Comparator b.sourceTier - a.sourceTier
                if (b.epCount != a.epCount) return@Comparator b.epCount - a.epCount
                if (wantDubbed && b.isExactThuyetMinh != a.isExactThuyetMinh) {
                    return@Comparator if (b.isExactThuyetMinh) 1 else -1
                }
                a.idx - b.idx
            }
        )
        return sorted.first().idx
    }

    fun serverHasEpisode(srv: ServerGroup, targetEpNum: Int): Boolean {
        return srv.episodes.anyIndexed { idx, ep ->
            if (com.hoathinh3d.donghua.player.PlayerUtils.extractEpisodeNumber(ep, idx) == targetEpNum) {
                return@anyIndexed true
            }
            val target = ep.slug.ifEmpty { ep.name }
            val m = Regex("""(\d+)\s*[-–]\s*(\d+)""").find(target) ?: return@anyIndexed false
            val start = m.groupValues[1].toIntOrNull() ?: return@anyIndexed false
            val end = m.groupValues[2].toIntOrNull() ?: return@anyIndexed false
            targetEpNum in minOf(start, end)..maxOf(start, end)
        }
    }

    /**
     * Khi người dùng chọn phát tập [targetEpNum] ở máy chủ [preferredServerIdx],
     * nếu máy chủ đó không có tập [targetEpNum] (VD: chỉ có Tập 44..168) thì tìm máy chủ khác
     * cùng nhóm âm thanh có chứa đúng tập [targetEpNum] (VD: 500Phim / PhimAPI có Tập 1..107).
     */
    fun resolveServerIdxForEpisode(
        serverGroups: List<ServerGroup>,
        preferredServerIdx: Int,
        targetEpNum: Int
    ): Int {
        if (serverGroups.isEmpty()) return 0
        val safeIdx = preferredServerIdx.coerceIn(0, serverGroups.lastIndex)
        if (serverHasEpisode(serverGroups[safeIdx], targetEpNum)) return safeIdx

        val wantDub = isDubbedServerName(serverGroups[safeIdx].serverName)
        val visible = visibleServerIndices(serverGroups, safeIdx)
        val sameAudioMatch = visible.firstOrNull { idx ->
            isDubbedServerName(serverGroups[idx].serverName) == wantDub &&
                serverHasEpisode(serverGroups[idx], targetEpNum)
        }
        return sameAudioMatch ?: visible.firstOrNull { idx ->
            serverHasEpisode(serverGroups[idx], targetEpNum)
        } ?: safeIdx
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        for (i in indices) {
            if (predicate(i, get(i))) return true
        }
        return false
    }

    private fun canonicalSlugKey(movie: DonghuaMovie): String {
        val raw = movie.slug?.takeIf { it.isNotBlank() } ?: movie.id
        return raw.trim().trimEnd('/').lowercase()
    }

    private fun canonicalTitleKey(title: String?): String {
        val norm = VietnameseSearch.removeVietnameseTones(title.orEmpty())
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return norm
    }

    private fun mergeMoviePair(existing: DonghuaMovie, incoming: DonghuaMovie): DonghuaMovie {
        val existingDead = isDeadOrBlankPoster(existing.poster)
        val incomingLive = !isDeadOrBlankPoster(incoming.poster)
        val preferIncomingIdentity = existingDead && incomingLive
        val chosenPoster = when {
            existingDead && incomingLive -> incoming.poster
            !existingDead -> existing.poster
            !incoming.poster.isBlank() -> incoming.poster
            else -> ""
        }
        val chosenThumb = when {
            !isDeadOrBlankPoster(existing.thumb) -> existing.thumb
            !isDeadOrBlankPoster(incoming.thumb) -> incoming.thumb
            chosenPoster.isNotBlank() -> chosenPoster
            else -> existing.thumb
        }
        val wpMap = LinkedHashMap<String, WpSourceRef>()
        (existing.wpSources.orEmpty() + incoming.wpSources.orEmpty()).forEach { ws ->
            wpMap["${ws.baseUrl}_${ws.postId}"] = ws
        }
        return existing.copy(
            id = if (preferIncomingIdentity) incoming.id else existing.id,
            slug = if (preferIncomingIdentity) incoming.slug ?: existing.slug else existing.slug ?: incoming.slug,
            title = if (preferIncomingIdentity) incoming.title else existing.title,
            originalTitle = if (preferIncomingIdentity) incoming.originalTitle else existing.originalTitle,
            studio = if (preferIncomingIdentity) incoming.studio else existing.studio,
            poster = chosenPoster,
            thumb = chosenThumb,
            yanPostId = incoming.yanPostId ?: existing.yanPostId,
            yanUrl = incoming.yanUrl ?: existing.yanUrl,
            customWebUrl = existing.customWebUrl ?: incoming.customWebUrl,
            wpSources = if (wpMap.isNotEmpty()) wpMap.values.toList() else null,
            statusLabel = existing.statusLabel ?: incoming.statusLabel,
            latestEp = maxOf(existing.latestEp, incoming.latestEp),
            totalEp = maxOf(existing.totalEp, incoming.totalEp),
            description = existing.description?.takeIf { it.isNotBlank() } ?: incoming.description,
            genres = (existing.genres + incoming.genres).distinct(),
            sources = (existing.sources + incoming.sources).distinct(),
            audioModes = (existing.audioModes + incoming.audioModes).distinct()
        )
    }

    fun mergeMovieLists(lists: List<List<DonghuaMovie>>): List<DonghuaMovie> {
        val resultsMap = LinkedHashMap<String, DonghuaMovie>()
        val titleToKey = HashMap<String, String>()
        for (list in lists) {
            for (movie in list) {
                val slugKey = canonicalSlugKey(movie)
                val titleKey = canonicalTitleKey(movie.title)
                val matchedKey = when {
                    resultsMap.containsKey(slugKey) -> slugKey
                    titleKey.isNotEmpty() && titleToKey[titleKey]?.let { existingK ->
                        val prev = resultsMap[existingK]
                        prev != null && (isDeadOrBlankPoster(prev.poster) || isDeadOrBlankPoster(movie.poster) || prev.slug == movie.slug)
                    } == true -> titleToKey.getValue(titleKey)
                    else -> slugKey
                }
                val existing = resultsMap[matchedKey]
                if (existing == null) {
                    resultsMap[matchedKey] = movie
                    if (titleKey.isNotEmpty() && !titleToKey.containsKey(titleKey)) {
                        titleToKey[titleKey] = matchedKey
                    }
                } else {
                    resultsMap[matchedKey] = mergeMoviePair(existing, movie)
                }
            }
        }
        return resultsMap.values.toList()
    }

    suspend fun fetchCategoriesForSource(
        sourceId: String,
        forceRefresh: Boolean = false
    ): List<GenreItem> {
        val cacheKey = "cats_v6_$sourceId"
        if (!forceRefresh) {
            getCached<List<GenreItem>>(cacheKey)?.let { if (it.isNotEmpty()) return normalizeLeadingGenre(it) }
        }

        val customSrc = PrefsStore.getSources().firstOrNull { it.id == sourceId && it.isCustom }
        if (customSrc != null) {
            val customCats = CustomSourceParser.fetchCategories(customSrc, forceRefresh = forceRefresh)
            if (customCats.isNotEmpty()) {
                val normalized = normalizeLeadingGenre(customCats)
                setCached(cacheKey, normalized, CAT_CACHE_TTL_MS)
                return normalized
            }
        }

        if (sourceId == Phim500SourceProvider.SOURCE_ID) {
            val cats = Phim500SourceProvider.fetchCategoriesForApp(forceRefresh = forceRefresh)
            if (cats.isNotEmpty()) {
                val normalized = normalizeLeadingGenre(cats)
                setCached(cacheKey, normalized, CAT_CACHE_TTL_MS)
                return normalized
            }
        }
        if (sourceId == NguonCSourceProvider.SOURCE_ID) {
            val cats = NguonCSourceProvider.fetchCategoriesForApp(forceRefresh = forceRefresh)
            if (cats.isNotEmpty()) {
                val normalized = normalizeLeadingGenre(cats)
                setCached(cacheKey, normalized, CAT_CACHE_TTL_MS)
                return normalized
            }
        }
        if (sourceId == KKPhimSourceProvider.SOURCE_ID) {
            val cats = KKPhimSourceProvider.fetchCategoriesForApp(forceRefresh = forceRefresh)
            if (cats.isNotEmpty()) {
                val normalized = normalizeLeadingGenre(cats)
                setCached(cacheKey, normalized, CAT_CACHE_TTL_MS)
                return normalized
            }
        }
        if (sourceId == VsmovSourceProvider.SOURCE_ID) {
            val cats = VsmovSourceProvider.fetchCategoriesForApp(forceRefresh = forceRefresh)
            if (cats.isNotEmpty()) {
                val normalized = normalizeLeadingGenre(cats)
                setCached(cacheKey, normalized, CAT_CACHE_TTL_MS)
                return normalized
            }
        }
        if (sourceId == "YanHH3DNow") {
            val cats = YanHh3dNowScraper.fetchCategories(
                YanHh3dNowScraper.YAN_NOW_BASE,
                "YanHH3D Now",
                forceRefresh = forceRefresh
            )
            if (cats.isNotEmpty()) {
                val normalized = normalizeLeadingGenre(cats)
                setCached(cacheKey, normalized, CAT_CACHE_TTL_MS)
                return normalized
            }
        }
        if (sourceId == "YanHH3D") {
            val tax = YanHh3dScraper.fetchTaxonomies(
                YanHh3dScraper.YAN_BASE,
                forceRefresh = forceRefresh
            )
            if (tax.genres.isNotEmpty()) {
                val normalized = normalizeLeadingGenre(tax.genres)
                setCached(cacheKey, normalized, CAT_CACHE_TTL_MS)
                return normalized
            }
        }
        return normalizeLeadingGenre(InitialData.GENRES_DATA)
    }

    suspend fun fetchGenrePage(
        sourceId: String,
        genre: GenreItem,
        page: Int = 1,
        limit: Int = 30,
        forceRefresh: Boolean = false
    ): CatalogPageResult {
        val cacheKey = "src_genre_page_v7_${sourceId}_${genre.id}_${genre.sourceSlug ?: ""}_${genre.wpCategoryId ?: ""}_${genre.wpStatusId ?: ""}_$page"
        if (!forceRefresh) {
            getCached<CatalogPageResult>(cacheKey)?.let { if (it.movies.isNotEmpty()) return it }
        }

        return try {
            val customSrc = PrefsStore.getSources().firstOrNull { it.id == sourceId && it.isCustom }
            val topicSlug = genre.sourceSlug ?: ""
            val result: CatalogPageResult = if (customSrc != null) {
                CustomSourceParser.fetchMoviesByCategory(customSrc, genre, page)
            } else if (sourceId == Phim500SourceProvider.SOURCE_ID) {
                Phim500SourceProvider.fetchGenreCatalogForApp(genre, page)
            } else if (sourceId == NguonCSourceProvider.SOURCE_ID) {
                NguonCSourceProvider.fetchGenreCatalogForApp(genre, page)
            } else if (sourceId == KKPhimSourceProvider.SOURCE_ID) {
                KKPhimSourceProvider.fetchGenreCatalogForApp(genre, page)
            } else if (sourceId == VsmovSourceProvider.SOURCE_ID) {
                VsmovSourceProvider.fetchGenreCatalogForApp(genre, page)
            } else if (sourceId == "YanHH3DNow") {
                resolveNowTopicPage(topicSlug, genre.id, page, forceRefresh)
            } else if (topicSlug == "yanhh3d-moi-cap-nhat" || topicSlug == "top-yanhh3d-xem-nhieu") {
                val wpPage = YanHh3dScraper.fetchArchiveHtmlPage(
                    baseUrl = YanHh3dScraper.YAN_BASE,
                    path = topicSlug,
                    sourceName = "YanHH3D",
                    page = page,
                    forcedGenre = genre.id,
                    bypassCache = forceRefresh || topicSlug == "top-yanhh3d-xem-nhieu"
                )
                CatalogPageResult(
                    movies = wpPage.movies,
                    page = page,
                    totalPages = wpPage.totalPages.coerceAtLeast(if (wpPage.movies.isNotEmpty()) page else 1)
                )
            } else if (topicSlug.startsWith("topic:")) {
                resolveYanTopicPage(topicSlug, genre.id, page, limit, forceRefresh)
            } else {
                val postOptsId = if (topicSlug.startsWith("post_options:")) {
                    topicSlug.substringAfter("post_options:").toIntOrNull()
                } else null
                val res = YanHh3dScraper.fetchMovies(
                    baseUrl = YanHh3dScraper.YAN_BASE,
                    sourceName = "YanHH3D",
                    page = page,
                    perPage = limit,
                    categoryId = genre.wpCategoryId ?: genre.yanCategoryId,
                    statusId = genre.wpStatusId ?: genre.yanStatusId,
                    postOptionsId = postOptsId
                )
                val tagged = res.movies.map { m ->
                    if (genre.id != "Tất cả" &&
                        genre.id != "Đang chiếu" &&
                        genre.id != "Hoàn thành" &&
                        genre.id != "Đề Cử Hot" &&
                        !m.genres.contains(genre.id)
                    ) {
                        m.copy(genres = listOf(genre.id) + m.genres)
                    } else m
                }
                CatalogPageResult(
                    movies = tagged,
                    page = page,
                    totalPages = res.totalPages.coerceAtLeast(if (tagged.isNotEmpty()) page else 1)
                )
            }
            if (result.movies.isNotEmpty()) setCached(cacheKey, result, CAT_CACHE_TTL_MS)
            result
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            CatalogPageResult(emptyList(), page, 1)
        }
    }

    private suspend fun resolveNowTopicPage(
        topicSlug: String,
        genreLabel: String,
        page: Int,
        forceRefresh: Boolean
    ): CatalogPageResult {
        val base = YanHh3dNowScraper.YAN_NOW_BASE
        val src = "YanHH3D Now"
        return when (topicSlug) {
            "topic:phim-hot" -> {
                val topHot = if (page == 1) {
                    runCatching { YanHh3dNowScraper.fetchRealtimeTrendingTop10(base, src) }.getOrDefault(emptyList())
                } else emptyList()
                val latest = YanHh3dNowScraper.fetchMoviesByPath(
                    "moi-cap-nhat",
                    page,
                    base,
                    src,
                    forcedGenre = genreLabel,
                    bypassCache = forceRefresh || page == 1
                )
                val merged = mergeCatalogPages(
                    topHot.map { m -> m.copy(genres = (listOf(genreLabel) + m.genres).distinct()) },
                    latest.movies
                )
                CatalogPageResult(merged, page, latest.totalPages.coerceAtLeast(1))
            }
            "topic:phim-chieu-rap" -> {
                val direct = YanHh3dNowScraper.fetchMoviesByPath("phim-chieu-rap", page, base, src, genreLabel, forceRefresh)
                if (direct.movies.isNotEmpty()) {
                    CatalogPageResult(direct.movies, page, direct.totalPages)
                } else {
                    val ova = YanHh3dNowScraper.fetchMoviesByPath("phim-le", page, base, src, genreLabel, forceRefresh)
                    val matched = if (page == 1 && ova.movies.isEmpty()) {
                        YanHh3dNowScraper.searchMovies("movie", base, src).map {
                            it.copy(genres = (listOf(genreLabel) + it.genres).distinct())
                        }
                    } else ova.movies
                    CatalogPageResult(matched, page, ova.totalPages.coerceAtLeast(1))
                }
            }
            "topic:phim-bo" -> {
                val direct = YanHh3dNowScraper.fetchMoviesByPath("phim-bo", page, base, src, genreLabel, forceRefresh)
                if (direct.movies.isNotEmpty()) {
                    CatalogPageResult(direct.movies, page, direct.totalPages)
                } else {
                    val series = YanHh3dNowScraper.fetchMoviesByPath("dang-chieu", page, base, src, genreLabel, forceRefresh)
                    CatalogPageResult(series.movies, page, series.totalPages.coerceAtLeast(1))
                }
            }
            "topic:phim-hanh-dong" -> {
                val direct = YanHh3dNowScraper.fetchMoviesByPath("the-loai/hanh-dong", page, base, src, genreLabel, forceRefresh)
                if (direct.movies.isNotEmpty()) {
                    CatalogPageResult(direct.movies, page, direct.totalPages)
                } else {
                    val kf = YanHh3dNowScraper.fetchMoviesByPath("the-loai/hhkungfu", page, base, src, genreLabel, forceRefresh)
                    val kh = YanHh3dNowScraper.fetchMoviesByPath("the-loai/kiem-hiep", page, base, src, genreLabel, forceRefresh)
                    val merged = mergeCatalogPages(kf.movies, kh.movies)
                    CatalogPageResult(merged, page, maxOf(kf.totalPages, kh.totalPages, 1))
                }
            }
            "topic:tinh-cam" -> {
                val direct = YanHh3dNowScraper.fetchMoviesByPath("the-loai/tinh-cam", page, base, src, genreLabel, forceRefresh)
                if (direct.movies.isNotEmpty()) {
                    CatalogPageResult(direct.movies, page, direct.totalPages)
                } else {
                    val cotrang = YanHh3dNowScraper.fetchMoviesByPath("the-loai/co-trang", page, base, src, genreLabel, forceRefresh)
                    val hiendai = YanHh3dNowScraper.fetchMoviesByPath("the-loai/hien-dai", page, base, src, genreLabel, forceRefresh)
                    val merged = mergeCatalogPages(hiendai.movies, cotrang.movies)
                    CatalogPageResult(merged, page, maxOf(cotrang.totalPages, hiendai.totalPages, 1))
                }
            }
            else -> {
                val nowPage = YanHh3dNowScraper.fetchMoviesByPath(
                    path = topicSlug.ifBlank { "moi-cap-nhat" },
                    page = page,
                    baseUrl = base,
                    sourceName = src,
                    forcedGenre = genreLabel,
                    bypassCache = forceRefresh
                )
                CatalogPageResult(
                    movies = nowPage.movies,
                    page = page,
                    totalPages = nowPage.totalPages.coerceAtLeast(if (nowPage.movies.isNotEmpty()) page else 1)
                )
            }
        }
    }

    private suspend fun resolveYanTopicPage(
        topicSlug: String,
        genreLabel: String,
        page: Int,
        limit: Int,
        forceRefresh: Boolean
    ): CatalogPageResult {
        val base = YanHh3dScraper.YAN_BASE
        val src = "YanHH3D"
        return when (topicSlug) {
            "topic:phim-chieu-rap", "topic:phim-le" -> {
                val arch = YanHh3dScraper.fetchArchiveHtmlPage(base, "yanhh3d-moi-cap-nhat", src, page, genreLabel, forceRefresh)
                val singleEp = arch.movies.filter { it.totalEp <= 2 }
                val list = (if (singleEp.size >= 3) singleEp else arch.movies).map {
                    it.copy(genres = (listOf(genreLabel) + it.genres).distinct())
                }
                CatalogPageResult(list, page, arch.totalPages)
            }
            "topic:phim-bo" -> {
                val wp = YanHh3dScraper.fetchMovies(base, src, page, limit)
                val list = wp.movies.filter { it.totalEp > 1 || it.latestEp > 1 }.ifEmpty { wp.movies }.map {
                    it.copy(genres = (listOf(genreLabel) + it.genres).distinct())
                }
                CatalogPageResult(list, page, wp.totalPages.coerceAtLeast(1))
            }
            "topic:phim-hanh-dong" -> {
                val wp = YanHh3dScraper.fetchMovies(base, src, page, limit, categoryId = 23)
                val list = wp.movies.map { it.copy(genres = (listOf(genreLabel) + it.genres).distinct()) }
                CatalogPageResult(list, page, wp.totalPages.coerceAtLeast(1))
            }
            "topic:tinh-cam" -> {
                val wp = YanHh3dScraper.fetchMovies(base, src, page, limit, categoryId = 15)
                val list = wp.movies.map { it.copy(genres = (listOf(genreLabel) + it.genres).distinct()) }
                CatalogPageResult(list, page, wp.totalPages.coerceAtLeast(1))
            }
            else -> {
                val wp = YanHh3dScraper.fetchMovies(base, src, page, limit)
                CatalogPageResult(wp.movies, page, wp.totalPages.coerceAtLeast(1))
            }
        }
    }

    suspend fun fetchBySourceAndGenre(
        sourceId: String,
        genre: GenreItem,
        page: Int = 1,
        limit: Int = 30
    ): List<DonghuaMovie> = fetchGenrePage(sourceId, genre, page, limit).movies

    suspend fun fetchDonghuaPage(
        page: Int = 1,
        limit: Int = 30,
        preferredSourceId: String = "YanHH3DNow",
        bypassCache: Boolean = false
    ): CatalogPageResult {
        val cacheKey = "page_src_v5_${preferredSourceId}_${page}_$limit"
        if (!bypassCache) {
            getCached<CatalogPageResult>(cacheKey)?.let { if (it.movies.isNotEmpty()) return it }
        }

        // Check if custom source
        val customSrc = PrefsStore.getSources().firstOrNull { it.id == preferredSourceId && it.isCustom }
        if (customSrc != null) {
            val out = CustomSourceParser.fetchMoviesByCategory(
                customSrc,
                GenreItem(id = "Tất cả", label = "Thể loại phim", icon = "grid"),
                page
            )
            if (out.movies.isNotEmpty()) setCached(cacheKey, out, CAT_CACHE_TTL_MS)
            return out
        }

        val primary = runCatching {
            when (preferredSourceId) {
                Phim500SourceProvider.SOURCE_ID -> Phim500SourceProvider.fetchHomeCatalogForApp(page)
                NguonCSourceProvider.SOURCE_ID -> NguonCSourceProvider.fetchHomeCatalogForApp(page)
                KKPhimSourceProvider.SOURCE_ID -> KKPhimSourceProvider.fetchHomeCatalogForApp(page)
                VsmovSourceProvider.SOURCE_ID -> VsmovSourceProvider.fetchHomeCatalogForApp(page)
                "YanHH3D" -> {
                    val res = YanHh3dScraper.fetchMovies(YanHh3dScraper.YAN_BASE, "YanHH3D", page, limit, bypassCache = bypassCache)
                    CatalogPageResult(res.movies, page, res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1))
                }
                else -> {
                    val res = YanHh3dNowScraper.fetchMoviesByPath("moi-cap-nhat", page, bypassCache = bypassCache)
                    CatalogPageResult(res.movies, page, res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1))
                }
            }
        }.getOrDefault(CatalogPageResult(emptyList(), page, 1))

        if (primary.movies.isNotEmpty()) {
            setCached(cacheKey, primary)
            return primary
        }
        if (page > 1) return CatalogPageResult(emptyList(), page, page)

        val fallback = runCatching {
            if (preferredSourceId == "YanHH3D") {
                val res = YanHh3dNowScraper.fetchMoviesByPath("moi-cap-nhat", page, bypassCache = bypassCache)
                CatalogPageResult(res.movies, page, res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1))
            } else {
                val res = YanHh3dScraper.fetchMovies(YanHh3dScraper.YAN_BASE, "YanHH3D", page, limit, bypassCache = bypassCache)
                CatalogPageResult(res.movies, page, res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1))
            }
        }.getOrDefault(CatalogPageResult(emptyList(), page, 1))

        if (fallback.movies.isNotEmpty()) {
            setCached(cacheKey, fallback)
        }
        return fallback
    }

    /**
     * Đồng bộ trực tiếp danh sách phim theo Trạng thái (Đang chiếu / Hoàn thành) từ nguồn đang chọn.
     */
    suspend fun fetchStatusCatalogPage(
        sourceId: String,
        statusFilter: CatalogStatusFilter,
        page: Int = 1
    ): CatalogPageResult {
        if (statusFilter == CatalogStatusFilter.ALL) {
            return fetchDonghuaPage(page, 30, sourceId)
        }
        val wantCompleted = statusFilter == CatalogStatusFilter.COMPLETED
        return runCatching {
            when (sourceId) {
                "YanHH3DNow" -> {
                    val path = if (wantCompleted) "hoan-thanh" else "dang-chieu"
                    val res = YanHh3dNowScraper.fetchMoviesByPath(
                        path = path,
                        page = page,
                        baseUrl = YanHh3dNowScraper.YAN_NOW_BASE,
                        sourceName = "YanHH3D Now",
                        forcedGenre = if (wantCompleted) "Hoàn thành" else "Đang chiếu",
                        bypassCache = page == 1
                    )
                    CatalogPageResult(
                        movies = res.movies,
                        page = page,
                        totalPages = res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1)
                    )
                }
                "YanHH3D" -> {
                    val res = if (wantCompleted) {
                        YanHh3dScraper.fetchMovies(
                            baseUrl = YanHh3dScraper.YAN_BASE,
                            sourceName = "YanHH3D",
                            page = page,
                            perPage = 30,
                            statusId = 12,
                            bypassCache = page == 1
                        )
                    } else {
                        YanHh3dScraper.fetchMovies(
                            baseUrl = YanHh3dScraper.YAN_BASE,
                            sourceName = "YanHH3D",
                            page = page,
                            perPage = 30,
                            bypassCache = page == 1
                        )
                    }
                    val filtered = res.movies.filter { CatalogQueryEngine.matchesStatusFilter(it, statusFilter) }
                    CatalogPageResult(filtered, page, res.totalPages.coerceAtLeast(1))
                }
                else -> {
                    val p1 = fetchDonghuaPage(page, 30, sourceId, bypassCache = page == 1)
                    val extra = if (page == 1 && p1.totalPages > 1) {
                        fetchDonghuaPage(2, 30, sourceId)
                    } else {
                        CatalogPageResult(emptyList(), page, p1.totalPages)
                    }
                    val combined = mergeCatalogPages(p1.movies, extra.movies)
                    val matched = combined.filter { CatalogQueryEngine.matchesStatusFilter(it, statusFilter) }
                    CatalogPageResult(matched, page, maxOf(p1.totalPages, 1))
                }
            }
        }.getOrDefault(CatalogPageResult(emptyList(), page, 1))
    }

    suspend fun fetchDonghuaList(
        page: Int = 1,
        limit: Int = 30,
        preferredSourceId: String = "YanHH3DNow"
    ): List<DonghuaMovie> = fetchDonghuaPage(page, limit, preferredSourceId).movies

    /**
     * Gộp trang phim mới vào danh sách hiện có, giữ nguyên thứ tự trước đó và khử trùng lặp theo slug/id/tên chuẩn hóa.
     */
    fun mergeCatalogPages(
        existing: List<DonghuaMovie>,
        incoming: List<DonghuaMovie>
    ): List<DonghuaMovie> {
        if (incoming.isEmpty()) return mergeMovieLists(listOf(existing))
        if (existing.isEmpty()) return mergeMovieLists(listOf(incoming))
        return mergeMovieLists(listOf(existing, incoming))
    }

    /**
     * Đồng bộ Top 10 BXH Thịnh Hành theo thời gian thực trực tiếp từ Nguồn chính đang hoạt động.
     */
    suspend fun fetchRealtimeTrendingTop10(preferredSourceId: String = "YanHH3DNow"): List<DonghuaMovie> {
        val customSrc = PrefsStore.getSources().firstOrNull { it.id == preferredSourceId && it.isCustom }
        if (customSrc != null) {
            val customTop = runCatching {
                CustomSourceParser.fetchFromCustomSource(customSrc).take(10)
            }.getOrDefault(emptyList())
            if (customTop.isNotEmpty()) return customTop
        }

        if (preferredSourceId == Phim500SourceProvider.SOURCE_ID) {
            val p500Top = runCatching {
                Phim500SourceProvider.fetchTrendingTop10ForApp()
            }.getOrDefault(emptyList())
            if (p500Top.isNotEmpty()) return p500Top
        }

        if (preferredSourceId == NguonCSourceProvider.SOURCE_ID) {
            val ncTop = runCatching {
                NguonCSourceProvider.fetchTrendingTop10ForApp()
            }.getOrDefault(emptyList())
            if (ncTop.isNotEmpty()) return ncTop
        }

        if (preferredSourceId == KKPhimSourceProvider.SOURCE_ID) {
            val kkTop = runCatching {
                KKPhimSourceProvider.fetchTrendingTop10ForApp()
            }.getOrDefault(emptyList())
            if (kkTop.isNotEmpty()) return kkTop
        }

        if (preferredSourceId == VsmovSourceProvider.SOURCE_ID) {
            val vsmovTop = runCatching {
                VsmovSourceProvider.fetchTrendingTop10ForApp()
            }.getOrDefault(emptyList())
            if (vsmovTop.isNotEmpty()) return vsmovTop
        }

        if (preferredSourceId == "YanHH3D") {
            val yanTop = runCatching {
                YanHh3dScraper.fetchArchiveHtmlMovies(
                    baseUrl = YanHh3dScraper.YAN_BASE,
                    path = "top-yanhh3d-xem-nhieu",
                    sourceName = "YanHH3D",
                    page = 1,
                    bypassCache = true
                ).take(10)
            }.getOrDefault(emptyList())
            if (yanTop.isNotEmpty()) return yanTop
        }

        val nowTop = runCatching {
            YanHh3dNowScraper.fetchRealtimeTrendingTop10(
                baseUrl = YanHh3dNowScraper.YAN_NOW_BASE,
                sourceName = "YanHH3D Now"
            ).take(10)
        }.getOrDefault(emptyList())
        if (nowTop.isNotEmpty()) return nowTop

        return runCatching {
            YanHh3dScraper.fetchArchiveHtmlMovies(
                baseUrl = YanHh3dScraper.YAN_BASE,
                path = "top-yanhh3d-xem-nhieu",
                sourceName = "YanHH3D",
                page = 1,
                bypassCache = true
            ).take(10)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchMovieDetail(
        slug: String,
        movie: DonghuaMovie? = null
    ): MovieDetailResult = fetchMovieDetailFast(slug, movie, null)

    /**
     * Fast-Path: Gọi song song tất cả nguồn, nhưng ngay khi nguồn ưu tiên trả về máy chủ HLS trực tiếp
     * thì bắn `onFastReady` tức thì (~300-800ms) để Player phát ngay không phải đợi nguồn dự phòng chậm.
     */
    suspend fun fetchMovieDetailFast(
        slug: String,
        movie: DonghuaMovie? = null,
        onFastReady: (suspend (MovieDetailResult) -> Unit)? = null
    ): MovieDetailResult {
        if (slug.isBlank() && movie?.yanUrl.isNullOrBlank() && movie?.wpSources.isNullOrEmpty() && movie?.customWebUrl.isNullOrBlank()) {
            return MovieDetailResult()
        }

        val cacheKey = "detail_v5_${slug.ifBlank { movie?.id ?: "" }}"
        getCached<MovieDetailResult>(cacheKey)?.let { cached ->
            if (cached.serverGroups.isNotEmpty()) {
                onFastReady?.invoke(cached)
                return cached
            }
        }

        val serverGroups = mutableListOf<ServerGroup>()
        var description = movie?.description.orEmpty()
        var poster = movie?.poster.orEmpty()
        var totalEp = 0
        var latestEp = 0

        val targetNormTitle = movie?.title?.let { VietnameseSearch.removeVietnameseTones(it) }.orEmpty()
        val isPhim500Movie = movie?.sources?.contains(Phim500SourceProvider.SOURCE_ID) == true ||
            movie?.sources?.contains(Phim500SourceProvider.SOURCE_NAME) == true ||
            movie?.wpSources?.any { it.baseUrl.contains("500phim.net", ignoreCase = true) } == true
        val isPhim500Enabled = runCatching {
            PrefsStore.getSources().any { it.id == Phim500SourceProvider.SOURCE_ID && it.enabled }
        }.getOrDefault(false)
        val isNguonCMovie = movie?.sources?.contains(NguonCSourceProvider.SOURCE_ID) == true ||
            movie?.wpSources?.any { it.baseUrl.contains("nguonc.com", ignoreCase = true) } == true
        val isNguonCEnabled = runCatching {
            PrefsStore.getSources().any { it.id == NguonCSourceProvider.SOURCE_ID && it.enabled }
        }.getOrDefault(false)
        val isKkPhimMovie = movie?.sources?.contains(KKPhimSourceProvider.SOURCE_ID) == true ||
            movie?.wpSources?.any {
                it.baseUrl.contains("kkphim", ignoreCase = true) || it.baseUrl.contains("phimapi.com", ignoreCase = true)
            } == true
        val isKkPhimEnabled = runCatching {
            PrefsStore.getSources().any { it.id == KKPhimSourceProvider.SOURCE_ID && it.enabled }
        }.getOrDefault(false)
        val isVsmovMovie = movie?.sources?.contains(VsmovSourceProvider.SOURCE_ID) == true ||
            movie?.wpSources?.any { it.baseUrl.contains("vsmov.com", ignoreCase = true) } == true
        val isVsmovEnabled = runCatching {
            PrefsStore.getSources().any { it.id == VsmovSourceProvider.SOURCE_ID && it.enabled }
        }.getOrDefault(false)

        return coroutineScope {
            val phim500Task = async {
                if (slug.isNotBlank() && (isPhim500Movie || isPhim500Enabled)) {
                    withTimeoutOrNull(6500L) {
                        Phim500SourceProvider.fetchDetailForApp(slug)
                    } ?: MovieDetailResult()
                } else {
                    MovieDetailResult()
                }
            }
            val nguonCTask = async {
                if ((slug.isNotBlank() || !movie?.title.isNullOrBlank()) && (isNguonCMovie || isNguonCEnabled)) {
                    withTimeoutOrNull(6000L) {
                        NguonCSourceProvider.fetchDetailForApp(slug, movie?.title)
                    } ?: MovieDetailResult()
                } else {
                    MovieDetailResult()
                }
            }
            val kkphimTask = async {
                if ((slug.isNotBlank() || !movie?.title.isNullOrBlank()) && (isKkPhimMovie || isKkPhimEnabled)) {
                    withTimeoutOrNull(6000L) {
                        KKPhimSourceProvider.fetchDetailForApp(slug, movie?.title)
                    } ?: MovieDetailResult()
                } else {
                    MovieDetailResult()
                }
            }
            val vsmovTask = async {
                if (slug.isNotBlank() && (isVsmovMovie || isVsmovEnabled)) {
                    withTimeoutOrNull(5000L) {
                        VsmovSourceProvider.fetchDetailForApp(slug)
                    } ?: MovieDetailResult()
                } else {
                    MovieDetailResult()
                }
            }
            val yanNowTask = async {
                withTimeoutOrNull(9000L) {
                    val nowUrl = movie?.wpSources?.firstOrNull { it.baseUrl == YanHh3dNowScraper.YAN_NOW_BASE }?.url
                        ?: movie?.yanUrl?.takeIf { it.contains("yanhh3d.now", ignoreCase = true) }
                    YanHh3dNowScraper.fetchMovieDetail(
                        slug = slug,
                        movieUrl = nowUrl,
                        movieTitle = movie?.title,
                        baseUrl = YanHh3dNowScraper.YAN_NOW_BASE,
                        sourceName = "YanHH3D Now",
                        fallbackDesc = movie?.description.orEmpty()
                    )
                } ?: MovieDetailResult()
            }
            val phimApiTask = async {
                // Chỉ gọi dự phòng trực tiếp NguonCApiClient khi KKPhim đang tắt trong Cài đặt
                if (!isKkPhimEnabled && !isKkPhimMovie && (slug.isNotBlank() || !movie?.title.isNullOrBlank())) {
                    withTimeoutOrNull(5500L) {
                        NguonCApiClient.fetchPhimApiDetail(slug, movie?.title)
                    }
                } else null
            }
            val yanTask = async {
                withTimeoutOrNull(6500L) {
                    val known = movie?.wpSources?.firstOrNull { it.baseUrl == YanHh3dScraper.YAN_BASE }
                    var targetUrl = known?.url ?: movie?.yanUrl?.takeIf { it.contains("yanhh3d.ee", ignoreCase = true) }
                    var postId = known?.postId ?: movie?.yanPostId ?: 0

                    if (targetUrl.isNullOrBlank() && slug.isNotBlank()) {
                        val exact = YanHh3dScraper.findByExactSlug(
                            baseUrl = YanHh3dScraper.YAN_BASE,
                            sourceName = "YanHH3D",
                            slug = slug
                        )
                        if (exact != null) {
                            targetUrl = exact.wpSources?.firstOrNull()?.url ?: exact.yanUrl
                            postId = exact.wpSources?.firstOrNull()?.postId ?: exact.yanPostId ?: 0
                        }
                    }

                    if (targetUrl.isNullOrBlank() && slug.isNotBlank()) {
                        runCatching {
                            val searchKw = movie?.title ?: slug.replace('-', ' ')
                            val res = YanHh3dScraper.fetchMovies(
                                baseUrl = YanHh3dScraper.YAN_BASE,
                                sourceName = "YanHH3D",
                                perPage = 10,
                                search = searchKw
                            )
                            val matched = res.movies.firstOrNull { m ->
                                m.slug == slug ||
                                    (targetNormTitle.isNotEmpty() && VietnameseSearch.removeVietnameseTones(m.title) == targetNormTitle)
                            } ?: res.movies.firstOrNull { m ->
                                val candNorm = VietnameseSearch.removeVietnameseTones(m.title)
                                targetNormTitle.isNotEmpty() &&
                                    candNorm.startsWith(targetNormTitle) &&
                                    !candNorm.contains("ova") &&
                                    !candNorm.contains("movie")
                            }
                            if (matched != null) {
                                targetUrl = matched.wpSources?.firstOrNull()?.url ?: matched.yanUrl
                                postId = matched.wpSources?.firstOrNull()?.postId ?: matched.yanPostId ?: 0
                            }
                        }
                    }
                    if (targetUrl.isNullOrBlank()) {
                        MovieDetailResult()
                    } else {
                        YanHh3dScraper.fetchMovieDetail(
                            baseUrl = YanHh3dScraper.YAN_BASE,
                            sourceName = "YanHH3D",
                            targetUrl = targetUrl!!,
                            fallbackPostId = postId,
                            fallbackDesc = movie?.description.orEmpty()
                        )
                    }
                } ?: MovieDetailResult()
            }
            val customWebTask = async {
                val cwUrl = movie?.customWebUrl
                if (!cwUrl.isNullOrBlank()) {
                    withTimeoutOrNull(5000L) {
                        CustomSourceParser.extractDirectStreamsFromWebUrl(cwUrl, movie.studio)
                    }
                } else null
            }

            var fastEmitted = false
            fun mergeDetailPart(part: MovieDetailResult) {
                if (part.serverGroups.isEmpty()) return
                serverGroups.addAll(part.serverGroups)
                if (description.isBlank() && !part.description.isNullOrBlank()) description = part.description
                if (isDeadOrBlankPoster(poster) && !isDeadOrBlankPoster(part.poster)) {
                    poster = part.poster.orEmpty()
                }
                part.latestEp?.let { latestEp = maxOf(latestEp, it) }
                part.totalEp?.let { totalEp = maxOf(totalEp, it) }
            }
            suspend fun emitFastReadyIfPossible() {
                if (!fastEmitted && onFastReady != null && serverGroups.any { isDirectStreamServer(it) }) {
                    fastEmitted = true
                    onFastReady.invoke(
                        MovieDetailResult(
                            serverGroups = serverGroups.toList(),
                            description = description.ifBlank { null },
                            poster = poster.ifBlank { null },
                            totalEp = if (totalEp > 0) totalEp else null,
                            latestEp = if (latestEp > 0) latestEp else null
                        )
                    )
                }
            }

            if (isPhim500Movie) {
                mergeDetailPart(phim500Task.await())
                emitFastReadyIfPossible()
            }
            if (isNguonCMovie) {
                mergeDetailPart(nguonCTask.await())
                emitFastReadyIfPossible()
            }
            if (isKkPhimMovie) {
                mergeDetailPart(kkphimTask.await())
                emitFastReadyIfPossible()
            }
            if (isVsmovMovie) {
                mergeDetailPart(vsmovTask.await())
                emitFastReadyIfPossible()
            }

            mergeDetailPart(yanNowTask.await())
            emitFastReadyIfPossible()

            val phimApiRes = phimApiTask.await()
            if (phimApiRes != null && phimApiRes.optBoolean("status", false)) {
                val pm = phimApiRes.optJSONObject("movie")
                if (description.isBlank() && pm != null) {
                    description = pm.optString("content").replace(Regex("<[^>]+>"), "").trim()
                }
                if (poster.isBlank() && pm != null) {
                    val pUrl = pm.optString("poster_url")
                    if (pUrl.isNotBlank()) {
                        poster = if (pUrl.startsWith("http")) pUrl else "https://phimimg.com/${pUrl.trimStart('/')}"
                    }
                }
                val epsArr = phimApiRes.optJSONArray("episodes")
                var pmMaxEps = 0
                if (epsArr != null) {
                    for (si in 0 until epsArr.length()) {
                        val srv = epsArr.optJSONObject(si) ?: continue
                        val sData = srv.optJSONArray("server_data") ?: continue
                        val validEps = mutableListOf<EpisodeItem>()
                        val rawSrvName = srv.optString("server_name", "HLS")

                        for (ei in 0 until sData.length()) {
                            val ep = sData.optJSONObject(ei) ?: continue
                            val m3u8 = ep.optString("link_m3u8")
                            val embed = ep.optString("link_embed")
                            if (m3u8.isBlank() && embed.isBlank()) continue
                            val epSlug = ep.optString("slug")
                            val epRawName = ep.optString("name")
                            validEps.add(
                                EpisodeItem(
                                    name = if (epRawName.startsWith("Tập")) epRawName else "Tập $epRawName",
                                    slug = epSlug.ifEmpty { "tap-$epRawName" },
                                    linkM3u8 = m3u8,
                                    linkEmbed = embed
                                )
                            )
                        }
                        if (validEps.isNotEmpty()) {
                            pmMaxEps = maxOf(pmMaxEps, validEps.size)
                            val srvLabel = rawSrvName.replace(Regex("^#?\\w*\\s*-?\\s*", RegexOption.IGNORE_CASE), "").trim()
                                .ifEmpty { rawSrvName }
                            serverGroups.add(
                                ServerGroup(
                                    serverName = "Trình phát Tích hợp • $srvLabel",
                                    episodes = validEps
                                )
                            )
                        }
                    }
                }
                latestEp = maxOf(latestEp, pmMaxEps)
                totalEp = maxOf(totalEp, NguonCApiClient.parseFirstInt(pm?.opt("episode_total"), 0), pmMaxEps)

                if (!fastEmitted && onFastReady != null && serverGroups.any { isDirectStreamServer(it) }) {
                    fastEmitted = true
                    onFastReady.invoke(
                        MovieDetailResult(
                            serverGroups = serverGroups.toList(),
                            description = description.ifBlank { null },
                            poster = poster.ifBlank { null },
                            totalEp = if (totalEp > 0) totalEp else null,
                            latestEp = if (latestEp > 0) latestEp else null
                        )
                    )
                }
            }

            if (!isPhim500Movie) {
                mergeDetailPart(phim500Task.await())
            }
            if (!isNguonCMovie) {
                mergeDetailPart(nguonCTask.await())
            }
            if (!isKkPhimMovie) {
                mergeDetailPart(kkphimTask.await())
            }
            if (!isVsmovMovie) {
                mergeDetailPart(vsmovTask.await())
            }
            val yanRes = yanTask.await()
            val customSrv = customWebTask.await()

            if (yanRes.serverGroups.isNotEmpty()) {
                val nativeDirectYan = yanRes.serverGroups.filter { isDirectStreamServer(it) }
                if (nativeDirectYan.isNotEmpty()) {
                    serverGroups.addAll(nativeDirectYan)
                } else if (serverGroups.isEmpty()) {
                    serverGroups.addAll(yanRes.serverGroups)
                }
                if (description.isBlank() && !yanRes.description.isNullOrBlank()) description = yanRes.description
                if (isDeadOrBlankPoster(poster) && !isDeadOrBlankPoster(yanRes.poster)) {
                    poster = yanRes.poster.orEmpty()
                }
                yanRes.latestEp?.let { latestEp = maxOf(latestEp, it) }
                yanRes.totalEp?.let { totalEp = maxOf(totalEp, it) }
            }

            if (customSrv != null) {
                serverGroups.add(0, customSrv)
            }

            val dedupedServers = serverGroups
                .distinctBy { it.serverName.trim().lowercase() }
            val maxGroupEps = dedupedServers.maxOfOrNull { it.episodes.size } ?: 0
            latestEp = maxOf(latestEp, maxGroupEps, movie?.latestEp ?: 0)
            totalEp = maxOf(totalEp, latestEp, movie?.totalEp ?: 0)

            val result = MovieDetailResult(
                serverGroups = dedupedServers,
                description = description.ifBlank { null },
                poster = poster.takeUnless { isDeadOrBlankPoster(it) },
                totalEp = if (totalEp > 0) totalEp else null,
                latestEp = if (latestEp > 0) latestEp else null
            )
            if (dedupedServers.isNotEmpty()) setCached(cacheKey, result)
            if (!fastEmitted && onFastReady != null) {
                onFastReady.invoke(result)
            }
            result
        }
    }

    suspend fun searchDonghua(keyword: String): List<DonghuaMovie> {
        val exp = VietnameseSearch.expandVietnameseSearchQueries(keyword)
        if (exp.raw.isEmpty()) return emptyList()

        val primaryKw = exp.expanded.ifEmpty { exp.raw }
        val cacheKey = "search_v5_${primaryKw.lowercase()}"
        getCached<List<DonghuaMovie>>(cacheKey)?.let { return it }

        val queries = exp.apiKeywords.take(2)
        val allSources = runCatching { PrefsStore.getSources() }.getOrDefault(emptyList())
        val isPhim500Enabled = allSources.any { it.id == Phim500SourceProvider.SOURCE_ID && it.enabled }
        val isNguonCEnabled = allSources.any { it.id == NguonCSourceProvider.SOURCE_ID && it.enabled }
        val isKkPhimEnabled = allSources.any { it.id == KKPhimSourceProvider.SOURCE_ID && it.enabled }
        val isVsmovEnabled = allSources.any { it.id == VsmovSourceProvider.SOURCE_ID && it.enabled }
        val enabledCustomSources = allSources.filter { it.enabled && it.isCustom }
        val allLists = coroutineScope {
            val builtInTasks = queries.flatMap { kw ->
                listOf(
                    async {
                        runCatching {
                            YanHh3dNowScraper.searchMovies(kw, YanHh3dNowScraper.YAN_NOW_BASE, "YanHH3D Now")
                        }.getOrDefault(emptyList())
                    },
                    async {
                        runCatching {
                            YanHh3dScraper.fetchMovies(
                                baseUrl = YanHh3dScraper.YAN_BASE,
                                sourceName = "YanHH3D",
                                perPage = 20,
                                search = kw
                            ).movies
                        }.getOrDefault(emptyList())
                    },
                    async {
                        if (isVsmovEnabled) {
                            runCatching {
                                VsmovSourceProvider.searchAsDonghuaMovies(kw, limit = 20)
                            }.getOrDefault(emptyList())
                        } else emptyList()
                    },
                    async {
                        if (isPhim500Enabled) {
                            runCatching {
                                Phim500SourceProvider.searchAsDonghuaMovies(kw, limit = 20)
                            }.getOrDefault(emptyList())
                        } else emptyList()
                    },
                    async {
                        if (isNguonCEnabled) {
                            runCatching {
                                NguonCSourceProvider.searchAsDonghuaMovies(kw, limit = 20)
                            }.getOrDefault(emptyList())
                        } else emptyList()
                    },
                    async {
                        if (isKkPhimEnabled) {
                            runCatching {
                                KKPhimSourceProvider.searchAsDonghuaMovies(kw, limit = 20)
                            }.getOrDefault(emptyList())
                        } else emptyList()
                    }
                )
            }
            val customTasks = enabledCustomSources.map { customSrc ->
                async {
                    runCatching {
                        CustomSourceParser.searchCustomSource(customSrc, queries.firstOrNull() ?: exp.raw)
                    }.getOrDefault(emptyList())
                }
            }
            (builtInTasks + customTasks).awaitAll().filter { it.isNotEmpty() }
        }

        val merged = mergeMovieLists(allLists)
        val ranked = merged
            .map { it to VietnameseSearch.scoreMovieMatch(it, exp.raw) }
            .sortedWith(
                Comparator { a, b ->
                    if (b.second != a.second) b.second - a.second
                    else b.first.viewsNum.compareTo(a.first.viewsNum)
                }
            )
            .map { it.first }

        if (ranked.isNotEmpty()) setCached(cacheKey, ranked)
        return ranked
    }

    data class FavoritesCheckResult(
        val metaMap: Map<String, FavoriteEpisodeMeta>,
        val updatedMovies: List<DonghuaMovie>,
        val newlyUpdatedTitles: List<String>
    )

    suspend fun checkFavoritesNewEpisodes(
        favoriteIds: List<String>,
        knownMovies: List<DonghuaMovie>,
        deepCheckLimit: Int = 5
    ): FavoritesCheckResult = coroutineScope {
        val prevMeta = PrefsStore.getFavoriteMetaMap()
        val nextMeta = LinkedHashMap(prevMeta)
        val newlyUpdatedTitles = mutableListOf<String>()
        val updatedMovies = mutableListOf<DonghuaMovie>()

        val favMovies = favoriteIds.distinct().mapNotNull { favId ->
            knownMovies.firstOrNull { it.id == favId || it.slug == favId }
                ?: PrefsStore.getFavoriteMoviesMap()[favId]
        }
        val now = System.currentTimeMillis()
        val targets = favMovies.take(deepCheckLimit)

        val detailResults = targets.map { m ->
            async { runCatching { fetchMovieDetail(m.slug.orEmpty(), m) }.getOrNull() }
        }.awaitAll()

        val refreshedLatestById = HashMap<String, Int>()
        targets.forEachIndexed { idx, m ->
            val res = detailResults[idx]
            val ep = res?.latestEp
            if (res != null && ep != null && ep > 0) {
                refreshedLatestById[m.id] = ep
                val updated = m.copy(
                    latestEp = maxOf(m.latestEp, ep),
                    totalEp = maxOf(m.totalEp, res.totalEp ?: ep),
                    serverGroups = if (res.serverGroups.isNotEmpty()) res.serverGroups else m.serverGroups
                )
                updatedMovies.add(updated)
                PrefsStore.saveFavoriteMovieSnapshot(updated)
            }
        }

        for (m in favMovies) {
            val latestKnownEp = maxOf(m.latestEp.coerceAtLeast(1), refreshedLatestById[m.id] ?: 0)
            val existing = prevMeta[m.id]
            if (existing == null) {
                nextMeta[m.id] = FavoriteEpisodeMeta(
                    donghuaId = m.id,
                    lastSeenEp = latestKnownEp,
                    latestKnownEp = latestKnownEp,
                    newEpCount = 0,
                    lastCheckedAt = now
                )
            } else {
                val diff = (latestKnownEp - existing.lastSeenEp).coerceAtLeast(0)
                if (diff > 0 && latestKnownEp > existing.latestKnownEp) {
                    newlyUpdatedTitles.add("${m.title} (Tập $latestKnownEp)")
                }
                nextMeta[m.id] = FavoriteEpisodeMeta(
                    donghuaId = m.id,
                    lastSeenEp = existing.lastSeenEp,
                    latestKnownEp = latestKnownEp,
                    newEpCount = diff,
                    lastCheckedAt = now
                )
            }
        }

        PrefsStore.saveFavoriteMetaMap(nextMeta)
        FavoritesCheckResult(nextMeta, updatedMovies, newlyUpdatedTitles)
    }
}

object CatalogQueryEngine {

    fun matchesStatusFilter(movie: DonghuaMovie, statusFilter: CatalogStatusFilter): Boolean {
        return when (statusFilter) {
            CatalogStatusFilter.ALL -> true
            CatalogStatusFilter.ONGOING -> {
                val lbl = movie.statusLabel.orEmpty().lowercase()
                when {
                    lbl.contains("hoàn") || lbl.contains("full") || lbl.contains("completed") -> false
                    lbl.contains("đang") || lbl.contains("trailer") || lbl.contains("lên sóng") || lbl.contains("sắp") || lbl.contains("ongoing") -> true
                    else -> movie.totalEp <= 0 || movie.latestEp < movie.totalEp
                }
            }
            CatalogStatusFilter.COMPLETED -> {
                val lbl = movie.statusLabel.orEmpty().lowercase()
                when {
                    lbl.contains("hoàn") || lbl.contains("full") || lbl.contains("completed") -> true
                    lbl.contains("đang") || lbl.contains("trailer") || lbl.contains("lên sóng") || lbl.contains("sắp") || lbl.contains("ongoing") -> false
                    else -> movie.totalEp > 0 && movie.latestEp >= movie.totalEp
                }
            }
        }
    }

    fun matchesAudioFilter(movie: DonghuaMovie, audioFilter: CatalogAudioFilter): Boolean {
        return when (audioFilter) {
            CatalogAudioFilter.ALL -> true
            CatalogAudioFilter.DUBBED -> {
                val primaryDub = movie.audioModes.firstOrNull()?.let {
                    it.contains("thuyết", ignoreCase = true) || it.contains("lồng", ignoreCase = true)
                } == true
                val titleOrSlugDub = movie.title.contains("thuyết minh", ignoreCase = true) ||
                    movie.title.contains("lồng tiếng", ignoreCase = true) ||
                    (movie.slug?.contains("thuyet-minh", ignoreCase = true) == true)
                val serverDub = movie.serverGroups?.any { MovieRepository.isDubbedServerName(it.serverName) } == true
                primaryDub || titleOrSlugDub || serverDub
            }
            CatalogAudioFilter.SUB -> {
                val hasSubMode = movie.audioModes.any { it.contains("vietsub", ignoreCase = true) }
                val serverSub = movie.serverGroups?.any { !MovieRepository.isDubbedServerName(it.serverName) } == true
                hasSubMode || serverSub
            }
        }
    }

    fun matchesSearchSource(movie: DonghuaMovie, sourceFilter: String?): Boolean {
        val clean = sourceFilter?.trim().orEmpty()
        if (clean.isEmpty()) return true
        return movie.sources.any { it.equals(clean, ignoreCase = true) } ||
            movie.studio.equals(clean, ignoreCase = true)
    }

    fun countSearchHitsBySource(
        allMovies: List<DonghuaMovie>,
        remoteSearchResults: List<DonghuaMovie>,
        searchQuery: String,
        audioFilter: CatalogAudioFilter = CatalogAudioFilter.ALL,
        statusFilter: CatalogStatusFilter = CatalogStatusFilter.ALL
    ): List<Pair<String, Int>> {
        val cleanSearch = searchQuery.trim()
        if (cleanSearch.isEmpty()) return emptyList()
        val pooled = MovieRepository.mergeMovieLists(listOf(remoteSearchResults, allMovies))
        val counts = LinkedHashMap<String, Int>()
        pooled.forEach { movie ->
            if (matchesAudioFilter(movie, audioFilter) &&
                matchesStatusFilter(movie, statusFilter) &&
                VietnameseSearch.scoreMovieMatch(movie, cleanSearch) > 0
            ) {
                val srcNames = movie.sources.ifEmpty { listOf(movie.studio.ifBlank { "Kho phim" }) }
                srcNames.distinct().forEach { s ->
                    counts[s] = (counts[s] ?: 0) + 1
                }
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun filterAndSort(
        allMovies: List<DonghuaMovie>,
        activeGenreMovies: List<DonghuaMovie>?,
        remoteSearchResults: List<DonghuaMovie>,
        selectedGenre: String,
        searchQuery: String,
        audioFilter: CatalogAudioFilter = CatalogAudioFilter.ALL,
        sortMode: CatalogSortMode = CatalogSortMode.DEFAULT,
        statusFilter: CatalogStatusFilter = CatalogStatusFilter.ALL,
        searchSourceFilter: String? = null
    ): List<DonghuaMovie> {
        val cleanSearch = searchQuery.trim()
        if (cleanSearch.isNotEmpty()) {
            val pooled = MovieRepository.mergeMovieLists(listOf(remoteSearchResults, allMovies))
            val scored = pooled
                .asSequence()
                .filter {
                    matchesAudioFilter(it, audioFilter) &&
                        matchesStatusFilter(it, statusFilter) &&
                        matchesSearchSource(it, searchSourceFilter)
                }
                .map { it to VietnameseSearch.scoreMovieMatch(it, cleanSearch) }
                .filter { it.second > 0 }
                .toList()

            return when (sortMode) {
                CatalogSortMode.DEFAULT -> scored
                    .sortedWith(
                        Comparator { a, b ->
                            if (b.second != a.second) b.second - a.second
                            else b.first.viewsNum.compareTo(a.first.viewsNum)
                        }
                    )
                    .map { it.first }
                CatalogSortMode.MOST_EPISODES -> scored
                    .sortedWith(
                        Comparator { a, b ->
                            if (b.first.latestEp != a.first.latestEp) b.first.latestEp - a.first.latestEp
                            else if (b.first.totalEp != a.first.totalEp) b.first.totalEp - a.first.totalEp
                            else b.second - a.second
                        }
                    )
                    .map { it.first }
                CatalogSortMode.TOP_RATED -> scored
                    .sortedWith(
                        Comparator { a, b ->
                            val r = b.first.rating.compareTo(a.first.rating)
                            if (r != 0) r
                            else {
                                val v = b.first.viewsNum.compareTo(a.first.viewsNum)
                                if (v != 0) v else b.second - a.second
                            }
                        }
                    )
                    .map { it.first }
                CatalogSortMode.TITLE_AZ -> scored
                    .map { it.first }
                    .sortedBy { VietnameseSearch.removeVietnameseTones(it.title) }
            }
        }

        val genreFiltered = if (selectedGenre != "Tất cả") {
            if (!activeGenreMovies.isNullOrEmpty()) {
                activeGenreMovies
            } else {
                val needle = selectedGenre.lowercase()
                allMovies.filter { movie ->
                    val matchesStatus = movie.statusLabel?.lowercase() == needle
                    val matchesAudio = (needle.contains("thuyết minh") || needle.contains("lồng tiếng")) &&
                        matchesAudioFilter(movie, CatalogAudioFilter.DUBBED)
                    val matchesGenre = movie.genres.any { it.lowercase().contains(needle) }
                    matchesStatus || matchesAudio || matchesGenre
                }
            }
        } else {
            allMovies
        }

        val audioAndStatusFiltered = if (audioFilter == CatalogAudioFilter.ALL && statusFilter == CatalogStatusFilter.ALL) {
            genreFiltered
        } else {
            genreFiltered.filter {
                matchesAudioFilter(it, audioFilter) && matchesStatusFilter(it, statusFilter)
            }
        }

        return when (sortMode) {
            CatalogSortMode.DEFAULT -> audioAndStatusFiltered
            CatalogSortMode.MOST_EPISODES -> audioAndStatusFiltered.sortedWith(
                Comparator { a, b ->
                    if (b.latestEp != a.latestEp) b.latestEp - a.latestEp
                    else if (b.totalEp != a.totalEp) b.totalEp - a.totalEp
                    else b.viewsNum.compareTo(a.viewsNum)
                }
            )
            CatalogSortMode.TOP_RATED -> audioAndStatusFiltered.sortedWith(
                Comparator { a, b ->
                    val r = b.rating.compareTo(a.rating)
                    if (r != 0) r else b.viewsNum.compareTo(a.viewsNum)
                }
            )
            CatalogSortMode.TITLE_AZ -> audioAndStatusFiltered.sortedBy {
                VietnameseSearch.removeVietnameseTones(it.title)
            }
        }
    }
}
