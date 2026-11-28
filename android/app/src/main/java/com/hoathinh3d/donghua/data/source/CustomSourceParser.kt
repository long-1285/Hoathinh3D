package com.hoathinh3d.donghua.data.source

import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.data.model.CatalogPageResult
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.DonghuaSource
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.GenreItem
import com.hoathinh3d.donghua.data.model.ServerGroup
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

object CustomSourceParser {

    data class ProbeResult(
        val ok: Boolean,
        val hostname: String,
        val pingMs: Int,
        val detectedType: String,
        val quality: String,
        val note: String
    )

    suspend fun probeCustomSource(rawUrl: String, selectedType: String, selectedQuality: String): ProbeResult {
        val normalized = normalizeUrl(rawUrl)
        val host = extractHost(normalized)
        if (host.isBlank()) {
            return ProbeResult(false, "", 0, selectedType, selectedQuality, "URL không hợp lệ")
        }
        val pingMs = HttpClientProvider.measureLatencyMs(normalized)

        var fetchOk = false
        var autoDetected = selectedType
        var hintCount = 0
        try {
            val body = HttpClientProvider.getText(normalized, timeoutMs = 5500L)
            val trimmed = body.trim()
            fetchOk = trimmed.isNotEmpty()
            autoDetected = when {
                normalized.contains(".m3u8", ignoreCase = true) || trimmed.startsWith("#EXTM3U") -> {
                    hintCount = 1
                    "M3U8 Stream"
                }
                normalized.contains(".mp4", ignoreCase = true) -> {
                    hintCount = 1
                    "MP4 Direct"
                }
                trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                    hintCount = Regex("\"slug\"").findAll(trimmed).count()
                    "JSON / CMS API"
                }
                normalized.contains("vsmov.com", ignoreCase = true) -> {
                    hintCount = Regex("\"slug\"").findAll(trimmed).count().coerceAtLeast(10)
                    "JSON API + StreamVSMov"
                }
                normalized.contains("yanhh3d.now", ignoreCase = true) -> {
                    hintCount = Jsoup.parse(body, normalized).select(".flw-item").size
                    "Laravel Scraper + StreamRPT"
                }
                body.contains("wp-content", ignoreCase = true) -> {
                    hintCount = Jsoup.parse(body, normalized).select(".flw-item").size
                    "WP Scraper + AJAX"
                }
                body.contains("<iframe", ignoreCase = true) -> {
                    hintCount = Jsoup.parse(body, normalized).select("a[href]:has(img)").size
                    "Iframe Embed"
                }
                else -> {
                    hintCount = Jsoup.parse(body, normalized).select("a[href]:has(img)").size
                    selectedType
                }
            }
        } catch (_: Exception) {
            fetchOk = false
        }

        val note = when {
            !fetchOk -> "Không thể kết nối hoặc bị chặn truy cập ($host)"
            hintCount > 0 -> "Phát hiện ~$hintCount mục/luồng • Tương thích Media3 & AdBlock Shield Pro"
            else -> "Kết nối OK (${pingMs}ms) • Tương thích Media3 HLS/M3U8"
        }

        return ProbeResult(
            ok = fetchOk,
            hostname = host,
            pingMs = pingMs,
            detectedType = if (selectedType == "Smart Scraper") autoDetected else selectedType,
            quality = selectedQuality,
            note = note
        )
    }

    fun parseCategoriesFromHtml(html: String, baseUrl: String): List<GenreItem> {
        val origin = extractOrigin(baseUrl)
        val merged = LinkedHashMap<String, GenreItem>()
        merged["__all__"] = GenreItem(id = "Tất cả", label = "Thể loại phim", icon = "grid")

        val doc = Jsoup.parse(html, origin)
        val anchors = doc.select(
            "nav a[href], header a[href], .menu a[href], .navbar-nav a[href], .sub-menu a[href], .dropdown-menu a[href], a[href]"
        )
        val skipPaths = setOf("", "/", "home", "index", "login", "dang-nhap", "register", "dang-ky", "lien-he", "contact", "tim-kiem", "search", "lich-chieu")
        val catKeywords = listOf(
            "phim-hot", "phim-chieu-rap", "chieu-rap", "phim-le", "phim-bo", "hoat-hinh",
            "hanh-dong", "tinh-cam", "co-trang", "vien-tuong", "kinh-di", "hai-huoc",
            "vo-thuat", "than-thoai", "tam-ly", "phieu-luu", "bi-an", "hoc-duong",
            "tu-tien", "tien-hiep", "huyen-huyen", "kiem-hiep", "phim-moi"
        )
        for (a in anchors) {
            val rawHref = a.attr("href").trim()
            if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", true)) continue
            if (rawHref.contains("page=", true)) continue
            val absUrl = runCatching { a.absUrl("href").ifBlank { URI(origin).resolve(rawHref).toString() } }
                .getOrDefault(rawHref)
            val path = absUrl.replace(Regex("^https?://[^/]+"), "").trim('/')
            val lowPath = path.lowercase()
            if (lowPath in skipPaths || lowPath.endsWith(".jpg") || lowPath.endsWith(".png")) continue

            val matchesStructure = lowPath.startsWith("the-loai/") ||
                lowPath.startsWith("danh-sach/") ||
                lowPath.startsWith("loai-phim/") ||
                lowPath.startsWith("quoc-gia/") ||
                lowPath.startsWith("category/") ||
                lowPath.startsWith("genre/") ||
                lowPath.startsWith("phim-") ||
                lowPath.contains("?cat=") ||
                lowPath.contains("?genre=") ||
                catKeywords.any { lowPath == it || lowPath.endsWith("/$it") }
            if (!matchesStructure) continue

            val label = a.attr("title").ifBlank { a.text() }.replace(Regex("\\s+"), " ").trim()
            if (label.length < 2 || label.length > 36) continue
            val normKey = label.lowercase()
            if (!merged.containsKey(normKey) && merged.values.none { it.sourceSlug == absUrl }) {
                merged[normKey] = GenreItem(
                    id = label,
                    label = label,
                    icon = "tag",
                    sourceSlug = absUrl
                )
            }
        }
        return merged.values.toList()
    }

    fun parseCategoriesFromApiJson(jsonText: String, baseOrigin: String, isPhimApi: Boolean): List<GenreItem> {
        val merged = LinkedHashMap<String, GenreItem>()
        merged["__all__"] = GenreItem(id = "Tất cả", label = "Thể loại phim", icon = "grid")

        val fixedList = if (isPhimApi) {
            listOf(
                GenreItem("Phim Hot", "Phim Hot", "flame", sourceSlug = "$baseOrigin/v1/api/danh-sach/phim-moi-cap-nhat"),
                GenreItem("Phim Chiếu Rạp", "Phim Chiếu Rạp", "sparkles", sourceSlug = "$baseOrigin/v1/api/danh-sach/phim-chieu-rap"),
                GenreItem("Phim Lẻ", "Phim Lẻ", "film", sourceSlug = "$baseOrigin/v1/api/danh-sach/phim-le"),
                GenreItem("Phim Bộ", "Phim Bộ", "grid", sourceSlug = "$baseOrigin/v1/api/danh-sach/phim-bo"),
                GenreItem("Phim Hoạt Hình", "Phim Hoạt Hình", "dragon", sourceSlug = "$baseOrigin/v1/api/danh-sach/hoat-hinh")
            )
        } else {
            listOf(
                GenreItem("Phim Hot", "Phim Hot", "flame", sourceSlug = "$baseOrigin/api/films/phim-dang-chieu"),
                GenreItem("Phim Lẻ", "Phim Lẻ", "film", sourceSlug = "$baseOrigin/api/films/danh-sach/phim-le"),
                GenreItem("Phim Bộ", "Phim Bộ", "grid", sourceSlug = "$baseOrigin/api/films/danh-sach/phim-bo"),
                GenreItem("Phim Hoạt Hình", "Phim Hoạt Hình", "dragon", sourceSlug = "$baseOrigin/api/films/the-loai/hoat-hinh")
            )
        }
        fixedList.forEach { merged[it.id.lowercase()] = it }

        runCatching {
            val trimmed = jsonText.trim()
            val arr: JSONArray? = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("items") ?: obj.optJSONObject("data")?.optJSONArray("items")
                }
                else -> null
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    val slug = o.optString("slug").trim()
                    if (name.isBlank() || slug.isBlank()) continue
                    val ep = if (isPhimApi) {
                        "$baseOrigin/v1/api/the-loai/$slug"
                    } else {
                        "$baseOrigin/api/films/the-loai/$slug"
                    }
                    val catKey = name.lowercase()
                    if (!merged.containsKey(catKey)) {
                        merged[catKey] = GenreItem(id = name, label = name, icon = "tag", sourceSlug = ep)
                    }
                }
            }
        }
        return merged.values.toList()
    }

    suspend fun fetchCategories(source: DonghuaSource, forceRefresh: Boolean = false): List<GenreItem> {
        val url = normalizeUrl(source.url)
        val origin = extractOrigin(url)
        return try {
            if (url.contains("vsmov.com", ignoreCase = true)) {
                return VsmovSourceProvider.fetchCategoriesForApp(forceRefresh = forceRefresh)
            }
            if (url.contains("yanhh3d.now", ignoreCase = true)) {
                return YanHh3dNowScraper.fetchCategories(origin, source.name, forceRefresh = forceRefresh)
            }
            if (url.contains("phimapi.com", ignoreCase = true) || url.contains("kkphim", ignoreCase = true)) {
                val jsonText = runCatching {
                    HttpClientProvider.getText("$origin/the-loai", timeoutMs = 5500L)
                }.getOrDefault("")
                return parseCategoriesFromApiJson(jsonText, origin, isPhimApi = true)
            }
            if (url.contains("nguonc.com", ignoreCase = true)) {
                return parseCategoriesFromApiJson("", origin, isPhimApi = false)
            }
            val body = HttpClientProvider.getText(url, timeoutMs = 6500L)
            if (url.contains("yanhh3d", ignoreCase = true) || body.contains("wp-content")) {
                val tax = YanHh3dScraper.fetchTaxonomies(origin, forceRefresh = forceRefresh)
                if (tax.genres.isNotEmpty()) return tax.genres
            }
            val parsed = parseCategoriesFromHtml(body, origin)
            if (parsed.size > 1) parsed else listOf(GenreItem("Tất cả", "Thể loại phim", "grid"))
        } catch (_: Exception) {
            listOf(GenreItem("Tất cả", "Thể loại phim", "grid"))
        }
    }

    private fun extractHtmlMovies(
        body: String,
        pageUrl: String,
        source: DonghuaSource,
        forcedGenre: String? = null
    ): List<DonghuaMovie> {
        val doc = Jsoup.parse(body, pageUrl)
        val origin = extractOrigin(pageUrl)
        val found = mutableListOf<DonghuaMovie>()
        val seen = HashSet<String>()
        val cards = doc.select(".flw-item, .film-item, .movie-item, article")
        if (cards.isNotEmpty()) {
            for ((idx, card) in cards.take(30).withIndex()) {
                val a = card.selectFirst("a[href]:has(img), a[href]") ?: continue
                val href = a.absUrl("href").ifEmpty { a.attr("href") }
                if (href.isBlank() || !seen.add(href)) continue
                val img = card.selectFirst("img")
                val poster = MovieRepository.extractPosterUrl(img, origin)
                val title = a.attr("title")
                    .ifEmpty { card.selectFirst(".film-name, .name, h2, h3, .title")?.text().orEmpty() }
                    .ifEmpty { img?.attr("alt").orEmpty() }
                    .ifEmpty { a.text() }
                    .trim()
                if (title.length < 2) continue
                val genres = if (!forcedGenre.isNullOrBlank() && forcedGenre != "Tất cả") {
                    listOf(forcedGenre)
                } else {
                    listOf("Phim Hot")
                }
                found.add(
                    DonghuaMovie(
                        id = "custom_${source.id}_${href.hashCode().toUInt()}",
                        slug = href.trimEnd('/').substringAfterLast('/'),
                        title = title,
                        originalTitle = "Nguồn: ${source.hostname ?: source.name}",
                        studio = source.name,
                        latestEp = 1,
                        totalEp = 24,
                        rating = 9.5,
                        views = "1.5M",
                        viewsNum = 1.5,
                        quality = source.quality,
                        audioModes = listOf("Vietsub", "Thuyết Minh"),
                        sources = listOf(source.name),
                        genres = genres,
                        scheduleDay = "Chủ Nhật",
                        scheduleTime = "11:00",
                        poster = poster,
                        customWebUrl = href.ifEmpty { origin },
                        streamIndex = idx % 4
                    )
                )
            }
            if (found.isNotEmpty()) return found
        }

        val links = doc.select("a[href]:has(img)")
        for ((idx, a) in links.take(30).withIndex()) {
            val href = a.absUrl("href").ifEmpty { a.attr("href") }
            if (href.isBlank() || !seen.add(href)) continue
            val img = a.selectFirst("img")
            val poster = MovieRepository.extractPosterUrl(img, origin)
            val title = a.attr("title").ifEmpty { img?.attr("alt").orEmpty() }.ifEmpty { a.text() }.trim()
            if (title.length < 2) continue
            val genres = if (!forcedGenre.isNullOrBlank() && forcedGenre != "Tất cả") {
                listOf(forcedGenre)
            } else {
                listOf("Phim Hot")
            }
            found.add(
                DonghuaMovie(
                    id = "custom_${source.id}_$idx",
                    slug = href.trimEnd('/').substringAfterLast('/'),
                    title = title,
                    originalTitle = "Nguồn: ${source.hostname ?: source.name}",
                    studio = source.name,
                    latestEp = 1,
                    totalEp = 24,
                    rating = 9.5,
                    views = "1.5M",
                    viewsNum = 1.5,
                    quality = source.quality,
                    audioModes = listOf("Vietsub", "Thuyết Minh"),
                    sources = listOf(source.name),
                    genres = genres,
                    scheduleDay = "Chủ Nhật",
                    scheduleTime = "11:00",
                    poster = poster.ifEmpty { "https://phimimg.com/upload/vod/20231201-1/28004f285854bb69335ef000414e277d.jpg" },
                    customWebUrl = href.ifEmpty { origin },
                    streamIndex = idx % 4
                )
            )
        }
        return found
    }

    suspend fun fetchFromCustomSource(source: DonghuaSource): List<DonghuaMovie> {
        return fetchMoviesByUrl(normalizeUrl(source.url), source, page = 1, forcedGenre = null).movies
    }

    suspend fun fetchMoviesByCategory(
        source: DonghuaSource,
        genre: GenreItem,
        page: Int = 1
    ): CatalogPageResult {
        val origin = extractOrigin(source.url)
        val rawSpec = genre.sourceSlug?.trim().orEmpty()
        if (rawSpec.isBlank() || genre.id == "Tất cả") {
            return fetchMoviesByUrl(normalizeUrl(source.url), source, page = page, forcedGenre = null)
        }
        if (origin.contains("vsmov.com", ignoreCase = true)) {
            if (rawSpec.isBlank() || genre.id == "Tất cả") {
                return VsmovSourceProvider.fetchHomeCatalogForApp(page)
            }
            return VsmovSourceProvider.fetchGenreCatalogForApp(genre, page)
        }
        if (origin.contains("yanhh3d.now", ignoreCase = true) && !rawSpec.startsWith("http", true)) {
            val res = YanHh3dNowScraper.fetchMoviesByPath(
                path = rawSpec,
                page = page,
                baseUrl = origin,
                sourceName = source.name,
                forcedGenre = genre.id
            )
            return CatalogPageResult(res.movies, page, res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1))
        }
        if (genre.wpCategoryId != null || genre.wpStatusId != null) {
            val res = YanHh3dScraper.fetchMovies(
                baseUrl = origin,
                sourceName = source.name,
                page = page,
                perPage = 24,
                categoryId = genre.wpCategoryId,
                statusId = genre.wpStatusId
            )
            return CatalogPageResult(res.movies, page, res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1))
        }
        val targetUrl = if (rawSpec.startsWith("http", true)) {
            rawSpec
        } else {
            "$origin/${rawSpec.trimStart('/')}"
        }
        return fetchMoviesByUrl(targetUrl, source, page = page, forcedGenre = genre.id)
    }

    private suspend fun fetchMoviesByUrl(
        baseTargetUrl: String,
        source: DonghuaSource,
        page: Int,
        forcedGenre: String?
    ): CatalogPageResult {
        val pagedUrl = if (page <= 1) {
            baseTargetUrl
        } else {
            val sep = if (baseTargetUrl.contains("?")) "&" else "?"
            "${baseTargetUrl}${sep}page=$page"
        }
        return try {
            val body = HttpClientProvider.getText(pagedUrl, timeoutMs = 7000L)
            val trimmed = body.trim()
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val dataObj = obj.optJSONObject("data")
                val items = obj.optJSONArray("items") ?: dataObj?.optJSONArray("items")
                if (items != null) {
                    val mapped = if (dataObj != null) {
                        NguonCApiClient.mapPhimApiItems(items, source.name, forcedGenre = forcedGenre)
                    } else {
                        NguonCApiClient.mapNguonCItems(items, source.name, forcedGenre = forcedGenre)
                    }
                    val totalPages = dataObj?.optJSONObject("params")?.optJSONObject("pagination")
                        ?.optInt("totalPages", if (mapped.isNotEmpty()) page + 1 else page)
                        ?: obj.optJSONObject("paginate")?.optInt("total_page", if (mapped.isNotEmpty()) page + 1 else page)
                        ?: if (mapped.isNotEmpty()) page + 1 else page
                    return CatalogPageResult(mapped, page, totalPages.coerceAtLeast(page))
                }
            }
            if (pagedUrl.contains("yanhh3d.now", ignoreCase = true) && forcedGenre == null) {
                val res = YanHh3dNowScraper.fetchMoviesByPath(
                    path = "moi-cap-nhat",
                    page = page,
                    baseUrl = extractOrigin(pagedUrl),
                    sourceName = source.name
                )
                return CatalogPageResult(res.movies, page, res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1))
            }
            if (pagedUrl.contains("yanhh3d", ignoreCase = true) || body.contains("wp-content")) {
                val res = YanHh3dScraper.fetchMovies(
                    baseUrl = extractOrigin(pagedUrl),
                    sourceName = source.name,
                    page = page,
                    perPage = 24
                )
                return CatalogPageResult(res.movies, page, res.totalPages.coerceAtLeast(if (res.movies.isNotEmpty()) page else 1))
            }
            val list = extractHtmlMovies(body, pagedUrl, source, forcedGenre)
            val hasNextPage = Regex("""[?&]page=${page + 1}\b|/page/${page + 1}\b""").containsMatchIn(body)
            CatalogPageResult(list, page, if (hasNextPage) page + 1 else page)
        } catch (_: Exception) {
            CatalogPageResult(emptyList(), page, 1)
        }
    }

    suspend fun searchCustomSource(source: DonghuaSource, query: String): List<DonghuaMovie> {
        val url = normalizeUrl(source.url)
        return try {
            when {
                url.contains("vsmov.com", ignoreCase = true) -> {
                    VsmovSourceProvider.searchAsDonghuaMovies(query)
                }
                url.contains("yanhh3d.now", ignoreCase = true) -> {
                    YanHh3dNowScraper.searchMovies(query, extractOrigin(url), source.name)
                }
                url.contains("yanhh3d", ignoreCase = true) -> {
                    YanHh3dScraper.fetchMovies(
                        baseUrl = extractOrigin(url),
                        sourceName = source.name,
                        perPage = 20,
                        search = query
                    ).movies
                }
                else -> {
                    fetchFromCustomSource(source).filter {
                        VietnameseSearch.scoreMovieMatch(it, query) > 0
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun extractDirectStreamsFromWebUrl(webUrl: String, sourceName: String): ServerGroup? {
        val url = normalizeUrl(webUrl)
        if (url.contains(".m3u8", ignoreCase = true) || url.contains(".mp4", ignoreCase = true)) {
            return ServerGroup(
                serverName = "$sourceName • Direct Stream",
                episodes = listOf(EpisodeItem(name = "Tập 1", slug = "tap-1", linkM3u8 = url))
            )
        }
        return try {
            val html = HttpClientProvider.getText(url, mapOf("Referer" to url), 7000L)
            val m3u8 = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: ""
            val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1) ?: ""
            if (m3u8.isNotEmpty() || iframe.isNotEmpty()) {
                ServerGroup(
                    serverName = "$sourceName • Web Stream",
                    episodes = listOf(
                        EpisodeItem(
                            name = "Tập 1",
                            slug = "tap-1",
                            linkM3u8 = m3u8,
                            linkEmbed = iframe
                        )
                    )
                )
            } else {
                ServerGroup(
                    serverName = "$sourceName • Web Embed",
                    episodes = listOf(EpisodeItem(name = "Tập 1", slug = "tap-1", linkEmbed = url))
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun extractHost(url: String): String {
        return try {
            URI(normalizeUrl(url)).host.orEmpty().removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    fun extractOrigin(url: String): String {
        return try {
            val u = URI(normalizeUrl(url))
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            url
        }
    }
}
