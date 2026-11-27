package com.hoathinh3d.donghua

import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.source.VietnameseSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VietnameseSearchTest {

    @Test
    fun removeVietnameseTones_stripsAccentsAndD() {
        assertEquals("dau pha thuong khung", VietnameseSearch.removeVietnameseTones("Đấu Phá Thương Khung"))
        assertEquals("pham nhan tu tien", VietnameseSearch.removeVietnameseTones("Phàm Nhân Tu Tiên"))
    }

    @Test
    fun expandAliases_resolvesDptkAndTghm() {
        val dptk = VietnameseSearch.expandVietnameseSearchQueries("dptk")
        assertEquals("dau pha thuong khung", dptk.expanded)
        assertTrue(dptk.apiKeywords.contains("dau pha thuong khung"))

        val tghm = VietnameseSearch.expandVietnameseSearchQueries("tghm")
        assertEquals("the gioi hoan my", tghm.expanded)
    }

    @Test
    fun scoreMovieMatch_ranksExactAndAliasMatchesHighest() {
        val catalog = InitialData.INITIAL_DONGHUA_CATALOG
        val ranked = catalog
            .map { it to VietnameseSearch.scoreMovieMatch(it, "dptk") }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        assertTrue(ranked.isNotEmpty())
        assertEquals("dptk", ranked.first().first.id)

        val acronymMatch = VietnameseSearch.matchesMovieQuery("Luyện Khí Mười Vạn Năm", "lkmvn")
        assertTrue(acronymMatch)
    }
}
