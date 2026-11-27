package com.hoathinh3d.donghua

import com.hoathinh3d.donghua.data.source.YanHh3dScraper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YanHh3dScraperTest {

    @Test
    fun parseTickRateBadge_extractsEpAndQuality() {
        val meta = YanHh3dScraper.parseTickRateBadge("Tập 288/300 [4K]")
        assertNotNull(meta)
        assertEquals(288, meta!!.latestEp)
        assertEquals(300, meta.totalEp)
        assertEquals("4K", meta.quality)

        val fullMeta = YanHh3dScraper.parseTickRateBadge("FULL HD [4K]")
        assertNotNull(fullMeta)
        assertEquals(1, fullMeta!!.latestEp)
        assertEquals(1, fullMeta.totalEp)
        assertEquals("4K", fullMeta.quality)
    }

    @Test
    fun parseArchiveHtmlCards_extractsFlwItemsWithSubTmAndTickRate() {
        val html = """
            <div class="flw-item item-qtip">
              <div class="film-poster">
                <div class="tick tick-rate">Tập 168/180 [4K]</div>
                <div class="tick ltr"><div class="tick-item tick-sub">SUB+TM</div></div>
                <img data-src="https://yanhh3d.ee/wp-content/uploads/tien-nghich.webp" alt="Tiên Nghịch" />
                <a href="https://yanhh3d.ee/tu-tien/tien-nghich" class="film-poster-ahref" title="Tiên Nghịch"></a>
              </div>
              <h3 class="film-name"><a href="https://yanhh3d.ee/tu-tien/tien-nghich">Tiên Nghịch</a></h3>
            </div>
        """.trimIndent()
        val list = YanHh3dScraper.parseArchiveHtmlCards(html, forcedGenre = "Top Xem Nhiều")
        assertEquals(1, list.size)
        assertEquals("tien-nghich", list[0].slug)
        assertEquals(168, list[0].latestEp)
        assertEquals(180, list[0].totalEp)
        assertTrue(list[0].audioModes.contains("Thuyết Minh"))
    }

    @Test
    fun parseHomeEpisodeMetaHtml_extractsFromFlwItems() {
        val html = """
            <div class="flw-item">
              <div class="tick-rate">Tập 135/156 [4K]</div>
              <a href="https://yanhh3d.ee/tu-tien/dau-pha-thuong-khung.html">Đấu Phá</a>
            </div>
        """.trimIndent()
        val map = YanHh3dScraper.parseHomeEpisodeMetaHtml(html)
        val item = map["dau-pha-thuong-khung"]
        assertNotNull(item)
        assertEquals(135, item!!.latestEp)
        assertEquals(156, item.totalEp)
    }

    @Test
    fun parseDetailHtmlOnly_parsesYanEpRangeAndPrioritizesEpItemOverBtnPlay() {
        val yanHtml = """
            <meta property="og:image" content="https://yanhh3d.ee/wp-content/uploads/poster.webp" />
            <div class="film-description">Phim tu tiên <b>cực hay</b></div>
            <div class="tick-rate">Tập 3/16 [4K]</div>
            <a href="https://yanhh3d.ee/tu-tien/x/tap-2.html" class="btn-play">Xem phim</a>
            <div class="ep-range">
              <a class="ssl-item ep-item" href="https://yanhh3d.ee/tap-2.html" data-post-id="15249" data-ep="tap-2" data-sv="2">Tập 2</a>
              <a class="ssl-item ep-item" href="https://yanhh3d.ee/tap-1.html" data-post-id="15249" data-ep="tap-1" data-sv="1">Tập 1</a>
            </div>
        """.trimIndent()
        val (res, latestHref) = YanHh3dScraper.parseDetailHtmlOnly("https://yanhh3d.ee", "YanHH3D", yanHtml, 0)
        assertEquals("Phim tu tiên cực hay", res.description)
        assertEquals("https://yanhh3d.ee/wp-content/uploads/poster.webp", res.poster)
        assertEquals(8, res.serverGroups.size)
        assertEquals(2, res.serverGroups[0].episodes.size)
        assertEquals("tap-1", res.serverGroups[0].episodes[0].slug)
        assertEquals(15249, res.serverGroups[0].episodes[1].wpPostId)
        assertEquals("2", res.serverGroups[0].episodes[1].wpServerSv)
        assertEquals("https://yanhh3d.ee/tap-2.html", latestHref)
        assertEquals(16, res.totalEp)
    }

    @Test
    fun parseResolveStreamHtml_normalizesProtocolAndDirectM3u8() {
        val res1 = YanHh3dScraper.parseResolveStreamHtml(
            """<iframe src="//streamfree.vip/embed/abc"></iframe>""",
            "https://yanhh3d.ee"
        )
        assertEquals("https://streamfree.vip/embed/abc", res1.linkEmbed)

        val res2 = YanHh3dScraper.parseResolveStreamHtml(
            """<script>var url = "https://cdn.example.com/hls/index.m3u8?t=1";</script>""",
            "https://yanhh3d.ee"
        )
        assertTrue(res2.linkM3u8.contains(".m3u8"))
    }

    @Test
    fun parseArchiveMaxPage_extractsMaxPageFromPathAndQueryLinks() {
        val htmlPath = """
            <div class="pagination">
                <a href="https://yanhh3d.ee/top-yanhh3d-xem-nhieu/page/2">2</a>
                <a href="https://yanhh3d.ee/top-yanhh3d-xem-nhieu/page/25">25</a>
            </div>
        """.trimIndent()
        assertEquals(25, YanHh3dScraper.parseArchiveMaxPage(htmlPath))

        val htmlQuery = """<a href="https://yanhh3d.ee/yanhh3d-moi-cap-nhat?page=12">12</a>"""
        assertEquals(12, YanHh3dScraper.parseArchiveMaxPage(htmlQuery))
        assertEquals(1, YanHh3dScraper.parseArchiveMaxPage("<div>empty</div>"))
    }
}
