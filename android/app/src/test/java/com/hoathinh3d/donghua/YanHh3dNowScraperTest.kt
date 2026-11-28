package com.hoathinh3d.donghua

import com.hoathinh3d.donghua.data.source.YanHh3dNowScraper
import com.hoathinh3d.donghua.player.HlsCacheProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class YanHh3dNowScraperTest {

    @Test
    fun parseMoviesListHtml_extractsCardsAndPagination() {
        val html = """
            <div class="flw-item">
                <div class="film-poster">
                    <div class="tick tick-rate">168/180 [4K]</div>
                    <div class="tick ltr"><div class="tick-item tick-dub">4K TM-VS</div><h4>Tiên Nghịch</h4></div>
                    <img data-src="https://yanhh3d.now/storage/movies/tien-nghich.png" alt="Tiên Nghịch" />
                    <a href="https://yanhh3d.now/tien-nghich" class="film-poster-ahref" title="Tiên Nghịch"></a>
                </div>
            </div>
            <ul class="pagination"><li><a href="https://yanhh3d.now/moi-cap-nhat?page=20">20</a></li></ul>
        """.trimIndent()
        val page = YanHh3dNowScraper.parseMoviesListHtml(html)
        assertEquals(1, page.movies.size)
        assertEquals("tien-nghich", page.movies[0].slug)
        assertEquals(168, page.movies[0].latestEp)
        assertEquals(180, page.movies[0].totalEp)
        assertTrue(page.movies[0].audioModes.contains("Thuyết Minh"))
        assertEquals(20, page.totalPages)
    }

    @Test
    fun parseWatchPageEpisodesAndButtons_extractsBothAudioTabsAndServers() {
        val watchHtml = """
            <div id="list_sv">
                <a class="btn btn3dsv" id="sv_LINK1" name="LINK1" data-src="https://scontent.streamrpt1.site/o2/v/test1.m3u8">1080</a>
                <a class="btn btn3dsv" id="sv_LINK5" name="LINK5" data-src="https://scontent.streamrpt1.site/o2/v/test5.m3u8">4K</a>
                <a class="btn btn3dsv" id="sv_LINK2" name="LINK2" data-src="https://scontent.streamrpt1.site/play-fb-v8/play/123">HD</a>
            </div>
            <div id="detail-ss-list">
                <ul class="nav nav-tabs pre-tabs sever-ep">
                    <li class="nav-item"><a href="#top-comment" class="nav-link">Thuyết Minh</a></li>
                    <li class="nav-item"><a href="#new-comment" class="nav-link">Vietsub</a></li>
                </ul>
                <div id="comment-widget">
                    <div id="top-comment" class="tab-pane active">
                        <a class="ssl-item ep-item" href="https://yanhh3d.now/tien-nghich/tap-168" title="168">168</a>
                        <a class="ssl-item ep-item" href="https://yanhh3d.now/tien-nghich/tap-44" title="44">44</a>
                    </div>
                    <div id="new-comment" class="tab-pane">
                        <a class="ssl-item ep-item" href="https://yanhh3d.now/sever2/tien-nghich/tap-2" title="2">2</a>
                        <a class="ssl-item ep-item" href="https://yanhh3d.now/sever2/tien-nghich/tap-1" title="1">1</a>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val btns = YanHh3dNowScraper.parseWatchButtons(watchHtml)
        assertEquals(3, btns.size)
        assertEquals("4K", btns[1].label)

        val tabs = YanHh3dNowScraper.parseWatchPageEpisodes(watchHtml)
        assertEquals(2, tabs.size)
        assertEquals("Thuyết Minh", tabs[0].audioLabel)
        assertEquals("tap-44", tabs[0].episodes[0].slug)
        assertEquals("tap-168", tabs[0].episodes[1].slug)
        assertEquals("Vietsub", tabs[1].audioLabel)
        assertEquals("tap-1", tabs[1].episodes[0].slug)
    }

    @Test
    fun decodeStreamrptObfAndCccc_extractsDirectUrls() {
        val json = """{"sU":"https://cdn.test/stream?t=1","pU":"https://cdn.test/stream-plain?t=1"}"""
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val html = """<div id="player" data-obf="$b64"></div>"""
        assertEquals("https://cdn.test/stream-plain?t=1", YanHh3dNowScraper.decodeStreamrptObf(html))

        // Hỗ trợ cả URL-safe Base64 không padding trên API 23
        val urlSafeNoPad = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
        assertEquals(
            "https://cdn.test/stream-plain?t=1",
            YanHh3dNowScraper.decodeStreamrptObf("""<div id="player" data-obf="$urlSafeNoPad"></div>""")
        )

        // Không fallback sang sU (luồng mã hóa #ENC-AESGCM) khi thiếu pU
        val encOnlyB64 = Base64.getEncoder().encodeToString("""{"sU":"https://cdn.test/stream?t=1"}""".toByteArray(Charsets.UTF_8))
        assertEquals(null, YanHh3dNowScraper.decodeStreamrptObf("""<div data-obf="$encOnlyB64"></div>"""))

        val fbHtml = """<script>var cccc = "https:\/\/video.fbcdn.net\/v\/play.mp4?a=1\u0026b=2";</script>"""
        assertEquals("https://video.fbcdn.net/v/play.mp4?a=1&b=2", YanHh3dNowScraper.extractFbCcccMp4(fbHtml))

        val embedTpl = """<script>const storage='drive'; const m3u8Url = `https://cdn.test/file/abc/master.m3u8?storage=${'$'}{storage}`;</script>"""
        assertEquals("https://cdn.test/file/abc/master.m3u8?storage=drive", YanHh3dNowScraper.extractEmbedMasterM3u8(embedTpl))

        val sev2Div = """<div id="player" data-stream-url="https://cdn.test/stream/m3u8/xyz.m3u8"></div>"""
        assertEquals("https://cdn.test/stream/m3u8/xyz.m3u8", YanHh3dNowScraper.extractEmbedMasterM3u8(sev2Div))
    }

    @Test
    fun findPngCamouflageTsOffset_detectsOffsetBeforeMpegTsSync() {
        val fakePngLen = 941
        val buf = ByteArray(2048) { 0xFF.toByte() }
        // PNG magic
        buf[0] = 0x89.toByte()
        buf[1] = 0x50.toByte()
        buf[2] = 0x4E.toByte()
        buf[3] = 0x47.toByte()
        // Repeating MPEG-TS sync byte 0x47 every 188 bytes starting at 941 (drive/lh3 PNG camouflage)
        buf[fakePngLen] = 0x47.toByte()
        buf[fakePngLen + 188] = 0x47.toByte()
        buf[fakePngLen + 376] = 0x47.toByte()

        assertTrue(HlsCacheProvider.isCamouflagedImageHeader(buf, buf.size))
        assertEquals(941, HlsCacheProvider.findPngCamouflageTsOffset(buf, buf.size))
    }

    @Test
    fun parseRealtimeRankingHtml_extractsTop10RealtimeFromPrimarySource() {
        val sb = StringBuilder()
        sb.append("""<section class="block_area block_area_sidebar block_area-realtime"><div id="top-viewed-day"><ul class="ulclear">""")
        for (i in 1..12) {
            sb.append(
                """
                <li class="item-top">
                    <div class="film-number"><span>$i</span></div>
                    <div class="film-poster"><a href="https://yanhh3d.now/phim-$i"><img data-src="https://yanhh3d.now/storage/movies/p$i.jpg" alt="Phim $i"></a></div>
                    <div class="film-detail">
                        <h3 class="film-name"><a href="https://yanhh3d.now/phim-$i" title="Phim Hot $i">Phim Hot $i</a></h3>
                        <div class="fd-infor"><span class="fdi-item">${100 + i}/180 [4K]</span></div>
                    </div>
                </li>
                """.trimIndent()
            )
        }
        sb.append("</ul></div></section>")

        val top10 = YanHh3dNowScraper.parseRealtimeRankingHtml(sb.toString())
        assertEquals(10, top10.size)
        assertEquals("phim-1", top10[0].slug)
        assertEquals("Phim Hot 1", top10[0].title)
        assertEquals(101, top10[0].latestEp)
        assertEquals("BXH #1", top10[0].views)
        assertEquals("phim-10", top10[9].slug)
        assertEquals("BXH #10", top10[9].views)
        assertTrue(top10[0].viewsNum > top10[9].viewsNum)
    }

    @Test
    fun parseCategoriesFromHtml_exposesCoreTopicsAndSyncsWebLinks() {
        val navHtml = """
            <nav>
              <a href="https://yanhh3d.now/hoat-hinh-3d">Hoạt Hình 3D</a>
              <a href="https://yanhh3d.now/phim-le">Phim Lẻ | OVA</a>
              <a href="https://yanhh3d.now/the-loai/tien-hiep">Tiên Hiệp</a>
              <a href="https://yanhh3d.now/the-loai/kiem-hiep">Kiếm Hiệp</a>
            </nav>
        """.trimIndent()
        val cats = YanHh3dNowScraper.parseCategoriesFromHtml(navHtml)
        assertEquals("Thể loại phim", cats.first().label)
        val ids = cats.map { it.id }
        assertTrue(ids.contains("Phim Hot"))
        assertTrue(ids.contains("Phim Chiếu Rạp"))
        assertTrue(ids.contains("Phim Lẻ"))
        assertTrue(ids.contains("Phim Bộ"))
        assertTrue(ids.contains("Phim Hoạt Hình"))
        assertTrue(ids.contains("Phim Hành Động"))
        assertTrue(ids.contains("Tình Cảm"))
        assertTrue(ids.contains("Tiên Hiệp"))
    }

    @Test
    fun resolveButtonsForTab_skipsTrailerEpisodeAndKeepsDirectThuyetMinhServers() = runBlocking {
        val watchHtmlWithTrailerAtEnd = """
            <div id="list_sv">
                <a class="btn btn3dsv" id="sv_LINK1" name="LINK1" data-src="https://scontent.streamrpt1.site/o2/v/vs1.m3u8">1080</a>
                <a class="btn btn3dsv" id="sv_LINK5" name="LINK5" data-src="https://scontent.streamrpt1.site/o2/v/vs4k.m3u8">4K</a>
            </div>
            <div id="detail-ss-list">
                <ul class="nav nav-tabs pre-tabs sever-ep">
                    <li class="nav-item"><a href="#top-comment" class="nav-link">Thuyết Minh</a></li>
                    <li class="nav-item"><a href="#new-comment" class="nav-link">Vietsub</a></li>
                </ul>
                <div id="comment-widget">
                    <div id="top-comment" class="tab-pane active">
                        <a class="ssl-item ep-item" href="https://yanhh3d.now/tien-nghich/tap-168" title="168">168</a>
                        <a class="ssl-item ep-item" href="https://yanhh3d.now/tien-nghich/tap-169" title="169 TL">169 TL</a>
                    </div>
                    <div id="new-comment" class="tab-pane">
                        <a class="ssl-item ep-item" href="https://yanhh3d.now/sever2/tien-nghich/tap-168" title="168">168</a>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val tabs = YanHh3dNowScraper.parseWatchPageEpisodes(watchHtmlWithTrailerAtEnd)
        val tmTab = tabs[0]
        assertEquals("Thuyết Minh", tmTab.audioLabel)
        assertFalse(YanHh3dNowScraper.isTrailerEpisode(tmTab.episodes[0]))
        assertTrue(YanHh3dNowScraper.isTrailerEpisode(tmTab.episodes[1]))

        val tmButtons = YanHh3dNowScraper.resolveButtonsForTab(
            tab = tmTab,
            primarySampleUrl = "https://yanhh3d.now/sever2/tien-nghich/tap-168",
            primaryBtns = YanHh3dNowScraper.parseWatchButtons(watchHtmlWithTrailerAtEnd)
        ) { epUrl ->
            when (epUrl) {
                "https://yanhh3d.now/tien-nghich/tap-169" -> """
                    <div id="list_sv">
                        <a class="btn btn3dsv" id="sv_Trailer" name="Trailer" data-src="https://www.facebook.com/plugins/video.php?href=123">Trailer</a>
                    </div>
                """.trimIndent()
                "https://yanhh3d.now/tien-nghich/tap-168" -> """
                    <div id="list_sv">
                        <a class="btn btn3dsv" id="sv_LINK1" name="LINK1" data-src="https://scontent.streamrpt1.site/o2/v/tm1080.m3u8">1080</a>
                        <a class="btn btn3dsv" id="sv_LINK5" name="LINK5" data-src="https://scontent.streamrpt1.site/o2/v/tm4k.m3u8">4K</a>
                    </div>
                """.trimIndent()
                else -> ""
            }
        }

        assertEquals(2, tmButtons.size)
        assertEquals("4K", tmButtons[0].label)
        assertTrue(tmButtons[0].dataSrc.contains("tm4k.m3u8"))
        assertEquals("1080", tmButtons[1].label)
    }
}
