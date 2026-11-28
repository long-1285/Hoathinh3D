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
 * Triển khai đầy đủ [MovieSourceProvider] cho https://www.500phim.net/.
 *
 * Kiến trúc mục tiêu (Reverse Engineering):
 * - JSON API công khai (`/baseapi/api/v1/categories?limit=100`, `/baseapi/api/v1/regions?limit=100`).
 * - Next.js 15 App Router SSR + RSC (`self.__next_f.push([1,"..."])`) cho Trang chủ (`collections`),
 *   Danh mục (`/the-loai/...`, `/phim-bo`, `/phim-le`, `/c/...`), Tìm kiếm (`/tim-kiem?q=...`),
 *   Bộ lọc nâng cao (`/tim-kiem?genres=...&countries=...&years=...&type=...&sort=...&page=...`),
 *   Chi tiết phim đa server (`/phim/<slug>`) và Trang phát (`/xem-phim/<slug>.<epId>`), kèm Jsoup HTML fallback.
 * - Giải mã luồng `initialEpisode.link` (`.m3u8` / `.mp4` trực tiếp hoặc iframe embed như `streamc.xyz`)
 *   và tự động rơi về [HeadlessWebViewSniffer] khi iframe bị Cloudflare/JS mã hóa.
 */
object Phim500SourceProvider : MovieSourceProvider {

    const val BASE_URL = "https://www.500phim.net"
    const val SOURCE_ID = "Phim500"
    const val SOURCE_NAME = "500Phim"
    const val SERVER_TYPE_500PHIM = "500phim"

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

    private val phim500Client: OkHttpClient by lazy {
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
        accept: String = "text/html, application/json, */*;q=0.8",
        timeoutMs: Long = 10_000L,
        maxAttempts: Int = 3
    ): String = withContext(Dispatchers.IO) {
        val client = if (timeoutMs == 10_000L) {
            phim500Client
        } else {
            phim500Client.newBuilder()
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
        AppDiagnostics.log("500Phim", "HTTP thất bại ($url): $msg")
        throw lastError ?: IOException("Failed $url")
    }

    fun encodeUrlParam(raw: String): String =
        URLEncoder.encode(raw.trim(), "UTF-8").replace("+", "%20")

    fun normalizeMediaUrl(raw: String?, base: String = BASE_URL): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty() || clean == "null" || clean.startsWith("$")) return ""
        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> "${base.trimEnd('/')}/${clean.trimStart('/')}"
        }
    }

    fun parseEpisodeNumber(raw: Any?, fallback: Int = 1): Int {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return fallback
        val parenRatio = Regex("""\((\d+)\s*/\s*\d+\)""").find(text)
        if (parenRatio != null) {
            return parenRatio.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: fallback
        }
        val tapMatch = Regex("""(?:tập|tap|ep|episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        if (tapMatch != null) {
            return tapMatch.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: fallback
        }
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()?.takeIf { it > 0 } ?: fallback
    }

    /**
     * Giải mã toàn bộ payload RSC của Next.js (`self.__next_f.push([1,"..."])`) thành văn bản JSON chuẩn.
     */
    fun decodeNextFlightPayload(html: String): String {
        if (html.isBlank()) return ""
        val chunkRegex = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder(html.length / 2)
        for (m in chunkRegex.findAll(html)) {
            val rawLiteral = m.groupValues[1]
            val decoded = runCatching {
                JSONArray("[\"$rawLiteral\"]").getString(0)
            }.getOrElse {
                rawLiteral
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
            }
            sb.append(decoded)
        }
        return sb.toString()
    }

    /**
     * Trích xuất một khối JSON `{...}` hoặc `[...]` cân bằng ngoặc bắt đầu từ [startIndex].
     */
    fun extractBalancedJson(text: String, startIndex: Int): String? {
        if (startIndex < 0 || startIndex >= text.length) return null
        val openChar = text[startIndex]
        val closeChar = when (openChar) {
            '{' -> '}'
            '[' -> ']'
            else -> return null
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIndex until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == openChar) {
                    depth++
                } else if (ch == closeChar) {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseMovieObject(o: JSONObject): MovieItem? {
        val slug = o.optString("slug").trim()
        val title = o.optString("name").trim()
        if (slug.isEmpty() || title.isEmpty()) return null
        val originTitle = o.optString("origin_name").trim().ifEmpty { title }
        val poster = normalizeMediaUrl(o.optString("poster"))
            .ifEmpty { normalizeMediaUrl(o.optString("poster_url")) }
            .ifEmpty { normalizeMediaUrl(o.optString("thumbnail")) }
            .ifEmpty { normalizeMediaUrl(o.optString("thumb_url")) }
        val thumb = normalizeMediaUrl(o.optString("thumbnail"))
            .ifEmpty { normalizeMediaUrl(o.optString("thumb_url")) }
            .ifEmpty { poster }

        val epCurRaw = o.optString("episode_current")
        val epTotRaw = o.optString("episode_total")
        val epCount = o.optInt("episodes_count", 0)
        val latestEp = parseEpisodeNumber(epCurRaw, if (epCount > 0) 1 else 1)
        val totalEp = maxOf(parseEpisodeNumber(epTotRaw, latestEp), latestEp)

        val lang = o.optString("language").ifEmpty { o.optString("lang") }.trim()
        val audioModes = buildList {
            if (lang.contains("thuyết", true) || lang.contains("thuyet", true)) add("Thuyết Minh")
            if (lang.contains("lồng", true) || lang.contains("long", true)) add("Lồng Tiếng")
            if (lang.contains("vietsub", true) || isEmpty()) add("Vietsub")
        }.distinct()

        val genres = mutableListOf<String>()
        o.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val cName = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (cName.isNotEmpty() && !genres.contains(cName)) genres.add(cName)
            }
        }
        val statusRaw = o.optString("status").lowercase()
        val statusLabel = when {
            statusRaw == "completed" || epCurRaw.contains("hoàn tất", true) -> "Hoàn thành"
            statusRaw == "ongoing" -> "Đang chiếu"
            else -> null
        }
        val imdbScore = o.optString("imdb_rating").toDoubleOrNull()
            ?: o.optDouble("imdb_rating", 0.0).takeIf { !it.isNaN() && it > 0.0 }
            ?: o.optString("rating").toDoubleOrNull()
            ?: 9.5
        val year = o.optInt("publish_year", o.optInt("year", 0))
        val views = o.optLong("view_total", o.optLong("views", o.optLong("view", 0L)))
        return MovieItem(
            id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: slug,
            title = title,
            originTitle = originTitle,
            posterUrl = poster,
            thumbUrl = thumb,
            latestEpisode = latestEp,
            quality = o.optString("quality").trim().ifEmpty { "FHD" },
            year = year,
            sourceId = SOURCE_ID,
            slug = slug,
            rating = if (imdbScore > 0.0) imdbScore else 9.5,
            audioModes = audioModes,
            genres = genres,
            totalEpisodes = totalEp,
            viewCount = views,
            statusLabel = statusLabel
        )
    }

    data class PaginatedMovies(
        val items: List<MovieItem>,
        val currentPage: Int,
        val totalPages: Int
    )

    /**
     * Bóc tách danh sách phim từ bất kỳ trang SSR/RSC nào của 500Phim (`search`, `category`, `collection`, `home`).
     */
    fun parseMoviesFromPageHtml(html: String, requestedPage: Int = 1): PaginatedMovies {
        if (html.isBlank()) return PaginatedMovies(emptyList(), requestedPage, 1)
        val nf = decodeNextFlightPayload(html)
        val outMap = LinkedHashMap<String, MovieItem>()

        fun addArray(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val item = parseMovieObject(obj) ?: continue
                if (!outMap.containsKey(item.slug)) {
                    outMap[item.slug] = item
                }
            }
        }

        // 1. Kết quả Search (`"movies":{"status":true,"result":[...],"meta":{"totalPages":...}}`)
        val resultPattern = Regex(""""result"\s*:\s*\[""")
        for (m in resultPattern.findAll(nf)) {
            val arrStart = m.range.last
            val arrJson = extractBalancedJson(nf, arrStart) ?: continue
            runCatching { addArray(JSONArray(arrJson)) }
        }

        // 2. Các card phim dạng `{"movie":{"id":...}}` trên trang thể loại, phim bộ, phim lẻ, bộ sưu tập, lọc
        val cardPattern = Regex("""\{"movie"\s*:\s*\{""")
        for (m in cardPattern.findAll(nf)) {
            val objJson = extractBalancedJson(nf, m.range.first) ?: continue
            runCatching {
                val wrapper = JSONObject(objJson)
                val mvObj = wrapper.optJSONObject("movie") ?: return@runCatching
                val item = parseMovieObject(mvObj) ?: return@runCatching
                if (!outMap.containsKey(item.slug)) {
                    outMap[item.slug] = item
                }
            }
        }

        // 3. Trang chủ `"collections":[...,"movies":[...]]`
        if (outMap.isEmpty()) {
            val moviesArrPattern = Regex(""""movies"\s*:\s*\[""")
            for (m in moviesArrPattern.findAll(nf)) {
                val arrStart = m.range.last
                val arrJson = extractBalancedJson(nf, arrStart) ?: continue
                runCatching { addArray(JSONArray(arrJson)) }
            }
        }

        // 4. HTML Jsoup fallback nếu Next.js đổi định dạng RSC
        if (outMap.isEmpty()) {
            val htmlItems = parseHtmlMovieCards(html, BASE_URL)
            htmlItems.forEach { if (!outMap.containsKey(it.slug)) outMap[it.slug] = it }
        }

        val totalPages = Regex(""""totalPages"\s*:\s*(\d+)""")
            .find(nf)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.coerceAtLeast(if (outMap.isNotEmpty()) requestedPage else 1)
            ?: if (Regex("""[?&]page=${requestedPage + 1}\b""").containsMatchIn(html)) requestedPage + 1
            else requestedPage.coerceAtLeast(1)

        val currentPage = Regex(""""currentPage"\s*:\s*(\d+)""")
            .find(nf)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: requestedPage.coerceAtLeast(1)

        return PaginatedMovies(
            items = outMap.values.toList(),
            currentPage = currentPage.coerceAtLeast(1),
            totalPages = totalPages.coerceAtLeast(1)
        )
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
                .ifBlank { a.selectFirst("h2, h3, h4, .name, .title, .item-title")?.text().orEmpty() }
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

    fun parseTaxonomyJson(jsonText: String): List<TaxonomyOption> {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        return try {
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("data") ?: root.optJSONArray("items") ?: JSONArray()
                }
            }
            val list = ArrayList<TaxonomyOption>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val slug = o.optString("slug").trim()
                val movieCount = if (o.has("movie_count") && !o.isNull("movie_count")) o.optInt("movie_count", 1) else 1
                if (name.isEmpty() || slug.isEmpty() || movieCount <= 0) continue
                if (slug == "phim-18" || name.contains("18+", true)) continue
                if (seen.add(slug)) {
                    list.add(TaxonomyOption(name = name, slug = slug))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Bóc tách chi tiết phim + toàn bộ danh sách tập phân nhóm theo từng Server từ trang `/phim/<slug>`.
     * Chỉ dùng quét tiến (`extractBalancedJson`) để tránh lệch dấu ngoặc trong chuỗi JSON thoát ký tự.
     */
    fun parseMovieDetailFromHtml(html: String, fallbackSlug: String): MovieDetail? {
        if (html.isBlank()) return null
        val nf = decodeNextFlightPayload(html)

        val summaryObjs = findMovieObjectsBySlug(nf, fallbackSlug)
        val summaryObj = summaryObjs.firstOrNull { it.optString("description").isNotBlank() }
            ?: summaryObjs.firstOrNull()

        // Tìm mảng `"episodes":[...]` chính xác của phim hiện tại:
        // ưu tiên mảng có trường `"type"` (`"m3u8"` / `"embed"`), nếu trùng thì lấy mảng lớn nhất.
        var bestEpsArr: JSONArray? = null
        var bestHasTyped = false
        val epPattern = Regex(""""episodes"\s*:\s*\[""")
        for (m in epPattern.findAll(nf)) {
            val arrStart = m.range.last
            val arrJson = extractBalancedJson(nf, arrStart) ?: continue
            val arr = runCatching { JSONArray(arrJson) }.getOrNull() ?: continue
            if (arr.length() == 0) continue
            val hasTyped = (0 until minOf(arr.length(), 10)).any { arr.optJSONObject(it)?.has("type") == true }
            val isBetter = when {
                bestEpsArr == null -> true
                hasTyped && !bestHasTyped -> true
                hasTyped == bestHasTyped && arr.length() > bestEpsArr.length() -> true
                else -> false
            }
            if (isBetter) {
                bestEpsArr = arr
                bestHasTyped = hasTyped
            }
        }

        // Tìm thêm object bao chứa `"regions":[...]` (chứa `regions`, `publish_year`, `status`, `quality` của phần đang xem)
        val regionObj = findMovieDetailMetaObject(nf)

        if (summaryObj == null && regionObj == null && bestEpsArr == null) return null

        val slug = summaryObj?.optString("slug")?.trim()?.takeIf { it.isNotEmpty() }
            ?: regionObj?.optString("slug")?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackSlug
        val id = summaryObj?.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: regionObj?.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: slug
        val title = summaryObj?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: regionObj?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: Jsoup.parse(html).selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val originTitle = summaryObj?.optString("origin_name")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: regionObj?.optString("origin_name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: title
        val rawDesc = summaryObj?.optString("description").orEmpty()
            .ifBlank { regionObj?.optString("description").orEmpty() }
        val description = Jsoup.parse(rawDesc).text()
            .replace(Regex("""\s+"""), " ")
            .trim()
        val poster = normalizeMediaUrl(summaryObj?.optString("poster"))
            .ifEmpty { normalizeMediaUrl(regionObj?.optString("poster")) }
            .ifEmpty { normalizeMediaUrl(summaryObj?.optString("thumbnail")) }
            .ifEmpty { normalizeMediaUrl(regionObj?.optString("thumbnail")) }
        val thumb = normalizeMediaUrl(summaryObj?.optString("thumbnail"))
            .ifEmpty { normalizeMediaUrl(regionObj?.optString("thumbnail")) }
            .ifEmpty { poster }

        val genres = mutableListOf<String>()
        for (cand in summaryObjs + listOfNotNull(regionObj)) {
            cand.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { genres.add(it) }
                }
            }
        }
        val countries = mutableListOf<String>()
        (regionObj?.optJSONArray("regions") ?: summaryObj?.optJSONArray("regions"))?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { countries.add(it) }
            }
        }

        val epCurRaw = summaryObj?.optString("episode_current").orEmpty()
            .ifEmpty { regionObj?.optString("episode_current").orEmpty() }
        val epTotRaw = summaryObj?.optString("episode_total").orEmpty()
            .ifEmpty { regionObj?.optString("episode_total").orEmpty() }
        val latestEp = parseEpisodeNumber(epCurRaw, 1)
        val totalEp = parseEpisodeNumber(epTotRaw, latestEp)

        // Phân tách chính xác danh sách tập theo từng Server (`500 (Thuyết Minh)`, `500 (Vietsub)`, `c500 (Vietsub)`...)
        val groupedEps = LinkedHashMap<String, MutableList<Episode>>()
        val serverHasDirectM3u8 = HashMap<String, Boolean>()
        val epsArr = bestEpsArr ?: JSONArray()
        for (i in 0 until epsArr.length()) {
            val ep = epsArr.optJSONObject(i) ?: continue
            val epId = ep.opt("id")?.toString()?.trim().orEmpty()
            if (epId.isEmpty() || epId == "null") continue
            val serverRaw = ep.optString("server", "Server #1")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifEmpty { "Server #1" }
            val epType = ep.optString("type").trim().lowercase()
            if (epType == "m3u8" || epType == "mp4") {
                serverHasDirectM3u8[serverRaw] = true
            }
            val rawName = ep.optString("name").trim()
            val cleanName = when {
                rawName.isEmpty() -> "Tập ${i + 1}"
                rawName.startsWith("Tập", true) || rawName.equals("Full", true) -> rawName
                else -> "Tập $rawName"
            }
            val epSlug = ep.optString("slug").trim().ifEmpty { "tap-${i + 1}" }
            val watchUrl = "$BASE_URL/xem-phim/$slug.$epId"
            val list = groupedEps.getOrPut(serverRaw) { mutableListOf() }
            list.add(
                Episode(
                    name = cleanName,
                    slug = epSlug,
                    playUrl = watchUrl,
                    isDirectStream = false
                )
            )
        }

        // Sắp xếp Server: ưu tiên server type=m3u8 & Thuyết Minh / Lồng Tiếng trước
        val servers = groupedEps.entries
            .sortedWith(
                Comparator { a, b ->
                    val aDirect = serverHasDirectM3u8[a.key] == true
                    val bDirect = serverHasDirectM3u8[b.key] == true
                    if (bDirect != aDirect) return@Comparator if (bDirect) 1 else -1
                    val aDub = MovieRepository.isDubbedServerName(a.key)
                    val bDub = MovieRepository.isDubbedServerName(b.key)
                    if (bDub != aDub) return@Comparator if (bDub) 1 else -1
                    b.value.size - a.value.size
                }
            )
            .map { (srvName, eps) -> ServerData(serverName = srvName, episodes = eps) }

        val maxServerEps = servers.maxOfOrNull { it.episodes.size } ?: 0
        val yearVal = summaryObj?.optInt("publish_year", 0)?.takeIf { it > 0 }
            ?: regionObj?.optInt("publish_year", 0)
            ?: 0
        val qualityVal = summaryObj?.optString("quality")?.takeIf { it.isNotBlank() }
            ?: regionObj?.optString("quality")?.takeIf { it.isNotBlank() }
            ?: "FHD"
        val statusVal = summaryObj?.optString("status")?.takeIf { it.isNotBlank() }
            ?: regionObj?.optString("status").orEmpty()

        return MovieDetail(
            id = id,
            title = title,
            originTitle = originTitle,
            description = description,
            status = statusVal,
            genres = genres.distinct(),
            countries = countries.distinct(),
            year = yearVal,
            posterUrl = poster,
            thumbUrl = thumb,
            latestEpisode = maxOf(latestEp, maxServerEps),
            totalEpisodes = maxOf(totalEp, maxServerEps, latestEp),
            quality = qualityVal,
            servers = servers
        )
    }

    private fun findMovieObjectsBySlug(nf: String, slug: String): List<JSONObject> {
        val marker = "\"movie\":{"
        var pos = 0
        val out = mutableListOf<JSONObject>()
        while (pos < nf.length) {
            val idx = nf.indexOf(marker, pos)
            if (idx < 0) break
            val objStr = extractBalancedJson(nf, idx + "\"movie\":".length)
            if (objStr != null) {
                val parsed = runCatching { JSONObject(objStr) }.getOrNull()
                if (parsed != null && parsed.optString("name").isNotBlank() && parsed.optString("slug") == slug) {
                    out.add(parsed)
                }
            }
            pos = idx + marker.length
        }
        return out
    }

    private fun findMovieDetailMetaObject(nf: String): JSONObject? {
        val marker = "\"regions\":["
        val idx = nf.indexOf(marker)
        if (idx < 0) return null
        val arrJson = extractBalancedJson(nf, idx + "\"regions\":".length) ?: return null
        return runCatching { JSONObject("{\"regions\":$arrJson}") }.getOrNull()
    }

    /**
     * Bóc tách `initialEpisode.link` (và `subtitles`) từ trang xem phim `/xem-phim/<slug>.<epId>`.
     * Trả về cặp `(link, isDirectStream, subtitleUrl)`.
     */
    data class WatchPageExtraction(
        val link: String,
        val isDirectStream: Boolean,
        val subtitleUrl: String? = null
    )

    fun extractWatchEpisodeFromHtml(html: String): WatchPageExtraction? {
        if (html.isBlank()) return null
        val nf = decodeNextFlightPayload(html)

        val initIdx = nf.indexOf("\"initialEpisode\"")
        if (initIdx >= 0) {
            val braceIdx = nf.indexOf('{', initIdx)
            val objJson = extractBalancedJson(nf, braceIdx)
            if (objJson != null) {
                val obj = runCatching { JSONObject(objJson) }.getOrNull()
                if (obj != null) {
                    val link = obj.optString("link").trim()
                    val epType = obj.optString("type").trim().lowercase()
                    val subsArr = obj.optJSONArray("subtitles")
                    var subUrl: String? = null
                    if (subsArr != null) {
                        for (i in 0 until subsArr.length()) {
                            val s = subsArr.optJSONObject(i) ?: continue
                            val u = s.optString("url").ifEmpty { s.optString("file") }.trim()
                            if (u.startsWith("http", true)) {
                                subUrl = u
                                break
                            }
                        }
                    }
                    if (link.isNotBlank()) {
                        val isDirect = epType == "m3u8" || epType == "mp4" ||
                            link.contains(".m3u8", true) || link.contains(".mp4", true)
                        return WatchPageExtraction(link = link, isDirectStream = isDirect, subtitleUrl = subUrl)
                    }
                }
            }
        }

        val m3u8 = EmbedFallbackView.extractM3u8FromHtml(nf.ifBlank { html })
        if (!m3u8.isNullOrBlank()) {
            return WatchPageExtraction(link = m3u8, isDirectStream = true)
        }
        val iframeSrc = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!iframeSrc.isNullOrBlank() && !EmbedFallbackView.isAdHost(iframeSrc)) {
            return WatchPageExtraction(link = iframeSrc, isDirectStream = false)
        }
        return null
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
        val cacheKey = "p500_home_$safePage"
        getCached<ProviderHomeFeed>(cacheKey)?.let { return@coroutineScope it }

        if (safePage > 1) {
            val paged = fetchPageMovies("$BASE_URL/the-loai/hoat-hinh?page=$safePage", safePage)
            val feed = ProviderHomeFeed(
                latestUpdated = paged.items,
                animations = paged.items,
                page = paged.currentPage,
                totalPages = paged.totalPages
            )
            if (feed.latestUpdated.isNotEmpty()) setCached(cacheKey, feed)
            return@coroutineScope feed
        }

        val homeTask = async { fetchPageMovies("$BASE_URL/", 1) }
        val animeTask = async { fetchPageMovies("$BASE_URL/the-loai/hoat-hinh?page=1", 1) }
        val seriesTask = async { fetchPageMovies("$BASE_URL/phim-bo?page=1", 1) }
        val singleTask = async { fetchPageMovies("$BASE_URL/phim-le?page=1", 1) }

        val homePaged = homeTask.await()
        val animePaged = animeTask.await()
        val seriesPaged = seriesTask.await()
        val singlePaged = singleTask.await()

        val latestCombined = LinkedHashMap<String, MovieItem>()
        (animePaged.items + homePaged.items).forEach {
            if (!latestCombined.containsKey(it.slug)) latestCombined[it.slug] = it
        }
        val latestList = latestCombined.values.toList()
        val spotlight = homePaged.items.filter { it.thumbUrl.isNotBlank() }.take(8)
            .ifEmpty { latestList.filter { it.thumbUrl.isNotBlank() }.take(8) }

        val feed = ProviderHomeFeed(
            spotlight = spotlight,
            latestUpdated = latestList,
            series = seriesPaged.items,
            singleMovies = singlePaged.items,
            animations = animePaged.items,
            page = 1,
            totalPages = maxOf(animePaged.totalPages, seriesPaged.totalPages, 1)
        )
        if (feed.latestUpdated.isNotEmpty()) setCached(cacheKey, feed)
        feed
    }

    override suspend fun getByCategory(categorySlug: String, page: Int): List<MovieItem> {
        val cleanSlug = categorySlug.trim().trim('/')
        if (cleanSlug.isBlank()) return getHome(page).latestUpdated
        val safePage = page.coerceAtLeast(1)
        val url = buildCategoryPageUrl(cleanSlug, safePage)
        return fetchPageMovies(url, safePage).items
    }

    private fun buildCategoryPageUrl(cleanSlug: String, page: Int): String {
        val trimmed = cleanSlug.trim().trim('/')
        return when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
                val sep = if (trimmed.contains("?")) "&" else "?"
                "$trimmed${sep}page=$page"
            }
            trimmed.startsWith("c/") || trimmed.startsWith("the-loai/") || trimmed.startsWith("quoc-gia/") ->
                "$BASE_URL/$trimmed?page=$page"
            trimmed in setOf("phim-bo", "phim-le", "phim-ai") ->
                "$BASE_URL/$trimmed?page=$page"
            else -> "$BASE_URL/the-loai/$trimmed?page=$page"
        }
    }

    override suspend fun search(keyword: String, limit: Int): List<MovieItem> {
        val clean = keyword.trim()
        if (clean.length < 2) return emptyList()
        val cacheKey = "p500_search_${clean.lowercase()}_$limit"
        getCached<List<MovieItem>>(cacheKey)?.let { return it }

        val encoded = encodeUrlParam(clean)
        val url = "$BASE_URL/tim-kiem?q=$encoded&page=1"
        val list = fetchPageMovies(url, 1).items.take(limit.coerceIn(1, 60))
        if (list.isNotEmpty()) setCached(cacheKey, list)
        return list
    }

    override suspend fun filter(filter: MovieFilter): List<MovieItem> {
        val safePage = filter.page.coerceAtLeast(1)
        val queryParts = mutableListOf<String>()
        filter.categorySlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }?.let {
            queryParts.add("genres=${encodeUrlParam(it)}")
        }
        filter.countrySlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }?.let {
            queryParts.add("countries=${encodeUrlParam(it)}")
        }
        filter.year?.takeIf { it > 1900 }?.let {
            queryParts.add("years=$it")
        }
        filter.typeSlug?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }?.let { t ->
            val mappedType = when (t.lowercase()) {
                "phim-bo", "series" -> "series"
                "phim-le", "single" -> "single"
                else -> t
            }
            queryParts.add("type=${encodeUrlParam(mappedType)}")
        }
        filter.sortField?.trim()?.takeIf { it.isNotEmpty() }?.let { rawSort ->
            val mappedSort = when (rawSort.lowercase()) {
                "view", "views", "view_total", "xem-nhieu" -> "view_total"
                "year", "publish_year", "nam" -> "publish_year"
                "imdb", "imdb_rating" -> "imdb_rating"
                "rating", "rating_star" -> "rating_star"
                else -> "updatedAt"
            }
            if (mappedSort != "updatedAt") {
                queryParts.add("sort=$mappedSort")
            }
        }
        queryParts.add("page=$safePage")
        val url = "$BASE_URL/tim-kiem?${queryParts.joinToString("&")}"
        return fetchPageMovies(url, safePage).items
    }

    override suspend fun getGenres(): List<TaxonomyOption> {
        getCached<List<TaxonomyOption>>("p500_genres")?.let { return it }
        val apiList = runCatching {
            val json = executeGetWithRetry(
                "$BASE_URL/baseapi/api/v1/categories?limit=100",
                accept = "application/json"
            )
            parseTaxonomyJson(json)
        }.getOrDefault(emptyList())
        val finalList = if (apiList.isNotEmpty()) {
            apiList
        } else {
            runCatching {
                val html = executeGetWithRetry("$BASE_URL/the-loai")
                val doc = Jsoup.parse(html, BASE_URL)
                val seen = HashSet<String>()
                doc.select("a[href^=/the-loai/]").mapNotNull { a ->
                    val slug = a.attr("href").substringAfter("/the-loai/").substringBefore('?').trim('/')
                    val name = a.text().trim()
                    if (slug.isEmpty() || name.isEmpty() || slug == "phim-18" || !seen.add(slug)) null
                    else TaxonomyOption(name = name, slug = slug)
                }
            }.getOrDefault(emptyList())
        }
        if (finalList.isNotEmpty()) setCached("p500_genres", finalList, 60L * 60L * 1000L)
        return finalList
    }

    override suspend fun getCountries(): List<TaxonomyOption> {
        getCached<List<TaxonomyOption>>("p500_countries")?.let { return it }
        val apiList = runCatching {
            val json = executeGetWithRetry(
                "$BASE_URL/baseapi/api/v1/regions?limit=100",
                accept = "application/json"
            )
            parseTaxonomyJson(json)
        }.getOrDefault(emptyList())
        val finalList = if (apiList.isNotEmpty()) {
            apiList
        } else {
            runCatching {
                val html = executeGetWithRetry("$BASE_URL/quoc-gia")
                val doc = Jsoup.parse(html, BASE_URL)
                val seen = HashSet<String>()
                doc.select("a[href^=/quoc-gia/]").mapNotNull { a ->
                    val slug = a.attr("href").substringAfter("/quoc-gia/").substringBefore('?').trim('/')
                    val name = a.text().trim()
                    if (slug.isEmpty() || name.isEmpty() || !seen.add(slug)) null
                    else TaxonomyOption(name = name, slug = slug)
                }
            }.getOrDefault(emptyList())
        }
        if (finalList.isNotEmpty()) setCached("p500_countries", finalList, 60L * 60L * 1000L)
        return finalList
    }

    override suspend fun getDetail(slugOrId: String): MovieDetail? {
        val slug = slugOrId.trim().substringAfter("/phim/").substringBefore('?').trim('/')
        if (slug.isBlank()) return null
        val cacheKey = "p500_detail_$slug"
        getCached<MovieDetail>(cacheKey)?.let { return it }

        val parsed = runCatching {
            val html = executeGetWithRetry("$BASE_URL/phim/$slug")
            parseMovieDetailFromHtml(html, slug)
        }.getOrNull()

        if (parsed != null) {
            setCached(cacheKey, parsed)
        }
        return parsed
    }

    override suspend fun resolveVideoStream(episode: Episode): VideoStream? {
        return resolveVideoStream(episode, context = null)
    }

    suspend fun resolveVideoStream(episode: Episode, context: Context?): VideoStream? {
        val playUrl = episode.playUrl.trim()
        if (playUrl.isBlank()) return null
        val lower = playUrl.lowercase()
        if (episode.isDirectStream || lower.contains(".m3u8") || lower.contains(".mp4")) {
            return VideoStream(streamUrl = playUrl, headers = buildStreamHeaders(playUrl))
        }
        val cacheKey = "p500_stream_$playUrl"
        getCached<VideoStream>(cacheKey)?.let { return it }

        val extracted = runCatching {
            val watchHtml = executeGetWithRetry(playUrl, referer = "$BASE_URL/", timeoutMs = 9000L, maxAttempts = 2)
            extractWatchEpisodeFromHtml(watchHtml)
        }.getOrNull()

        if (extracted != null) {
            if (extracted.isDirectStream) {
                val vs = VideoStream(
                    streamUrl = extracted.link,
                    headers = buildStreamHeaders(extracted.link, playUrl),
                    subtitleUrl = extracted.subtitleUrl
                )
                setCached(cacheKey, vs, STREAM_CACHE_TTL_MS)
                return vs
            }

            // Link là iframe embed (VD: streamc.xyz): thử HTTP extract trước, lỗi Cloudflare/JS thì sniff bằng WebView ẩn
            val directFromEmbed = runCatching {
                val embHtml = executeGetWithRetry(
                    url = extracted.link,
                    referer = playUrl,
                    timeoutMs = 6500L,
                    maxAttempts = 1
                )
                VsmovSourceProvider.extractStreamFromEmbedHtml(embHtml, extracted.link)
            }.getOrNull()
            if (directFromEmbed != null && directFromEmbed.streamUrl.isNotBlank()) {
                val merged = directFromEmbed.copy(
                    subtitleUrl = directFromEmbed.subtitleUrl ?: extracted.subtitleUrl
                )
                setCached(cacheKey, merged, STREAM_CACHE_TTL_MS)
                return merged
            }

            if (context != null) {
                val sniffed = HeadlessWebViewSniffer.sniffVideoStream(
                    context = context,
                    embedUrl = extracted.link,
                    refererUrl = playUrl,
                    adBlock = true,
                    timeoutMs = 6000L
                )
                if (sniffed != null && sniffed.streamUrl.isNotBlank()) {
                    val merged = sniffed.copy(subtitleUrl = sniffed.subtitleUrl ?: extracted.subtitleUrl)
                    setCached(cacheKey, merged, STREAM_CACHE_TTL_MS)
                    return merged
                }
            }
        }

        // Fallback cuối: chạy HeadlessWebViewSniffer trực tiếp trên trang xem phim
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

    private suspend fun fetchPageMovies(url: String, page: Int): PaginatedMovies {
        return runCatching {
            val html = executeGetWithRetry(url)
            parseMoviesFromPageHtml(html, page)
        }.getOrDefault(PaginatedMovies(emptyList(), page, 1))
    }

    // =====================================================================
    // Bridges vào kiến trúc UI/Repository hiện có của HoatHinh3D
    // =====================================================================

    suspend fun fetchHomeCatalogForApp(page: Int = 1): CatalogPageResult {
        val safePage = page.coerceAtLeast(1)
        val paged = fetchPageMovies("$BASE_URL/the-loai/hoat-hinh?page=$safePage", safePage)
        val items = if (paged.items.isNotEmpty() || safePage > 1) {
            paged
        } else {
            fetchPageMovies("$BASE_URL/", 1)
        }
        val movies = items.items.mapIndexed { idx, item -> item.toDonghuaMovie(BASE_URL, SOURCE_NAME, idx) }
        return CatalogPageResult(
            movies = movies,
            page = items.currentPage,
            totalPages = items.totalPages
        )
    }

    suspend fun fetchCategoriesForApp(forceRefresh: Boolean = false): List<GenreItem> {
        if (forceRefresh) {
            memoryCache.remove("p500_genres")
            memoryCache.remove("p500_countries")
        }
        val list = LinkedHashMap<String, GenreItem>()
        list["__all__"] = GenreItem("Tất cả", "Thể loại phim", "grid", sourceSlug = "the-loai/hoat-hinh")

        val featuredTopics = listOf(
            GenreItem("Hoạt Hình", "Hoạt Hình 3D / Anime", "dragon", sourceSlug = "the-loai/hoat-hinh"),
            GenreItem("Phim 4K", "Phim 4K Ultra HD", "sparkles", sourceSlug = "c/phim-4k"),
            GenreItem("Thuyết Minh", "Phim Thuyết Minh", "mic", sourceSlug = "c/thuyet-minh"),
            GenreItem("Lồng Tiếng", "Lồng Tiếng Cực Mạnh", "volume", sourceSlug = "c/long-tieng-cuc-manh"),
            GenreItem("Phim Bộ", "Phim Bộ", "grid", sourceSlug = "phim-bo"),
            GenreItem("Phim Lẻ", "Phim Lẻ", "film", sourceSlug = "phim-le"),
            GenreItem("Chiếu Rạp", "Phim Chiếu Rạp", "sparkles", sourceSlug = "c/chieu-rap"),
            GenreItem("Cổ Trang", "Cổ Trang Trung Quốc", "crown", sourceSlug = "c/co-trang-trung-quoc"),
            GenreItem("Trung Quốc", "Phim Trung Quốc", "dragon", sourceSlug = "quoc-gia/trung-quoc"),
            GenreItem("Nhật Bản", "Anime Nhật Bản", "sparkles", sourceSlug = "quoc-gia/nhat-ban"),
            GenreItem("Hàn Quốc", "Phim Hàn Quốc", "tag", sourceSlug = "quoc-gia/han-quoc")
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
        val url = if (rawSpec.isBlank() || genre.id == "Tất cả") {
            "$BASE_URL/the-loai/hoat-hinh?page=$safePage"
        } else {
            buildCategoryPageUrl(rawSpec, safePage)
        }
        val paged = fetchPageMovies(url, safePage)
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
        val hot = filter(MovieFilter(categorySlug = "hoat-hinh", sortField = "view_total", page = 1))
            .ifEmpty { getHome(1).spotlight }
        return hot.take(10).mapIndexed { idx, item -> item.toDonghuaMovie(BASE_URL, SOURCE_NAME, idx) }
    }

    suspend fun searchAsDonghuaMovies(keyword: String, limit: Int = 20): List<DonghuaMovie> {
        return search(keyword, limit).mapIndexed { idx, item -> item.toDonghuaMovie(BASE_URL, SOURCE_NAME, idx) }
    }

    suspend fun fetchDetailForApp(slug: String): MovieDetailResult {
        val detail = getDetail(slug) ?: return MovieDetailResult()
        return detail.toMovieDetailResult(
            sourceBaseUrl = BASE_URL,
            sourceLabel = SOURCE_NAME,
            wpServerType = SERVER_TYPE_500PHIM
        )
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
}
