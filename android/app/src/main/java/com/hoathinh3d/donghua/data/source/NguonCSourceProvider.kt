package com.hoathinh3d.donghua.data.source

import android.content.Context
import com.hoathinh3d.donghua.core.AppDiagnostics
import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.data.model.CatalogPageResult
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.GenreItem
import com.hoathinh3d.donghua.data.model.MovieDetailResult
import com.hoathinh3d.donghua.data.model.StreamResult
import com.hoathinh3d.donghua.player.EmbedFallbackView
import com.hoathinh3d.donghua.player.HeadlessWebViewSniffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Triển khai đầy đủ [MovieSourceProvider] cho https://phim.nguonc.com/.
 *
 * Kiến trúc khai thác:
 * - JSON API chính thức (`/api/films/phim-moi-cap-nhat`, `/api/films/the-loai/{slug}`,
 *   `/api/films/danh-sach/{slug}`, `/api/films/quoc-gia/{slug}`, `/api/films/nam-phat-hanh/{year}`,
 *   `/api/films/search?keyword=...`, `/api/film/{slug}`) kết hợp HTML Jsoup fallback.
 * - Giải mã stream trực tiếp `.m3u8`/`.mp4` hoặc tự động trích xuất / sniff qua
 *   [HeadlessWebViewSniffer] cho các iframe nhúng (`embed*.streamc.xyz`).
 * - Hỗ trợ CookieJar, User-Agent di động, Retry backoff & thực thi hoàn toàn trên `Dispatchers.IO`.
 */
object NguonCSourceProvider : MovieSourceProvider {

    const val BASE_URL = "https://phim.nguonc.com"
    const val SOURCE_ID = "NguonC"
    const val SOURCE_NAME = "NguonC"
    const val SERVER_TYPE_NGUONC = "nguonc"

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val CACHE_TTL_MS = 15L * 60L * 1000L
    private const val STREAM_CACHE_TTL_MS = 10L * 60L * 1000L

    override val sourceId: String = SOURCE_ID
    override val sourceName: String = SOURCE_NAME
    override val baseUrl: String = BASE_URL

    private data class CacheEntry(val value: Any, val expiresAtMs: Long)

    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val sessionCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val hostKey = rootDomainKey(url.host)
            val list = cookieStore.getOrPut(hostKey) { mutableListOf() }
            synchronized(list) {
                val now = System.currentTimeMillis()
                list.removeAll { old ->
                    old.expiresAt <= now || cookies.any { nc -> nc.name == old.name }
                }
                list.addAll(cookies.filter { it.expiresAt > now })
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostKey = rootDomainKey(url.host)
            val list = cookieStore[hostKey] ?: return emptyList()
            val now = System.currentTimeMillis()
            synchronized(list) {
                list.removeAll { it.expiresAt <= now }
                return list.toList()
            }
        }
    }

    private val nguonCClient: OkHttpClient by lazy {
        HttpClientProvider.okHttpClient.newBuilder()
            .cookieJar(sessionCookieJar)
            .connectTimeout(9, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun rootDomainKey(host: String): String {
        val parts = host.lowercase().split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host.lowercase()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getCached(key: String): T? {
        val hit = memoryCache[key] ?: return null
        return if (hit.expiresAtMs > System.currentTimeMillis()) hit.value as? T else null
    }

    private fun setCached(key: String, value: Any, ttlMs: Long = CACHE_TTL_MS) {
        memoryCache[key] = CacheEntry(value, System.currentTimeMillis() + ttlMs)
    }

    private suspend fun executeGetWithRetry(
        url: String,
        referer: String = "$BASE_URL/",
        accept: String = "application/json, text/html, */*;q=0.8",
        timeoutMs: Long = 10_000L,
        maxAttempts: Int = 3
    ): String = withContext(Dispatchers.IO) {
        val client = if (timeoutMs == 10_000L) {
            nguonCClient
        } else {
            nguonCClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }

        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", accept)
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8")
                    .header("Referer", referer)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 404) {
                        throw IOException("HTTP 404 for $url")
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} for $url")
                    }
                    return@withContext resp.body?.string().orEmpty()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                val isNotFound = t.message?.contains("404") == true
                if (attempt >= maxAttempts || isNotFound) break
                delay(250L * attempt)
            }
        }
        val msg = lastError?.message.orEmpty().take(80)
        AppDiagnostics.log("NguonC", "HTTP thất bại ($url): $msg")
        throw lastError ?: IOException("Failed $url")
    }

    fun encodeUrlParam(raw: String): String =
        URLEncoder.encode(raw.trim(), "UTF-8").replace("+", "%20")

    fun normalizeMediaUrl(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty() || clean == "null") return ""
        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> "$BASE_URL/${clean.trimStart('/')}"
        }
    }

    fun parseEpisodeNumber(raw: Any?, fallback: Int = 1): Int {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return fallback
        val ratio = Regex("""\((\d+)\s*/\s*\d+\)""").find(text)
        if (ratio != null) {
            return ratio.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: fallback
        }
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()?.takeIf { it > 0 } ?: fallback
    }

    data class PaginatedMovies(
        val items: List<MovieItem>,
        val currentPage: Int,
        val totalPages: Int,
        val totalItems: Int
    )

    private fun extractCategoryNames(catObj: JSONObject?): Triple<List<String>, List<String>, Int> {
        val genres = mutableListOf<String>()
        val countries = mutableListOf<String>()
        var parsedYear = 0
        if (catObj == null) return Triple(genres, countries, parsedYear)
        for (key in catObj.keys()) {
            val section = catObj.optJSONObject(key) ?: continue
            val groupName = section.optJSONObject("group")?.optString("name")?.trim().orEmpty().lowercase()
            val arr = section.optJSONArray("list") ?: continue
            for (i in 0 until arr.length()) {
                val itemName = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (itemName.isEmpty()) continue
                when {
                    groupName.contains("quốc gia") || groupName.contains("quoc gia") -> {
                        if (!countries.contains(itemName)) countries.add(itemName)
                    }
                    groupName.contains("năm") || groupName.contains("nam") -> {
                        if (parsedYear == 0) parsedYear = itemName.toIntOrNull() ?: 0
                    }
                    else -> {
                        if (!genres.contains(itemName)) genres.add(itemName)
                    }
                }
            }
        }
        return Triple(genres, countries, parsedYear)
    }

    fun parseMoviesListJson(jsonText: String): PaginatedMovies {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("{")) return PaginatedMovies(emptyList(), 1, 1, 0)
        return try {
            val root = JSONObject(trimmed)
            val statusStr = root.optString("status")
            if (statusStr.equals("error", ignoreCase = true) || root.optBoolean("status", true) == false) {
                return PaginatedMovies(emptyList(), 1, 1, 0)
            }
            val arr = root.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<MovieItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slug = o.optString("slug").trim()
                val title = o.optString("name").trim()
                if (slug.isEmpty() || title.isEmpty()) continue
                val originTitle = o.optString("original_name").trim().ifEmpty { title }
                val poster = normalizeMediaUrl(o.optString("poster_url_webp"))
                    .ifEmpty { normalizeMediaUrl(o.optString("poster_url")) }
                    .ifEmpty { normalizeMediaUrl(o.optString("thumb_url_webp")) }
                    .ifEmpty { normalizeMediaUrl(o.optString("thumb_url")) }
                val thumb = normalizeMediaUrl(o.optString("thumb_url_webp"))
                    .ifEmpty { normalizeMediaUrl(o.optString("thumb_url")) }
                    .ifEmpty { poster }
                val curRaw = o.optString("current_episode")
                val totRaw = o.opt("total_episodes")
                val latestEp = parseEpisodeNumber(curRaw, 1)
                val totalEp = maxOf(parseEpisodeNumber(totRaw, latestEp), latestEp)
                val lang = o.optString("language").trim()
                val audioModes = buildList {
                    if (lang.contains("thuyết", true) || lang.contains("thuyet", true)) add("Thuyết Minh")
                    if (lang.contains("lồng", true) || lang.contains("long", true)) add("Lồng Tiếng")
                    if (lang.contains("vietsub", true) || isEmpty()) add("Vietsub")
                }.distinct()
                val (genres, _, catYear) = extractCategoryNames(o.optJSONObject("category"))
                val year = o.optString("year").trim().toIntOrNull()
                    ?.takeIf { it > 1900 }
                    ?: catYear
                val statusLabel = when {
                    curRaw.contains("hoàn tất", true) || curRaw.contains("full", true) ||
                        (totalEp > 0 && latestEp >= totalEp && curRaw.isNotBlank()) -> "Hoàn thành"
                    else -> "Đang chiếu"
                }
                val rating = o.optJSONObject("tmdb")?.optDouble("vote_average", 0.0)
                    ?.takeIf { !it.isNaN() && it > 0.0 }
                    ?: o.optJSONObject("imdb")?.optDouble("vote_average", 0.0)
                        ?.takeIf { !it.isNaN() && it > 0.0 }
                    ?: 9.5

                items.add(
                    MovieItem(
                        id = o.optString("id").ifEmpty { slug },
                        title = title,
                        originTitle = originTitle,
                        posterUrl = poster,
                        thumbUrl = thumb,
                        latestEpisode = latestEp,
                        quality = o.optString("quality").trim().ifEmpty { "FHD" },
                        year = year,
                        sourceId = SOURCE_ID,
                        slug = slug,
                        rating = rating,
                        audioModes = audioModes,
                        genres = genres.ifEmpty { listOf("Hoạt Hình") },
                        totalEpisodes = totalEp,
                        viewCount = o.optLong("views", 0L),
                        statusLabel = statusLabel
                    )
                )
            }
            val pag = root.optJSONObject("paginate")
            val curPage = pag?.optInt("current_page", 1)?.coerceAtLeast(1) ?: 1
            val totPages = pag?.optInt("total_page", 1)?.coerceAtLeast(if (items.isNotEmpty()) curPage else 1) ?: 1
            val totItems = pag?.optInt("total_items", items.size) ?: items.size
            PaginatedMovies(items, curPage, totPages, totItems)
        } catch (_: Exception) {
            PaginatedMovies(emptyList(), 1, 1, 0)
        }
    }

    fun parseHtmlMovieCards(html: String, basePageUrl: String = BASE_URL): List<MovieItem> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, basePageUrl)
        val links = doc.select("a[href*=/phim/]")
        val seen = HashSet<String>()
        val out = mutableListOf<MovieItem>()
        for (a in links) {
            val href = a.absUrl("href").ifEmpty { a.attr("href") }
            val slug = href.substringAfter("/phim/", "").substringBefore('?').substringBefore('/').trim()
            if (slug.isEmpty() || !seen.add(slug)) continue
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            val title = a.attr("title")
                .ifBlank { img?.attr("alt").orEmpty() }
                .ifBlank { a.selectFirst("h2, h3, h4, .name, .title")?.text().orEmpty() }
                .ifBlank { a.text() }
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (title.length < 2) continue
            val poster = MovieRepository.extractPosterUrl(img, basePageUrl)
            out.add(
                MovieItem(
                    id = slug,
                    title = title,
                    originTitle = title,
                    posterUrl = poster,
                    thumbUrl = poster,
                    latestEpisode = 1,
                    quality = "FHD",
                    year = 0,
                    sourceId = SOURCE_ID,
                    slug = slug
                )
            )
            if (out.size >= 40) break
        }
        return out
    }

    fun parseTaxonomiesFromHtml(html: String, pathPrefix: String): List<TaxonomyOption> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, BASE_URL)
        val seen = HashSet<String>()
        val out = mutableListOf<TaxonomyOption>()
        for (a in doc.select("a[href*=$pathPrefix]")) {
            val href = a.attr("href").trim()
            val slug = href.substringAfter(pathPrefix, "").substringBefore('?').trim('/').trim()
            val name = a.text().replace(Regex("""\s+"""), " ").trim()
            if (slug.isEmpty() || name.isEmpty() || slug == "phim-18" || name.contains("18+", true)) continue
            if (seen.add(slug)) {
                out.add(TaxonomyOption(name = name, slug = slug))
            }
        }
        return out
    }

    fun parseMovieDetailJson(jsonText: String): MovieDetail? {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val root = JSONObject(trimmed)
            if (root.optString("status").equals("error", true)) return null
            val m = root.optJSONObject("movie") ?: return null
            val slug = m.optString("slug").trim()
            val id = m.optString("id").ifEmpty { slug }
            val title = m.optString("name").trim()
            if (title.isEmpty()) return null
            val originTitle = m.optString("original_name").trim().ifEmpty { title }
            val desc = m.optString("description")
                .takeIf { it != "null" }
                .orEmpty()
                .let { Jsoup.parse(it).text().trim() }
            val poster = normalizeMediaUrl(m.optString("poster_url_webp"))
                .ifEmpty { normalizeMediaUrl(m.optString("poster_url")) }
                .ifEmpty { normalizeMediaUrl(m.optString("thumb_url_webp")) }
                .ifEmpty { normalizeMediaUrl(m.optString("thumb_url")) }
            val thumb = normalizeMediaUrl(m.optString("thumb_url_webp"))
                .ifEmpty { normalizeMediaUrl(m.optString("thumb_url")) }
                .ifEmpty { poster }
            val (genres, countries, catYear) = extractCategoryNames(m.optJSONObject("category"))
            val year = m.optString("year").trim().toIntOrNull()?.takeIf { it > 1900 } ?: catYear

            val servers = mutableListOf<ServerData>()
            val epArr = m.optJSONArray("episodes")
            if (epArr != null) {
                for (si in 0 until epArr.length()) {
                    val srv = epArr.optJSONObject(si) ?: continue
                    val srvName = srv.optString("server_name", "Server ${si + 1}")
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                        .ifEmpty { "Server ${si + 1}" }
                    val items = srv.optJSONArray("items") ?: continue
                    val eps = ArrayList<Episode>(items.length())
                    for (ei in 0 until items.length()) {
                        val ep = items.optJSONObject(ei) ?: continue
                        val m3u8 = ep.optString("m3u8").trim().takeIf { it != "null" }.orEmpty()
                        val embed = ep.optString("embed").trim().takeIf { it != "null" }.orEmpty()
                        val playUrl = m3u8.ifBlank { embed }
                        if (playUrl.isBlank()) continue
                        val rawName = ep.optString("name").trim()
                        val epName = when {
                            rawName.isEmpty() -> "Tập ${ei + 1}"
                            rawName.startsWith("Tập", true) || rawName.equals("Full", true) -> rawName
                            else -> "Tập $rawName"
                        }
                        val epSlug = ep.optString("slug").trim().ifEmpty { "tap-${ei + 1}" }
                        eps.add(
                            Episode(
                                name = epName,
                                slug = epSlug,
                                playUrl = playUrl,
                                isDirectStream = m3u8.isNotBlank()
                            )
                        )
                    }
                    if (eps.isNotEmpty()) {
                        servers.add(ServerData(serverName = srvName, episodes = eps))
                    }
                }
            }

            // Ưu tiên server Thuyết Minh / Lồng Tiếng & server có link m3u8 trực tiếp
            val sortedServers = servers.sortedWith(
                Comparator { a, b ->
                    val aDirect = a.episodes.any { it.isDirectStream }
                    val bDirect = b.episodes.any { it.isDirectStream }
                    if (bDirect != aDirect) return@Comparator if (bDirect) 1 else -1
                    val aDub = MovieRepository.isDubbedServerName(a.serverName)
                    val bDub = MovieRepository.isDubbedServerName(b.serverName)
                    if (bDub != aDub) return@Comparator if (bDub) 1 else -1
                    b.episodes.size - a.episodes.size
                }
            )

            val maxServerEps = sortedServers.maxOfOrNull { it.episodes.size } ?: 0
            val curRaw = m.optString("current_episode")
            val latestEp = maxOf(parseEpisodeNumber(curRaw, 1), maxServerEps)
            val totalEp = maxOf(parseEpisodeNumber(m.opt("total_episodes"), latestEp), latestEp)
            val statusText = if (curRaw.contains("hoàn tất", true) || curRaw.contains("full", true)) {
                "completed"
            } else {
                "ongoing"
            }

            MovieDetail(
                id = id,
                title = title,
                originTitle = originTitle,
                description = desc,
                status = statusText,
                genres = genres,
                countries = countries,
                year = year,
                posterUrl = poster,
                thumbUrl = thumb,
                latestEpisode = latestEp,
                totalEpisodes = totalEp,
                quality = m.optString("quality").trim().ifEmpty { "FHD" },
                servers = sortedServers
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildStreamHeaders(streamUrl: String, refererUrl: String = "$BASE_URL/"): Map<String, String> {
        val origin = runCatching {
            val u = URI(refererUrl)
            if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) "${u.scheme}://${u.host}" else BASE_URL
        }.getOrDefault(BASE_URL)
        return mapOf(
            "Referer" to if (refererUrl.startsWith("http", true)) refererUrl else "$BASE_URL/",
            "Origin" to origin,
            "User-Agent" to MOBILE_UA
        )
    }

    // =====================================================================
    // Implement 6 core features of MovieSourceProvider
    // =====================================================================

    override suspend fun getHome(page: Int): ProviderHomeFeed = coroutineScope {
        val safePage = page.coerceAtLeast(1)
        val cacheKey = "nc_home_$safePage"
        getCached<ProviderHomeFeed>(cacheKey)?.let { return@coroutineScope it }

        val latestTask = async { fetchPaged("$BASE_URL/api/films/phim-moi-cap-nhat?page=$safePage") }
        val animeTask = async { fetchPaged("$BASE_URL/api/films/the-loai/hoat-hinh?page=$safePage") }
        val seriesTask = async {
            if (safePage == 1) fetchPaged("$BASE_URL/api/films/danh-sach/phim-bo?page=1")
            else PaginatedMovies(emptyList(), safePage, 1, 0)
        }
        val singleTask = async {
            if (safePage == 1) fetchPaged("$BASE_URL/api/films/danh-sach/phim-le?page=1")
            else PaginatedMovies(emptyList(), safePage, 1, 0)
        }

        val latest = latestTask.await()
        val anime = animeTask.await()
        val series = seriesTask.await()
        val single = singleTask.await()

        val feed = ProviderHomeFeed(
            spotlight = anime.items.ifEmpty { latest.items }.take(8),
            latestUpdated = latest.items.ifEmpty { anime.items },
            series = series.items,
            singleMovies = single.items,
            animations = anime.items,
            page = safePage,
            totalPages = maxOf(latest.totalPages, anime.totalPages, 1)
        )
        if (feed.latestUpdated.isNotEmpty() || feed.animations.isNotEmpty()) {
            setCached(cacheKey, feed)
        }
        feed
    }

    override suspend fun getByCategory(categorySlug: String, page: Int): List<MovieItem> {
        return getByCategoryPaged(categorySlug, page).items
    }

    private suspend fun getByCategoryPaged(categorySlug: String, page: Int): PaginatedMovies {
        val safePage = page.coerceAtLeast(1)
        val clean = categorySlug.trim().trim('/')
        val url = when {
            clean.isBlank() -> "$BASE_URL/api/films/the-loai/hoat-hinh?page=$safePage"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> {
                val sep = if (clean.contains("?")) "&" else "?"
                "$clean${sep}page=$safePage"
            }
            clean.startsWith("danh-sach/") || clean.startsWith("the-loai/") ||
                clean.startsWith("quoc-gia/") || clean.startsWith("nam-phat-hanh/") ->
                "$BASE_URL/api/films/$clean?page=$safePage"
            clean.contains(":") -> {
                val parts = clean.split(":", limit = 2)
                "$BASE_URL/api/films/${parts[0]}/${parts[1]}?page=$safePage"
            }
            clean in setOf("phim-bo", "phim-le", "phim-dang-chieu", "tv-shows") ->
                "$BASE_URL/api/films/danh-sach/$clean?page=$safePage"
            else -> "$BASE_URL/api/films/the-loai/$clean?page=$safePage"
        }
        return fetchPaged(url)
    }

    override suspend fun search(keyword: String, limit: Int): List<MovieItem> {
        val clean = keyword.trim()
        if (clean.length < 2) return emptyList()
        val safeLimit = limit.coerceIn(1, 40)
        val cacheKey = "nc_search_${clean.lowercase()}_$safeLimit"
        getCached<List<MovieItem>>(cacheKey)?.let { return it }

        val encoded = encodeUrlParam(clean)
        val page1 = fetchPaged("$BASE_URL/api/films/search?keyword=$encoded&page=1")
        val combined = LinkedHashMap<String, MovieItem>()
        page1.items.forEach { combined[it.slug] = it }
        if (combined.size < safeLimit && page1.totalPages > 1) {
            val page2 = fetchPaged("$BASE_URL/api/films/search?keyword=$encoded&page=2")
            page2.items.forEach { if (!combined.containsKey(it.slug)) combined[it.slug] = it }
        }
        val out = combined.values.take(safeLimit)
        if (out.isNotEmpty()) setCached(cacheKey, out)
        return out
    }

    override suspend fun filter(filter: MovieFilter): List<MovieItem> {
        val safePage = filter.page.coerceAtLeast(1)
        val cat = filter.categorySlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        val country = filter.countrySlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        val year = filter.year?.takeIf { it > 1900 }
        val type = filter.typeSlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }

        val endpoint = when {
            cat != null -> "$BASE_URL/api/films/the-loai/$cat?page=$safePage"
            country != null -> "$BASE_URL/api/films/quoc-gia/$country?page=$safePage"
            year != null -> "$BASE_URL/api/films/nam-phat-hanh/$year?page=$safePage"
            type != null -> "$BASE_URL/api/films/danh-sach/$type?page=$safePage"
            else -> "$BASE_URL/api/films/the-loai/hoat-hinh?page=$safePage"
        }
        val baseItems = fetchPaged(endpoint).items
        val yearFiltered = if (year != null && (cat != null || country != null)) {
            val matched = baseItems.filter { it.year == 0 || it.year == year }
            matched.ifEmpty { baseItems }
        } else {
            baseItems
        }
        return when (filter.sortField?.lowercase()) {
            "view", "views", "view_total", "xem-nhieu" -> yearFiltered.sortedByDescending { it.viewCount }
            "year", "publish_year", "nam" -> yearFiltered.sortedByDescending { it.year }
            else -> yearFiltered
        }
    }

    override suspend fun getGenres(): List<TaxonomyOption> {
        getCached<List<TaxonomyOption>>("nc_genres")?.let { return it }
        val htmlGenres = runCatching {
            val html = executeGetWithRetry("$BASE_URL/", accept = "text/html")
            parseTaxonomiesFromHtml(html, "/the-loai/")
        }.getOrDefault(emptyList())
        val finalList = htmlGenres.ifEmpty { DEFAULT_GENRES }
        setCached("nc_genres", finalList, 60L * 60L * 1000L)
        return finalList
    }

    override suspend fun getCountries(): List<TaxonomyOption> {
        getCached<List<TaxonomyOption>>("nc_countries")?.let { return it }
        val htmlCountries = runCatching {
            val html = executeGetWithRetry("$BASE_URL/", accept = "text/html")
            parseTaxonomiesFromHtml(html, "/quoc-gia/")
        }.getOrDefault(emptyList())
        val finalList = htmlCountries.ifEmpty { DEFAULT_COUNTRIES }
        setCached("nc_countries", finalList, 60L * 60L * 1000L)
        return finalList
    }

    override suspend fun getDetail(slugOrId: String): MovieDetail? {
        val slug = slugOrId.trim().substringAfter("/phim/").substringBefore('?').trim('/')
        if (slug.isBlank()) return null
        val cacheKey = "nc_detail_$slug"
        getCached<MovieDetail>(cacheKey)?.let { return it }

        val direct = runCatching {
            val raw = executeGetWithRetry("$BASE_URL/api/film/$slug")
            parseMovieDetailJson(raw)
        }.getOrNull()
        if (direct != null) {
            setCached(cacheKey, direct)
            return direct
        }
        return null
    }

    override suspend fun resolveVideoStream(episode: Episode): VideoStream? {
        return resolveVideoStream(episode, context = null)
    }

    suspend fun resolveVideoStream(episode: Episode, context: Context?): VideoStream? {
        val playUrl = episode.playUrl.trim()
        if (playUrl.isBlank()) return null
        val low = playUrl.lowercase()
        if (episode.isDirectStream || low.contains(".m3u8") || low.contains(".mp4")) {
            return VideoStream(streamUrl = playUrl, headers = buildStreamHeaders(playUrl, BASE_URL))
        }
        val cacheKey = "nc_stream_$playUrl"
        getCached<VideoStream>(cacheKey)?.let { return it }

        // Thử HTTP GET bóc tách JS/playerOptions trực tiếp từ iframe (VD: embed*.streamc.xyz)
        val extracted = runCatching {
            val html = executeGetWithRetry(
                url = playUrl,
                referer = "$BASE_URL/",
                accept = "text/html, */*;q=0.8",
                timeoutMs = 7000L,
                maxAttempts = 1
            )
            VsmovSourceProvider.extractStreamFromEmbedHtml(html, playUrl)
                ?: EmbedFallbackView.extractM3u8FromHtml(html)?.let { direct ->
                    VideoStream(streamUrl = direct, headers = buildStreamHeaders(direct, playUrl))
                }
        }.getOrNull()

        if (extracted != null && extracted.streamUrl.isNotBlank()) {
            setCached(cacheKey, extracted, STREAM_CACHE_TTL_MS)
            return extracted
        }

        // Nếu bị JS mã hóa hoặc Cloudflare: dùng HeadlessWebViewSniffer bắt .m3u8 kèm Referer/Origin/UA
        if (context != null && playUrl.startsWith("http", true)) {
            val sniffed = HeadlessWebViewSniffer.sniffVideoStream(
                context = context,
                embedUrl = playUrl,
                refererUrl = "$BASE_URL/",
                adBlock = true,
                timeoutMs = 6000L
            )
            if (sniffed != null && sniffed.streamUrl.isNotBlank()) {
                setCached(cacheKey, sniffed, STREAM_CACHE_TTL_MS)
                return sniffed
            }
        }
        return null
    }

    private suspend fun fetchPaged(apiUrl: String): PaginatedMovies {
        return runCatching {
            val json = executeGetWithRetry(apiUrl, accept = "application/json")
            val parsed = parseMoviesListJson(json)
            if (parsed.items.isNotEmpty()) parsed
            else {
                val htmlUrl = apiUrl.replace("/api/films/", "/").substringBefore("?")
                val html = executeGetWithRetry(htmlUrl, accept = "text/html")
                val fallback = parseHtmlMovieCards(html, BASE_URL)
                PaginatedMovies(fallback, 1, 1, fallback.size)
            }
        }.getOrDefault(PaginatedMovies(emptyList(), 1, 1, 0))
    }

    // =====================================================================
    // Bridges vào kiến trúc UI/Repository hiện có của HoatHinh3D
    // =====================================================================

    suspend fun fetchHomeCatalogForApp(page: Int = 1): CatalogPageResult {
        val safePage = page.coerceAtLeast(1)
        val p1 = getByCategoryPaged("the-loai/hoat-hinh", safePage)
        val p2 = if (safePage == 1 && p1.totalPages > 1) {
            getByCategoryPaged("the-loai/hoat-hinh", 2)
        } else {
            PaginatedMovies(emptyList(), safePage, p1.totalPages, p1.totalItems)
        }
        val combined = LinkedHashMap<String, MovieItem>()
        (p1.items + p2.items).forEach { if (!combined.containsKey(it.slug)) combined[it.slug] = it }
        val movies = combined.values.mapIndexed { idx, item ->
            item.toDonghuaMovie(BASE_URL, SOURCE_NAME, idx)
        }
        return CatalogPageResult(
            movies = movies,
            page = safePage,
            totalPages = maxOf(p1.totalPages, 1)
        )
    }

    suspend fun fetchCategoriesForApp(forceRefresh: Boolean = false): List<GenreItem> {
        if (forceRefresh) {
            memoryCache.remove("nc_genres")
            memoryCache.remove("nc_countries")
        }
        val list = LinkedHashMap<String, GenreItem>()
        list["__all__"] = GenreItem("Tất cả", "Thể loại phim", "grid", sourceSlug = "the-loai/hoat-hinh")

        val featured = listOf(
            GenreItem("Hoạt Hình", "Hoạt Hình (7.100+)", "dragon", sourceSlug = "the-loai/hoat-hinh"),
            GenreItem("Mới Cập Nhật", "Phim Mới Cập Nhật", "sparkles", sourceSlug = "danh-sach/phim-moi-cap-nhat"),
            GenreItem("Phim Bộ", "Phim Bộ", "grid", sourceSlug = "danh-sach/phim-bo"),
            GenreItem("Phim Lẻ", "Phim Lẻ", "film", sourceSlug = "danh-sach/phim-le"),
            GenreItem("Trung Quốc", "Trung Quốc", "dragon", sourceSlug = "quoc-gia/trung-quoc"),
            GenreItem("Nhật Bản", "Nhật Bản", "sparkles", sourceSlug = "quoc-gia/nhat-ban"),
            GenreItem("Hàn Quốc", "Hàn Quốc", "tag", sourceSlug = "quoc-gia/han-quoc")
        )
        featured.forEach { list[it.id.lowercase()] = it }

        for (g in getGenres()) {
            val key = g.name.lowercase()
            if (!list.containsKey(key)) {
                list[key] = GenreItem(
                    id = g.name,
                    label = g.name,
                    icon = "tag",
                    sourceSlug = "the-loai/${g.slug}"
                )
            }
        }
        return list.values.toList()
    }

    suspend fun fetchGenreCatalogForApp(genre: GenreItem, page: Int = 1): CatalogPageResult {
        val safePage = page.coerceAtLeast(1)
        val rawSpec = genre.sourceSlug?.trim()?.trim('/').orEmpty()
        val paged = if (rawSpec.isBlank() || genre.id == "Tất cả") {
            getByCategoryPaged("the-loai/hoat-hinh", safePage)
        } else if (rawSpec == "danh-sach/phim-moi-cap-nhat") {
            fetchPaged("$BASE_URL/api/films/phim-moi-cap-nhat?page=$safePage")
        } else {
            getByCategoryPaged(rawSpec, safePage)
        }
        val tagGenre = genre.id.takeIf { it.isNotBlank() && it != "Tất cả" }
        val movies = paged.items.mapIndexed { idx, item ->
            val mergedGenres = if (tagGenre != null && !item.genres.contains(tagGenre)) {
                listOf(tagGenre) + item.genres
            } else item.genres
            item.copy(genres = mergedGenres).toDonghuaMovie(BASE_URL, SOURCE_NAME, idx)
        }
        return CatalogPageResult(
            movies = movies,
            page = paged.currentPage,
            totalPages = paged.totalPages
        )
    }

    suspend fun fetchTrendingTop10ForApp(): List<DonghuaMovie> {
        val list = getByCategory("the-loai/hoat-hinh", 1).take(10)
        return list.mapIndexed { idx, item -> item.toDonghuaMovie(BASE_URL, SOURCE_NAME, idx) }
    }

    suspend fun searchAsDonghuaMovies(keyword: String, limit: Int = 20): List<DonghuaMovie> {
        return search(keyword, limit).mapIndexed { idx, item ->
            item.toDonghuaMovie(BASE_URL, SOURCE_NAME, idx)
        }
    }

    suspend fun fetchDetailForApp(slug: String, fallbackTitle: String? = null): MovieDetailResult {
        var detail = getDetail(slug)
        if (detail == null && !fallbackTitle.isNullOrBlank()) {
            val candidates = search(fallbackTitle, 6)
            val normTarget = VietnameseSearch.removeVietnameseTones(fallbackTitle)
            val matched = candidates.firstOrNull { c ->
                c.slug == slug || VietnameseSearch.removeVietnameseTones(c.title) == normTarget
            } ?: candidates.firstOrNull { c ->
                val cn = VietnameseSearch.removeVietnameseTones(c.title)
                normTarget.isNotEmpty() && cn.startsWith(normTarget) && !c.slug.contains("ova", true)
            }
            if (matched != null) {
                detail = getDetail(matched.slug)
            }
        }
        return detail?.toMovieDetailResult(
            sourceBaseUrl = BASE_URL,
            sourceLabel = SOURCE_NAME,
            wpServerType = SERVER_TYPE_NGUONC
        ) ?: MovieDetailResult()
    }

    suspend fun resolveStreamResult(playUrl: String, context: Context? = null): StreamResult {
        val ep = Episode(name = "Tập", slug = "tap", playUrl = playUrl, isDirectStream = false)
        val vs = resolveVideoStream(ep, context) ?: return StreamResult()
        val isDirect = vs.streamUrl.contains(".m3u8", true) || vs.streamUrl.contains(".mp4", true)
        return StreamResult(
            linkM3u8 = if (isDirect) vs.streamUrl else "",
            linkEmbed = if (isDirect) "" else vs.streamUrl,
            headers = vs.headers,
            subtitleUrl = vs.subtitleUrl,
            storyboardUrl = vs.storyboardUrl
        )
    }

    private val DEFAULT_GENRES = listOf(
        TaxonomyOption("Hoạt Hình", "hoat-hinh"),
        TaxonomyOption("Hành Động", "hanh-dong"),
        TaxonomyOption("Phiêu Lưu", "phieu-luu"),
        TaxonomyOption("Cổ Trang", "co-trang"),
        TaxonomyOption("Giả Tưởng", "gia-tuong"),
        TaxonomyOption("Khoa Học Viễn Tưởng", "khoa-hoc-vien-tuong"),
        TaxonomyOption("Hài Hước", "hai-huoc"),
        TaxonomyOption("Bí Ẩn", "bi-an"),
        TaxonomyOption("Chính Kịch", "chinh-kich"),
        TaxonomyOption("Lãng Mạn", "lang-man"),
        TaxonomyOption("Kinh Dị", "kinh-di")
    )

    private val DEFAULT_COUNTRIES = listOf(
        TaxonomyOption("Trung Quốc", "trung-quoc"),
        TaxonomyOption("Nhật Bản", "nhat-ban"),
        TaxonomyOption("Hàn Quốc", "han-quoc"),
        TaxonomyOption("Âu Mỹ", "my"),
        TaxonomyOption("Đài Loan", "dai-loan"),
        TaxonomyOption("Hồng Kông", "hong-kong")
    )
}
