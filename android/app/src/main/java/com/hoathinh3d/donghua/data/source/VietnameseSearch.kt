package com.hoathinh3d.donghua.data.source

import com.hoathinh3d.donghua.data.model.DonghuaMovie
import java.text.Normalizer

object VietnameseSearch {

    private val DIACRITICS_REGEX = Regex("[\\u0300-\\u036f]")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    val DONGHUA_ALIASES: Map<String, String> = mapOf(
        "dptk" to "dau pha thuong khung",
        "tghm" to "the gioi hoan my",
        "pntt" to "pham nhan tu tien",
        "tptk" to "thon phe tinh khong",
        "dldl" to "dau la dai luc",
        "vgdt" to "van gioi doc ton",
        "vdck" to "vu dong can khon",
        "mtk" to "muc than ky",
        "nntt" to "nhat niem vinh hang",
        "nnvh" to "nhat niem vinh hang",
        "lkmvn" to "luyen khi muoi van nam",
        "tcct" to "than chung chi ton",
        "btdl" to "bach luyen thanh than",
        "bltt" to "bach luyen thanh than",
        "hh3d" to "hoat hinh 3d"
    )

    data class ExpandedQuery(
        val raw: String,
        val clean: String,
        val expanded: String,
        val apiKeywords: List<String>
    )

    fun removeVietnameseTones(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(nfd, "")
            .replace('đ', 'd')
            .replace('Đ', 'd')
            .lowercase()
            .let { NON_ALNUM_REGEX.replace(it, " ") }
            .let { MULTI_SPACE_REGEX.replace(it, " ") }
            .trim()
    }

    fun expandVietnameseSearchQueries(rawQuery: String?): ExpandedQuery {
        val raw = (rawQuery ?: "").trim()
        val clean = removeVietnameseTones(raw)
        val expanded = DONGHUA_ALIASES[clean] ?: clean

        val keywords = LinkedHashSet<String>()
        if (expanded.isNotEmpty() && expanded != clean) {
            keywords.add(expanded)
            if (raw.isNotEmpty()) keywords.add(raw)
        } else {
            if (raw.isNotEmpty()) keywords.add(raw)
            if (clean.isNotEmpty() && clean != raw.lowercase()) {
                keywords.add(clean)
            }
        }

        return ExpandedQuery(
            raw = raw,
            clean = clean,
            expanded = expanded,
            apiKeywords = keywords.filter { it.isNotEmpty() }
        )
    }

    fun getTitleAcronym(title: String?): String {
        val clean = removeVietnameseTones(title)
        if (clean.isEmpty()) return ""
        return clean.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString("") { it[0].toString() }
    }

    fun matchesMovieQuery(target: String?, query: String?): Boolean {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return true
        val cleanTarget = removeVietnameseTones(target)
        val exp = expandVietnameseSearchQueries(q)
        if (exp.clean.isEmpty()) return true

        if (cleanTarget.contains(exp.clean) || cleanTarget.contains(exp.expanded)) {
            return true
        }

        val tokens = exp.expanded.split(' ').filter { it.isNotEmpty() }
        if (tokens.size > 1 && tokens.all { cleanTarget.contains(it) }) {
            return true
        }

        val acronym = getTitleAcronym(target)
        if (exp.clean.length >= 2 && acronym.startsWith(exp.clean)) {
            return true
        }

        return false
    }

    fun scoreMovieMatch(movie: DonghuaMovie, rawQuery: String?): Int {
        val q = (rawQuery ?: "").trim()
        if (q.isEmpty()) return 1

        val exp = expandVietnameseSearchQueries(q)
        if (exp.clean.isEmpty()) return 1

        val title = removeVietnameseTones(movie.title)
        val orig = removeVietnameseTones(movie.originalTitle)
        val slug = removeVietnameseTones((movie.slug ?: "").replace('-', ' '))
        val studio = removeVietnameseTones(movie.studio)
        val genres = removeVietnameseTones(movie.genres.joinToString(" "))
        val acronym = getTitleAcronym(movie.title)

        val candidates = if (exp.expanded != exp.clean) {
            listOf(exp.expanded, exp.clean)
        } else {
            listOf(exp.clean)
        }

        var best = 0
        for (term in candidates) {
            when {
                title == term -> best = maxOf(best, 1000)
                title.startsWith(term) -> best = maxOf(best, 850)
                title.contains(term) -> best = maxOf(best, 720)
            }

            when {
                orig == term || slug == term -> best = maxOf(best, 780)
                orig.contains(term) || slug.contains(term) -> best = maxOf(best, 560)
            }

            if (term.length >= 2) {
                when {
                    acronym == term -> best = maxOf(best, 520)
                    acronym.startsWith(term) -> best = maxOf(best, 440)
                }
            }

            val tokens = term.split(' ').filter { it.isNotEmpty() }
            if (tokens.isNotEmpty()) {
                val titleHits = tokens.count { title.contains(it) }
                val allText = "$title $orig $slug"
                val allHits = tokens.count { allText.contains(it) }

                when {
                    titleHits == tokens.size -> best = maxOf(best, 420 + titleHits * 20)
                    allHits == tokens.size -> best = maxOf(best, 360 + allHits * 15)
                    tokens.size >= 2 && titleHits >= kotlin.math.ceil(tokens.size * 0.66).toInt() -> {
                        val ratioBonus = Math.round((titleHits.toDouble() / tokens.size) * 100).toInt()
                        best = maxOf(best, 180 + ratioBonus)
                    }
                }
            }

            if (studio.contains(term) || genres.contains(term)) {
                best = maxOf(best, 80)
            }
        }
        return best
    }
}
