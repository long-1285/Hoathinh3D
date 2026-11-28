package com.hoathinh3d.donghua

import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.source.Episode
import com.hoathinh3d.donghua.data.source.MovieDetail
import com.hoathinh3d.donghua.data.source.MovieItem
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.data.source.ServerData
import com.hoathinh3d.donghua.data.source.VideoStream
import com.hoathinh3d.donghua.data.source.VsmovSourceProvider
import com.hoathinh3d.donghua.player.ExoPlayerStreamHelper
import com.hoathinh3d.donghua.player.HeadlessWebViewSniffer
import com.hoathinh3d.donghua.player.HlsCacheProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmovSourceProviderTest {

    @Test
    fun parseMoviesListJson_extractsItemsPaginationAndConvertsToDonghuaMovie() {
        val sampleJson = """
            {
              "status": true,
              "items": [
                {
                  "_id": 54207,
                  "name": "Hoàng Hậu Tái Hôn",
                  "origin_name": "The Remarried Empress",
                  "slug": "hoang-hau-tai-hon",
                  "poster_url": "https://vsmov.com/storage/images/poster.jpg",
                  "thumb_url": "/storage/images/thumb.jpg",
                  "year": 2026,
                  "episode_current": "Hoàn Tất (10/10)",
                  "episode_total": "10",
                  "quality": "HD",
                  "lang": "Vietsub",
                  "view": 784,
                  "tmdb": { "vote_average": "8.7" },
                  "category": [{ "name": "Chính Kịch" }, { "name": "Viễn Tưởng" }]
                }
              ],
              "pagination": { "totalItems": 21746, "totalItemsPerPage": 24, "currentPage": 1, "totalPages": 907 }
            }
        """.trimIndent()

        val paged = VsmovSourceProvider.parseMoviesListJson(sampleJson)
        assertEquals(1, paged.items.size)
        assertEquals(1, paged.currentPage)
        assertEquals(907, paged.totalPages)

        val item = paged.items.first()
        assertEquals("54207", item.id)
        assertEquals("hoang-hau-tai-hon", item.slug)
        assertEquals(10, item.latestEpisode)
        assertEquals(10, item.totalEpisodes)
        assertEquals(2026, item.year)
        assertEquals("https://vsmov.com/storage/images/thumb.jpg", item.thumbUrl)
        assertEquals(8.7, item.rating, 0.01)

        val donghua = item.toDonghuaMovie(VsmovSourceProvider.VSMOV_BASE, VsmovSourceProvider.SOURCE_NAME)
        assertEquals("hoang-hau-tai-hon", donghua.id)
        assertEquals("VSMov", donghua.sources.first())
        assertEquals("2026 • VSMov", donghua.studio)
        assertEquals("Hoàn thành", donghua.statusLabel)
    }

    @Test
    fun parseMovieDetailJson_normalizesServersAndMapsVisibleHlsEpisodes() {
        val sampleDetail = """
            {
              "status": true,
              "movie": {
                "_id": 54207,
                "name": "Hoàng Hậu Tái Hôn",
                "origin_name": "The Remarried Empress",
                "slug": "hoang-hau-tai-hon",
                "content": "<p>Nội dung <b>phim</b> mới.</p>",
                "status": "completed",
                "poster_url": "https://vsmov.com/storage/images/poster.jpg",
                "thumb_url": "https://vsmov.com/storage/images/thumb.jpg",
                "episode_current": "Hoàn Tất (10/10)",
                "episode_total": "10",
                "quality": "FHD",
                "year": 2026,
                "category": [{ "id": 15, "name": "Chính Kịch" }],
                "country": [{ "id": 10, "name": "Hàn Quốc" }]
              },
              "episodes": [
                {
                  "server_name": "Vietsub\r\n                        #1",
                  "server_data": [
                    { "name": "1", "slug": "tap-1", "link_embed": "https://v1.streamvsmov.com/video/uuid-1" },
                    { "name": "2", "slug": "tap-2", "link_embed": "https://v8.streamvsmov.com/video/uuid-2" }
                  ]
                },
                {
                  "server_name": "Thuyết Minh #1",
                  "server_data": [
                    { "name": "1", "slug": "tap-1", "link_m3u8": "https://cdn.example/tm/1.m3u8" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val detail = VsmovSourceProvider.parseMovieDetailJson(sampleDetail)
        assertNotNull(detail)
        assertEquals("Nội dung phim mới.", detail!!.description)
        assertEquals(2, detail.servers.size)
        assertEquals("Vietsub #1", detail.servers[0].serverName)
        assertEquals("Tập 1", detail.servers[0].episodes[0].name)

        val mapped = detail.toMovieDetailResult(
            sourceBaseUrl = VsmovSourceProvider.VSMOV_BASE,
            sourceLabel = VsmovSourceProvider.SOURCE_NAME,
            wpServerType = VsmovSourceProvider.SERVER_TYPE_VSMOV
        )
        assertEquals(2, mapped.serverGroups.size)
        assertTrue(MovieRepository.isDirectStreamServer(mapped.serverGroups[0]))
        assertTrue(MovieRepository.isDirectStreamServer(mapped.serverGroups[1]))
        assertEquals("vsmov", mapped.serverGroups[0].episodes[0].wpServerType)
        assertEquals("https://cdn.example/tm/1.m3u8", mapped.serverGroups[1].episodes[0].linkM3u8)
    }

    @Test
    fun extractStreamFromEmbedHtml_extractsSignedAndUnsignedUrlsAndSubtitles() {
        val signedEmbed = """
            <script>
              const playerOptions = {
                subtitles: [{ file: "https://cdn.streamvsmov.com/sub/vi.vtt", label: "Tiếng Việt" }],
                thumb: "https://cdn.streamvsmov.com/thumb/sprite.vtt",
                enableSignedUrl: true,
                signedMasterUrl: "https://v1.streamvsmov.com/stream/abc-123/master.m3u8?expires=1795842658&signature=deadbeef"
              };
            </script>
        """.trimIndent()

        val vs = VsmovSourceProvider.extractStreamFromEmbedHtml(
            signedEmbed,
            "https://v1.streamvsmov.com/video/abc-123"
        )
        assertNotNull(vs)
        assertEquals(
            "https://v1.streamvsmov.com/stream/abc-123/master.m3u8?expires=1795842658&signature=deadbeef",
            vs!!.streamUrl
        )
        assertEquals("https://v1.streamvsmov.com/", vs.headers["Referer"])
        assertEquals("https://v1.streamvsmov.com", vs.headers["Origin"])
        assertEquals("https://cdn.streamvsmov.com/sub/vi.vtt", vs.subtitleUrl)
        assertEquals("https://cdn.streamvsmov.com/thumb/sprite.vtt", vs.storyboardUrl)

        val unsignedEmbed = """
            <script>
              const playerOptions = { enableSignedUrl: false, signedMasterUrl: "" };
              const baseUrl = "https://v8.streamvsmov.com";
              const videoHash = "hash-999";
            </script>
        """.trimIndent()
        val vs2 = VsmovSourceProvider.extractStreamFromEmbedHtml(
            unsignedEmbed,
            "https://v8.streamvsmov.com/video/hash-999"
        )
        assertEquals("https://v8.streamvsmov.com/stream/hash-999/master.m3u8", vs2?.streamUrl)
    }

    @Test
    fun taxonomySearchEncoding_headerRoutingAndBuiltInSource_workCleanly() {
        val taxJson = """
            {"status":"success","data":{"items":[{"_id":1,"name":"Tiên Hiệp","slug":"tien-hiep"},{"_id":2,"name":"18+","slug":"phim-18"}]}}
        """.trimIndent()
        val taxes = VsmovSourceProvider.parseTaxonomyItemsJson(taxJson)
        assertEquals(1, taxes.size)
        assertEquals("tien-hiep", taxes[0].slug)

        assertEquals("ti%c3%aan%20ngh%e1%bb%8bch", VsmovSourceProvider.encodeUrlParam("tiên nghịch").lowercase())

        val headers = HeadlessWebViewSniffer.buildStreamHeaders(
            targetUrl = "https://p25.streamvsmov.com/file/seg_1.png",
            refererUrl = "https://v1.streamvsmov.com/video/123"
        )
        HlsCacheProvider.registerStreamHeaders("https://v1.streamvsmov.com/stream/123/master.m3u8", headers)
        val cdnHeaders = HlsCacheProvider.resolveHeadersForUrl("https://p25.streamvsmov.com/file/seg_1.png")
        assertEquals("https://v1.streamvsmov.com/video/123", cdnHeaders["Referer"])
        assertEquals("https://v1.streamvsmov.com", cdnHeaders["Origin"])

        val vs = VideoStream(
            streamUrl = "https://v1.streamvsmov.com/stream/123/master.m3u8",
            headers = mapOf("Referer" to "https://v1.streamvsmov.com/", "User-Agent" to "CustomUA/1.0")
        )
        assertNotNull(ExoPlayerStreamHelper.createHttpDataSourceFactory(vs))
        assertTrue(InitialData.DEFAULT_BUILT_IN_SOURCES.any { it.id == "VSMov" && it.url == "https://vsmov.com" })
    }
}
