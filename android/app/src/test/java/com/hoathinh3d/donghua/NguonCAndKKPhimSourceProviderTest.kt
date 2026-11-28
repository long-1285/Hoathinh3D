package com.hoathinh3d.donghua

import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.source.KKPhimSourceProvider
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.data.source.NguonCSourceProvider
import com.hoathinh3d.donghua.data.source.VideoStream
import com.hoathinh3d.donghua.player.ExoPlayerStreamHelper
import com.hoathinh3d.donghua.player.HlsCacheProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NguonCAndKKPhimSourceProviderTest {

    @Test
    fun nguonC_parsesListDetailHtmlTaxonomies_andResolvesHeaders() {
        val listJson = """
            {
              "status": "success",
              "paginate": {"current_page": 1, "total_page": 717, "total_items": 7164, "items_per_page": 10},
              "items": [{
                "id": "nc1",
                "name": "Tiên Nghịch",
                "original_name": "Renegade Immortal",
                "slug": "tien-nghich",
                "year": "2023",
                "total_episodes": 200,
                "current_episode": "Tập 168",
                "quality": "4K",
                "language": "Vietsub + Thuyết Minh",
                "poster_url_webp": "https://cdn.nguonc.com/tien-nghich.webp",
                "thumb_url_webp": "https://cdn.nguonc.com/tien-nghich-thumb.webp",
                "category": {
                  "2": {"group": {"name": "Thể loại"}, "list": [{"name": "Hoạt hình"}, {"name": "Tiên hiệp"}]}
                }
              }]
            }
        """.trimIndent()
        val paged = NguonCSourceProvider.parseMoviesListJson(listJson)
        assertEquals(1, paged.items.size)
        assertEquals(717, paged.totalPages)
        val item = paged.items[0]
        assertEquals("tien-nghich", item.slug)
        assertEquals(168, item.latestEpisode)
        assertEquals(200, item.totalEpisodes)
        assertTrue(item.audioModes.contains("Thuyết Minh"))

        val detailJson = """
            {
              "status": "success",
              "movie": {
                "id": "nc1",
                "name": "Tiên Nghịch",
                "original_name": "Renegade Immortal",
                "slug": "tien-nghich",
                "description": "<p>Vương Lâm nghịch thiên tu tiên.</p>",
                "current_episode": "Tập 168",
                "total_episodes": 200,
                "quality": "4K",
                "poster_url_webp": "https://cdn.nguonc.com/tien-nghich.webp",
                "category": {
                  "2": {"group": {"name": "Thể loại"}, "list": [{"name": "Tiên Hiệp"}]},
                  "3": {"group": {"name": "Năm"}, "list": [{"name": "2023"}]},
                  "4": {"group": {"name": "Quốc gia"}, "list": [{"name": "Trung Quốc"}]}
                },
                "episodes": [
                  {
                    "server_name": "Vietsub #1",
                    "items": [
                      {"name": "1", "slug": "tap-1", "embed": "https://embed1.streamc.xyz/embed.php?hash=abc"}
                    ]
                  },
                  {
                    "server_name": "Thuyết Minh #1",
                    "items": [
                      {"name": "1", "slug": "tap-1", "m3u8": "https://cdn.nguonc.com/tm/1/index.m3u8", "embed": ""}
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val detail = NguonCSourceProvider.parseMovieDetailJson(detailJson)
        assertNotNull(detail)
        assertEquals(2023, detail!!.year)
        assertEquals(listOf("Trung Quốc"), detail.countries)
        assertEquals("Vương Lâm nghịch thiên tu tiên.", detail.description)
        assertEquals(2, detail.servers.size)
        // Thuyết Minh direct m3u8 prioritized first
        assertEquals("Thuyết Minh #1", detail.servers[0].serverName)
        assertTrue(detail.servers[0].episodes[0].isDirectStream)
        assertEquals("Vietsub #1", detail.servers[1].serverName)
        assertFalse(detail.servers[1].episodes[0].isDirectStream)

        val mapped = detail.toMovieDetailResult(
            sourceBaseUrl = NguonCSourceProvider.BASE_URL,
            sourceLabel = NguonCSourceProvider.SOURCE_NAME,
            wpServerType = NguonCSourceProvider.SERVER_TYPE_NGUONC
        )
        assertTrue(MovieRepository.isDirectStreamServer(mapped.serverGroups[0]))
        assertTrue(MovieRepository.isDirectStreamServer(mapped.serverGroups[1]))
        assertEquals(NguonCSourceProvider.SERVER_TYPE_NGUONC, mapped.serverGroups[1].episodes[0].wpServerType)

        val htmlNav = """<nav><a href="/the-loai/co-trang">Cổ Trang</a><a href="/quoc-gia/trung-quoc">Trung Quốc</a></nav>"""
        assertEquals("co-trang", NguonCSourceProvider.parseTaxonomiesFromHtml(htmlNav, "/the-loai/")[0].slug)
        assertEquals("trung-quoc", NguonCSourceProvider.parseTaxonomiesFromHtml(htmlNav, "/quoc-gia/")[0].slug)
    }

    @Test
    fun kkPhim_parsesV1ListDetailPlayerQuery_andExoHeaders() {
        val listJson = """
            {
              "status": true,
              "data": {
                "APP_DOMAIN_CDN_IMAGE": "https://phimimg.com",
                "params": {"pagination": {"currentPage": 1, "totalPages": 93, "totalItems": 464}},
                "items": [{
                  "_id": "kk1",
                  "name": "Thành Dã Tiêu Hà",
                  "origin_name": "To Be Winner",
                  "slug": "thanh-da-tieu-ha",
                  "poster_url": "uploads/movies/20260805/thanh-da-tieu-ha-poster.webp",
                  "thumb_url": "uploads/movies/20260805/thanh-da-tieu-ha-thumb.webp",
                  "episode_current": "Tập 12",
                  "episode_total": "24",
                  "quality": "FHD",
                  "lang": "Thuyết Minh",
                  "year": 2026,
                  "category": [{"name": "Hoạt Hình"}]
                }]
              }
            }
        """.trimIndent()
        val paged = KKPhimSourceProvider.parseMoviesListJson(listJson)
        assertEquals(1, paged.items.size)
        assertEquals(93, paged.totalPages)
        val item = paged.items[0]
        assertEquals("https://phimimg.com/uploads/movies/20260805/thanh-da-tieu-ha-poster.webp", item.posterUrl)
        assertTrue(item.audioModes.contains("Thuyết Minh"))

        val detailJson = """
            {
              "status": true,
              "movie": {
                "_id": "kk1",
                "name": "Tiên Nghịch",
                "origin_name": "Renegade Immortal",
                "slug": "tien-nghich",
                "content": "<p>Phim <b>KKPhim</b>.</p>",
                "status": "ongoing",
                "year": 2023,
                "quality": "FHD",
                "episode_current": "Tập 168",
                "episode_total": "200",
                "poster_url": "https://phimimg.com/p.webp",
                "category": [{"name": "Tiên Hiệp"}],
                "country": [{"name": "Trung Quốc"}]
              },
              "episodes": [
                {
                  "server_name": "#Hà Nội (Vietsub)",
                  "server_data": [{"name": "1", "slug": "tap-1", "link_m3u8": "https://s4.phim1280.tv/vs/index.m3u8", "link_embed": ""}]
                },
                {
                  "server_name": "#Hà Nội (Thuyết Minh)",
                  "server_data": [{"name": "1", "slug": "tap-1", "link_m3u8": "https://s5.phim1280.tv/tm/index.m3u8", "link_embed": ""}]
                }
              ]
            }
        """.trimIndent()
        val detail = KKPhimSourceProvider.parseMovieDetailJson(detailJson)
        assertNotNull(detail)
        assertEquals("Phim KKPhim.", detail!!.description)
        assertEquals(2, detail.servers.size)
        assertEquals("Hà Nội (Thuyết Minh)", detail.servers[0].serverName)
        assertEquals("Hà Nội (Vietsub)", detail.servers[1].serverName)

        val directFromPlayer = KKPhimSourceProvider.extractDirectUrlFromPlayerQuery(
            "https://player.phimapi.com/player/?url=https%3A%2F%2Fs4.phim1280.tv%2Fvs%2Findex.m3u8"
        )
        assertEquals("https://s4.phim1280.tv/vs/index.m3u8", directFromPlayer)

        val headers = KKPhimSourceProvider.buildStreamHeaders(
            streamUrl = directFromPlayer!!,
            refererUrl = "https://kkphim.com/phim/tien-nghich"
        )
        HlsCacheProvider.registerStreamHeaders(directFromPlayer, headers)
        val resolved = HlsCacheProvider.resolveHeadersForUrl("https://s4.phim1280.tv/vs/seg0.ts")
        assertEquals("https://kkphim.com", resolved["Origin"])
        assertNotNull(
            ExoPlayerStreamHelper.createHttpDataSourceFactory(
                VideoStream(streamUrl = directFromPlayer, headers = headers)
            )
        )

        assertEquals("ti%c3%aan%20ngh%e1%bb%8bch", NguonCSourceProvider.encodeUrlParam("tiên nghịch").lowercase())
        assertEquals("ti%c3%aan%20ngh%e1%bb%8bch", KKPhimSourceProvider.encodeUrlParam("tiên nghịch").lowercase())
        assertTrue(InitialData.DEFAULT_BUILT_IN_SOURCES.any { it.id == "NguonC" && it.url == "https://phim.nguonc.com" })
        assertTrue(InitialData.DEFAULT_BUILT_IN_SOURCES.any { it.id == "KKPhim" && it.url == "https://kkphim.com" })
    }
}
