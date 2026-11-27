package com.hoathinh3d.donghua.data.source

import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.GenreItem
import com.hoathinh3d.donghua.data.model.MovieDetailResult
import com.hoathinh3d.donghua.data.model.ServerGroup
import com.hoathinh3d.donghua.data.model.StreamResult
import com.hoathinh3d.donghua.data.model.WpSourceRef
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object YanHh3dScraper {
    const val YAN_BASE = "https://yanhh3d.ee"
    private const val CACHE_TTL_MS = 2L * 60L * 60L * 1000L

    private data class CacheEntry(val value: Any, val expiresAtMs: Long)

    private val wpCache = ConcurrentHashMap<String, CacheEntry>()
    private val categoryMaps = ConcurrentHashMap<String, Map<Int, String>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> getCache(key: String): T? {
        val hit = wpCache[key] ?: return null
        return if (hit.expiresAtMs > System.currentTimeMillis()) hit.value as? T else null
    }

    private fun setCache(key: String, value: Any) {
        wpCache[key] = CacheEntry(value, System.currentTimeMillis() + CACHE_TTL_MS)
    }

    fun decodeHtmlEntities(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return Jsoup.parse(str).text().trim()
    }

    private val CATEGORY_ICON_MAP = mapOf(
        "tu-tien" to "sparkles",
        "tien-hiep" to "yinyang",
        "huyen-huyen" to "dragon",
        "kiem-hiep" to "sword",
        "co-trang" to "crown",
        "do-thi" to "city",
        "hai-huoc" to "laugh",
        "hien-dai" to "bolt",
        "trung-sinh" to "flame",
        "xuyen-khong" to "hurricane",
        "luyen-cap" to "up",
        "ova" to "film"
    )

    val SHOWTIME_ID_TO_DAY = mapOf(
        4 to "Chủ Nhật",
        5 to "Thứ 2",
        6 to "Thứ 3",
        7 to "Thứ 4",
        8 to "Thứ 5",
        9 to "Thứ 6",
        10 to "Thứ 7"
    )

    val DAY_TO_SHOWTIME_ID = mapOf(
        "Chủ Nhật" to 4,
        "Thứ 2" to 5,
        "Thứ 3" to 6,
        "Thứ 4" to 7,
        "Thứ 5" to 8,
        "Thứ 6" to 9,
        "Thứ 7" to 10
    )

    data class YanHomeEpisodeMeta(
        val latestEp: Int,
        val totalEp: Int,
        val quality: String? = null
    )

    data class YanTaxonomyResult(
        val genres: List<GenreItem>,
        val categoryMap: Map<Int, String>,
        val totalMovies: Int
    )

    data class WpMoviesPage(
        val movies: List<DonghuaMovie>,
        val totalPages: Int,
        val totalItems: Int
    )

    fun parseTickRateBadge(raw: String?): YanHomeEpisodeMeta? {
        val clean = decodeHtmlEntities(raw)
        if (clean.isEmpty()) return null
        val qualityMatch = Regex("\\[([^\\]]+)]").find(clean)
        val quality = qualityMatch?.groupValues?.getOrNull(1)?.trim()
        val stripped = clean.replace(Regex("\\[[^\\]]+]"), "")
        if (stripped.contains("FULL", ignoreCase = true)) {
            return YanHomeEpisodeMeta(latestEp = 1, totalEp = 1, quality = quality ?: "Full HD")
        }
        val nums = Regex("\\d+").findAll(stripped).mapNotNull { it.value.toIntOrNull() }.toList()
        if (nums.isEmpty()) return null
        val latestEp = nums[0]
        if (latestEp <= 0) return null
        val totalEp = if (nums.size >= 2) maxOf(latestEp, nums[1]) else latestEp
        return YanHomeEpisodeMeta(latestEp = latestEp, totalEp = totalEp, quality = quality)
    }

    fun parseHomeEpisodeMetaHtml(html: String): Map<String, YanHomeEpisodeMeta> {
        val metaMap = LinkedHashMap<String, YanHomeEpisodeMeta>()
        // Regex match matching Web version + Jsoup fallback
        val itemRegex = Regex(
            """<div[^>]+class=["'][^"']*flw-item[^"']*["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        for (match in itemRegex.findAll(html)) {
            val block = match.groupValues[1]
            val tickRaw = Regex(
                """<div[^>]+class=["'][^"']*tick-rate[^"']*["'][^>]*>([\s\S]*?)</div>""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.getOrNull(1) ?: continue
            val href = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.getOrNull(1) ?: continue
            val parsed = parseTickRateBadge(tickRaw) ?: continue
            val slug = href.trimEnd('/').substringAfterLast('/').replace(Regex("\\.html$", RegexOption.IGNORE_CASE), "")
            if (slug.isNotEmpty()) {
                val prev = metaMap[slug]
                if (prev == null || parsed.latestEp > prev.latestEp) {
                    metaMap[slug] = parsed
                }
            }
        }
        if (metaMap.isEmpty()) {
            val doc = Jsoup.parse(html)
            for (item in doc.select(".flw-item")) {
                val tickRaw = item.selectFirst(".tick-rate")?.text() ?: continue
                val href = item.selectFirst("a[href]")?.attr("href") ?: continue
                val parsed = parseTickRateBadge(tickRaw) ?: continue
                val slug = href.trimEnd('/').substringAfterLast('/').replace(Regex("\\.html$", RegexOption.IGNORE_CASE), "")
                if (slug.isNotEmpty()) {
                    val prev = metaMap[slug]
                    if (prev == null || parsed.latestEp > prev.latestEp) {
                        metaMap[slug] = parsed
                    }
                }
            }
        }
        return metaMap
    }

    suspend fun fetchHomeEpisodeMeta(baseUrl: String = YAN_BASE): Map<String, YanHomeEpisodeMeta> {
        val cacheKey = "wp_home_ep_meta_v2_$baseUrl"
        getCache<Map<String, YanHomeEpisodeMeta>>(cacheKey)?.let { return it }
        return try {
            coroutineScope {
                val homeDeferred = async {
                    runCatching { HttpClientProvider.getText("$baseUrl/", mapOf("Referer" to "$baseUrl/"), 7000L) }.getOrDefault("")
                }
                val latestDeferred = async {
                    runCatching { HttpClientProvider.getText("$baseUrl/yanhh3d-moi-cap-nhat", mapOf("Referer" to "$baseUrl/"), 7000L) }.getOrDefault("")
                }
                val merged = LinkedHashMap<String, YanHomeEpisodeMeta>()
                for (html in listOf(homeDeferred.await(), latestDeferred.await())) {
                    if (html.isBlank()) continue
                    parseHomeEpisodeMetaHtml(html).forEach { (slug, meta) ->
                        val prev = merged[slug]
                        if (prev == null || meta.latestEp > prev.latestEp) {
                            merged[slug] = meta
                        }
                    }
                }
                if (merged.isNotEmpty()) setCache(cacheKey, merged)
                merged
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun parseArchiveHtmlCards(
        html: String,
        baseUrl: String = YAN_BASE,
        sourceName: String = "YanHH3D VIP",
        forcedGenre: String? = null
    ): List<DonghuaMovie> {
        val cleanBase = baseUrl.trimEnd('/')
        val doc = Jsoup.parse(html, cleanBase)
        val items = doc.select(".flw-item")
        val seen = HashSet<String>()
        val list = ArrayList<DonghuaMovie>(items.size)

        for ((idx, item) in items.withIndex()) {
            val linkEl = item.selectFirst("a.film-poster-ahref[href], .film-name a[href], a[href]") ?: continue
            val rawHref = linkEl.absUrl("href").ifBlank {
                val rel = linkEl.attr("href")
                if (rel.startsWith("http")) rel else "$cleanBase/${rel.trimStart('/')}"
            }
            val slug = rawHref.trimEnd('/').substringAfterLast('/').substringBefore('?')
            if (slug.isBlank() || !seen.add(slug)) continue

            val imgEl = item.selectFirst("img")
            val poster = imgEl?.absUrl("data-src")?.ifBlank { imgEl.attr("data-src") }
                ?.ifBlank { imgEl.absUrl("src").ifBlank { imgEl.attr("src") } }
                .orEmpty()
            val title = item.selectFirst(".film-name a, .film-name, h3, h4")?.text()?.trim()
                ?.ifBlank { linkEl.attr("title").trim() }
                ?.ifBlank { imgEl?.attr("alt")?.trim().orEmpty() }
                .orEmpty()
            if (title.isBlank()) continue

            val tickMeta = parseTickRateBadge(item.selectFirst(".tick-rate")?.text())
            val tickSub = item.selectFirst(".tick-sub, .tick-dub")?.text().orEmpty()
            val hasTm = tickSub.contains("TM", ignoreCase = true)
            val audioModes = if (hasTm) listOf("Thuyết Minh", "Vietsub") else listOf("Vietsub", "Thuyết Minh")
            val genres = mutableListOf("Tu Tiên")
            if (!forcedGenre.isNullOrBlank() && forcedGenre != "Tất cả" && forcedGenre != "Top Xem Nhiều" && forcedGenre != "Mới Cập Nhật") {
                genres.add(0, forcedGenre)
            }

            list.add(
                DonghuaMovie(
                    id = "yan_arch_$slug",
                    slug = slug,
                    yanUrl = rawHref,
                    wpSources = listOf(WpSourceRef(cleanBase, 0, rawHref, sourceName)),
                    title = title,
                    originalTitle = "$sourceName • $title",
                    studio = "$sourceName 4K",
                    latestEp = tickMeta?.latestEp ?: 1,
                    totalEp = tickMeta?.totalEp ?: 1,
                    rating = 9.8,
                    views = String.format(java.util.Locale.US, "%.1fM", 18.0 - (idx * 0.4).coerceAtMost(9.0)),
                    viewsNum = (100 - idx).coerceAtLeast(1).toDouble(),
                    quality = tickMeta?.quality?.takeIf { it.isNotBlank() }?.let { "$it • 1080p" } ?: "4K / 1080p",
                    audioModes = audioModes,
                    sources = listOf(sourceName),
                    genres = genres.distinct(),
                    scheduleDay = "Chủ Nhật",
                    scheduleTime = "09:30",
                    poster = poster,
                    thumb = poster,
                    streamIndex = idx % 4
                )
            )
        }
        return list
    }

    fun parseArchiveMaxPage(html: String): Int {
        val doc = Jsoup.parse(html)
        val byQuery = doc.select("a[href*='page=']").mapNotNull { el ->
            Regex("""page=(\d+)""").find(el.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        val byPath = doc.select("a[href*='/page/']").mapNotNull { el ->
            Regex("""/page/(\d+)""").find(el.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return (byQuery + byPath).maxOrNull() ?: 1
    }

    suspend fun fetchArchiveHtmlPage(
        baseUrl: String = YAN_BASE,
        path: String,
        sourceName: String = "YanHH3D VIP",
        page: Int = 1,
        forcedGenre: String? = null,
        bypassCache: Boolean = false
    ): WpMoviesPage {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trim().trim('/')
        val url = if (page > 1) "$cleanBase/$cleanPath/page/$page" else "$cleanBase/$cleanPath"
        val cacheKey = "yan_arch_page_${cleanBase}_${cleanPath}_${page}_${forcedGenre.orEmpty()}"
        if (!bypassCache) {
            getCache<WpMoviesPage>(cacheKey)?.let { if (it.movies.isNotEmpty()) return it }
        }
        return try {
            val headers = if (bypassCache) {
                mapOf("Referer" to "$cleanBase/", "Cache-Control" to "no-cache", "Pragma" to "no-cache")
            } else {
                mapOf("Referer" to "$cleanBase/")
            }
            val html = HttpClientProvider.getText(url, headers, 8000L)
            val movies = parseArchiveHtmlCards(html, cleanBase, sourceName, forcedGenre)
            val maxPage = parseArchiveMaxPage(html).coerceAtLeast(if (movies.isNotEmpty()) page else 1)
            val pageOut = WpMoviesPage(movies, maxPage, movies.size)
            if (movies.isNotEmpty()) setCache(cacheKey, pageOut)
            pageOut
        } catch (_: Exception) {
            WpMoviesPage(emptyList(), 1, 0)
        }
    }

    suspend fun fetchArchiveHtmlMovies(
        baseUrl: String = YAN_BASE,
        path: String,
        sourceName: String = "YanHH3D VIP",
        page: Int = 1,
        forcedGenre: String? = null,
        bypassCache: Boolean = false
    ): List<DonghuaMovie> = fetchArchiveHtmlPage(
        baseUrl = baseUrl,
        path = path,
        sourceName = sourceName,
        page = page,
        forcedGenre = forcedGenre,
        bypassCache = bypassCache
    ).movies

    private fun defaultCatMap(): Map<Int, String> = mapOf(
        15 to "Cổ Trang",
        17 to "Đô Thị",
        19 to "Hài Hước",
        22 to "Hiện Đại",
        23 to "Kiếm Hiệp",
        24 to "Tiên Hiệp",
        21 to "Trùng Sinh",
        25 to "Tu Tiên",
        1 to "Xuyên Không"
    )

    suspend fun fetchTaxonomies(
        baseUrl: String = YAN_BASE,
        forceRefresh: Boolean = false
    ): YanTaxonomyResult {
        val cacheKey = "wp_tax_$baseUrl"
        if (!forceRefresh) {
            getCache<YanTaxonomyResult>(cacheKey)?.let { return it }
        }

        val catMap = LinkedHashMap<Int, String>(categoryMaps[baseUrl] ?: defaultCatMap())
        return try {
            coroutineScope {
                val catsDeferred = async {
                    runCatching { HttpClientProvider.getText("$baseUrl/wp-json/wp/v2/categories?per_page=100") }.getOrNull()
                }
                val statusDeferred = async {
                    runCatching { HttpClientProvider.getText("$baseUrl/wp-json/wp/v2/status?per_page=20") }.getOrNull()
                }
                val catsJson = catsDeferred.await()?.let { JSONArray(it) }
                val statusJson = statusDeferred.await()?.let { JSONArray(it) }

                var totalMovies = 0
                val statusItems = mutableListOf<GenreItem>()
                if (statusJson != null) {
                    for (i in 0 until statusJson.length()) {
                        val st = statusJson.optJSONObject(i) ?: continue
                        val count = st.optInt("count", 0)
                        if (count <= 0) continue
                        val id = st.optInt("id")
                        val slug = st.optString("slug").lowercase()
                        val name = st.optString("name").lowercase()
                        if (slug.contains("ongoing") || name.contains("đang")) {
                            statusItems.add(
                                GenreItem(
                                    id = "Đang chiếu",
                                    label = "Đang chiếu ($count)",
                                    icon = "broadcast",
                                    count = count,
                                    yanStatusId = id,
                                    wpStatusId = id
                                )
                            )
                            totalMovies += count
                        } else if (slug.contains("completed") || name.contains("hoàn")) {
                            statusItems.add(
                                GenreItem(
                                    id = "Hoàn thành",
                                    label = "Hoàn thành ($count)",
                                    icon = "check",
                                    count = count,
                                    yanStatusId = id,
                                    wpStatusId = id
                                )
                            )
                            totalMovies += count
                        }
                    }
                }

                val genreItems = mutableListOf<GenreItem>()
                if (catsJson != null) {
                    val rawCats = (0 until catsJson.length())
                        .mapNotNull { catsJson.optJSONObject(it) }
                        .filter { it.optInt("count", 0) > 0 }
                        .sortedByDescending { it.optInt("count", 0) }
                    for (c in rawCats) {
                        val cid = c.optInt("id")
                        val cname = decodeHtmlEntities(c.optString("name"))
                        val cslug = c.optString("slug")
                        val ccount = c.optInt("count", 0)
                        catMap[cid] = cname
                        genreItems.add(
                            GenreItem(
                                id = cname,
                                label = "$cname ($ccount)",
                                icon = CATEGORY_ICON_MAP[cslug] ?: "tag",
                                count = ccount,
                                sourceSlug = cslug,
                                yanCategoryId = cid,
                                wpCategoryId = cid
                            )
                        )
                    }
                    if (totalMovies == 0) {
                        totalMovies = rawCats.maxOfOrNull { it.optInt("count", 0) } ?: 0
                    }
                }

                categoryMaps[baseUrl] = catMap

                val genres = buildList {
                    add(
                        GenreItem(
                            id = "Tất cả",
                            label = "Thể loại phim",
                            icon = "grid",
                            count = totalMovies
                        )
                    )
                    add(
                        GenreItem(
                            id = "Phim Hot",
                            label = "Phim Hot",
                            icon = "flame",
                            sourceSlug = "top-yanhh3d-xem-nhieu"
                        )
                    )
                    val cinemaCat = genreItems.firstOrNull {
                        it.id.lowercase().contains("chiếu rạp") || (it.sourceSlug ?: "").contains("chieu-rap")
                    }
                    add(
                        GenreItem(
                            id = "Phim Chiếu Rạp",
                            label = "Phim Chiếu Rạp",
                            icon = "sparkles",
                            sourceSlug = cinemaCat?.sourceSlug ?: "topic:phim-chieu-rap",
                            wpCategoryId = cinemaCat?.wpCategoryId
                        )
                    )
                    val leCat = genreItems.firstOrNull {
                        val s = (it.sourceSlug ?: "").lowercase()
                        s.contains("ova") || s.contains("phim-le") || it.id.lowercase().contains("phim lẻ")
                    }
                    add(
                        GenreItem(
                            id = "Phim Lẻ",
                            label = "Phim Lẻ",
                            icon = "film",
                            sourceSlug = leCat?.sourceSlug ?: "topic:phim-le",
                            wpCategoryId = leCat?.wpCategoryId
                        )
                    )
                    add(
                        GenreItem(
                            id = "Phim Bộ",
                            label = "Phim Bộ",
                            icon = "grid",
                            sourceSlug = "topic:phim-bo"
                        )
                    )
                    add(
                        GenreItem(
                            id = "Phim Hoạt Hình",
                            label = "Phim Hoạt Hình",
                            icon = "dragon",
                            sourceSlug = "yanhh3d-moi-cap-nhat"
                        )
                    )
                    val actionCat = genreItems.firstOrNull {
                        it.id.lowercase().contains("hành động") || (it.sourceSlug ?: "").contains("hanh-dong")
                    }
                    add(
                        GenreItem(
                            id = "Phim Hành Động",
                            label = "Phim Hành Động",
                            icon = "sword",
                            sourceSlug = actionCat?.sourceSlug ?: "topic:phim-hanh-dong",
                            wpCategoryId = actionCat?.wpCategoryId
                        )
                    )
                    val romanceCat = genreItems.firstOrNull {
                        it.id.lowercase().contains("tình cảm") || (it.sourceSlug ?: "").contains("tinh-cam")
                    }
                    add(
                        GenreItem(
                            id = "Tình Cảm",
                            label = "Tình Cảm",
                            icon = "tag",
                            sourceSlug = romanceCat?.sourceSlug ?: "topic:tinh-cam",
                            wpCategoryId = romanceCat?.wpCategoryId
                        )
                    )
                    add(
                        GenreItem(
                            id = "Đề Cử Hot",
                            label = "Đề Cử Hot (126)",
                            icon = "sparkles",
                            sourceSlug = "post_options:11",
                            count = 126
                        )
                    )
                    addAll(statusItems)
                    val coreLower = setOf(
                        "phim hot", "phim chiếu rạp", "phim lẻ", "phim bộ",
                        "phim hoạt hình", "phim hành động", "tình cảm"
                    )
                    addAll(genreItems.filterNot { it.id.lowercase() in coreLower })
                }

                val result = YanTaxonomyResult(genres, catMap, totalMovies)
                if (genreItems.isNotEmpty()) setCache(cacheKey, result)
                result
            }
        } catch (_: Exception) {
            YanTaxonomyResult(emptyList(), catMap, 0)
        }
    }

    suspend fun findByExactSlug(
        baseUrl: String = YAN_BASE,
        sourceName: String = "YanHH3D",
        slug: String
    ): DonghuaMovie? {
        if (slug.isBlank()) return null
        val cacheKey = "wp_exact_slug_${baseUrl}_$slug"
        getCache<DonghuaMovie>(cacheKey)?.let { return it }
        return try {
            val encoded = URLEncoder.encode(slug.trim(), "UTF-8")
            val resp = HttpClientProvider.getTextWithHeaders("$baseUrl/wp-json/wp/v2/posts?slug=$encoded&_embed=1", timeoutMs = 5000L)
            val arr = JSONArray(resp.body)
            if (arr.length() == 0) return null
            val p = arr.getJSONObject(0)
            val postId = p.optInt("id")
            val link = p.optString("link")
            val titleRaw = p.optJSONObject("title")?.optString("rendered") ?: slug
            val title = decodeHtmlEntities(titleRaw).ifEmpty { "Donghua 3D" }
            val isYan = baseUrl == YAN_BASE
            val m = DonghuaMovie(
                id = "${if (isYan) "yan" else sourceName.lowercase()}_$postId",
                title = title,
                originalTitle = "$sourceName • $title",
                slug = p.optString("slug", slug),
                yanPostId = if (isYan) postId else null,
                yanUrl = if (isYan) link else null,
                wpSources = listOf(WpSourceRef(baseUrl, postId, link, sourceName)),
                studio = "$sourceName 4K",
                latestEp = 1,
                totalEp = 1,
                rating = 9.8,
                views = "10.0M",
                viewsNum = 10.0,
                quality = "4K / 1080p",
                audioModes = listOf("Vietsub", "Thuyết Minh"),
                sources = listOf(sourceName),
                genres = listOf("Tu Tiên"),
                scheduleDay = "Chủ Nhật",
                scheduleTime = "09:30",
                poster = ""
            )
            setCache(cacheKey, m)
            m
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchMovies(
        baseUrl: String = YAN_BASE,
        sourceName: String = "YanHH3D",
        page: Int = 1,
        perPage: Int = 30,
        categoryId: Int? = null,
        statusId: Int? = null,
        showtimeId: Int? = null,
        postOptionsId: Int? = null,
        search: String? = null
    ): WpMoviesPage {
        val cacheKey = "wp_posts_v2_${baseUrl}_${page}_${perPage}_${categoryId ?: ""}_${statusId ?: ""}_${showtimeId ?: ""}_${postOptionsId ?: ""}_${search ?: ""}"
        getCache<WpMoviesPage>(cacheKey)?.let { return it }

        val catMap = categoryMaps[baseUrl] ?: defaultCatMap()
        val qs = buildString {
            append("page=").append(page)
            append("&per_page=").append(perPage)
            append("&_embed=1")
            if (categoryId != null && categoryId > 0) append("&categories=").append(categoryId)
            if (statusId != null && statusId > 0) append("&status=").append(statusId)
            if (showtimeId != null && showtimeId > 0) append("&showtimes=").append(showtimeId)
            if (postOptionsId != null && postOptionsId > 0) append("&post_options=").append(postOptionsId)
            if (!search.isNullOrBlank()) {
                append("&search=").append(URLEncoder.encode(search.trim(), "UTF-8"))
            }
        }

        val isYan = baseUrl == YAN_BASE
        return coroutineScope {
            val postsDeferred = async {
                HttpClientProvider.getTextWithHeaders("$baseUrl/wp-json/wp/v2/posts?$qs")
            }
            val epMetaDeferred = async {
                if (isYan) fetchHomeEpisodeMeta(baseUrl) else emptyMap()
            }

            val resp = postsDeferred.await()
            val homeEpMeta = epMetaDeferred.await()
            val posts = JSONArray(resp.body)
            val totalItems = resp.headers["x-wp-total"]?.toIntOrNull() ?: posts.length()
            val totalPages = resp.headers["x-wp-totalpages"]?.toIntOrNull() ?: 1
            val idPrefix = if (isYan) "yan" else sourceName.lowercase().replace(Regex("[^a-z0-9]"), "")

            val movies = ArrayList<DonghuaMovie>(posts.length())
            for (index in 0 until posts.length()) {
                val p = posts.optJSONObject(index) ?: continue
                val postId = p.optInt("id")
                val slug = p.optString("slug")
                val link = p.optString("link")
                val titleRaw = p.optJSONObject("title")?.optString("rendered") ?: slug
                val title = decodeHtmlEntities(titleRaw).ifEmpty { "Donghua 3D" }

                val embedded = p.optJSONObject("_embedded")
                val mediaArr = embedded?.optJSONArray("wp:featuredmedia")
                val featured = mediaArr?.optJSONObject(0)
                val medLarge = featured?.optJSONObject("media_details")
                    ?.optJSONObject("sizes")
                    ?.optJSONObject("medium_large")
                    ?.optString("source_url")
                val poster = medLarge?.takeIf { it.isNotBlank() }
                    ?: featured?.optString("source_url")?.takeIf { it.isNotBlank() }
                    ?: "https://yanhh3d.ee/wp-content/uploads/2025/04/Tien-Nghich-300x450.webp"

                val genres = mutableListOf<String>()
                val catsArr = p.optJSONArray("categories")
                if (catsArr != null) {
                    for (ci in 0 until catsArr.length()) {
                        val cid = catsArr.optInt(ci)
                        val cname = catMap[cid]
                        if (!cname.isNullOrBlank() && !genres.contains(cname)) {
                            genres.add(cname)
                        }
                    }
                }
                if (genres.isEmpty()) genres.add("Tu Tiên")

                val showtimesArr = p.optJSONArray("showtimes")
                val showtimeIdFirst = if (showtimesArr != null && showtimesArr.length() > 0) showtimesArr.optInt(0) else 0
                val scheduleDay = SHOWTIME_ID_TO_DAY[showtimeIdFirst] ?: "Chủ Nhật"

                val statusArr = p.optJSONArray("status")
                var isCompleted = false
                if (statusArr != null) {
                    for (si in 0 until statusArr.length()) {
                        if (statusArr.optInt(si) == 12) {
                            isCompleted = true
                            break
                        }
                    }
                }
                val statusLabel = if (isCompleted) "Hoàn thành" else "Đang chiếu"

                val contentHtml = p.optJSONObject("content")?.optString("rendered")
                    ?: p.optJSONObject("excerpt")?.optString("rendered") ?: ""
                val desc = decodeHtmlEntities(contentHtml)

                val slugMeta = if (slug.isNotEmpty()) homeEpMeta[slug] else null
                val latestEp = slugMeta?.latestEp ?: 1
                val totalEp = slugMeta?.totalEp ?: if (isCompleted) maxOf(latestEp, 52) else maxOf(latestEp, 180)
                val quality = if (!slugMeta?.quality.isNullOrBlank()) "${slugMeta!!.quality} • 1080p" else "4K / 1080p"

                movies.add(
                    DonghuaMovie(
                        id = "${idPrefix}_$postId",
                        slug = slug,
                        yanPostId = if (isYan) postId else null,
                        yanUrl = if (isYan) link else null,
                        wpSources = listOf(WpSourceRef(baseUrl, postId, link, sourceName)),
                        statusLabel = statusLabel,
                        title = title,
                        originalTitle = "$statusLabel • $sourceName",
                        studio = "$sourceName 4K",
                        latestEp = latestEp,
                        totalEp = totalEp,
                        rating = 9.8,
                        views = String.format(java.util.Locale.US, "%.1fM", 11.0 + ((index * 0.9) % 9.0)),
                        viewsNum = 11.0 + ((index * 0.9) % 9.0),
                        quality = quality,
                        audioModes = listOf("Vietsub", "Thuyết Minh"),
                        sources = listOf(sourceName),
                        genres = genres,
                        scheduleDay = scheduleDay,
                        scheduleTime = "09:30",
                        poster = poster,
                        thumb = poster,
                        description = desc,
                        streamIndex = index % 4
                    )
                )
            }

            val pageOut = WpMoviesPage(movies, totalPages, totalItems)
            if (movies.isNotEmpty()) setCache(cacheKey, pageOut)
            pageOut
        }
    }

    private data class ParsedEpEntry(
        val postId: Int,
        val epSlug: String,
        val href: String,
        val name: String,
        val epNum: Double,
        val sv: String
    )

    fun parseDetailHtmlOnly(
        baseUrl: String,
        sourceName: String,
        detailHtml: String,
        fallbackPostId: Int = 0,
        fallbackDesc: String = ""
    ): Pair<MovieDetailResult, String?> {
        val descRegexes = listOf(
            Regex("""<div[^>]+class=["'][^"']*film-description[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE),
            Regex("""<div[^>]+class=["'][^"']*item-content[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE),
            Regex("""<div[^>]+class=["'][^"']*description[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
        )
        val descMatch = descRegexes.firstNotNullOfOrNull { it.find(detailHtml)?.groupValues?.getOrNull(1) }
        val description = if (!descMatch.isNullOrBlank()) decodeHtmlEntities(descMatch) else fallbackDesc

        // Strategy 1: Halim server blocks
        val halimBlockRegex = Regex(
            """<div[^>]+class=["'][^"']*halim-server[^"']*["'][^>]*>([\s\S]*?)</ul>\s*</div>""",
            RegexOption.IGNORE_CASE
        )
        val halimBlocks = halimBlockRegex.findAll(detailHtml).toList()
        if (halimBlocks.isNotEmpty()) {
            val rawGroups = mutableListOf<Pair<String, List<ParsedEpEntry>>>()
            for (blk in halimBlocks) {
                val blkHtml = blk.groupValues[1]
                val rawSrvName = Regex(
                    """<span[^>]+class=["'][^"']*halim-server-name[^"']*["'][^>]*>([\s\S]*?)</span>""",
                    RegexOption.IGNORE_CASE
                ).find(blkHtml)?.groupValues?.getOrNull(1)
                val cleanSrvName = decodeHtmlEntities(rawSrvName ?: "Vietsub")
                    .replace(Regex("^[#:\\s]+|[#:\\s]+$"), "")
                    .trim()
                    .ifEmpty { "Vietsub" }

                val eps = mutableListOf<ParsedEpEntry>()
                val seen = HashSet<String>()
                val aRegex = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                for (m in aRegex.findAll(blkHtml)) {
                    val attrs = m.groupValues[1]
                    val rawText = decodeHtmlEntities(m.groupValues[2])
                    val href = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.getOrNull(1) ?: continue
                    val postIdStr = Regex("""data-post-id=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.getOrNull(1)
                    val epAttr = Regex("""data-ep=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.getOrNull(1)
                    val svAttr = Regex("""data-sv=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.getOrNull(1) ?: "1"

                    val normalizedEpSlug = epAttr ?: href.substringAfterLast('/').replace(Regex("\\.html$", RegexOption.IGNORE_CASE), "")
                    val key = "${svAttr}_${normalizedEpSlug.ifEmpty { href }}"
                    if (!seen.add(key)) continue

                    val numMatch = Regex("""(\d+(?:\.\d+)?)""").find(normalizedEpSlug.ifEmpty { rawText })
                    val epNum = numMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: (eps.size + 1).toDouble()
                    eps.add(
                        ParsedEpEntry(
                            postId = postIdStr?.toIntOrNull() ?: fallbackPostId,
                            epSlug = normalizedEpSlug.ifEmpty { "tap-${epNum.toInt()}" },
                            href = href,
                            name = rawText.ifEmpty { "Tập ${epNum.toInt()}" },
                            epNum = epNum,
                            sv = svAttr
                        )
                    )
                }
                if (eps.isNotEmpty()) {
                    eps.sortBy { it.epNum }
                    rawGroups.add(cleanSrvName to eps)
                }
            }
            if (rawGroups.isNotEmpty()) {
                val sampleHref = rawGroups[0].second.last().href
                val defaultVariants = listOf("tiktik" to "1080P V1", "pro" to "1080P V2")
                val groups = mutableListOf<ServerGroup>()
                for ((srvName, eps) in rawGroups) {
                    for ((vType, vLabel) in defaultVariants) {
                        groups.add(
                            ServerGroup(
                                serverName = "$sourceName • $srvName ($vLabel)",
                                episodes = eps.map { ep ->
                                    EpisodeItem(
                                        name = ep.name,
                                        slug = ep.epSlug,
                                        wpBaseUrl = baseUrl,
                                        wpPostId = ep.postId,
                                        wpEpSlug = ep.epSlug,
                                        wpServerType = vType,
                                        wpServerSv = ep.sv,
                                        wpPageUrl = ep.href
                                    )
                                }
                            )
                        )
                    }
                }
                val maxEp = groups.maxOfOrNull { it.episodes.size } ?: 1
                return MovieDetailResult(groups, description, null, maxEp, maxEp) to sampleHref
            }
        }

        // Strategy 2: YanHH3D .ep-range / .ep-item
        val parsedEps = mutableListOf<ParsedEpEntry>()
        val seenEp = HashSet<String>()
        val epRegex = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val allAnchors = epRegex.findAll(detailHtml).toList()
        // Ưu tiên quét các thẻ tập chính thức (data-ep / ep-item / ssl-item) trước để không bị nút "Xem phim" (btn-play) nuốt mất data-post-id
        val prioritized = allAnchors.sortedBy { m ->
            val attrs = m.groupValues[1].lowercase()
            if (attrs.contains("data-ep=") || attrs.contains("ep-item") || attrs.contains("ssl-item")) 0 else 1
        }
        for (m in prioritized) {
            val attrs = m.groupValues[1]
            val rawText = decodeHtmlEntities(m.groupValues[2])
            val href = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.getOrNull(1) ?: continue
            val postIdStr = Regex("""data-post-id=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.getOrNull(1)
            val epSlug = Regex("""data-ep=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.getOrNull(1)
            val svAttr = Regex("""data-sv=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.getOrNull(1) ?: "1"

            val isEpisodeLink = !epSlug.isNullOrEmpty() ||
                href.contains("/tap-") || href.contains("/xem-phim-") || href.contains("/watch-")
            if (!isEpisodeLink) continue

            val normalizedEpSlug = epSlug ?: href.substringAfterLast('/').replace(Regex("\\.html$", RegexOption.IGNORE_CASE), "")
                .ifEmpty { "tap-${parsedEps.size + 1}" }
            if (!seenEp.add(normalizedEpSlug)) continue

            val numMatch = Regex("""(\d+(?:\.\d+)?)""").find(normalizedEpSlug.ifEmpty { rawText })
            val epNum = numMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: (parsedEps.size + 1).toDouble()
            val displayName = when {
                normalizedEpSlug.contains("full", ignoreCase = true) -> "Full"
                rawText.isNotEmpty() && !rawText.lowercase().contains("xem phim") -> rawText
                else -> "Tập ${epNum.toInt()}"
            }

            parsedEps.add(
                ParsedEpEntry(
                    postId = postIdStr?.toIntOrNull() ?: fallbackPostId,
                    epSlug = normalizedEpSlug,
                    href = href,
                    name = displayName,
                    epNum = epNum,
                    sv = svAttr
                )
            )
        }

        parsedEps.sortBy { it.epNum }
        if (parsedEps.isEmpty()) {
            return MovieDetailResult(emptyList(), description) to null
        }

        val defaultServers = listOf(
            "tiktik" to "VIETSUB 1080 V1",
            "pro" to "VIETSUB 1080 V2",
            "vip4k" to "VIETSUB 4K V1",
            "vip4k_v2" to "VIETSUB 4K V2",
            "tiktm" to "Thuyết minh 1080 V1",
            "pro_tm" to "Thuyết minh 1080 V2",
            "vip4ktm" to "Thuyết minh 4k V1",
            "vip4ktm_v2" to "Thuyết minh 4k V2"
        )
        val serverGroups = defaultServers.map { (svType, svLabel) ->
            ServerGroup(
                serverName = "$sourceName • $svLabel",
                episodes = parsedEps.map { ep ->
                    EpisodeItem(
                        name = ep.name,
                        slug = ep.epSlug,
                        yanPostId = ep.postId,
                        yanEpSlug = ep.epSlug,
                        yanServerType = svType,
                        yanPageUrl = ep.href,
                        wpBaseUrl = baseUrl,
                        wpPostId = ep.postId,
                        wpEpSlug = ep.epSlug,
                        wpServerType = svType,
                        wpServerSv = ep.sv,
                        wpPageUrl = ep.href
                    )
                }
            )
        }
        val highestEp = parsedEps.maxOf { kotlin.math.floor(it.epNum).toInt() }.coerceAtLeast(parsedEps.size)
        val tickMatch = Regex(
            """<div[^>]+class=["'][^"']*tick-rate[^"']*["'][^>]*>([\s\S]*?)</div>""",
            RegexOption.IGNORE_CASE
        ).find(detailHtml)?.groupValues?.getOrNull(1)
        val tickParsed = parseTickRateBadge(tickMatch)
        val posterUrl = Regex(
            """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(detailHtml)?.groupValues?.getOrNull(1)
            ?: Regex(
                """<img[^>]+class=["'][^"']*film-poster-img[^"']*["'][^>]+src=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(detailHtml)?.groupValues?.getOrNull(1)
        val latest = maxOf(highestEp, tickParsed?.latestEp ?: 0)
        val total = maxOf(highestEp, tickParsed?.totalEp ?: 0)
        return MovieDetailResult(serverGroups, description, posterUrl, total, latest) to parsedEps.last().href
    }

    suspend fun fetchMovieDetail(
        baseUrl: String,
        sourceName: String,
        targetUrl: String,
        fallbackPostId: Int = 0,
        fallbackDesc: String = ""
    ): MovieDetailResult {
        if (targetUrl.isBlank()) return MovieDetailResult()
        val cacheKey = "wp_detail_${baseUrl}_$targetUrl"
        getCache<MovieDetailResult>(cacheKey)?.let { if (it.serverGroups.isNotEmpty()) return it }

        return try {
            val detailHtml = HttpClientProvider.getText(targetUrl, mapOf("Referer" to "$baseUrl/"))
            val (parsed, sampleEpHref) = parseDetailHtmlOnly(baseUrl, sourceName, detailHtml, fallbackPostId, fallbackDesc)
            if (parsed.serverGroups.isEmpty() || sampleEpHref.isNullOrBlank()) return parsed

            // Fetch real server variants from latest episode page (and fallback to first episode if incomplete)
            val servers = try {
                fun parseServersFromEpHtml(epHtml: String): List<Pair<String, String>> {
                    val btnRegex = Regex(
                        """<span[^>]+class=["'][^"']*btn3dsv[^"']*["'][^>]*data-type=["']([^"']+)["'][^>]*>([\s\S]*?)</span>""",
                        RegexOption.IGNORE_CASE
                    )
                    val foundBtn = btnRegex.findAll(epHtml).map {
                        it.groupValues[1] to decodeHtmlEntities(it.groupValues[2])
                    }.distinctBy { it.first }.toList()
                    if (foundBtn.isNotEmpty()) return foundBtn
                    return Regex("""<span[^>]+data-type=["']([^"']+)["'][^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
                        .findAll(epHtml)
                        .map { it.groupValues[1] to decodeHtmlEntities(it.groupValues[2]) }
                        .distinctBy { it.first }
                        .toList()
                }

                val epHtml = HttpClientProvider.getText(sampleEpHref, mapOf("Referer" to targetUrl))
                val latestServers = parseServersFromEpHtml(epHtml)
                val firstEpHref = parsed.serverGroups.firstOrNull()?.episodes?.firstOrNull()?.wpPageUrl
                if (latestServers.size < 8 && !firstEpHref.isNullOrBlank() && firstEpHref != sampleEpHref) {
                    val firstHtml = runCatching {
                        HttpClientProvider.getText(firstEpHref, mapOf("Referer" to targetUrl))
                    }.getOrNull()
                    if (!firstHtml.isNullOrBlank()) {
                        val merged = LinkedHashMap<String, String>()
                        latestServers.forEach { (k, v) -> merged[k] = v }
                        parseServersFromEpHtml(firstHtml).forEach { (k, v) ->
                            if (!merged.containsKey(k)) merged[k] = v
                        }
                        merged.entries.map { it.key to it.value }
                    } else latestServers
                } else latestServers
            } catch (_: Exception) {
                emptyList()
            }

            val finalResult = if (servers.isNotEmpty()) {
                val baseEpisodes = parsed.serverGroups.first().episodes
                val updatedGroups = servers.map { (svType, svLabel) ->
                    ServerGroup(
                        serverName = "$sourceName • $svLabel",
                        episodes = baseEpisodes.map { ep ->
                            ep.copy(
                                yanServerType = svType,
                                wpServerType = svType
                            )
                        }
                    )
                }
                parsed.copy(serverGroups = updatedGroups)
            } else parsed

            if (finalResult.serverGroups.isNotEmpty()) {
                setCache(cacheKey, finalResult)
            }
            finalResult
        } catch (_: Exception) {
            MovieDetailResult()
        }
    }

    fun parseResolveStreamHtml(html: String, baseUrl: String): StreamResult {
        val cleanBase = baseUrl.ifBlank { YAN_BASE }
        var iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1) ?: ""
        if (iframeSrc.startsWith("//")) {
            iframeSrc = "https:$iframeSrc"
        } else if (iframeSrc.startsWith("/")) {
            iframeSrc = "$cleanBase$iframeSrc"
        }
        val directM3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1) ?: ""
        return StreamResult(linkEmbed = iframeSrc, linkM3u8 = directM3u8)
    }

    suspend fun resolveStream(
        baseUrl: String,
        postId: Int,
        epSlug: String,
        serverType: String,
        sv: String = "1",
        refererUrl: String? = null
    ): StreamResult {
        val cleanBase = baseUrl.ifBlank { YAN_BASE }
        val cacheKey = "wp_stream_${cleanBase}_${postId}_${epSlug}_${serverType}_$sv"
        getCache<StreamResult>(cacheKey)?.let { if (it.linkEmbed.isNotEmpty() || it.linkM3u8.isNotEmpty()) return it }

        val url = "$cleanBase/player/player.php?action=dox_ajax_player" +
            "&post_id=${URLEncoder.encode(postId.toString(), "UTF-8")}" +
            "&chapter_st=${URLEncoder.encode(epSlug, "UTF-8")}" +
            "&type=${URLEncoder.encode(serverType, "UTF-8")}" +
            "&sv=${URLEncoder.encode(sv.ifEmpty { "1" }, "UTF-8")}"

        val html = HttpClientProvider.getText(
            url,
            mapOf(
                "Referer" to (refererUrl ?: "$cleanBase/"),
                "X-Requested-With" to "XMLHttpRequest"
            )
        )
        val out = parseResolveStreamHtml(html, cleanBase)
        if (out.linkEmbed.isNotEmpty() || out.linkM3u8.isNotEmpty()) {
            setCache(cacheKey, out)
        }
        return out
    }
}
