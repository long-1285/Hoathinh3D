package com.hoathinh3d.donghua.data.source

import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.GenreItem
import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.model.ServerGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object NguonCApiClient {
    const val NGUONC_BASE = "https://phim.nguonc.com"
    const val PHIMAPI_BASE = "https://phimapi.com"

    fun parseFirstInt(value: Any?, fallback: Int = 1): Int {
        val m = Regex("""\d+""").find(value?.toString().orEmpty()) ?: return fallback
        val n = m.value.toIntOrNull() ?: return fallback
        return if (n > 0) n else fallback
    }

    fun mapPhimApiItems(
        items: JSONArray?,
        sourceTag: String = "NguonC",
        forcedGenre: String? = null
    ): List<DonghuaMovie> {
        if (items == null) return emptyList()
        val out = ArrayList<DonghuaMovie>(items.length())
        for (index in 0 until items.length()) {
            val it = items.optJSONObject(index) ?: continue
            val rawPoster = it.optString("poster_url").ifEmpty { it.optString("thumb_url") }
            val poster = if (rawPoster.startsWith("http")) rawPoster else "https://phimimg.com/${rawPoster.trimStart('/')}"
            val rawThumb = it.optString("thumb_url").ifEmpty { it.optString("poster_url") }
            val thumb = if (rawThumb.startsWith("http")) rawThumb else "https://phimimg.com/${rawThumb.trimStart('/')}"

            val langStr = it.optString("lang").lowercase()
            val isDub = langStr.contains("thuyết") || langStr.contains("lồng")
            val cats = mutableListOf<String>()
            val catArr = it.optJSONArray("category")
            if (catArr != null) {
                for (ci in 0 until catArr.length()) {
                    val name = catArr.optJSONObject(ci)?.optString("name")?.trim().orEmpty()
                    if (name.isNotEmpty() && !cats.contains(name)) cats.add(name)
                }
            }
            if (!forcedGenre.isNullOrBlank() && !cats.contains(forcedGenre)) {
                cats.add(0, forcedGenre)
            }
            if (cats.isEmpty()) cats.add("Hoạt Hình")

            val slug = it.optString("slug")
            val id = it.optString("_id").ifEmpty { slug }
            val viewsNum = 8.5 + ((index * 1.1) % 7.0)

            out.add(
                DonghuaMovie(
                    id = id,
                    slug = slug,
                    title = it.optString("name"),
                    originalTitle = it.optString("origin_name").ifEmpty { it.optString("name") },
                    studio = it.optString("year").takeIf { y -> y.isNotBlank() && y != "null" }
                        ?.let { y -> "$y • $sourceTag" } ?: "$sourceTag Stream",
                    latestEp = parseFirstInt(it.opt("episode_current"), 1),
                    totalEp = parseFirstInt(it.opt("episode_total"), 24),
                    rating = 9.6,
                    views = String.format(java.util.Locale.US, "%.1fM", viewsNum),
                    viewsNum = viewsNum,
                    quality = it.optString("quality").ifEmpty { "FHD" },
                    audioModes = if (isDub) listOf("Thuyết Minh", "Vietsub") else listOf("Vietsub", "Thuyết Minh"),
                    sources = listOf(sourceTag),
                    genres = cats,
                    scheduleDay = InitialData.SCHEDULE_DAYS[(index + 1) % InitialData.SCHEDULE_DAYS.size],
                    scheduleTime = "${10 + (index % 2)}:00",
                    poster = poster,
                    thumb = thumb,
                    streamIndex = index % 4
                )
            )
        }
        return out
    }

    fun mapNguonCItems(
        items: JSONArray?,
        sourceTag: String = "NguonC",
        forcedGenre: String? = null
    ): List<DonghuaMovie> {
        if (items == null) return emptyList()
        val out = ArrayList<DonghuaMovie>(items.length())
        for (index in 0 until items.length()) {
            val it = items.optJSONObject(index) ?: continue
            val poster = it.optString("poster_url_webp")
                .ifEmpty { it.optString("poster_url") }
                .ifEmpty { it.optString("thumb_url_webp") }
                .ifEmpty { it.optString("thumb_url") }
            val thumb = it.optString("thumb_url_webp")
                .ifEmpty { it.optString("thumb_url") }
                .ifEmpty { poster }
            val langLow = it.optString("language").lowercase()
            val isDub = langLow.contains("thuyết") || langLow.contains("lồng")
            val genres = mutableListOf("Hoạt Hình", "Tu Tiên", "Huyền Huyễn")
            if (!forcedGenre.isNullOrBlank() && !genres.contains(forcedGenre)) {
                genres.add(0, forcedGenre)
            }
            if (isDub && !genres.contains("Thuyết Minh")) {
                genres.add("Thuyết Minh")
            }
            val slug = it.optString("slug")
            val id = it.optString("id").ifEmpty { slug }
            val viewsNum = 8.2 + ((index * 1.1) % 7.0)
            val rawDesc = it.optString("description")
                .takeIf { d -> d.isNotBlank() && d != "null" }
                ?.replace(Regex("<[^>]+>"), "")

            out.add(
                DonghuaMovie(
                    id = id,
                    slug = slug,
                    title = it.optString("name"),
                    originalTitle = it.optString("original_name").ifEmpty { it.optString("name") },
                    studio = it.optString("year").takeIf { y -> y.isNotBlank() && y != "null" }
                        ?.let { y -> "$y • $sourceTag" } ?: "$sourceTag Stream",
                    latestEp = parseFirstInt(it.opt("current_episode"), 1),
                    totalEp = parseFirstInt(it.opt("total_episodes"), 52),
                    rating = 9.6,
                    views = String.format(java.util.Locale.US, "%.1fM", viewsNum),
                    viewsNum = viewsNum,
                    quality = it.optString("quality").ifEmpty { "FHD" },
                    audioModes = if (isDub) listOf("Thuyết Minh", "Vietsub") else listOf("Vietsub", "Thuyết Minh"),
                    sources = listOf(sourceTag),
                    genres = genres,
                    scheduleDay = InitialData.SCHEDULE_DAYS[(index + 2) % InitialData.SCHEDULE_DAYS.size],
                    scheduleTime = "${10 + (index % 2)}:00",
                    poster = poster,
                    thumb = thumb,
                    description = rawDesc,
                    streamIndex = index % 4
                )
            )
        }
        return out
    }

    suspend fun fetchHoatHinhFeed(page: Int = 1): List<DonghuaMovie> = coroutineScope {
        val jobs = listOf(page, page + 1, page + 2).map { p ->
            async {
                runCatching {
                    val json = HttpClientProvider.getText("$NGUONC_BASE/api/films/the-loai/hoat-hinh?page=$p", timeoutMs = 6500L)
                    JSONObject(json).optJSONArray("items")
                }.getOrNull()
            }
        }
        val combined = JSONArray()
        for (job in jobs) {
            val arr = job.await() ?: continue
            for (i in 0 until arr.length()) combined.put(arr.get(i))
        }
        mapNguonCItems(combined, "NguonC")
    }

    suspend fun fetchAllAnimationCategories(): List<GenreItem> {
        val base = InitialData.NGUONC_GENRES.take(6).toMutableList() // Tất cả, Thuyết Minh, Lồng Tiếng, Vietsub, Trung Quốc, Nhật Bản
        val seenIds = base.map { it.id }.toHashSet()
        return try {
            val raw = HttpClientProvider.getText("$PHIMAPI_BASE/the-loai", timeoutMs = 4500L)
            val items = JSONObject(raw).optJSONObject("data")?.optJSONArray("items")
            if (items != null && items.length() > 0) {
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    val slug = o.optString("slug").trim()
                    if (name.isEmpty() || slug.isEmpty() || slug == "phim-18") continue
                    if (seenIds.add(name)) {
                        base.add(
                            GenreItem(
                                id = name,
                                label = name,
                                icon = "tag",
                                sourceSlug = "hoat-hinh:cat=$slug"
                            )
                        )
                    }
                }
                base
            } else {
                InitialData.NGUONC_GENRES
            }
        } catch (_: Exception) {
            InitialData.NGUONC_GENRES
        }
    }

    suspend fun fetchByGenreSpec(spec: String, genreLabel: String, page: Int = 1): List<DonghuaMovie> = coroutineScope {
        if (spec.startsWith("hoat-hinh:")) {
            val filterExpr = spec.removePrefix("hoat-hinh:")
            val parts = filterExpr.split("=", limit = 2)
            val fKey = parts.getOrNull(0).orEmpty()
            val fVal = parts.getOrNull(1).orEmpty()
            val qs = when (fKey) {
                "lang" -> "sort_lang=${URLEncoder.encode(fVal, "UTF-8")}"
                "country" -> "country=${URLEncoder.encode(fVal, "UTF-8")}"
                else -> "category=${URLEncoder.encode(fVal, "UTF-8")}"
            }
            val jobs = listOf(page, page + 1).map { p ->
                async {
                    runCatching {
                        val json = HttpClientProvider.getText("$PHIMAPI_BASE/v1/api/danh-sach/hoat-hinh?page=$p&limit=24&$qs", timeoutMs = 6500L)
                        JSONObject(json).optJSONObject("data")?.optJSONArray("items")
                    }.getOrNull()
                }
            }
            val combined = JSONArray()
            for (job in jobs) {
                val arr = job.await() ?: continue
                for (i in 0 until arr.length()) combined.put(arr.get(i))
            }
            mapPhimApiItems(combined, "NguonC", genreLabel)
        } else {
            val parts = if (spec.contains(":")) spec.split(":", limit = 2) else listOf("the-loai", spec)
            val kind = parts[0]
            val slug = parts[1]
            val jobs = listOf(page, page + 1, page + 2).map { p ->
                async {
                    runCatching {
                        val json = HttpClientProvider.getText("$NGUONC_BASE/api/films/$kind/$slug?page=$p", timeoutMs = 6500L)
                        JSONObject(json).optJSONArray("items")
                    }.getOrNull()
                }
            }
            val combined = JSONArray()
            for (job in jobs) {
                val arr = job.await() ?: continue
                for (i in 0 until arr.length()) combined.put(arr.get(i))
            }
            mapNguonCItems(combined, "NguonC")
        }
    }

    suspend fun search(keyword: String): List<DonghuaMovie> {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val json = HttpClientProvider.getText("$NGUONC_BASE/api/films/search?keyword=$encoded", timeoutMs = 6500L)
            val items = JSONObject(json).optJSONArray("items")
            mapNguonCItems(items, "NguonC")
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchPhimApiDetail(slug: String, fallbackTitle: String? = null): JSONObject? {
        val direct = try {
            val raw = HttpClientProvider.getText("$PHIMAPI_BASE/phim/$slug", timeoutMs = 4500L)
            val obj = JSONObject(raw)
            if (obj.optBoolean("status", false)) obj else null
        } catch (_: Exception) {
            null
        }
        if (direct != null) return direct
        if (fallbackTitle.isNullOrBlank()) return null

        // Slug mismatch fallback (e.g., dau-pha-thuong-khung -> dau-pha-thuong-khung-phan-5)
        return try {
            val kw = URLEncoder.encode(fallbackTitle.trim(), "UTF-8")
            val searchRaw = HttpClientProvider.getText(
                "$PHIMAPI_BASE/v1/api/tim-kiem?keyword=$kw&limit=6",
                timeoutMs = 4000L
            )
            val items = JSONObject(searchRaw).optJSONObject("data")?.optJSONArray("items") ?: return null
            val normTarget = VietnameseSearch.removeVietnameseTones(fallbackTitle)
            var matchedSlug: String? = null
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val type = it.optString("type")
                if (type != "hoathinh") continue
                val candName = VietnameseSearch.removeVietnameseTones(it.optString("name"))
                val candSlug = it.optString("slug")
                if (candSlug == slug || candName == normTarget) {
                    matchedSlug = candSlug
                    break
                }
                if (matchedSlug == null && candName.startsWith(normTarget) && !candSlug.contains("ova")) {
                    matchedSlug = candSlug
                }
            }
            if (matchedSlug.isNullOrBlank()) return null
            val detailRaw = HttpClientProvider.getText("$PHIMAPI_BASE/phim/$matchedSlug", timeoutMs = 4500L)
            val detailObj = JSONObject(detailRaw)
            if (detailObj.optBoolean("status", false)) detailObj else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchNguonCDetail(slug: String): JSONObject? {
        return try {
            val raw = HttpClientProvider.getText("$NGUONC_BASE/api/film/$slug", timeoutMs = 5500L)
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun parseNguonCDetail(
        nm: JSONObject
    ): Triple<List<ServerGroup>, String, Pair<String, Int>> {
        val desc = nm.optString("description")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.replace(Regex("<[^>]+>"), "") ?: ""
        val poster = nm.optString("poster_url_webp")
            .ifEmpty { nm.optString("poster_url") }
            .ifEmpty { nm.optString("thumb_url") }
        val rawEps = nm.optJSONArray("episodes")
        val groups = mutableListOf<ServerGroup>()
        var maxEps = 0
        if (rawEps != null) {
            for (i in 0 until rawEps.length()) {
                val srv = rawEps.optJSONObject(i) ?: continue
                val items = srv.optJSONArray("items") ?: continue
                if (items.length() == 0) continue
                maxEps = maxOf(maxEps, items.length())
                val eps = ArrayList<EpisodeItem>(items.length())
                for (j in 0 until items.length()) {
                    val ep = items.optJSONObject(j) ?: continue
                    val rawName = ep.optString("name")
                    val epName = if (rawName.startsWith("Tập")) rawName else "Tập $rawName"
                    eps.add(
                        EpisodeItem(
                            name = epName,
                            slug = ep.optString("slug"),
                            linkM3u8 = ep.optString("m3u8"),
                            linkEmbed = ep.optString("embed")
                        )
                    )
                }
                groups.add(
                    ServerGroup(
                        serverName = "NguonC • ${srv.optString("server_name").ifEmpty { "Server 1" }}",
                        episodes = eps
                    )
                )
            }
        }
        val totalEp = maxOf(parseFirstInt(nm.opt("total_episodes"), 0), maxEps)
        return Triple(groups, desc, poster to maxEps.coerceAtLeast(totalEp))
    }
}
