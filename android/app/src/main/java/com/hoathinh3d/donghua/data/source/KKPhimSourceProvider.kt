package com.hoathinh3d.donghua.data.source

import android.content.Context
import android.net.Uri
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
 * Triển khai đầy đủ [MovieSourceProvider] cho https://kkphim.com/ (API chính thức https://phimapi.com/).
 *
 * Kiến trúc khai thác:
 * - JSON API chính thức của KKPhim (`phimapi.com`):
 *   - `/danh-sach/phim-moi-cap-nhat`, `/v1/api/danh-sach/{hoat-hinh|phim-bo|phim-le}`
 *   - `/v1/api/the-loai/{slug}`, `/v1/api/quoc-gia/{slug}`, `/v1/api/nam/{year}`
 *   - `/v1/api/tim-kiem?keyword=...`, `/the-loai`, `/quoc-gia`, `/phim/{slug}`
 *   - Fallback HTML Jsoup qua mirror `https://kkphim1.com` khi cần.
 * - Trích xuất trực tiếp `.m3u8` / `.mp4` hoặc bóc tham số `player.phimapi.com/player/?url=...`
 *   và fallback sang [HeadlessWebViewSniffer].
 * - Hỗ trợ CookieJar, User-Agent di động, Retry backoff & thực thi trên `Dispatchers.IO`.
 */
object KKPhimSourceProvider : MovieSourceProvider {

    const val WEB_BASE = "https://kkphim.com"
    const val WEB_MIRROR_BASE = "https://kkphim1.com"
    const val API_BASE = "https://phimapi.com"
    const val CDN_IMAGE_BASE = "https://phimimg.com"
    const val SOURCE_ID = "KKPhim"
    const val SOURCE_NAME = "KKPhim"
    const val SERVER_TYPE_KKPHIM = "kkphim"

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val CACHE_TTL_MS = 15L * 60L * 1000L
    private const val STREAM_CACHE_TTL_MS = 10L * 60L * 1000L

    override val sourceId: String = SOURCE_ID
    override val sourceName: String = SOURCE_NAME
    override val baseUrl: String = WEB_BASE

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

    private val kkPhimClient: OkHttpClient by lazy {
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
        referer: String = "$WEB_BASE/",
        accept: String = "application/json, text/html, */*;q=0.8",
        timeoutMs: Long = 10_000L,
        maxAttempts: Int = 3
    ): String = withContext(Dispatchers.IO) {
        val client = if (timeoutMs == 10_000L) {
            kkPhimClient
        } else {
            kkPhimClient.newBuilder()
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
        AppDiagnostics.log("KKPhim", "HTTP thất bại ($url): $msg")
        throw lastError ?: IOException("Failed $url")
    }

    fun encodeUrlParam(raw: String): String =
        URLEncoder.encode(raw.trim(), "UTF-8").replace("+", "%20")

    fun normalizeImageUrl(raw: String?, cdnBase: String = CDN_IMAGE_BASE): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty() || clean == "null") return ""
        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> "${cdnBase.trimEnd('/')}/${clean.trimStart('/')}"
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

    fun parseMoviesListJson(jsonText: String): PaginatedMovies {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("{")) return PaginatedMovies(emptyList(), 1, 1, 0)
        return try {
            val root = JSONObject(trimmed)
            val dataObj = root.optJSONObject("data")
            val cdnBase = dataObj?.optString("APP_DOMAIN_CDN_IMAGE")
                ?.takeIf { it.startsWith("http", true) }
                ?: CDN_IMAGE_BASE
            val arr = root.optJSONArray("items") ?: dataObj?.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<MovieItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slug = o.optString("slug").trim()
                val title = o.optString("name").trim()
                if (slug.isEmpty() || title.isEmpty()) continue
                val originTitle = o.optString("origin_name").trim().ifEmpty { title }
                val poster = normalizeImageUrl(o.optString("poster_url"), cdnBase)
                    .ifEmpty { normalizeImageUrl(o.optString("thumb_url"), cdnBase) }
                val thumb = normalizeImageUrl(o.optString("thumb_url"), cdnBase)
                    .ifEmpty { poster }
                val epCurRaw = o.optString("episode_current")
                val epTotRaw = o.optString("episode_total")
                val latestEp = parseEpisodeNumber(epCurRaw, 1)
                val totalEp = maxOf(parseEpisodeNumber(epTotRaw, latestEp), latestEp)
                val lang = o.optString("lang").trim()
                val audioModes = buildList {
                    if (lang.contains("thuyết", true) || lang.contains("thuyet", true)) add("Thuyết Minh")
                    if (lang.contains("lồng", true) || lang.contains("long", true)) add("Lồng Tiếng")
                    if (lang.contains("vietsub", true) || isEmpty()) add("Vietsub")
                }.distinct()
                val genres = mutableListOf<String>()
                o.optJSONArray("category")?.let { catArr ->
                    for (ci in 0 until catArr.length()) {
                        val cName = catArr.optJSONObject(ci)?.optString("name")?.trim().orEmpty()
                        if (cName.isNotEmpty() && !genres.contains(cName)) genres.add(cName)
                    }
                }
                val tmdbScore = o.optJSONObject("tmdb")?.optDouble("vote_average", 0.0)
                    ?.takeIf { !it.isNaN() && it > 0.0 }
                    ?: o.optJSONObject("imdb")?.optDouble("vote_average", 0.0)
                        ?.takeIf { !it.isNaN() && it > 0.0 }
                    ?: 9.5
                val statusRaw = o.optString("status").lowercase()
                val statusLabel = when {
                    statusRaw == "completed" || epCurRaw.contains("hoàn tất", true) -> "Hoàn thành"
                    else -> "Đang chiếu"
                }
                items.add(
                    MovieItem(
                        id = o.optString("_id").ifEmpty { slug },
                        title = title,
                        originTitle = originTitle,
                        posterUrl = poster,
                        thumbUrl = thumb,
                        latestEpisode = latestEp,
                        quality = o.optString("quality").trim().ifEmpty { "FHD" },
                        year = o.optInt("year", 0),
                        sourceId = SOURCE_ID,
                        slug = slug,
                        rating = tmdbScore,
                        audioModes = audioModes,
                        genres = genres.ifEmpty { listOf("Hoạt Hình") },
                        totalEpisodes = totalEp,
                        viewCount = o.optLong("view", 0L),
                        statusLabel = statusLabel
                    )
                )
            }
            val pag = root.optJSONObject("pagination")
                ?: dataObj?.optJSONObject("params")?.optJSONObject("pagination")
            val curPage = pag?.optInt("currentPage", 1)?.coerceAtLeast(1) ?: 1
            val totPages = pag?.optInt("totalPages", 1)?.coerceAtLeast(if (items.isNotEmpty()) curPage else 1) ?: 1
            val totItems = pag?.optInt("totalItems", items.size) ?: items.size
            PaginatedMovies(items, curPage, totPages, totItems)
        } catch (_: Exception) {
            PaginatedMovies(emptyList(), 1, 1, 0)
        }
    }

    fun parseHtmlMovieCards(html: String, basePageUrl: String = WEB_MIRROR_BASE): List<MovieItem> {
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

    fun parseTaxonomyItemsJson(jsonText: String): List<TaxonomyOption> {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        return try {
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val root = JSONObject(trimmed)
                    root.optJSONObject("data")?.optJSONArray("items")
                        ?: root.optJSONArray("items")
                        ?: JSONArray()
                }
            }
            val list = ArrayList<TaxonomyOption>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val slug = o.optString("slug").trim()
                if (name.isEmpty() || slug.isEmpty() || slug == "phim-18" || name.contains("18+", true)) continue
                if (seen.add(slug)) {
                    list.add(TaxonomyOption(name = name, slug = slug))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun cleanServerLabel(rawServerName: String): String {
        return rawServerName
            .replace(Regex("""^#?\s*[^-–]+?\s*[-–]\s*"""), "")
            .replace(Regex("""^#"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifEmpty { rawServerName.trim().ifEmpty { "Server #1" } }
    }

    fun parseMovieDetailJson(jsonText: String): MovieDetail? {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val root = JSONObject(trimmed)
            if (!root.optBoolean("status", true)) return null
            val m = root.optJSONObject("movie") ?: return null
            val slug = m.optString("slug").trim()
            val id = m.optString("_id").ifEmpty { slug }
            val title = m.optString("name").trim()
            if (title.isEmpty()) return null
            val originTitle = m.optString("origin_name").trim().ifEmpty { title }
            val desc = m.optString("content")
                .takeIf { it != "null" }
                .orEmpty()
                .let { Jsoup.parse(it).text().trim() }
            val poster = normalizeImageUrl(m.optString("poster_url"))
                .ifEmpty { normalizeImageUrl(m.optString("thumb_url")) }
            val thumb = normalizeImageUrl(m.optString("thumb_url"))
                .ifEmpty { poster }
            val genres = mutableListOf<String>()
            m.optJSONArray("category")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { genres.add(it) }
                }
            }
            val countries = mutableListOf<String>()
            m.optJSONArray("country")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { countries.add(it) }
                }
            }

            val servers = mutableListOf<ServerData>()
            val epArr = root.optJSONArray("episodes") ?: m.optJSONArray("episodes")
            if (epArr != null) {
                for (si in 0 until epArr.length()) {
                    val srv = epArr.optJSONObject(si) ?: continue
                    val rawName = srv.optString("server_name", "Server ${si + 1}")
                    val srvLabel = cleanServerLabel(rawName)
                    val sData = srv.optJSONArray("server_data") ?: continue
                    val eps = ArrayList<Episode>(sData.length())
                    for (ei in 0 until sData.length()) {
                        val ep = sData.optJSONObject(ei) ?: continue
                        val m3u8 = ep.optString("link_m3u8").trim().takeIf { it != "null" }.orEmpty()
                        val embed = ep.optString("link_embed").trim().takeIf { it != "null" }.orEmpty()
                        val playUrl = m3u8.ifBlank { embed }
                        if (playUrl.isBlank()) continue
                        val epRawName = ep.optString("name").trim()
                        val epName = when {
                            epRawName.isEmpty() -> "Tập ${ei + 1}"
                            epRawName.startsWith("Tập", true) || epRawName.equals("Full", true) -> epRawName
                            else -> "Tập $epRawName"
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
                        servers.add(ServerData(serverName = srvLabel, episodes = eps))
                    }
                }
            }

            // Ưu tiên server Thuyết Minh / Lồng Tiếng & server link .m3u8 trực tiếp
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
            val latestEp = maxOf(parseEpisodeNumber(m.optString("episode_current"), 1), maxServerEps)
            val totalEp = maxOf(parseEpisodeNumber(m.optString("episode_total"), latestEp), latestEp)

            MovieDetail(
                id = id,
                title = title,
                originTitle = originTitle,
                description = desc,
                status = m.optString("status").trim(),
                genres = genres.distinct(),
                countries = countries.distinct(),
                year = m.optInt("year", 0),
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

    fun extractDirectUrlFromPlayerQuery(embedUrl: String): String? {
        return runCatching {
            val uri = Uri.parse(embedUrl)
            val paramUrl = uri.getQueryParameter("url")?.trim().orEmpty()
            if (paramUrl.startsWith("http", true) &&
                (paramUrl.contains(".m3u8", true) || paramUrl.contains(".mp4", true))
            ) {
                paramUrl
            } else {
                null
            }
        }.getOrNull() ?: Regex("""[?&]url=(https?%3A%2F%2F[^&]+|https?://[^&]+)""", RegexOption.IGNORE_CASE)
            .find(embedUrl)?.groupValues?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.contains(".m3u8", true) || it.contains(".mp4", true) }
    }

    fun buildStreamHeaders(streamUrl: String, refererUrl: String = "$WEB_BASE/"): Map<String, String> {
        val origin = runCatching {
            val u = URI(refererUrl)
            if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) "${u.scheme}://${u.host}" else WEB_BASE
        }.getOrDefault(WEB_BASE)
        return mapOf(
            "Referer" to if (refererUrl.startsWith("http", true)) refererUrl else "$WEB_BASE/",
            "Origin" to origin,
            "User-Agent" to MOBILE_UA
        )
    }

    // =====================================================================
    // Implement 6 core features of MovieSourceProvider
    // =====================================================================

    override suspend fun getHome(page: Int): ProviderHomeFeed = coroutineScope {
        val safePage = page.coerceAtLeast(1)
        val cacheKey = "kk_home_$safePage"
        getCached<ProviderHomeFeed>(cacheKey)?.let { return@coroutineScope it }

        val latestTask = async { fetchPaged("$API_BASE/danh-sach/phim-moi-cap-nhat?page=$safePage") }
        val animeTask = async { fetchPaged("$API_BASE/v1/api/danh-sach/hoat-hinh?page=$safePage&limit=24") }
        val seriesTask = async {
            if (safePage == 1) fetchPaged("$API_BASE/v1/api/danh-sach/phim-bo?page=1&limit=24")
            else PaginatedMovies(emptyList(), safePage, 1, 0)
        }
        val singleTask = async {
            if (safePage == 1) fetchPaged("$API_BASE/v1/api/danh-sach/phim-le?page=1&limit=24")
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
            totalPages = maxOf(anime.totalPages, latest.totalPages, 1)
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
            clean.isBlank() -> "$API_BASE/v1/api/danh-sach/hoat-hinh?page=$safePage&limit=24"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> {
                val sep = if (clean.contains("?")) "&" else "?"
                "$clean${sep}page=$safePage"
            }
            clean.startsWith("hoat-hinh:") -> {
                val expr = clean.removePrefix("hoat-hinh:")
                val parts = expr.split("=", limit = 2)
                val fKey = parts.getOrNull(0).orEmpty()
                val fVal = parts.getOrNull(1).orEmpty()
                val qs = when (fKey) {
                    "lang" -> "sort_lang=${encodeUrlParam(fVal)}"
                    "country" -> "country=${encodeUrlParam(fVal)}"
                    else -> "category=${encodeUrlParam(fVal)}"
                }
                "$API_BASE/v1/api/danh-sach/hoat-hinh?page=$safePage&limit=24&$qs"
            }
            clean == "danh-sach/phim-moi-cap-nhat" ->
                "$API_BASE/danh-sach/phim-moi-cap-nhat?page=$safePage"
            clean.startsWith("danh-sach/") || clean.startsWith("the-loai/") ||
                clean.startsWith("quoc-gia/") || clean.startsWith("nam/") ->
                "$API_BASE/v1/api/$clean?page=$safePage&limit=24"
            clean in setOf("phim-bo", "phim-le", "hoat-hinh", "tv-shows", "phim-thuyet-minh", "phim-long-tieng") ->
                "$API_BASE/v1/api/danh-sach/$clean?page=$safePage&limit=24"
            else -> "$API_BASE/v1/api/the-loai/$clean?page=$safePage&limit=24"
        }
        return fetchPaged(url)
    }

    override suspend fun search(keyword: String, limit: Int): List<MovieItem> {
        val clean = keyword.trim()
        if (clean.length < 2) return emptyList()
        val safeLimit = limit.coerceIn(1, 50)
        val cacheKey = "kk_search_${clean.lowercase()}_$safeLimit"
        getCached<List<MovieItem>>(cacheKey)?.let { return it }

        val encoded = encodeUrlParam(clean)
        val url = "$API_BASE/v1/api/tim-kiem?keyword=$encoded&limit=$safeLimit"
        val list = fetchPaged(url).items.take(safeLimit)
        if (list.isNotEmpty()) setCached(cacheKey, list)
        return list
    }

    override suspend fun filter(filter: MovieFilter): List<MovieItem> {
        val safePage = filter.page.coerceAtLeast(1)
        val cat = filter.categorySlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        val country = filter.countrySlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        val year = filter.year?.takeIf { it > 1900 }
        val type = filter.typeSlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        val sortField = when (filter.sortField?.lowercase()) {
            "view", "views", "view_total", "xem-nhieu" -> "view"
            "year", "publish_year", "nam" -> "year"
            else -> "_id"
        }

        val basePath = when {
            type != null -> "danh-sach/$type"
            cat != null -> "the-loai/$cat"
            country != null -> "quoc-gia/$country"
            year != null -> "nam/$year"
            else -> "danh-sach/hoat-hinh"
        }
        val queryParts = mutableListOf("page=$safePage", "limit=24", "sort_field=$sortField", "sort_type=desc")
        if (type != null && cat != null) queryParts.add("category=${encodeUrlParam(cat)}")
        if ((type != null || cat != null) && country != null) queryParts.add("country=${encodeUrlParam(country)}")
        if ((type != null || cat != null || country != null) && year != null) queryParts.add("year=$year")

        val url = "$API_BASE/v1/api/$basePath?${queryParts.joinToString("&")}"
        return fetchPaged(url).items
    }

    override suspend fun getGenres(): List<TaxonomyOption> {
        getCached<List<TaxonomyOption>>("kk_genres")?.let { return it }
        val list = runCatching {
            val json = executeGetWithRetry("$API_BASE/the-loai", accept = "application/json")
            parseTaxonomyItemsJson(json)
        }.getOrDefault(emptyList())
        val finalList = list.ifEmpty { DEFAULT_GENRES }
        setCached("kk_genres", finalList, 60L * 60L * 1000L)
        return finalList
    }

    override suspend fun getCountries(): List<TaxonomyOption> {
        getCached<List<TaxonomyOption>>("kk_countries")?.let { return it }
        val list = runCatching {
            val json = executeGetWithRetry("$API_BASE/quoc-gia", accept = "application/json")
            parseTaxonomyItemsJson(json)
        }.getOrDefault(emptyList())
        val finalList = list.ifEmpty { DEFAULT_COUNTRIES }
        setCached("kk_countries", finalList, 60L * 60L * 1000L)
        return finalList
    }

    override suspend fun getDetail(slugOrId: String): MovieDetail? {
        val slug = slugOrId.trim().substringAfter("/phim/").substringBefore('?').trim('/')
        if (slug.isBlank()) return null
        val cacheKey = "kk_detail_$slug"
        getCached<MovieDetail>(cacheKey)?.let { return it }

        val detail = runCatching {
            val json = executeGetWithRetry("$API_BASE/phim/$slug", accept = "application/json")
            parseMovieDetailJson(json)
        }.getOrNull()
        if (detail != null) setCached(cacheKey, detail)
        return detail
    }

    override suspend fun resolveVideoStream(episode: Episode): VideoStream? {
        return resolveVideoStream(episode, context = null)
    }

    suspend fun resolveVideoStream(episode: Episode, context: Context?): VideoStream? {
        val playUrl = episode.playUrl.trim()
        if (playUrl.isBlank()) return null
        val low = playUrl.lowercase()
        if (episode.isDirectStream || low.contains(".m3u8") || low.contains(".mp4")) {
            return VideoStream(streamUrl = playUrl, headers = buildStreamHeaders(playUrl, WEB_BASE))
        }
        val cacheKey = "kk_stream_$playUrl"
        getCached<VideoStream>(cacheKey)?.let { return it }

        extractDirectUrlFromPlayerQuery(playUrl)?.let { direct ->
            val vs = VideoStream(streamUrl = direct, headers = buildStreamHeaders(direct, playUrl))
            setCached(cacheKey, vs, STREAM_CACHE_TTL_MS)
            return vs
        }

        val fromHtml = runCatching {
            val html = executeGetWithRetry(playUrl, referer = "$WEB_BASE/", accept = "text/html", timeoutMs = 6500L, maxAttempts = 1)
            VsmovSourceProvider.extractStreamFromEmbedHtml(html, playUrl)
                ?: EmbedFallbackView.extractM3u8FromHtml(html)?.let { m3u8 ->
                    VideoStream(streamUrl = m3u8, headers = buildStreamHeaders(m3u8, playUrl))
                }
        }.getOrNull()
        if (fromHtml != null && fromHtml.streamUrl.isNotBlank()) {
            setCached(cacheKey, fromHtml, STREAM_CACHE_TTL_MS)
            return fromHtml
        }

        if (context != null && playUrl.startsWith("http", true)) {
            val sniffed = HeadlessWebViewSniffer.sniffVideoStream(
                context = context,
                embedUrl = playUrl,
                refererUrl = "$WEB_BASE/",
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
                val mirrorHtml = runCatching {
                    executeGetWithRetry("$WEB_MIRROR_BASE/danh-sach/hoat-hinh", accept = "text/html", maxAttempts = 1)
                }.getOrDefault("")
                val fallback = parseHtmlMovieCards(mirrorHtml, WEB_MIRROR_BASE)
                PaginatedMovies(fallback, 1, 1, fallback.size)
            }
        }.getOrDefault(PaginatedMovies(emptyList(), 1, 1, 0))
    }

    // =====================================================================
    // Bridges vào kiến trúc UI/Repository hiện có của HoatHinh3D
    // =====================================================================

    suspend fun fetchHomeCatalogForApp(page: Int = 1): CatalogPageResult {
        val safePage = page.coerceAtLeast(1)
        val paged = getByCategoryPaged("danh-sach/hoat-hinh", safePage)
        val movies = paged.items.mapIndexed { idx, item ->
            item.toDonghuaMovie(WEB_BASE, SOURCE_NAME, idx)
        }
        return CatalogPageResult(
            movies = movies,
            page = paged.currentPage,
            totalPages = paged.totalPages
        )
    }

    suspend fun fetchCategoriesForApp(forceRefresh: Boolean = false): List<GenreItem> {
        if (forceRefresh) {
            memoryCache.remove("kk_genres")
            memoryCache.remove("kk_countries")
        }
        val list = LinkedHashMap<String, GenreItem>()
        list["__all__"] = GenreItem("Tất cả", "Thể loại phim", "grid", sourceSlug = "danh-sach/hoat-hinh")

        val featured = listOf(
            GenreItem("Hoạt Hình", "Hoạt Hình (KKPhim)", "dragon", sourceSlug = "danh-sach/hoat-hinh"),
            GenreItem("Thuyết Minh", "Hoạt Hình Thuyết Minh", "mic", sourceSlug = "hoat-hinh:lang=thuyet-minh"),
            GenreItem("Lồng Tiếng", "Hoạt Hình Lồng Tiếng", "volume", sourceSlug = "hoat-hinh:lang=long-tieng"),
            GenreItem("Vietsub", "Hoạt Hình Vietsub", "captions", sourceSlug = "hoat-hinh:lang=vietsub"),
            GenreItem("Trung Quốc", "Donghua Trung Quốc", "dragon", sourceSlug = "hoat-hinh:country=trung-quoc"),
            GenreItem("Nhật Bản", "Anime Nhật Bản", "sparkles", sourceSlug = "hoat-hinh:country=nhat-ban"),
            GenreItem("Mới Cập Nhật", "Phim Mới Cập Nhật", "sparkles", sourceSlug = "danh-sach/phim-moi-cap-nhat"),
            GenreItem("Phim Bộ", "Phim Bộ", "grid", sourceSlug = "danh-sach/phim-bo"),
            GenreItem("Phim Lẻ", "Phim Lẻ", "film", sourceSlug = "danh-sach/phim-le")
        )
        featured.forEach { list[it.id.lowercase()] = it }

        for (g in getGenres()) {
            val key = g.name.lowercase()
            if (!list.containsKey(key)) {
                list[key] = GenreItem(
                    id = g.name,
                    label = g.name,
                    icon = "tag",
                    sourceSlug = "hoat-hinh:cat=${g.slug}"
                )
            }
        }
        return list.values.toList()
    }

    suspend fun fetchGenreCatalogForApp(genre: GenreItem, page: Int = 1): CatalogPageResult {
        val safePage = page.coerceAtLeast(1)
        val rawSpec = genre.sourceSlug?.trim()?.trim('/').orEmpty()
        val paged = if (rawSpec.isBlank() || genre.id == "Tất cả") {
            getByCategoryPaged("danh-sach/hoat-hinh", safePage)
        } else {
            getByCategoryPaged(rawSpec, safePage)
        }
        val tagGenre = genre.id.takeIf { it.isNotBlank() && it != "Tất cả" }
        val movies = paged.items.mapIndexed { idx, item ->
            val mergedGenres = if (tagGenre != null && !item.genres.contains(tagGenre)) {
                listOf(tagGenre) + item.genres
            } else item.genres
            item.copy(genres = mergedGenres).toDonghuaMovie(WEB_BASE, SOURCE_NAME, idx)
        }
        return CatalogPageResult(
            movies = movies,
            page = paged.currentPage,
            totalPages = paged.totalPages
        )
    }

    suspend fun fetchTrendingTop10ForApp(): List<DonghuaMovie> {
        val hot = filter(MovieFilter(typeSlug = "hoat-hinh", sortField = "view", page = 1))
            .ifEmpty { getByCategory("danh-sach/hoat-hinh", 1) }
        return hot.take(10).mapIndexed { idx, item -> item.toDonghuaMovie(WEB_BASE, SOURCE_NAME, idx) }
    }

    suspend fun searchAsDonghuaMovies(keyword: String, limit: Int = 20): List<DonghuaMovie> {
        return search(keyword, limit).mapIndexed { idx, item ->
            item.toDonghuaMovie(WEB_BASE, SOURCE_NAME, idx)
        }
    }

    suspend fun fetchDetailForApp(slug: String, fallbackTitle: String? = null): MovieDetailResult {
        var detail = getDetail(slug)
        if (detail == null && !fallbackTitle.isNullOrBlank()) {
            val candidates = search(fallbackTitle, 8)
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
            sourceBaseUrl = WEB_BASE,
            sourceLabel = SOURCE_NAME,
            wpServerType = SERVER_TYPE_KKPHIM
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
        TaxonomyOption("Hành Động", "hanh-dong"),
        TaxonomyOption("Cổ Trang", "co-trang"),
        TaxonomyOption("Viễn Tưởng", "vien-tuong"),
        TaxonomyOption("Thần Thoại", "than-thoai"),
        TaxonomyOption("Võ Thuật", "vo-thuat"),
        TaxonomyOption("Phiêu Lưu", "phieu-luu"),
        TaxonomyOption("Hài Hước", "hai-huoc"),
        TaxonomyOption("Bí Ẩn", "bi-an"),
        TaxonomyOption("Khoa Học", "khoa-hoc"),
        TaxonomyOption("Tình Cảm", "tinh-cam")
    )

    private val DEFAULT_COUNTRIES = listOf(
        TaxonomyOption("Trung Quốc", "trung-quoc"),
        TaxonomyOption("Nhật Bản", "nhat-ban"),
        TaxonomyOption("Hàn Quốc", "han-quoc"),
        TaxonomyOption("Âu Mỹ", "au-my"),
        TaxonomyOption("Đài Loan", "dai-loan")
    )
}
