package com.hoathinh3d.donghua.data.source

import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.model.MovieDetailResult
import com.hoathinh3d.donghua.data.model.ServerGroup
import com.hoathinh3d.donghua.data.model.WpSourceRef
import java.io.Serializable
import java.util.Locale

/**
 * Chuẩn hóa giao diện đa nguồn (Multi-Source Architecture) để dễ dàng cắm thêm nhiều website
 * vào HoatHinh3D thông qua một hợp đồng dữ liệu thống nhất.
 */
data class MovieItem(
    val id: String,
    val title: String,
    val originTitle: String,
    val posterUrl: String,
    val thumbUrl: String,
    val latestEpisode: Int,
    val quality: String,
    val year: Int,
    val sourceId: String,
    val slug: String = id,
    val rating: Double = 9.5,
    val audioModes: List<String> = listOf("Vietsub"),
    val genres: List<String> = emptyList(),
    val totalEpisodes: Int = 0,
    val viewCount: Long = 0L,
    val statusLabel: String? = null
) : Serializable {

    fun toDonghuaMovie(
        baseUrl: String,
        sourceDisplayName: String = sourceId,
        indexHint: Int = 0
    ): DonghuaMovie {
        val safePoster = posterUrl.ifBlank { thumbUrl }
        val safeThumb = thumbUrl.ifBlank { safePoster }
        val viewsNum = if (viewCount > 0L) {
            (viewCount / 1_000.0).coerceIn(1.0, 99.9)
        } else {
            8.4 + ((indexHint * 0.7) % 6.5)
        }
        val viewsStr = when {
            viewCount >= 1_000_000L -> String.format(Locale.US, "%.1fM", viewCount / 1_000_000.0)
            viewCount >= 1_000L -> String.format(Locale.US, "%.1fK", viewCount / 1_000.0)
            viewCount > 0L -> viewCount.toString()
            else -> String.format(Locale.US, "%.1fK", viewsNum)
        }
        val studioParts = buildList {
            if (year > 1900) add(year.toString())
            add(sourceDisplayName)
        }
        val epLatest = latestEpisode.coerceAtLeast(1)
        val epTotal = totalEpisodes.coerceAtLeast(epLatest)
        val detailUrl = if (slug.isNotBlank()) "${baseUrl.trimEnd('/')}/phim/$slug" else baseUrl
        return DonghuaMovie(
            id = slug.ifBlank { id },
            slug = slug.ifBlank { id },
            title = title,
            originalTitle = originTitle.ifBlank { title },
            wpSources = listOf(
                WpSourceRef(
                    baseUrl = baseUrl.trimEnd('/'),
                    postId = 0,
                    url = detailUrl,
                    sourceName = sourceId
                )
            ),
            statusLabel = statusLabel,
            studio = studioParts.joinToString(" • "),
            latestEp = epLatest,
            totalEp = epTotal,
            rating = rating.coerceIn(7.0, 10.0),
            views = viewsStr,
            viewsNum = viewsNum,
            quality = quality.ifBlank { "HD" },
            audioModes = audioModes.ifEmpty { listOf("Vietsub") },
            sources = listOf(sourceId),
            genres = genres.ifEmpty { listOf("Phim Mới") },
            scheduleDay = InitialData.SCHEDULE_DAYS[indexHint % InitialData.SCHEDULE_DAYS.size],
            scheduleTime = "${9 + (indexHint % 3)}:00",
            poster = safePoster,
            thumb = safeThumb,
            streamIndex = indexHint % 4
        )
    }
}

data class Episode(
    val name: String,
    val slug: String,
    val playUrl: String,
    val isDirectStream: Boolean = false
) : Serializable

data class ServerData(
    val serverName: String,
    val episodes: List<Episode>
) : Serializable

data class MovieDetail(
    val id: String,
    val title: String,
    val originTitle: String,
    val description: String,
    val status: String,
    val genres: List<String>,
    val countries: List<String>,
    val year: Int,
    val posterUrl: String,
    val thumbUrl: String,
    val latestEpisode: Int,
    val totalEpisodes: Int,
    val quality: String,
    val servers: List<ServerData>
) : Serializable {

    fun toMovieDetailResult(
        sourceBaseUrl: String,
        sourceLabel: String,
        wpServerType: String
    ): MovieDetailResult {
        val cleanBase = sourceBaseUrl.trimEnd('/')
        val mappedGroups = servers.mapNotNull { srv ->
            if (srv.episodes.isEmpty()) return@mapNotNull null
            val eps = srv.episodes.map { ep ->
                val isDirect = ep.isDirectStream ||
                    ep.playUrl.contains(".m3u8", ignoreCase = true) ||
                    ep.playUrl.contains(".mp4", ignoreCase = true)
                EpisodeItem(
                    name = ep.name,
                    slug = ep.slug,
                    linkM3u8 = if (isDirect) ep.playUrl else "",
                    linkEmbed = if (isDirect) "" else ep.playUrl,
                    wpBaseUrl = cleanBase,
                    wpServerType = if (isDirect) null else wpServerType,
                    wpServerSv = "hls",
                    wpPageUrl = ep.playUrl
                )
            }
            ServerGroup(
                serverName = "$sourceLabel • ${srv.serverName}",
                episodes = eps
            )
        }
        return MovieDetailResult(
            serverGroups = mappedGroups,
            description = description.ifBlank { null },
            poster = posterUrl.ifBlank { thumbUrl }.ifBlank { null },
            totalEp = totalEpisodes.takeIf { it > 0 },
            latestEp = latestEpisode.takeIf { it > 0 }
        )
    }
}

data class VideoStream(
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitleUrl: String? = null,
    val storyboardUrl: String? = null,
    val quality: String? = null
) : Serializable

data class MovieFilter(
    val categorySlug: String? = null,
    val countrySlug: String? = null,
    val year: Int? = null,
    val sortField: String? = null,
    val typeSlug: String? = null,
    val page: Int = 1
) : Serializable

data class TaxonomyOption(
    val name: String,
    val slug: String
) : Serializable

data class ProviderHomeFeed(
    val spotlight: List<MovieItem> = emptyList(),
    val latestUpdated: List<MovieItem> = emptyList(),
    val series: List<MovieItem> = emptyList(),
    val singleMovies: List<MovieItem> = emptyList(),
    val animations: List<MovieItem> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1
) : Serializable

interface MovieSourceProvider {
    val sourceId: String
    val sourceName: String
    val baseUrl: String

    suspend fun getHome(page: Int = 1): ProviderHomeFeed
    suspend fun getByCategory(categorySlug: String, page: Int = 1): List<MovieItem>
    suspend fun search(keyword: String, limit: Int = 24): List<MovieItem>
    suspend fun filter(filter: MovieFilter): List<MovieItem>
    suspend fun getGenres(): List<TaxonomyOption>
    suspend fun getCountries(): List<TaxonomyOption>
    suspend fun getDetail(slugOrId: String): MovieDetail?
    suspend fun resolveVideoStream(episode: Episode): VideoStream?
}
