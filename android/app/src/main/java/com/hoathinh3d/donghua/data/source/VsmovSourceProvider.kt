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
 * Triển khai đầy đủ [MovieSourceProvider] cho https://vsmov.com/ (vsmov API v1 + Laravel Blade + StreamVSMov).
 * - Ưu tiên 100% JSON API chính chủ (`/api/danh-sach/...`, `/api/the-loai`, `/api/quoc-gia`, `/api/tim-kiem`, `/api/phim/...`)
 *   và tự động dự phòng bóc tách HTML Jsoup nếu endpoint API thay đổi hoặc gián đoạn.
 * - Bóc tách trực tiếp `signedMasterUrl` / `/stream/<hash>/master.m3u8` + `subtitles` + `thumb` từ `v*.streamvsmov.com`,
 *   tự động rơi về [HeadlessWebViewSniffer] nếu player mã hóa JS, đồng thời gắn đủ `Referer` + `Origin` + `User-Agent`.
 * - Chống chặn & tối ưu: Mobile User-Agent, CookieJar giữ phiên Laravel/Cloudflare, Timeout và Retry backoff khi mạng yếu.
 */
object VsmovSourceProvider : MovieSourceProvider {

    const val VSMOV_BASE = "https://vsmov.com"
    const val SOURCE_ID = "VSMov"
    const val SOURCE_NAME = "VSMov"
    const val SERVER_TYPE_VSMOV = "vsmov"

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val CACHE_TTL_MS = 15L * 60L * 1000L
    private const val STREAM_CACHE_TTL_MS = 10L * 60L * 1000L

    override val sourceId: String = SOURCE_ID
    override val sourceName: String = SOURCE_NAME
    override val baseUrl: String = VSMOV_BASE

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

    private val vsmovClient: OkHttpClient by lazy {
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
        referer: String = "$VSMOV_BASE/",
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Long = 9000L,
        maxAttempts: Int = 3
    ): String = withContext(Dispatchers.IO) {
        val client = if (timeoutMs == 9000L) {
            vsmovClient
        } else {
            vsmovClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }

        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "application/json, text/html, */*;q=0.8")
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8")
                    .header("Referer", referer)
                for ((k, v) in extraHeaders) {
                    reqBuilder.header(k, v)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
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
        AppDiagnostics.log("VSMov", "HTTP thất bại ($url): $msg")
        throw lastError ?: IOException("Failed $url")
    }

    fun encodeUrlParam(raw: String): String =
        URLEncoder.encode(raw.trim(), "UTF-8").replace("+", "%20")

    fun parseEpisodeNumber(raw: Any?, fallback: Int = 1): Int {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return fallback
        val parenRatio = Regex("""\((\d+)\s*/\s*\d+\)""").find(text)
        if (parenRatio != null) {
            return parenRatio.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: fallback
        }
        val tapMatch = Regex("""(?:tập|ep|episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        if (tapMatch != null) {
            return tapMatch.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: fallback
        }
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()?.takeIf { it > 0 } ?: fallback
    }

    fun normalizeMediaUrl(raw: String?, base: String = VSMOV_BASE): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty() || clean == "null") return ""
        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> "${base.trimEnd('/')}/${clean.trimStart('/')}"
        }
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
            val arr = root.optJSONArray("items") ?: dataObj?.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<MovieItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slug = o.optString("slug").trim()
                val title = o.optString("name").trim()
                if (slug.isEmpty() || title.isEmpty()) continue
                val originTitle = o.optString("origin_name").trim().ifEmpty { title }
                val poster = normalizeMediaUrl(o.optString("poster_url"))
                    .ifEmpty { normalizeMediaUrl(o.optString("thumb_url")) }
                val thumb = normalizeMediaUrl(o.optString("thumb_url"))
                    .ifEmpty { poster }
                val tmdb = o.optJSONObject("tmdb")
                val voteAvg = tmdb?.optString("vote_average")?.toDoubleOrNull() ?: 0.0
                val rating = if (voteAvg > 0.0) voteAvg else 9.4
                val epCurrentRaw = o.optString("episode_current")
                val epTotalRaw = o.optString("episode_total")
                val latestEp = parseEpisodeNumber(epCurrentRaw, 1)
                val totalEp = parseEpisodeNumber(epTotalRaw, latestEp)
                val lang = o.optString("lang").trim()
                val audioModes = when {
                    lang.contains("thuyết", true) || lang.contains("lồng", true) ->
                        listOf("Thuyết Minh", "Vietsub")
                    lang.isNotBlank() -> listOf(lang)
                    else -> listOf("Vietsub")
                }
                val genres = mutableListOf<String>()
                val catArr = o.optJSONArray("category")
                if (catArr != null) {
                    for (ci in 0 until catArr.length()) {
                        val cName = catArr.optJSONObject(ci)?.optString("name")?.trim().orEmpty()
                        if (cName.isNotEmpty() && !genres.contains(cName)) genres.add(cName)
                    }
                }
                val statusRaw = o.optString("status").lowercase()
                val statusLabel = when {
                    statusRaw == "completed" || epCurrentRaw.contains("hoàn tất", true) -> "Hoàn thành"
                    statusRaw == "ongoing" -> "Đang chiếu"
                    else -> null
                }
                items.add(
                    MovieItem(
                        id = o.optString("_id").ifEmpty { slug },
                        title = title,
                        originTitle = originTitle,
                        posterUrl = poster,
                        thumbUrl = thumb,
                        latestEpisode = latestEp,
                        quality = o.optString("quality").trim().ifEmpty { "HD" },
                        year = o.optInt("year", 0),
                        sourceId = SOURCE_ID,
                        slug = slug,
                        rating = rating,
                        audioModes = audioModes,
                        genres = genres,
                        totalEpisodes = totalEp,
                        viewCount = o.optLong("view", 0L),
                        statusLabel = statusLabel
                    )
                )
            }
            val pag = root.optJSONObject("pagination") ?: dataObj?.optJSONObject("params")?.optJSONObject("pagination")
            val curPage = pag?.optInt("currentPage", 1)?.coerceAtLeast(1) ?: 1
            val totPages = pag?.optInt("totalPages", 1)?.coerceAtLeast(if (items.isNotEmpty()) curPage else 1) ?: 1
            val totItems = pag?.optInt("totalItems", items.size) ?: items.size
            PaginatedMovies(items, curPage, totPages, totItems)
        } catch (_: Exception) {
            PaginatedMovies(emptyList(), 1, 1, 0)
        }
    }

    fun parseHtmlMovieCards(html: String, basePageUrl: String = VSMOV_BASE): List<MovieItem> {
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
                .ifBlank { a.selectFirst("h2, h3, .name, .title")?.text().orEmpty() }
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
                    quality = "HD",
                    year = 0,
                    sourceId = SOURCE_ID,
                    slug = slug
                )
            )
            if (out.size >= 36) break
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
                    root.optJSONObject("data")?.optJSONArray("items") ?: root.optJSONArray("items") ?: JSONArray()
                }
            }
            val list = ArrayList<TaxonomyOption>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val slug = o.optString("slug").trim()
                if (name.isEmpty() || slug.isEmpty() || slug == "phim-18") continue
                if (seen.add(slug)) {
                    list.add(TaxonomyOption(name = name, slug = slug))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
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
            val description = m.optString("content")
                .replace(Regex("""<[^>]+>"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val status = m.optString("status").trim()
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
            val epCurRaw = m.optString("episode_current")
            val epTotRaw = m.optString("episode_total")
            val latestEp = parseEpisodeNumber(epCurRaw, 1)
            val totalEp = parseEpisodeNumber(epTotRaw, latestEp)

            val servers = mutableListOf<ServerData>()
            val epsArr = root.optJSONArray("episodes")
            var maxServerEps = 0
            if (epsArr != null) {
                for (si in 0 until epsArr.length()) {
                    val srvObj = epsArr.optJSONObject(si) ?: continue
                    val rawSrvName = srvObj.optString("server_name", "Server #${si + 1}")
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                        .ifEmpty { "Server #${si + 1}" }
                    val dataArr = srvObj.optJSONArray("server_data") ?: continue
                    val epList = ArrayList<Episode>(dataArr.length())
                    for (ei in 0 until dataArr.length()) {
                        val epObj = dataArr.optJSONObject(ei) ?: continue
                        val m3u8 = epObj.optString("link_m3u8").trim()
                        val embed = epObj.optString("link_embed").trim()
                        val playUrl = m3u8.ifBlank { embed }
                        if (playUrl.isBlank()) continue
                        val rawName = epObj.optString("name").trim()
                        val cleanName = when {
                            rawName.isEmpty() -> "Tập ${ei + 1}"
                            rawName.startsWith("Tập", true) || rawName.equals("Full", true) -> rawName
                            else -> "Tập $rawName"
                        }
                        val epSlug = epObj.optString("slug").trim().ifEmpty { "tap-${ei + 1}" }
                        epList.add(
                            Episode(
                                name = cleanName,
                                slug = epSlug,
                                playUrl = playUrl,
                                isDirectStream = m3u8.isNotBlank()
                            )
                        )
                    }
                    if (epList.isNotEmpty()) {
                        maxServerEps = maxOf(maxServerEps, epList.size)
                        servers.add(ServerData(serverName = rawSrvName, episodes = epList))
                    }
                }
            }

            MovieDetail(
                id = id,
                title = title,
                originTitle = originTitle,
                description = description,
                status = status,
                genres = genres.distinct(),
                countries = countries.distinct(),
                year = m.optInt("year", 0),
                posterUrl = normalizeMediaUrl(m.optString("poster_url")),
                thumbUrl = normalizeMediaUrl(m.optString("thumb_url")),
                latestEpisode = maxOf(latestEp, maxServerEps),
                totalEpisodes = maxOf(totalEp, maxServerEps, latestEp),
                quality = m.optString("quality").trim().ifEmpty { "HD" },
                servers = servers
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bóc tách trực tiếp `.m3u8` / `.mp4`, `subtitleUrl` và `thumb` storyboard từ HTML trang embed `streamvsmov.com`
     * hoặc bất kỳ iframe player chuẩn nào khác.
     */
    fun extractStreamFromEmbedHtml(html: String, embedUrl: String): VideoStream? {
        if (html.isBlank()) return null
        val trimmed = html.trimStart()
        val headers = buildEmbedHeaders(embedUrl)

        if (trimmed.startsWith("#EXTM3U")) {
            return VideoStream(streamUrl = embedUrl, headers = headers)
        }

        val unescaped = html.replace("\\/", "/").replace("\\u0026", "&")

        // 1. signedMasterUrl trong `const playerOptions = { ... }` của streamvsmov.com
        val signedMaster = Regex("""signedMasterUrl\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(unescaped)?.groupValues?.getOrNull(1)?.trim()

        // 2. Fallback template `baseUrl + '/stream/' + videoHash + '/master.m3u8'` khi enableSignedUrl == false
        val constructedMaster = if (signedMaster.isNullOrBlank()) {
            val baseU = Regex("""const\s+baseUrl\s*=\s*["']([^"']+)["']""").find(unescaped)?.groupValues?.getOrNull(1)
            val hash = Regex("""const\s+videoHash\s*=\s*["']([^"']+)["']""").find(unescaped)?.groupValues?.getOrNull(1)
            if (!baseU.isNullOrBlank() && !hash.isNullOrBlank()) {
                "${baseU.trimEnd('/')}/stream/$hash/master.m3u8"
            } else null
        } else null

        // 3. Fallback regex M3U8 / MP4 phổ quát (JWPlayer sources, Video.js, HLS.js...)
        val genericM3u8 = EmbedFallbackView.extractM3u8FromHtml(unescaped)
        val genericMp4 = Regex("""["'](https?://[^"'\s\\<>]+\.mp4(?:\?[^"'\s\\<>]*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(unescaped)
            .map { it.groupValues[1] }
            .firstOrNull { !EmbedFallbackView.isAdHost(it) }

        val resolvedUrl = signedMaster?.takeIf { it.isNotBlank() }
            ?: constructedMaster?.takeIf { it.isNotBlank() }
            ?: genericM3u8?.takeIf { it.isNotBlank() }
            ?: genericMp4?.takeIf { it.isNotBlank() }
            ?: return null

        // Trích xuất phụ đề (.vtt/.srt) và thumb storyboard nếu có trong playerOptions
        val subBlock = Regex("""subtitles\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(unescaped)?.groupValues?.getOrNull(1).orEmpty()
        val subtitleUrl = Regex("""https?://[^"'\s\\<>]+\.(?:vtt|srt)(?:\?[^"'\s\\<>]*)?""", RegexOption.IGNORE_CASE)
            .find(subBlock)?.value
        val storyboardUrl = Regex("""thumb\s*:\s*["'](https?://[^"']+\.(?:vtt|jpg|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(unescaped)?.groupValues?.getOrNull(1)

        return VideoStream(
            streamUrl = resolvedUrl,
            headers = headers,
            subtitleUrl = subtitleUrl,
            storyboardUrl = storyboardUrl
        )
    }

    fun buildEmbedHeaders(embedUrl: String): Map<String, String> {
        val origin = runCatching {
            val u = URI(embedUrl)
            if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) "${u.scheme}://${u.host}" else VSMOV_BASE
        }.getOrDefault(VSMOV_BASE)
        return mapOf(
            "Referer" to "$origin/",
            "Origin" to origin,
            "User-Agent" to MOBILE_UA
        )
    }

    // =====================================================================
    // Implement 6 core features of MovieSourceProvider
    // =====================================================================

    override suspend fun getHome(page: Int): ProviderHomeFeed = coroutineScope {
        val safePage = page.coerceAtLeast(1)
        val cacheKey = "vsmov_home_$safePage"
        getCached<ProviderHomeFeed>(cacheKey)?.let { return@coroutineScope it }

        val latestTask = async { fetchListEndpoint("$VSMOV_BASE/api/danh-sach/phim-moi-cap-nhat?page=$safePage", "$VSMOV_BASE/?page=$safePage") }
        val seriesTask = async {
            if (safePage == 1) fetchListEndpoint("$VSMOV_BASE/api/danh-sach/phim-bo?page=1") else PaginatedMovies(emptyList(), safePage, 1, 0)
        }
        val singleTask = async {
            if (safePage == 1) fetchListEndpoint("$VSMOV_BASE/api/danh-sach/phim-le?page=1") else PaginatedMovies(emptyList(), safePage, 1, 0)
        }
        val animeTask = async {
            if (safePage == 1) fetchListEndpoint("$VSMOV_BASE/api/the-loai/hoat-hinh?page=1") else PaginatedMovies(emptyList(), safePage, 1, 0)
        }

        val latest = latestTask.await()
        val series = seriesTask.await()
        val singles = singleTask.await()
        val anime = animeTask.await()
        val spotlight = latest.items.filter { it.thumbUrl.isNotBlank() }.take(8)

        val feed = ProviderHomeFeed(
            spotlight = spotlight,
            latestUpdated = latest.items,
            series = series.items,
            singleMovies = singles.items,
            animations = anime.items,
            page = latest.currentPage,
            totalPages = latest.totalPages
        )
        if (feed.latestUpdated.isNotEmpty()) {
            setCached(cacheKey, feed)
        }
        feed
    }

    override suspend fun getByCategory(categorySlug: String, page: Int): List<MovieItem> {
        val cleanSlug = categorySlug.trim().trim('/')
        if (cleanSlug.isBlank()) return getHome(page).latestUpdated
        val safePage = page.coerceAtLeast(1)
        val apiUrl = when {
            cleanSlug.startsWith("danh-sach/") -> "$VSMOV_BASE/api/$cleanSlug?page=$safePage"
            cleanSlug.startsWith("quoc-gia/") -> "$VSMOV_BASE/api/$cleanSlug?page=$safePage"
            cleanSlug.startsWith("the-loai/") -> "$VSMOV_BASE/api/$cleanSlug?page=$safePage"
            cleanSlug in setOf("phim-moi-cap-nhat", "phim-bo", "phim-le", "subteam") ->
                "$VSMOV_BASE/api/danh-sach/$cleanSlug?page=$safePage"
            else -> "$VSMOV_BASE/api/the-loai/$cleanSlug?page=$safePage"
        }
        return fetchListEndpoint(apiUrl, "$VSMOV_BASE/$cleanSlug?page=$safePage").items
    }

    override suspend fun search(keyword: String, limit: Int): List<MovieItem> {
        val clean = keyword.trim()
        if (clean.length < 2) return emptyList()
        val cacheKey = "vsmov_search_${clean.lowercase()}_$limit"
        getCached<List<MovieItem>>(cacheKey)?.let { return it }

        val encoded = encodeUrlParam(clean)
        val apiUrl = "$VSMOV_BASE/api/tim-kiem?keyword=$encoded&limit=${limit.coerceIn(1, 50)}"
        val list = runCatching {
            val raw = executeGetWithRetry(apiUrl)
            val parsed = parseMoviesListJson(raw).items
            parsed.ifEmpty { parseHtmlMovieCards(raw, VSMOV_BASE) }
        }.getOrDefault(emptyList())

        if (list.isNotEmpty()) setCached(cacheKey, list)
        return list
    }

    override suspend fun filter(filter: MovieFilter): List<MovieItem> {
        val safePage = filter.page.coerceAtLeast(1)
        val cat = filter.categorySlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        val country = filter.countrySlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        val type = filter.typeSlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        val year = filter.year?.takeIf { it > 1900 }
        val sortField = filter.sortField?.trim()?.takeIf { it.isNotEmpty() }

        val queryParts = mutableListOf("page=$safePage")
        if (cat != null) {
            if (country != null) queryParts.add("country=${encodeUrlParam(country)}")
            if (year != null) queryParts.add("year=$year")
            if (sortField != null) queryParts.add("sort_field=${encodeUrlParam(sortField)}")
            val url = "$VSMOV_BASE/api/the-loai/$cat?${queryParts.joinToString("&")}"
            return fetchListEndpoint(url).items
        }
        if (country != null) {
            if (year != null) queryParts.add("year=$year")
            if (sortField != null) queryParts.add("sort_field=${encodeUrlParam(sortField)}")
            val url = "$VSMOV_BASE/api/quoc-gia/$country?${queryParts.joinToString("&")}"
            return fetchListEndpoint(url).items
        }

        val listSlug = type ?: "phim-moi-cap-nhat"
        val items = fetchListEndpoint("$VSMOV_BASE/api/danh-sach/$listSlug?page=$safePage").items
        var filtered = items
        if (year != null) {
            val exactYear = filtered.filter { it.year == year }
            if (exactYear.isNotEmpty()) filtered = exactYear
        }
        if (sortField == "view") {
            filtered = filtered.sortedByDescending { it.viewCount }
        }
        return filtered
    }

    override suspend fun getGenres(): List<TaxonomyOption> {
        getCached<List<TaxonomyOption>>("vsmov_genres")?.let { return it }
        val items = runCatching {
            val json = executeGetWithRetry("$VSMOV_BASE/api/the-loai")
            parseTaxonomyItemsJson(json)
        }.getOrDefault(emptyList())
        if (items.isNotEmpty()) setCached("vsmov_genres", items, 60L * 60L * 1000L)
        return items
    }

    override suspend fun getCountries(): List<TaxonomyOption> {
        getCached<List<TaxonomyOption>>("vsmov_countries")?.let { return it }
        val items = runCatching {
            val json = executeGetWithRetry("$VSMOV_BASE/api/quoc-gia")
            parseTaxonomyItemsJson(json)
        }.getOrDefault(emptyList())
        if (items.isNotEmpty()) setCached("vsmov_countries", items, 60L * 60L * 1000L)
        return items
    }

    override suspend fun getDetail(slugOrId: String): MovieDetail? {
        val slug = slugOrId.trim().substringAfter("/phim/").substringBefore('?').trim('/')
        if (slug.isBlank()) return null
        val cacheKey = "vsmov_detail_$slug"
        getCached<MovieDetail>(cacheKey)?.let { return it }

        val parsed = runCatching {
            val json = executeGetWithRetry("$VSMOV_BASE/api/phim/$slug")
            parseMovieDetailJson(json)
        }.getOrNull()

        if (parsed != null) {
            setCached(cacheKey, parsed)
            return parsed
        }
        return null
    }

    override suspend fun resolveVideoStream(episode: Episode): VideoStream? {
        return resolveVideoStream(episode, context = null)
    }

    suspend fun resolveVideoStream(episode: Episode, context: Context?): VideoStream? {
        val rawPlayUrl = episode.playUrl.trim()
        if (rawPlayUrl.isBlank()) return null
        val lower = rawPlayUrl.lowercase()
        if (episode.isDirectStream || lower.contains(".m3u8") || lower.contains(".mp4")) {
            return VideoStream(
                streamUrl = rawPlayUrl,
                headers = buildEmbedHeaders(rawPlayUrl)
            )
        }
        val cacheKey = "vsmov_stream_$rawPlayUrl"
        getCached<VideoStream>(cacheKey)?.let { return it }

        val httpStream = runCatching {
            val embedHtml = executeGetWithRetry(
                url = rawPlayUrl,
                referer = "$VSMOV_BASE/",
                timeoutMs = 7500L,
                maxAttempts = 2
            )
            extractStreamFromEmbedHtml(embedHtml, rawPlayUrl)
        }.getOrNull()

        if (httpStream != null && httpStream.streamUrl.isNotBlank()) {
            setCached(cacheKey, httpStream, STREAM_CACHE_TTL_MS)
            return httpStream
        }

        // Fallback dùng HeadlessWebViewSniffer nếu iframe player mã hóa JS động
        if (context != null) {
            val sniffed = HeadlessWebViewSniffer.sniffVideoStream(
                context = context,
                embedUrl = rawPlayUrl,
                refererUrl = "$VSMOV_BASE/",
                adBlock = true,
                timeoutMs = 5500L
            )
            if (sniffed != null && sniffed.streamUrl.isNotBlank()) {
                setCached(cacheKey, sniffed, STREAM_CACHE_TTL_MS)
                return sniffed
            }
        }
        return null
    }

    private suspend fun fetchListEndpoint(apiUrl: String, htmlFallbackUrl: String? = null): PaginatedMovies {
        val primary = runCatching {
            val raw = executeGetWithRetry(apiUrl)
            val parsed = parseMoviesListJson(raw)
            if (parsed.items.isNotEmpty()) parsed
            else {
                val htmlCards = parseHtmlMovieCards(raw, apiUrl)
                PaginatedMovies(htmlCards, 1, if (htmlCards.isNotEmpty()) 2 else 1, htmlCards.size)
            }
        }.getOrNull()
        if (primary != null && primary.items.isNotEmpty()) return primary

        if (!htmlFallbackUrl.isNullOrBlank()) {
            val fallback = runCatching {
                val html = executeGetWithRetry(htmlFallbackUrl)
                val cards = parseHtmlMovieCards(html, htmlFallbackUrl)
                PaginatedMovies(cards, 1, if (cards.isNotEmpty()) 2 else 1, cards.size)
            }.getOrNull()
            if (fallback != null && fallback.items.isNotEmpty()) return fallback
        }
        return PaginatedMovies(emptyList(), 1, 1, 0)
    }

    // =====================================================================
    // Bridges vào kiến trúc UI/Repository hiện có của HoatHinh3D
    // =====================================================================

    suspend fun fetchHomeCatalogForApp(page: Int = 1): CatalogPageResult {
        val safePage = page.coerceAtLeast(1)
        val paged = fetchListEndpoint(
            "$VSMOV_BASE/api/danh-sach/phim-moi-cap-nhat?page=$safePage",
            "$VSMOV_BASE/?page=$safePage"
        )
        val movies = paged.items.mapIndexed { idx, item -> item.toDonghuaMovie(VSMOV_BASE, SOURCE_NAME, idx) }
        return CatalogPageResult(
            movies = movies,
            page = paged.currentPage,
            totalPages = paged.totalPages
        )
    }

    suspend fun fetchCategoriesForApp(forceRefresh: Boolean = false): List<GenreItem> {
        if (forceRefresh) {
            memoryCache.remove("vsmov_genres")
            memoryCache.remove("vsmov_countries")
        }
        val list = LinkedHashMap<String, GenreItem>()
        list["__all__"] = GenreItem("Tất cả", "Thể loại phim", "grid", sourceSlug = "danh-sach/phim-moi-cap-nhat")

        val featuredTopics = listOf(
            GenreItem("Hoạt Hình", "Hoạt Hình / Anime", "dragon", sourceSlug = "the-loai/hoat-hinh"),
            GenreItem("Phim Bộ", "Phim Bộ", "grid", sourceSlug = "danh-sach/phim-bo"),
            GenreItem("Phim Lẻ", "Phim Lẻ", "film", sourceSlug = "danh-sach/phim-le"),
            GenreItem("Subteam Độc Quyền", "Subteam Độc Quyền", "sparkles", sourceSlug = "danh-sach/subteam"),
            GenreItem("Tiên Hiệp", "Tiên Hiệp", "yinyang", sourceSlug = "the-loai/tien-hiep"),
            GenreItem("Huyền Huyễn", "Huyền Huyễn", "flame", sourceSlug = "the-loai/huyen-huyen"),
            GenreItem("Cổ Trang", "Cổ Trang", "crown", sourceSlug = "the-loai/co-trang"),
            GenreItem("Trung Quốc", "Phim Trung Quốc", "dragon", sourceSlug = "quoc-gia/trung-quoc"),
            GenreItem("Hàn Quốc", "Phim Hàn Quốc", "tag", sourceSlug = "quoc-gia/han-quoc"),
            GenreItem("Nhật Bản", "Anime Nhật Bản", "sparkles", sourceSlug = "quoc-gia/nhat-ban")
        )
        featuredTopics.forEach { list[it.id.lowercase()] = it }

        val liveGenres = getGenres()
        for (g in liveGenres) {
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
        val apiUrl = when {
            rawSpec.isBlank() || genre.id == "Tất cả" -> "$VSMOV_BASE/api/danh-sach/phim-moi-cap-nhat?page=$safePage"
            rawSpec.startsWith("http", true) -> {
                val sep = if (rawSpec.contains("?")) "&" else "?"
                "${rawSpec}${sep}page=$safePage"
            }
            rawSpec.startsWith("danh-sach/") || rawSpec.startsWith("the-loai/") || rawSpec.startsWith("quoc-gia/") ->
                "$VSMOV_BASE/api/$rawSpec?page=$safePage"
            else -> "$VSMOV_BASE/api/the-loai/$rawSpec?page=$safePage"
        }
        val paged = fetchListEndpoint(apiUrl)
        val tagGenre = genre.id.takeIf { it.isNotBlank() && it != "Tất cả" }
        val movies = paged.items.mapIndexed { idx, item ->
            val mergedGenres = if (tagGenre != null && !item.genres.contains(tagGenre)) {
                listOf(tagGenre) + item.genres
            } else item.genres
            item.copy(genres = mergedGenres).toDonghuaMovie(VSMOV_BASE, SOURCE_NAME, idx)
        }
        return CatalogPageResult(
            movies = movies,
            page = paged.currentPage,
            totalPages = paged.totalPages
        )
    }

    suspend fun fetchTrendingTop10ForApp(): List<DonghuaMovie> {
        val items = runCatching {
            val animeHot = filter(MovieFilter(categorySlug = "hoat-hinh", sortField = "view", page = 1)).take(5)
            val subteam = getByCategory("danh-sach/subteam", page = 1).take(5)
            (animeHot + subteam).distinctBy { it.slug }.take(10)
        }.getOrDefault(emptyList())
        return items.mapIndexed { idx, item -> item.toDonghuaMovie(VSMOV_BASE, SOURCE_NAME, idx) }
    }

    suspend fun searchAsDonghuaMovies(keyword: String, limit: Int = 24): List<DonghuaMovie> {
        return search(keyword, limit).mapIndexed { idx, item ->
            item.toDonghuaMovie(VSMOV_BASE, SOURCE_NAME, idx)
        }
    }

    suspend fun fetchDetailForApp(slug: String): MovieDetailResult {
        val detail = getDetail(slug) ?: return MovieDetailResult()
        return detail.toMovieDetailResult(
            sourceBaseUrl = VSMOV_BASE,
            sourceLabel = SOURCE_NAME,
            wpServerType = SERVER_TYPE_VSMOV
        )
    }

    suspend fun resolveStreamResult(embedOrPlayUrl: String, context: Context? = null): StreamResult {
        val ep = Episode(
            name = "",
            slug = "",
            playUrl = embedOrPlayUrl,
            isDirectStream = embedOrPlayUrl.contains(".m3u8", true) || embedOrPlayUrl.contains(".mp4", true)
        )
        val vs = resolveVideoStream(ep, context) ?: return StreamResult(linkEmbed = embedOrPlayUrl)
        return StreamResult(
            linkEmbed = embedOrPlayUrl,
            linkM3u8 = vs.streamUrl,
            headers = vs.headers,
            subtitleUrl = vs.subtitleUrl,
            storyboardUrl = vs.storyboardUrl
        )
    }
}
