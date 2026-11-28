package com.hoathinh3d.donghua.data.source

import android.util.Base64
import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.GenreItem
import com.hoathinh3d.donghua.data.model.MovieDetailResult
import com.hoathinh3d.donghua.data.model.ServerGroup
import com.hoathinh3d.donghua.data.model.StreamResult
import com.hoathinh3d.donghua.data.model.WpSourceRef
import com.hoathinh3d.donghua.player.EmbedFallbackView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Scraper & Stream Resolver chuyên sâu cho https://yanhh3d.now (Laravel + StreamRPT1/FB/StreamC).
 * Hỗ trợ:
 * - Lấy toàn bộ 18+ phân loại, lọc trang chủ, tìm kiếm và phân trang
 * - Lấy lịch chiếu 7 ngày qua CSRF + AJAX `/ajax/sever/load-lich-chieu`
 * - Lấy đầy đủ audio tabs (Thuyết Minh / Vietsub), toàn bộ các tập và toàn bộ máy chủ (#list_sv .btn3dsv)
 * - Giải mã trực tiếp streamrpt1 `data-obf -> pU (stream-plain)` (#EXTM3U) và `play-fb-v8 -> cccc` (.mp4)
 */
object YanHh3dNowScraper {
    const val YAN_NOW_BASE = "https://yanhh3d.now"
    private const val CACHE_TTL_MS = 60L * 60L * 1000L

    private data class CacheEntry(val value: Any, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val homeHtmlMutex = Mutex()
    private val homeHtmlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val HOME_HTML_SHARED_TTL_MS = 15_000L

    @Volatile
    private var cachedCsrfToken: String = ""

    @Volatile
    private var cachedCookieHeader: String = ""

    @Suppress("UNCHECKED_CAST")
    private fun <T> getCache(key: String): T? {
        val hit = cache[key] ?: return null
        return if (hit.expiresAtMs > System.currentTimeMillis()) hit.value as? T else null
    }

    private fun setCache(key: String, value: Any) {
        cache[key] = CacheEntry(value, System.currentTimeMillis() + CACHE_TTL_MS)
    }

    val DAY_TO_NOW_PARAM = mapOf(
        "Thứ 2" to "mon",
        "Thứ 3" to "tue",
        "Thứ 4" to "wed",
        "Thứ 5" to "thu",
        "Thứ 6" to "fri",
        "Thứ 7" to "sat",
        "Chủ Nhật" to "sun"
    )

    val STATIC_CATEGORIES = listOf(
        GenreItem(id = "Tất cả", label = "Thể loại phim", icon = "grid", sourceSlug = "moi-cap-nhat"),
        GenreItem(id = "Phim Hot", label = "Phim Hot", icon = "flame", sourceSlug = "topic:phim-hot"),
        GenreItem(id = "Phim Chiếu Rạp", label = "Phim Chiếu Rạp", icon = "sparkles", sourceSlug = "topic:phim-chieu-rap"),
        GenreItem(id = "Phim Lẻ", label = "Phim Lẻ", icon = "film", sourceSlug = "phim-le"),
        GenreItem(id = "Phim Bộ", label = "Phim Bộ", icon = "grid", sourceSlug = "topic:phim-bo"),
        GenreItem(id = "Phim Hoạt Hình", label = "Phim Hoạt Hình", icon = "dragon", sourceSlug = "hoat-hinh-3d"),
        GenreItem(id = "Phim Hành Động", label = "Phim Hành Động", icon = "sword", sourceSlug = "topic:phim-hanh-dong"),
        GenreItem(id = "Tình Cảm", label = "Tình Cảm", icon = "tag", sourceSlug = "topic:tinh-cam"),
        GenreItem(id = "Đang chiếu", label = "Đang chiếu", icon = "broadcast", sourceSlug = "dang-chieu"),
        GenreItem(id = "Hoàn thành", label = "Đã hoàn thành", icon = "check", sourceSlug = "hoan-thanh"),
        GenreItem(id = "Hoạt Hình 4K", label = "Hoạt Hình 4K", icon = "sparkles", sourceSlug = "hoat-hinh-4k"),
        GenreItem(id = "Hoạt Hình 2D", label = "Hoạt Hình 2D", icon = "film", sourceSlug = "hoat-hinh-2d"),
        GenreItem(id = "Hoạt Hình AI", label = "Hoạt Hình AI", icon = "bolt", sourceSlug = "hoat-hinh-ai"),
        GenreItem(id = "Tiên Hiệp", label = "Tiên Hiệp", icon = "yinyang", sourceSlug = "the-loai/tien-hiep"),
        GenreItem(id = "Huyền Huyễn", label = "Huyền Huyễn", icon = "dragon", sourceSlug = "the-loai/huyen-huyen"),
        GenreItem(id = "Kiếm Hiệp", label = "Kiếm Hiệp", icon = "sword", sourceSlug = "the-loai/kiem-hiep"),
        GenreItem(id = "Cổ Trang", label = "Cổ Trang", icon = "crown", sourceSlug = "the-loai/co-trang"),
        GenreItem(id = "Trùng Sinh", label = "Trùng Sinh", icon = "flame", sourceSlug = "the-loai/trung-sinh"),
        GenreItem(id = "Xuyên Không", label = "Xuyên Không", icon = "hurricane", sourceSlug = "the-loai/xuyen-khong"),
        GenreItem(id = "Hài Hước", label = "Hài Hước", icon = "laugh", sourceSlug = "the-loai/hai-huoc"),
        GenreItem(id = "Hiện Đại", label = "Hiện Đại", icon = "city", sourceSlug = "the-loai/hien-dai"),
        GenreItem(id = "HHKungFu", label = "HHKungFu", icon = "sword", sourceSlug = "the-loai/hhkungfu"),
        GenreItem(id = "CN Animation", label = "CN Animation", icon = "tag", sourceSlug = "the-loai/cn-animation")
    )

    data class WatchServerBtn(
        val id: String,
        val name: String,
        val label: String,
        val dataSrc: String
    )

    data class NowMoviesPage(
        val movies: List<DonghuaMovie>,
        val totalPages: Int
    )

    data class NowTabEpisodes(
        val audioLabel: String,
        val episodes: List<EpisodeItem>
    )

    fun parseCategoriesFromHtml(html: String): List<GenreItem> {
        val merged = LinkedHashMap<String, GenreItem>()
        val usedIds = HashSet<String>()
        merged["moi-cap-nhat"] = STATIC_CATEGORIES.first()
        usedIds.add("tất cả")

        // Ưu tiên đưa 7 chủ đề phổ biến lên đầu ngay sau nút "Thể loại phim"
        val doc = Jsoup.parse(html)
        val links = doc.select("nav a[href], header a[href], .menu a[href], a[href]")
        val exactSlugs = setOf(
            "moi-cap-nhat", "hoat-hinh-3d", "hoat-hinh-2d", "hoat-hinh-4k",
            "hoat-hinh-ai", "hoan-thanh", "dang-chieu", "phim-le", "phim-bo",
            "phim-chieu-rap", "phim-hot", "hoat-hinh", "hanh-dong", "tinh-cam"
        )
        val liveLinks = LinkedHashMap<String, GenreItem>()
        for (a in links) {
            val href = a.attr("href").trim()
            if (href.contains("page=") || href.contains("#")) continue
            val cleanPath = href
                .replace(Regex("^https?://[^/]+"), "")
                .trim('/')
            val lowPath = cleanPath.lowercase()
            val isCategoryPath = lowPath.startsWith("the-loai/") ||
                lowPath.startsWith("danh-sach/") ||
                lowPath.startsWith("loai-phim/") ||
                lowPath.startsWith("quoc-gia/") ||
                lowPath.startsWith("phim-") ||
                lowPath.startsWith("top-") ||
                lowPath in exactSlugs
            if (isCategoryPath) {
                val label = a.attr("title").ifBlank { a.text() }.trim()
                if (label.isNotBlank() && label.length <= 42 && !liveLinks.containsKey(cleanPath)) {
                    liveLinks[cleanPath] = GenreItem(
                        id = label,
                        label = label,
                        icon = "tag",
                        sourceSlug = cleanPath
                    )
                }
            }
        }

        for (cat in STATIC_CATEGORIES.drop(1)) {
            val liveMatch = liveLinks.values.firstOrNull { g ->
                val l = g.label.lowercase()
                val s = (g.sourceSlug ?: "").lowercase()
                when (cat.id) {
                    "Phim Hot" -> l.contains("phim hot") || s.contains("phim-hot")
                    "Phim Chiếu Rạp" -> l.contains("chiếu rạp") || s.contains("chieu-rap")
                    "Phim Lẻ" -> s == "phim-le" || l.contains("phim lẻ")
                    "Phim Bộ" -> s == "phim-bo" || l.contains("phim bộ")
                    "Phim Hoạt Hình" -> s == "hoat-hinh-3d" || s == "hoat-hinh" || l == "hoạt hình 3d"
                    "Phim Hành Động" -> l.contains("hành động") || s.contains("hanh-dong")
                    "Tình Cảm" -> l.contains("tình cảm") || s.contains("tinh-cam")
                    else -> s == cat.sourceSlug
                }
            }
            val chosen = if (liveMatch != null) {
                cat.copy(sourceSlug = liveMatch.sourceSlug)
            } else cat
            val key = chosen.sourceSlug ?: chosen.id
            if (usedIds.add(chosen.id.lowercase())) {
                merged[key] = chosen
            }
        }

        for ((path, g) in liveLinks) {
            val normLabel = g.id.lowercase()
            if (normLabel == "xem thêm" || normLabel == "mới cập nhật") continue
            if (usedIds.add(normLabel) && !merged.containsKey(path)) {
                merged[path] = g
            }
        }
        return merged.values.toList()
    }

    suspend fun fetchCategories(
        baseUrl: String = YAN_NOW_BASE,
        sourceName: String = "YanHH3D Now",
        forceRefresh: Boolean = false
    ): List<GenreItem> {
        val cacheKey = "now_cats_$baseUrl"
        if (!forceRefresh) {
            getCache<List<GenreItem>>(cacheKey)?.let { return it }
        }
        return try {
            val html = fetchHtmlAndCaptureSession(baseUrl)
            val parsed = parseCategoriesFromHtml(html)
            if (parsed.isNotEmpty()) setCache(cacheKey, parsed)
            parsed.ifEmpty { STATIC_CATEGORIES }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            STATIC_CATEGORIES
        }
    }

    fun parseMoviesListHtml(
        html: String,
        baseUrl: String = YAN_NOW_BASE,
        sourceName: String = "YanHH3D Now",
        forcedGenre: String? = null
    ): NowMoviesPage {
        val cleanBase = baseUrl.trimEnd('/')
        val doc = Jsoup.parse(html, cleanBase)
        val items = doc.select(".flw-item")
        val seen = HashSet<String>()
        val list = ArrayList<DonghuaMovie>(items.size)

        for ((idx, item) in items.withIndex()) {
            val linkEl = item.selectFirst("a.film-poster-ahref[href], a[href]") ?: continue
            val rawHref = linkEl.absUrl("href").ifBlank {
                val rel = linkEl.attr("href")
                if (rel.startsWith("http")) rel else "$cleanBase/${rel.trimStart('/')}"
            }
            val slug = rawHref.trimEnd('/').substringAfterLast('/').substringBefore('?')
            val normSlug = slug.lowercase()
            if (slug.isBlank() || !seen.add(normSlug)) continue

            val imgEl = item.selectFirst("img")
            val poster = MovieRepository.extractPosterUrl(imgEl, cleanBase)

            val title = item.selectFirst("h4, .film-name a, .film-name")?.text()?.trim()
                ?.ifBlank { linkEl.attr("title").trim() }
                ?.ifBlank { imgEl?.attr("alt")?.trim().orEmpty() }
                .orEmpty()
            if (title.isBlank()) continue

            val tickRate = item.selectFirst(".tick-rate")?.text().orEmpty()
            val tickMeta = YanHh3dScraper.parseTickRateBadge(tickRate)
            val tickDub = item.selectFirst(".tick-dub")?.text().orEmpty()
            val isTm = tickDub.contains("TM", ignoreCase = true) ||
                title.contains("thuyết minh", ignoreCase = true) ||
                slug.contains("thuyet-minh", ignoreCase = true)

            val audioModes = if (isTm) listOf("Thuyết Minh", "Vietsub") else listOf("Vietsub", "Thuyết Minh")
            val quality = tickMeta?.quality?.takeIf { it.isNotBlank() }
                ?: if (tickDub.contains("4K", ignoreCase = true)) "4K UHD" else "1080p"

            val genres = mutableListOf("Hoạt Hình 3D")
            if (!forcedGenre.isNullOrBlank() &&
                forcedGenre != "Tất cả" &&
                forcedGenre != "Đang chiếu" &&
                forcedGenre != "Hoàn thành"
            ) {
                genres.add(0, forcedGenre)
            }

            list.add(
                DonghuaMovie(
                    id = "yannow_$slug",
                    slug = slug,
                    title = title,
                    originalTitle = "$sourceName • $title",
                    yanUrl = rawHref,
                    wpSources = listOf(WpSourceRef(cleanBase, 0, rawHref, sourceName)),
                    statusLabel = when {
                        forcedGenre == "Đang chiếu" -> "Đang chiếu"
                        forcedGenre == "Hoàn thành" -> "Hoàn thành"
                        tickMeta?.isCompletedHint == true -> "Hoàn thành"
                        tickMeta?.isOngoingHint == true -> "Đang chiếu"
                        else -> null
                    },
                    studio = "$sourceName • $quality",
                    latestEp = tickMeta?.latestEp ?: 1,
                    totalEp = tickMeta?.totalEp ?: 0,
                    rating = 9.8,
                    views = "4K VIP",
                    viewsNum = (100 - idx).coerceAtLeast(1).toDouble(),
                    quality = quality,
                    audioModes = audioModes,
                    sources = listOf(sourceName),
                    genres = genres.distinct(),
                    scheduleDay = "Hàng ngày",
                    scheduleTime = "10:00",
                    poster = poster,
                    thumb = poster,
                    streamIndex = idx % 4
                )
            )
        }

        val maxPage = doc.select("a[href*='page=']").mapNotNull { el ->
            Regex("""page=(\d+)""").find(el.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.maxOrNull() ?: 1

        return NowMoviesPage(list, maxPage)
    }

    /**
     * Trích xuất BXH Top 10 thịnh hành trực tiếp từ khối `.block_area-realtime` (#top-viewed-day)
     * trên trang chủ nguồn chính yanhh3d.now theo thứ tự #1..#10.
     */
    fun parseRealtimeRankingHtml(
        html: String,
        baseUrl: String = YAN_NOW_BASE,
        sourceName: String = "YanHH3D Now"
    ): List<DonghuaMovie> {
        val cleanBase = baseUrl.trimEnd('/')
        val doc = Jsoup.parse(html, cleanBase)
        val chartItems = doc.select(".block_area-realtime #top-viewed-day li.item-top")
            .ifEmpty { doc.select(".block_area-realtime li.item-top") }
        if (chartItems.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val result = ArrayList<DonghuaMovie>(10)
        for ((idx, item) in chartItems.withIndex()) {
            if (result.size >= 10) break
            val linkEl = item.selectFirst(".film-name a[href], .film-poster a[href], a[href]") ?: continue
            val rawHref = linkEl.absUrl("href").ifBlank {
                val rel = linkEl.attr("href")
                if (rel.startsWith("http")) rel else "$cleanBase/${rel.trimStart('/')}"
            }
            val slug = rawHref.trimEnd('/').substringAfterLast('/').substringBefore('?')
            val normSlug = slug.lowercase()
            if (slug.isBlank() || !seen.add(normSlug)) continue

            val imgEl = item.selectFirst("img")
            val poster = MovieRepository.extractPosterUrl(imgEl, cleanBase)

            val title = item.selectFirst(".film-name a, .film-name")?.text()?.trim()
                ?.ifBlank { linkEl.attr("title").trim() }
                ?.ifBlank { imgEl?.attr("alt")?.trim().orEmpty() }
                .orEmpty()
            if (title.isBlank()) continue

            val rank = item.selectFirst(".film-number span")?.text()?.trim()?.toIntOrNull() ?: (idx + 1)
            val infoRaw = item.selectFirst(".fd-infor .fdi-item, .fdi-item")?.text().orEmpty()
            val tickMeta = YanHh3dScraper.parseTickRateBadge(infoRaw)
            val quality = tickMeta?.quality?.takeIf { it.isNotBlank() }
                ?: if (infoRaw.contains("4K", ignoreCase = true)) "4K UHD" else "1080p"

            result.add(
                DonghuaMovie(
                    id = "yannow_$slug",
                    slug = slug,
                    title = title,
                    originalTitle = "$sourceName • BXH #$rank",
                    yanUrl = rawHref,
                    wpSources = listOf(WpSourceRef(cleanBase, 0, rawHref, sourceName)),
                    studio = "$sourceName • $quality",
                    latestEp = tickMeta?.latestEp ?: 1,
                    totalEp = tickMeta?.totalEp ?: maxOf(tickMeta?.latestEp ?: 1, 1),
                    rating = 9.9,
                    views = "BXH #$rank",
                    viewsNum = (1000 - (rank - 1)).toDouble(),
                    quality = quality,
                    audioModes = listOf("Thuyết Minh", "Vietsub"),
                    sources = listOf(sourceName),
                    genres = listOf("Hoạt Hình 3D"),
                    scheduleDay = "Hàng ngày",
                    scheduleTime = "10:00",
                    poster = poster,
                    thumb = poster,
                    streamIndex = idx % 4
                )
            )
        }
        return result
    }

    /**
     * Đồng bộ hóa thời gian thực Top 10 BXH thịnh hành từ trang chủ nguồn chính (không dùng cache cũ).
     */
    suspend fun fetchRealtimeTrendingTop10(
        baseUrl: String = YAN_NOW_BASE,
        sourceName: String = "YanHH3D Now",
        bypassCache: Boolean = true
    ): List<DonghuaMovie> {
        val cleanBase = baseUrl.trimEnd('/')
        return try {
            val html = fetchHtmlAndCaptureSession(cleanBase, bypassCache = bypassCache)
            parseRealtimeRankingHtml(html, cleanBase, sourceName)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            com.hoathinh3d.donghua.core.AppDiagnostics.log("YanNow", "Top10 err: ${t.message}")
            emptyList()
        }
    }

    suspend fun fetchMoviesByPath(
        path: String = "moi-cap-nhat",
        page: Int = 1,
        baseUrl: String = YAN_NOW_BASE,
        sourceName: String = "YanHH3D Now",
        forcedGenre: String? = null,
        bypassCache: Boolean = false
    ): NowMoviesPage {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trim().trim('/')
        val cacheKey = "now_list_${cleanBase}_${cleanPath}_${page}_${forcedGenre.orEmpty()}"
        if (!bypassCache) {
            getCache<NowMoviesPage>(cacheKey)?.let { return it }
        }

        val targetUrl = buildString {
            append(cleanBase)
            if (cleanPath.isNotEmpty()) append("/$cleanPath")
            if (page > 1) append("?page=$page")
        }
        return try {
            val headers = if (bypassCache) {
                mapOf("Referer" to "$cleanBase/", "Cache-Control" to "no-cache", "Pragma" to "no-cache")
            } else {
                mapOf("Referer" to "$cleanBase/")
            }
            val html = HttpClientProvider.getText(targetUrl, headers, 8000L)
            val parsed = parseMoviesListHtml(html, cleanBase, sourceName, forcedGenre)
            if (parsed.movies.isNotEmpty()) setCache(cacheKey, parsed)
            parsed
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            com.hoathinh3d.donghua.core.AppDiagnostics.log("YanNow", "List err ($path): ${t.message}")
            NowMoviesPage(emptyList(), 1)
        }
    }

    suspend fun searchMovies(
        keyword: String,
        baseUrl: String = YAN_NOW_BASE,
        sourceName: String = "YanHH3D Now"
    ): List<DonghuaMovie> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        val cleanBase = baseUrl.trimEnd('/')
        val cacheKey = "now_search_${cleanBase}_${q.lowercase()}"
        getCache<List<DonghuaMovie>>(cacheKey)?.let { return it }

        return try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val html = HttpClientProvider.getText(
                "$cleanBase/search?keysearch=$encoded",
                mapOf("Referer" to "$cleanBase/"),
                7500L
            )
            val parsed = parseMoviesListHtml(html, cleanBase, sourceName)
            if (parsed.movies.isNotEmpty()) setCache(cacheKey, parsed.movies)
            parsed.movies
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchScheduleByDay(
        dayName: String,
        baseUrl: String = YAN_NOW_BASE,
        sourceName: String = "YanHH3D Now"
    ): List<DonghuaMovie> = withContext(Dispatchers.IO) {
        val dayParam = DAY_TO_NOW_PARAM[dayName] ?: return@withContext emptyList()
        val cleanBase = baseUrl.trimEnd('/')
        val cacheKey = "now_sched_${cleanBase}_$dayParam"
        getCache<List<DonghuaMovie>>(cacheKey)?.let { return@withContext it }

        try {
            if (cachedCsrfToken.isBlank() || cachedCookieHeader.isBlank()) {
                fetchHtmlAndCaptureSession(cleanBase)
            }
            if (cachedCsrfToken.isBlank()) return@withContext emptyList()

            val formBody = FormBody.Builder()
                .add("days", dayParam)
                .build()
            val req = Request.Builder()
                .url("$cleanBase/ajax/sever/load-lich-chieu")
                .post(formBody)
                .header("User-Agent", HttpClientProvider.DEFAULT_UA)
                .header("Referer", "$cleanBase/")
                .header("X-CSRF-TOKEN", cachedCsrfToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .apply {
                    if (cachedCookieHeader.isNotBlank()) header("Cookie", cachedCookieHeader)
                }
                .build()

            HttpClientProvider.okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val raw = resp.body?.string().orEmpty()
                val obj = JSONObject(raw)
                val dataHtml = obj.optString("data")
                if (dataHtml.isBlank()) return@withContext emptyList()
                val list = parseMoviesListHtml(dataHtml, cleanBase, sourceName).movies.map {
                    it.copy(scheduleDay = dayName)
                }
                if (list.isNotEmpty()) setCache(cacheKey, list)
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchHtmlAndCaptureSession(
        baseUrl: String,
        bypassCache: Boolean = false
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val now = System.currentTimeMillis()
        if (!bypassCache) {
            homeHtmlCache[cleanBase]?.let { (cachedHtml, ts) ->
                if (now - ts < HOME_HTML_SHARED_TTL_MS && cachedHtml.isNotEmpty()) {
                    return cachedHtml
                }
            }
        }
        return homeHtmlMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!bypassCache) {
                homeHtmlCache[cleanBase]?.let { (cachedHtml, ts) ->
                    if (lockedNow - ts < HOME_HTML_SHARED_TTL_MS && cachedHtml.isNotEmpty()) {
                        return@withLock cachedHtml
                    }
                }
            }
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("$cleanBase/")
                    .header("User-Agent", HttpClientProvider.DEFAULT_UA)
                    .header("Referer", "$cleanBase/")
                    .build()
                HttpClientProvider.okHttpClient.newCall(req).execute().use { resp ->
                    val setCookies = resp.headers("Set-Cookie")
                    if (setCookies.isNotEmpty()) {
                        cachedCookieHeader = setCookies.joinToString("; ") { it.substringBefore(';') }
                    }
                    val html = resp.body?.string().orEmpty()
                    val csrf = Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1).orEmpty()
                    if (csrf.isNotBlank()) cachedCsrfToken = csrf
                    if (html.isNotEmpty()) {
                        homeHtmlCache[cleanBase] = html to System.currentTimeMillis()
                    }
                    html
                }
            }
        }
    }

    fun parseWatchButtons(html: String): List<WatchServerBtn> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<WatchServerBtn>()
        val seen = HashSet<String>()
        for (el in doc.select("#list_sv .btn3dsv, a.btn3dsv[data-src]")) {
            val src = el.attr("data-src").trim()
            if (src.isBlank()) continue
            val name = el.attr("name").ifBlank {
                el.attr("id").removePrefix("sv_")
            }.trim().ifBlank { "LINK${out.size + 1}" }
            val label = el.text().trim().ifBlank { name }
            if (seen.add("$name|$src")) {
                out.add(WatchServerBtn(id = el.attr("id"), name = name, label = label, dataSrc = src))
            }
        }
        return out
    }

    fun parseWatchPageEpisodes(
        watchHtml: String,
        baseUrl: String = YAN_NOW_BASE
    ): List<NowTabEpisodes> {
        val cleanBase = baseUrl.trimEnd('/')
        val doc = Jsoup.parse(watchHtml, cleanBase)
        val tabLabels = LinkedHashMap<String, String>()
        for (a in doc.select("#detail-ss-list .sever-ep a[href], ul.nav-tabs.sever-ep a[href]")) {
            val paneId = a.attr("href").trim().removePrefix("#")
            val lbl = a.text().trim()
            if (paneId.isNotBlank() && lbl.isNotBlank()) {
                tabLabels[paneId] = lbl
            }
        }

        val tabsOut = mutableListOf<NowTabEpisodes>()
        val panes = doc.select("#comment-widget .tab-pane")
        for (pane in panes) {
            val paneId = pane.id()
            val audioLabel = tabLabels[paneId] ?: when (paneId) {
                "top-comment" -> "Thuyết Minh"
                "new-comment" -> "Vietsub"
                else -> if (tabsOut.isEmpty()) "Thuyết Minh" else "Vietsub"
            }
            val parsed = parseEpAnchors(pane.select("a.ssl-item.ep-item, a[href*='/tap-']"), cleanBase)
            if (parsed.isNotEmpty()) {
                tabsOut.add(NowTabEpisodes(audioLabel, parsed))
            }
        }

        if (tabsOut.isEmpty()) {
            val fallback = parseEpAnchors(doc.select("#episodes-content a.ep-item, a.ssl-item.ep-item"), cleanBase)
            if (fallback.isNotEmpty()) {
                tabsOut.add(NowTabEpisodes("Thuyết Minh", fallback))
            }
        }
        return tabsOut
    }

    private fun parseEpAnchors(anchors: org.jsoup.select.Elements, cleanBase: String): List<EpisodeItem> {
        val seen = HashSet<String>()
        val list = mutableListOf<Pair<Double, EpisodeItem>>()
        for (a in anchors) {
            val href = a.absUrl("href").ifBlank {
                val rel = a.attr("href")
                if (rel.startsWith("http")) rel else "$cleanBase/${rel.trimStart('/')}"
            }
            if (href.isBlank() || href.startsWith("javascript")) continue
            val epSlug = href.trimEnd('/').substringAfterLast('/').substringBefore('?')
            if (epSlug.isBlank() || !seen.add(epSlug)) continue

            val rawTitle = a.attr("title").ifBlank { a.selectFirst(".ep-name")?.text().orEmpty() }
                .ifBlank { a.text() }.trim()
            val numMatch = Regex("""(\d+(?:\.\d+)?)""").find(epSlug.ifEmpty { rawTitle })
            val epNum = numMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: (list.size + 1).toDouble()
            val displayName = when {
                epSlug.contains("full", ignoreCase = true) -> "Full"
                rawTitle.startsWith("Tập", ignoreCase = true) -> rawTitle
                rawTitle.all { it.isDigit() || it == '.' } && rawTitle.isNotEmpty() -> "Tập $rawTitle"
                rawTitle.isNotEmpty() -> rawTitle
                else -> "Tập ${floor(epNum).toInt()}"
            }

            list.add(
                epNum to EpisodeItem(
                    name = displayName,
                    slug = epSlug,
                    wpBaseUrl = cleanBase,
                    wpPageUrl = href
                )
            )
        }
        return list.sortedBy { it.first }.map { it.second }
    }

    private fun serverPriorityScore(btn: WatchServerBtn): Int {
        val lbl = btn.label.lowercase()
        val src = btn.dataSrc.lowercase()
        val isNativeCapable = src.contains("streamrpt1") || src.contains(".m3u8") || src.contains(".mp4")
        val base = if (isNativeCapable) 0 else 100
        val resRank = when {
            lbl == "4k" -> 1
            lbl == "1080" -> 2
            lbl == "4k-" -> 3
            lbl == "1080-" -> 4
            lbl == "1080+" -> 5
            lbl == "hd" -> 6
            lbl == "nc" -> 50
            else -> 20
        }
        return base + resRank
    }

    internal fun isTrailerEpisode(ep: EpisodeItem): Boolean =
        isTrailerLabelOrSlug(ep.name, ep.slug)

    internal fun isTrailerLabelOrSlug(name: String, slug: String): Boolean {
        val combined = "$name $slug".lowercase()
        return Regex("""(?:^|[^a-z0-9])(tl|trailer|preview|teaser)(?:[^a-z0-9]|$)""").containsMatchIn(combined)
    }

    private fun pickSampleEpisodeUrlsForTab(tab: NowTabEpisodes): List<String> {
        val withUrl = tab.episodes.filter { !it.wpPageUrl.isNullOrBlank() }
        if (withUrl.isEmpty()) return emptyList()
        val nonTrailer = withUrl.filterNot { isTrailerEpisode(it) }
        val candidates = LinkedHashSet<String>()
        if (nonTrailer.isNotEmpty()) {
            nonTrailer.lastOrNull()?.wpPageUrl?.let { candidates.add(it) }
            if (nonTrailer.size >= 2) {
                nonTrailer[nonTrailer.size - 2].wpPageUrl?.let { candidates.add(it) }
            }
            nonTrailer.firstOrNull()?.wpPageUrl?.let { candidates.add(it) }
        }
        withUrl.lastOrNull()?.wpPageUrl?.let { candidates.add(it) }
        return candidates.toList()
    }

    internal suspend fun resolveButtonsForTab(
        tab: NowTabEpisodes,
        primarySampleUrl: String,
        primaryBtns: List<WatchServerBtn>,
        fetchWatchHtml: suspend (String) -> String
    ): List<WatchServerBtn> {
        val candidates = pickSampleEpisodeUrlsForTab(tab)
        if (candidates.isEmpty()) return primaryBtns
        var firstNonEmpty: List<WatchServerBtn> = emptyList()
        for (sampleEpUrl in candidates) {
            val btns = if (sampleEpUrl == primarySampleUrl) {
                primaryBtns
            } else {
                runCatching {
                    parseWatchButtons(fetchWatchHtml(sampleEpUrl)).sortedBy { serverPriorityScore(it) }
                }.getOrDefault(emptyList())
            }
            if (btns.any { isDirectNativeServer(it) }) {
                return btns
            }
            if (firstNonEmpty.isEmpty() && btns.isNotEmpty()) {
                firstNonEmpty = btns
            }
        }
        return if (primaryBtns.any { isDirectNativeServer(it) }) primaryBtns
        else firstNonEmpty.ifEmpty { primaryBtns }
    }

    private fun isDirectNativeServer(btn: WatchServerBtn): Boolean {
        val src = btn.dataSrc.lowercase()
        // streamc.xyz dùng Cloudflare Turnstile JS shell, không trả .m3u8 trực tiếp cho ExoPlayer
        return src.contains("streamrpt1") || src.contains(".m3u8") || src.contains(".mp4")
    }

    suspend fun resolveDetailPageUrl(
        slug: String,
        title: String? = null,
        baseUrl: String = YAN_NOW_BASE
    ): String? {
        val cleanBase = baseUrl.trimEnd('/')
        if (slug.isNotBlank()) {
            val directUrl = "$cleanBase/$slug"
            val html = runCatching {
                HttpClientProvider.getText(directUrl, mapOf("Referer" to "$cleanBase/"), 5000L)
            }.getOrNull()
            if (!html.isNullOrBlank() && (html.contains("film-description") || html.contains("/$slug/"))) {
                return directUrl
            }
        }
        val kw = title?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        if (kw.isBlank()) return null
        val candidates = searchMovies(kw, cleanBase)
        val normTarget = VietnameseSearch.removeVietnameseTones(title ?: kw)
        val exact = candidates.firstOrNull { m ->
            m.slug == slug || (normTarget.isNotEmpty() && VietnameseSearch.removeVietnameseTones(m.title) == normTarget)
        } ?: candidates.firstOrNull { m ->
            val cn = VietnameseSearch.removeVietnameseTones(m.title)
            normTarget.isNotEmpty() && cn.startsWith(normTarget) && !cn.contains("ova") && !cn.contains("movie")
        } ?: candidates.firstOrNull()
        return exact?.yanUrl
    }

    suspend fun fetchMovieDetail(
        slug: String,
        movieUrl: String? = null,
        movieTitle: String? = null,
        baseUrl: String = YAN_NOW_BASE,
        sourceName: String = "YanHH3D Now",
        fallbackDesc: String = ""
    ): MovieDetailResult {
        val cleanBase = baseUrl.trimEnd('/')
        val targetDetailUrl = movieUrl?.takeIf { it.startsWith("http") }
            ?: resolveDetailPageUrl(slug, movieTitle, cleanBase)
            ?: return MovieDetailResult()

        val cacheKey = "now_detail_${cleanBase}_$targetDetailUrl"
        getCache<MovieDetailResult>(cacheKey)?.let { if (it.serverGroups.isNotEmpty()) return it }

        return try {
            val detailHtml = HttpClientProvider.getText(targetDetailUrl, mapOf("Referer" to "$cleanBase/"), 7500L)
            val doc = Jsoup.parse(detailHtml, cleanBase)

            val description = doc.selectFirst(".film-description .text, .film-description")?.text()?.trim()
                ?.ifBlank { doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty() }
                ?.ifBlank { fallbackDesc }
                .orEmpty()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.ifBlank { doc.selectFirst(".film-poster-img")?.absUrl("src").orEmpty() }

            val targetSlug = targetDetailUrl.trimEnd('/').substringAfterLast('/')
            val watchCandidates = doc.select("a[href]").mapNotNull { a ->
                val h = a.absUrl("href")
                val isWatch = h.contains("/$targetSlug/tap-", ignoreCase = true) ||
                    h.contains("/sever2/$targetSlug/", ignoreCase = true) ||
                    (h.contains("/$targetSlug/") && !h.endsWith("/$targetSlug") && !h.endsWith("/$targetSlug/"))
                if (!isWatch) null else (h to a.text().trim())
            }
            val watchHref = watchCandidates.firstOrNull { (h, text) ->
                !isTrailerLabelOrSlug(text, h.substringAfterLast('/'))
            }?.first ?: watchCandidates.firstOrNull()?.first
                ?: return MovieDetailResult(emptyList(), description.ifBlank { null }, poster)

            val watchHtml = HttpClientProvider.getText(watchHref, mapOf("Referer" to targetDetailUrl), 7500L)
            val audioTabs = parseWatchPageEpisodes(watchHtml, cleanBase)
            if (audioTabs.isEmpty()) {
                return MovieDetailResult(emptyList(), description.ifBlank { null }, poster)
            }

            val primaryBtns = parseWatchButtons(watchHtml).sortedBy { serverPriorityScore(it) }
            val primarySampleUrl = watchHref

            // Bỏ qua các tập Trailer/Preview (VD: "169 TL") khi dò máy chủ cho từng tab âm thanh
            // để tránh việc nút "Trailer" (Facebook embed) che mất các máy chủ streamrpt1 trực tiếp.
            val tabButtonsMap = LinkedHashMap<Int, List<WatchServerBtn>>()
            coroutineScope {
                val jobs = audioTabs.mapIndexed { idx, tab ->
                    async {
                        val btns = resolveButtonsForTab(
                            tab = tab,
                            primarySampleUrl = primarySampleUrl,
                            primaryBtns = primaryBtns
                        ) { epUrl ->
                            HttpClientProvider.getText(epUrl, mapOf("Referer" to targetDetailUrl), 6000L)
                        }
                        idx to btns
                    }
                }
                for (j in jobs) {
                    val (idx, btns) = j.await()
                    tabButtonsMap[idx] = btns
                }
            }

            val serverGroups = mutableListOf<ServerGroup>()
            audioTabs.forEachIndexed { tabIdx, tab ->
                val btns = tabButtonsMap[tabIdx].orEmpty()
                if (btns.isEmpty()) {
                    serverGroups.add(
                        ServerGroup(
                            serverName = "$sourceName • ${tab.audioLabel}",
                            episodes = tab.episodes.map { ep ->
                                ep.copy(wpServerType = "now:AUTO", wpServerSv = "hls")
                            }
                        )
                    )
                } else {
                    for (btn in btns) {
                        val isHlsCapable = isDirectNativeServer(btn)
                        serverGroups.add(
                            ServerGroup(
                                serverName = "$sourceName • ${tab.audioLabel} (${btn.label})",
                                episodes = tab.episodes.map { ep ->
                                    ep.copy(
                                        wpServerType = "now:${btn.name}",
                                        wpServerSv = if (isHlsCapable) "hls" else "embed",
                                        linkEmbed = if (ep.wpPageUrl == tab.episodes.lastOrNull()?.wpPageUrl && !isHlsCapable) btn.dataSrc else ""
                                    )
                                }
                            )
                        )
                    }
                }
            }

            val maxEps = serverGroups.maxOfOrNull { it.episodes.size } ?: 0
            val out = MovieDetailResult(
                serverGroups = serverGroups,
                description = description.ifBlank { null },
                poster = poster?.ifBlank { null },
                totalEp = if (maxEps > 0) maxEps else null,
                latestEp = if (maxEps > 0) maxEps else null
            )
            if (out.serverGroups.isNotEmpty()) setCache(cacheKey, out)
            out
        } catch (e: Exception) {
            com.hoathinh3d.donghua.core.AppDiagnostics.log("YanNow", "Detail err ($slug): ${e.message}")
            MovieDetailResult()
        }
    }

    private fun decodeBase64Compat(input: String): ByteArray {
        val clean = input.trim().replace('-', '+').replace('_', '/')
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        val androidDecoded = runCatching { Base64.decode(padded, Base64.DEFAULT) }.getOrNull()
        if (androidDecoded != null && androidDecoded.isNotEmpty()) return androidDecoded

        // Pure Kotlin Base64 decoder (API 23+ & JVM unit-test safe, avoids java.util.Base64 API 26+)
        val out = ByteArray((padded.length * 3) / 4)
        var outIdx = 0
        var buf = 0
        var bits = 0
        for (ch in padded) {
            if (ch == '=') break
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> continue
            }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIdx++] = ((buf shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outIdx)
    }

    fun decodeStreamrptObf(html: String): String? {
        val b64 = Regex("""data-obf=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        return try {
            val decoded = String(decodeBase64Compat(b64), Charsets.UTF_8)
            val obj = JSONObject(decoded)
            // Chỉ lấy pU (stream-plain #EXTM3U thuần); không dùng sU vì sU trả payload mã hóa #ENC-AESGCM
            obj.optString("pU").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    fun extractFbCcccMp4(html: String): String? {
        val unescaped = html.replace("\\/", "/").replace("\\u0026", "&")
        return Regex("""var\s+cccc\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(unescaped)?.groupValues?.getOrNull(1)
    }

    fun extractEmbedMasterM3u8(html: String): String? {
        val unescaped = html.replace("\\/", "/").replace("\\u0026", "&")
        val templateMatch = Regex(
            """(https?://[^"'`\s]+/master\.m3u8\?storage=)\$\{[^}]+\}""",
            RegexOption.IGNORE_CASE
        ).find(unescaped)
        if (templateMatch != null) {
            return "${templateMatch.groupValues[1]}drive"
        }
        val dataAttr = Regex("""data-stream-url=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(unescaped)?.groupValues?.getOrNull(1)
        if (!dataAttr.isNullOrBlank()) return dataAttr
        return extractFbCcccMp4(unescaped)?.takeIf { it.contains(".m3u8", ignoreCase = true) }
            ?: EmbedFallbackView.extractM3u8FromHtml(unescaped)
    }

    suspend fun resolveStream(
        watchPageUrl: String,
        serverKey: String,
        refererBase: String = YAN_NOW_BASE
    ): StreamResult {
        if (watchPageUrl.isBlank()) return StreamResult()
        val btnName = serverKey.removePrefix("now:")
        val cacheKey = "now_stream_${watchPageUrl}_$btnName"
        getCache<StreamResult>(cacheKey)?.let { if (it.linkM3u8.isNotBlank()) return it }

        return try {
            val watchHtml = HttpClientProvider.getText(watchPageUrl, mapOf("Referer" to "$refererBase/"), 7500L)
            val btns = parseWatchButtons(watchHtml).sortedBy { serverPriorityScore(it) }
            val chosen = btns.firstOrNull { it.name.equals(btnName, ignoreCase = true) || it.label.equals(btnName, ignoreCase = true) }
                ?: btns.firstOrNull()
                ?: return StreamResult()

            var result = resolveDataSrc(chosen.dataSrc, watchPageUrl)
            // Nếu nút máy chủ được chọn không trả luồng trực tiếp, thử lần lượt các nút native khác cùng tập
            if (result.linkM3u8.isBlank()) {
                for (alt in btns) {
                    if (alt.dataSrc == chosen.dataSrc || !isDirectNativeServer(alt)) continue
                    val altRes = resolveDataSrc(alt.dataSrc, watchPageUrl)
                    if (altRes.linkM3u8.isNotBlank()) {
                        result = altRes
                        break
                    }
                }
            }
            if (result.linkM3u8.isNotBlank()) {
                // Short cache (~15 min) because stream-plain?t=... has expiry timestamp
                cache[cacheKey] = CacheEntry(result, System.currentTimeMillis() + 15L * 60L * 1000L)
            }
            result
        } catch (e: Exception) {
            com.hoathinh3d.donghua.core.AppDiagnostics.log("YanNow", "Stream err ($btnName): ${e.message}")
            StreamResult()
        }
    }

    suspend fun resolveDataSrc(dataSrc: String, refererUrl: String): StreamResult {
        val low = dataSrc.lowercase()
        return when {
            low.endsWith(".mp4") -> StreamResult(linkEmbed = dataSrc, linkM3u8 = dataSrc)
            low.contains("streamrpt1") && low.contains("play-fb") -> {
                val html = runCatching {
                    HttpClientProvider.getText(dataSrc, mapOf("Referer" to refererUrl), 6500L)
                }.getOrNull().orEmpty()
                val mp4 = extractFbCcccMp4(html)
                StreamResult(linkEmbed = dataSrc, linkM3u8 = mp4.orEmpty())
            }
            low.contains("streamrpt1") && low.contains("/embed/") -> {
                val html = runCatching {
                    HttpClientProvider.getText(dataSrc, mapOf("Referer" to refererUrl), 6500L)
                }.getOrNull().orEmpty()
                val m3u8 = if (html.trimStart().startsWith("#EXTM3U")) {
                    dataSrc
                } else {
                    decodeStreamrptObf(html) ?: extractEmbedMasterM3u8(html).orEmpty()
                }
                StreamResult(linkEmbed = dataSrc, linkM3u8 = m3u8)
            }
            low.contains("streamrpt1") -> {
                val body = runCatching {
                    HttpClientProvider.getText(dataSrc, mapOf("Referer" to refererUrl), 6500L)
                }.getOrNull().orEmpty()
                val directM3u8 = if (body.trimStart().startsWith("#EXTM3U")) {
                    dataSrc
                } else {
                    decodeStreamrptObf(body) ?: extractEmbedMasterM3u8(body).orEmpty()
                }
                StreamResult(linkEmbed = dataSrc, linkM3u8 = directM3u8)
            }
            low.contains(".m3u8") -> {
                val html = runCatching {
                    HttpClientProvider.getText(dataSrc, mapOf("Referer" to refererUrl), 5000L)
                }.getOrNull().orEmpty()
                val direct = if (html.trimStart().startsWith("#EXTM3U")) dataSrc
                else extractEmbedMasterM3u8(html).orEmpty()
                StreamResult(linkEmbed = dataSrc, linkM3u8 = direct)
            }
            else -> StreamResult(linkEmbed = dataSrc, linkM3u8 = "")
        }
    }
}
