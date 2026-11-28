package com.hoathinh3d.donghua

import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.data.source.Phim500SourceProvider
import com.hoathinh3d.donghua.data.source.VideoStream
import com.hoathinh3d.donghua.player.ExoPlayerStreamHelper
import com.hoathinh3d.donghua.player.HlsCacheProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phim500SourceProviderTest {

    @Test
    fun decodeNextFlightPayload_and_parseMoviesFromPageHtml_extractsSearchAndCards() {
        val searchRsc = """
            <html><body>
            <script>self.__next_f.push([1,"1a:[\"$\",\"${'$'}L23\",null,{\"movies\":{\"status\":true,\"result\":[{\"id\":67130,\"name\":\"Tiên Nghịch\",\"origin_name\":\"Renegade Immortal\",\"slug\":\"tien-nghich\",\"thumbnail\":\"https://cdn.500phim.net/img?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Foriginal%2Fa.jpg&output=webp\",\"poster\":\"https://cdn.500phim.net/img?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Foriginal%2Fb.jpg&output=webp\",\"quality\":\"4K\",\"language\":\"Vietsub + Thuyết Minh\",\"publish_year\":2023,\"episode_current\":\"Tập 120\",\"episode_total\":\"168\",\"imdb_rating\":\"9.2\",\"view_total\":95000,\"status\":\"ongoing\",\"categories\":[{\"id\":1,\"name\":\"Hoạt Hình\",\"slug\":\"hoat-hinh\"},{\"id\":2,\"name\":\"Tiên Hiệp\",\"slug\":\"tien-hiep\"}]}],\"meta\":{\"page\":1,\"total\":60,\"totalPages\":2}}}]"])</script>
            </body></html>
        """.trimIndent()

        val paged = Phim500SourceProvider.parseMoviesFromPageHtml(searchRsc, requestedPage = 1)
        assertEquals(1, paged.items.size)
        assertEquals(2, paged.totalPages)
        val item = paged.items.first()
        assertEquals("67130", item.id)
        assertEquals("tien-nghich", item.slug)
        assertEquals("Tiên Nghịch", item.title)
        assertEquals(120, item.latestEpisode)
        assertEquals(168, item.totalEpisodes)
        assertEquals(2023, item.year)
        assertEquals("4K", item.quality)
        assertTrue(item.audioModes.contains("Thuyết Minh"))
        assertEquals(95000L, item.viewCount)

        val donghua = item.toDonghuaMovie(Phim500SourceProvider.BASE_URL, Phim500SourceProvider.SOURCE_NAME)
        assertEquals("Phim500", donghua.sources.first())
        assertEquals("2023 • 500Phim", donghua.studio)
        assertEquals("Đang chiếu", donghua.statusLabel)
        assertEquals(
            "https://cdn.500phim.net/img?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Foriginal%2Fb.jpg&output=webp",
            MovieRepository.getOptimizedPosterUrl(donghua.poster)
        )
    }

    @Test
    fun parseMovieDetailFromHtml_separatesCurrentPartAndGroupsServers() {
        val detailHtml = """
            <html><body>
            <script>self.__next_f.push([1,"1b:[\"$\",\"${'$'}L2c\",null,{\"movie\":{\"id\":67094,\"name\":\"Phàm Nhân Tu Tiên\",\"origin_name\":\"A Record of a Mortal's Journey to Immortality\",\"slug\":\"pham-nhan-tu-tien\",\"description\":\"<p>Hàn Lập <b>tu tiên</b>.</p>\",\"poster\":\"https://cdn.500phim.net/img?url=p1.jpg&output=webp\",\"thumbnail\":\"https://cdn.500phim.net/img?url=t1.jpg&output=webp\",\"quality\":\"FHD\",\"publish_year\":2020,\"episode_current\":\"Tập 202\",\"episode_total\":\"228\",\"status\":\"completed\"}}]\n"])</script>
            <script>self.__next_f.push([1,"1c:{\"parts\":[{\"id\":68173,\"name\":\"Phàm Nhân Tu Tiên (Phần 2)\",\"slug\":\"pham-nhan-tu-tien-phan-2\",\"episodes\":[{\"id\":999,\"name\":\"1\",\"slug\":\"tap-1\",\"server\":\"500 (Vietsub)\"}]}],\"movie\":{\"id\":67094,\"name\":\"Phàm Nhân Tu Tiên\",\"slug\":\"pham-nhan-tu-tien\",\"categories\":[{\"name\":\"Hoạt Hình\"}],\"regions\":[{\"id\":24,\"name\":\"Trung Quốc\",\"slug\":\"trung-quoc\"}],\"episodes\":[{\"id\":1064552,\"name\":\"1\",\"server\":\"c500 (Vietsub)\",\"slug\":\"tap-1\",\"type\":\"embed\"},{\"id\":1079927,\"name\":\"1\",\"server\":\"500 (Vietsub)\",\"slug\":\"tap-1\",\"type\":\"m3u8\"},{\"id\":1080001,\"name\":\"1\",\"server\":\"500 (Thuyết Minh)\",\"slug\":\"tap-1\",\"type\":\"m3u8\"}]}}"])</script>
            </body></html>
        """.trimIndent()

        val detail = Phim500SourceProvider.parseMovieDetailFromHtml(detailHtml, "pham-nhan-tu-tien")
        assertNotNull(detail)
        assertEquals("Hàn Lập tu tiên.", detail!!.description)
        assertEquals(2020, detail.year)
        assertEquals(listOf("Hoạt Hình"), detail.genres)
        assertEquals(listOf("Trung Quốc"), detail.countries)
        assertEquals(3, detail.servers.size)
        // Direct m3u8 + Thuyết Minh must be sorted first
        assertEquals("500 (Thuyết Minh)", detail.servers[0].serverName)
        assertEquals("500 (Vietsub)", detail.servers[1].serverName)
        assertEquals("c500 (Vietsub)", detail.servers[2].serverName)
        assertEquals(
            "https://www.500phim.net/xem-phim/pham-nhan-tu-tien.1080001",
            detail.servers[0].episodes[0].playUrl
        )
        assertFalse(detail.servers[0].episodes[0].isDirectStream)

        val mapped = detail.toMovieDetailResult(
            sourceBaseUrl = Phim500SourceProvider.BASE_URL,
            sourceLabel = Phim500SourceProvider.SOURCE_NAME,
            wpServerType = Phim500SourceProvider.SERVER_TYPE_500PHIM
        )
        assertEquals(3, mapped.serverGroups.size)
        assertTrue(MovieRepository.isDirectStreamServer(mapped.serverGroups[0]))
        assertEquals(Phim500SourceProvider.SERVER_TYPE_500PHIM, mapped.serverGroups[0].episodes[0].wpServerType)
    }

    @Test
    fun extractWatchEpisodeFromHtml_handlesDirectM3u8AndEmbedAndExoHeaders() {
        val directWatch = """
            <html><body>
            <script>self.__next_f.push([1,"1d:{\"initialEpisode\":{\"id\":1079927,\"name\":\"1\",\"server\":\"500 (Vietsub)\",\"slug\":\"tap-1\",\"type\":\"m3u8\",\"link\":\"https://s3.phim1280.tv/20240715/6VEHSDxf/index.m3u8\",\"movieId\":67094,\"subtitles\":[{\"url\":\"https://cdn.500phim.net/sub/vi.vtt\"}]}}"])</script>
            </body></html>
        """.trimIndent()
        val extDirect = Phim500SourceProvider.extractWatchEpisodeFromHtml(directWatch)
        assertNotNull(extDirect)
        assertTrue(extDirect!!.isDirectStream)
        assertEquals("https://s3.phim1280.tv/20240715/6VEHSDxf/index.m3u8", extDirect.link)
        assertEquals("https://cdn.500phim.net/sub/vi.vtt", extDirect.subtitleUrl)

        val embedWatch = """
            <html><body>
            <script>self.__next_f.push([1,"1e:{\"initialEpisode\":{\"id\":1064552,\"name\":\"1\",\"server\":\"c500 (Vietsub)\",\"slug\":\"tap-1\",\"type\":\"embed\",\"link\":\"https://embed14.streamc.xyz/embed.php?hash=abc\",\"movieId\":67094,\"subtitles\":[]}}"])</script>
            </body></html>
        """.trimIndent()
        val extEmbed = Phim500SourceProvider.extractWatchEpisodeFromHtml(embedWatch)
        assertNotNull(extEmbed)
        assertFalse(extEmbed!!.isDirectStream)
        assertEquals("https://embed14.streamc.xyz/embed.php?hash=abc", extEmbed.link)

        val headers = Phim500SourceProvider.buildStreamHeaders(
            streamUrl = extDirect.link,
            refererUrl = "https://www.500phim.net/xem-phim/pham-nhan-tu-tien.1079927"
        )
        HlsCacheProvider.registerStreamHeaders(extDirect.link, headers)
        val resolved = HlsCacheProvider.resolveHeadersForUrl("https://s3.phim1280.tv/20240715/6VEHSDxf/seg0.ts")
        assertEquals("https://www.500phim.net", resolved["Origin"])
        assertNotNull(
            ExoPlayerStreamHelper.createHttpDataSourceFactory(
                VideoStream(streamUrl = extDirect.link, headers = headers)
            )
        )
    }

    @Test
    fun taxonomyJson_urlEncoding_andBuiltInSourceRegistered() {
        val taxJson = """
            {"data":[{"id":31,"name":"Cổ Trang","slug":"co-trang","movie_count":1934},{"id":99,"name":"Phim 18+","slug":"phim-18","movie_count":10}]}
        """.trimIndent()
        val list = Phim500SourceProvider.parseTaxonomyJson(taxJson)
        assertEquals(1, list.size)
        assertEquals("co-trang", list[0].slug)
        assertEquals("ti%c3%aan%20ngh%e1%bb%8bch", Phim500SourceProvider.encodeUrlParam("tiên nghịch").lowercase())
        assertTrue(InitialData.DEFAULT_BUILT_IN_SOURCES.any { it.id == "Phim500" && it.url == "https://www.500phim.net" })
    }
}
