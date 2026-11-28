package com.hoathinh3d.donghua.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import java.net.URI
import java.util.Calendar
import java.util.Locale

enum class AspectMode(val wireValue: String, val label: String) {
    FIT("16:9", "Thu phóng"),
    COVER("cover", "Lấp đầy"),
    STRETCH("stretch", "Kéo giãn"),
    RATIO_4_3("4:3", "Tỷ lệ 4:3");

    fun next(): AspectMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        fun fromWire(value: String?): AspectMode =
            entries.firstOrNull { it.wireValue == value } ?: FIT
    }
}

enum class PlayerDrawerType {
    NONE, EPISODES, SOURCES, QUALITY, SPEED, PLAYBACK_SETTINGS
}

data class QualityTrackOption(
    val index: Int, // -1 = Auto ABR
    val label: String,
    val height: Int,
    val bitrate: Int
)

data class EpisodeRangeSlice(
    val startPos: Int,
    val endExclusive: Int,
    val label: String
)

object PlayerUtils {
    const val EP_PAGE_SIZE = 50
    val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    fun descendingEpisodeDisplayIndices(episodes: List<EpisodeItem>, fallbackCount: Int): List<Int> {
        if (episodes.isEmpty()) {
            val total = fallbackCount.coerceAtLeast(1)
            return (total - 1 downTo 0).toList()
        }
        return episodes.indices.sortedWith(
            Comparator { a, b ->
                val epB = extractEpisodeNumber(episodes[b], b)
                val epA = extractEpisodeNumber(episodes[a], a)
                if (epB != epA) epB - epA else b - a
            }
        )
    }

    fun buildDescendingEpisodeRanges(
        displayIndices: List<Int>,
        episodes: List<EpisodeItem>,
        pageSize: Int = EP_PAGE_SIZE
    ): List<EpisodeRangeSlice> {
        if (displayIndices.isEmpty()) return emptyList()
        val size = pageSize.coerceAtLeast(1)
        val slices = mutableListOf<EpisodeRangeSlice>()
        var start = 0
        while (start < displayIndices.size) {
            val end = minOf(start + size, displayIndices.size)
            val firstRealIdx = displayIndices[start]
            val lastRealIdx = displayIndices[end - 1]
            val firstNum = extractEpisodeNumber(episodes.getOrNull(firstRealIdx), firstRealIdx)
            val lastNum = extractEpisodeNumber(episodes.getOrNull(lastRealIdx), lastRealIdx)
            val label = if (firstNum == lastNum) "Tập $firstNum" else "Tập $firstNum - $lastNum"
            slices.add(EpisodeRangeSlice(start, end, label))
            start += size
        }
        return slices
    }

    fun computeBufferedAheadSec(positionMs: Long, bufferedPositionMs: Long): Long {
        if (bufferedPositionMs <= positionMs || bufferedPositionMs <= 0L) return 0L
        return ((bufferedPositionMs - positionMs.coerceAtLeast(0L)) / 1000L).coerceAtLeast(0L)
    }

    fun formatSpeedChip(speed: Float): String {
        val rounded = (kotlin.math.round(speed * 100f) / 100f)
        return if (rounded % 1.0f == 0f) "${rounded.toInt()}x" else "${rounded}x"
    }

    fun extractEpisodeNumber(ep: EpisodeItem?, fallbackIdx: Int): Int {
        if (ep == null) return fallbackIdx + 1
        val target = ep.slug.ifEmpty { ep.name }
        val m = Regex("""(\d+(?:\.\d+)?)""").find(target)
        val parsed = m?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
        return if (parsed != null && parsed > 0) parsed else fallbackIdx + 1
    }

    fun findEpisodeIdxByNumber(episodes: List<EpisodeItem>, targetEpNum: Int): Int {
        if (episodes.isEmpty()) return (targetEpNum - 1).coerceAtLeast(0)
        val exactIdx = episodes.indexOfFirst { ep ->
            val idx = episodes.indexOf(ep)
            extractEpisodeNumber(ep, idx) == targetEpNum
        }
        if (exactIdx >= 0) return exactIdx
        // Hỗ trợ tập gộp như "Tập 21-25" / "tap-21-25" khi đối chiếu từ nguồn tách lẻ từng tập
        val rangeIdx = episodes.indexOfFirst { ep ->
            val target = ep.slug.ifEmpty { ep.name }
            val m = Regex("""(\d+)\s*[-–]\s*(\d+)""").find(target) ?: return@indexOfFirst false
            val start = m.groupValues[1].toIntOrNull() ?: return@indexOfFirst false
            val end = m.groupValues[2].toIntOrNull() ?: return@indexOfFirst false
            targetEpNum in minOf(start, end)..maxOf(start, end)
        }
        if (rangeIdx >= 0) return rangeIdx
        return (targetEpNum - 1).coerceIn(0, episodes.size - 1)
    }

    fun appendAutoplayParam(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        return try {
            val u = URI(rawUrl)
            val query = u.query
            if (query != null && query.contains("autoplay=")) {
                rawUrl
            } else {
                val sep = if (query.isNullOrEmpty()) "?" else "&"
                "$rawUrl${sep}autoplay=1"
            }
        } catch (_: Exception) {
            if (rawUrl.contains("autoplay=")) rawUrl
            else "$rawUrl${if (rawUrl.contains("?")) "&" else "?"}autoplay=1"
        }
    }

    fun formatPlayerTime(totalSeconds: Long): String {
        if (totalSeconds <= 0L) return "00:00"
        val h = totalSeconds / 3600L
        val m = (totalSeconds % 3600L) / 60L
        val s = totalSeconds % 60L
        return if (h > 0L) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }

    fun formatPlayerTimeMs(ms: Long): String = formatPlayerTime((ms / 1000L).coerceAtLeast(0L))

    fun formatContinueMeta(episode: Int, currentTimeSec: Double, progressPct: Int): String {
        val sec = currentTimeSec.toLong().coerceAtLeast(0L)
        val pct = progressPct.coerceIn(0, 100)
        return if (sec > 0L) {
            "Tập $episode • ${formatPlayerTime(sec)} ($pct%)"
        } else {
            "Tập $episode • $pct%"
        }
    }

    fun formatShareText(title: String, epName: String? = null): String {
        val cleanTitle = title.trim().ifBlank { "Hoạt Hình 3D" }
        return if (epName.isNullOrBlank()) {
            "Xem hoạt hình 3D \"$cleanTitle\" trên HoatHinh3D Universal!"
        } else {
            "Đang xem \"$cleanTitle\" - $epName trên HoatHinh3D Universal!"
        }
    }

    fun maxHeightForQualityPreset(preset: String?): Int? {
        return when (preset?.trim()) {
            "4K 60FPS" -> 2160
            "2K HDR" -> 1440
            "1080p Pro" -> 1080
            else -> null
        }
    }

    fun formatSeekDelta(deltaMs: Long): String {
        val deltaSec = deltaMs / 1000L
        return when {
            deltaSec == 0L -> "0s"
            deltaSec > 0L && deltaSec < 60L -> "+${deltaSec}s"
            deltaSec < 0L && deltaSec > -60L -> "${deltaSec}s"
            deltaSec > 0L -> "+${formatPlayerTime(deltaSec)}"
            else -> "-${formatPlayerTime(-deltaSec)}"
        }
    }

    fun formatRelativeDayLabel(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (updatedAtMs <= 0L) return "Gần đây"
        val targetCal = Calendar.getInstance().apply { timeInMillis = updatedAtMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val sameYear = targetCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        val dayDiff = if (sameYear) {
            nowCal.get(Calendar.DAY_OF_YEAR) - targetCal.get(Calendar.DAY_OF_YEAR)
        } else {
            val prevYearCal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, -1)
            }
            if (targetCal.get(Calendar.YEAR) == prevYearCal.get(Calendar.YEAR) &&
                targetCal.get(Calendar.DAY_OF_YEAR) == prevYearCal.get(Calendar.DAY_OF_YEAR)
            ) 1 else 99
        }
        return when (dayDiff) {
            0 -> "Hôm nay"
            1 -> "Hôm qua"
            else -> String.format(
                Locale.US,
                "%02d/%02d",
                targetCal.get(Calendar.DAY_OF_MONTH),
                targetCal.get(Calendar.MONTH) + 1
            )
        }
    }

    fun nextUnwatchedEpisodeNumber(
        episodes: List<EpisodeItem>,
        watchedEpisodes: Set<Int>,
        fallbackTotalEp: Int = 1
    ): Int? {
        val candidates = if (episodes.isNotEmpty()) {
            episodes.mapIndexed { idx, ep -> extractEpisodeNumber(ep, idx) }.distinct().sorted()
        } else {
            (1..fallbackTotalEp.coerceAtLeast(1)).toList()
        }
        return candidates.firstOrNull { !watchedEpisodes.contains(it) }
    }

    fun pickRandomMovie(
        movies: List<DonghuaMovie>,
        excludeId: String? = null,
        seedMs: Long = System.currentTimeMillis()
    ): DonghuaMovie? {
        if (movies.isEmpty()) return null
        val pool = if (movies.size > 1 && !excludeId.isNullOrBlank()) {
            movies.filterNot { it.id == excludeId }
        } else {
            movies
        }
        val activePool = pool.ifEmpty { movies }
        val idx = ((seedMs xor (seedMs ushr 16)).toInt() and 0x7FFFFFFF) % activePool.size
        return activePool[idx]
    }
}

data class StoryboardCue(
    val startMs: Long,
    val endMs: Long,
    val imageUrl: String,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0
) {
    val hasCrop: Boolean get() = w > 0 && h > 0
}

object StoryboardParser {

    private val TIME_LINE = Regex("""^(\d{1,2}:)?\d{2}:\d{2}(?:[.,]\d{1,3})?\s*-->\s*(\d{1,2}:)?\d{2}:\d{2}(?:[.,]\d{1,3})?""")
    private val XYWH_REGEX = Regex("""#xywh=(\d+),(\d+),(\d+),(\d+)""", RegexOption.IGNORE_CASE)

    fun parseTimestampMs(raw: String): Long? {
        val clean = raw.trim().replace(',', '.')
        val secParts = clean.split('.')
        val msPart = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        val hms = secParts[0].split(':')
        return when (hms.size) {
            3 -> {
                val h = hms[0].toLongOrNull() ?: return null
                val m = hms[1].toLongOrNull() ?: return null
                val s = hms[2].toLongOrNull() ?: return null
                ((h * 3600L + m * 60L + s) * 1000L) + msPart
            }
            2 -> {
                val m = hms[0].toLongOrNull() ?: return null
                val s = hms[1].toLongOrNull() ?: return null
                ((m * 60L + s) * 1000L) + msPart
            }
            else -> null
        }
    }

    fun resolveImageUrl(rawUrl: String, baseUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || baseUrl.isBlank()) return trimmed
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            trimmed
        }
    }

    fun parseVtt(vttText: String, baseUrl: String = ""): List<StoryboardCue> {
        if (vttText.isBlank()) return emptyList()
        val lines = vttText.lines()
        val out = ArrayList<StoryboardCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (TIME_LINE.containsMatchIn(line)) {
                val arrowIdx = line.indexOf("-->")
                val startStr = line.substring(0, arrowIdx).trim()
                val endStr = line.substring(arrowIdx + 3).trim().substringBefore(' ')
                val startMs = parseTimestampMs(startStr)
                val endMs = parseTimestampMs(endStr)
                var payloadLine = ""
                var j = i + 1
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isEmpty()) break
                    if (payloadLine.isEmpty()) payloadLine = candidate
                    j++
                }
                if (startMs != null && endMs != null && payloadLine.isNotEmpty()) {
                    val m = XYWH_REGEX.find(payloadLine)
                    val rawPath = payloadLine.substringBefore("#xywh=").trim()
                    val fullUrl = resolveImageUrl(rawPath, baseUrl)
                    if (fullUrl.isNotEmpty()) {
                        out.add(
                            StoryboardCue(
                                startMs = startMs,
                                endMs = endMs,
                                imageUrl = fullUrl,
                                x = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
                                y = m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0,
                                w = m?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0,
                                h = m?.groupValues?.getOrNull(4)?.toIntOrNull() ?: 0
                            )
                        )
                    }
                }
                i = j + 1
                continue
            }
            i++
        }
        return out.sortedBy { it.startMs }
    }

    fun findCueAt(cues: List<StoryboardCue>, positionMs: Long): StoryboardCue? {
        if (cues.isEmpty()) return null
        val target = positionMs.coerceAtLeast(0L)
        var lo = 0
        var hi = cues.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cue = cues[mid]
            when {
                target < cue.startMs -> hi = mid - 1
                target >= cue.endMs -> lo = mid + 1
                else -> return cue
            }
        }
        return null
    }
}

/**
 * Zero-Delay Single Seek + Rapid Repeat Coalescer:
 * - Cú chạm/nhấn đầu tiên (khi khoảng cách từ lần trước > 150ms) bắn seekTo ngay lập tức (0ms).
 * - Chỉ gom lệnh ngắn (100ms) khi người dùng nhấn dồn liên tiếp trong cửa sổ <= 150ms.
 */
class ZeroDelaySeekCoalescer(
    private val rapidWindowMs: Long = 150L,
    private val coalesceDelayMs: Long = 100L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val onPreview: (targetMs: Long, accumulatedDeltaMs: Long) -> Unit,
    private val onCommitSeek: (targetMs: Long) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerMs = 0L
    private var pendingTargetMs = 0L
    private var accumulatedDeltaMs = 0L
    private var hasPending = false

    private val commitRunnable = Runnable {
        if (hasPending) {
            val target = pendingTargetMs
            hasPending = false
            accumulatedDeltaMs = 0L
            onCommitSeek(target)
        }
    }

    fun seekBy(deltaMs: Long, currentPosMs: Long, durationMs: Long) {
        val now = clockMs()
        val isRapidRepeat = (now - lastTriggerMs) <= rapidWindowMs || hasPending
        lastTriggerMs = now

        val basePos = if (hasPending) pendingTargetMs else currentPosMs
        val clampedDuration = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val target = (basePos + deltaMs).coerceIn(0L, clampedDuration)

        if (!isRapidRepeat) {
            // 0ms immediate seek on first press!
            handler.removeCallbacks(commitRunnable)
            hasPending = false
            accumulatedDeltaMs = deltaMs
            onPreview(target, deltaMs)
            onCommitSeek(target)
            // Start short window tracking in case user presses again immediately
            pendingTargetMs = target
        } else {
            // Coalesce rapid repeats over ~100ms
            pendingTargetMs = target
            accumulatedDeltaMs += deltaMs
            hasPending = true
            onPreview(target, accumulatedDeltaMs)
            handler.removeCallbacks(commitRunnable)
            handler.postDelayed(commitRunnable, coalesceDelayMs)
        }
    }

    fun release() {
        handler.removeCallbacks(commitRunnable)
        hasPending = false
    }
}

/**
 * Dịch mốc thời gian phụ đề rời (.srt / .vtt) theo độ trễ +/- ms, kẹp tối thiểu về 00:00:00.000.
 */
object SubtitleOffsetHelper {
    private val TIMESTAMP_REGEX = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})([,.])(\d{3})""")

    fun parseTimestampMs(raw: String): Long? {
        val m = TIMESTAMP_REGEX.matchEntire(raw.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: return null
        val sec = m.groupValues[3].toLongOrNull() ?: return null
        val ms = m.groupValues[5].toLongOrNull() ?: return null
        return ((h * 3600L) + (min * 60L) + sec) * 1000L + ms
    }

    fun formatTimestamp(ms: Long, separator: Char): String {
        val safe = ms.coerceAtLeast(0L)
        val h = safe / 3_600_000L
        val m = (safe % 3_600_000L) / 60_000L
        val s = (safe % 60_000L) / 1000L
        val rem = safe % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", h, m, s, separator, rem)
    }

    fun shiftSrtOrVtt(content: String, offsetMs: Long): String {
        if (offsetMs == 0L || content.isBlank()) return content
        return content.lines().joinToString("\n") { line ->
            if (!line.contains("-->")) {
                line
            } else {
                TIMESTAMP_REGEX.replace(line) { m ->
                    val sep = m.groupValues[4].firstOrNull() ?: ','
                    val origMs = parseTimestampMs(m.value) ?: return@replace m.value
                    val shifted = (origMs + offsetMs).coerceAtLeast(0L)
                    formatTimestamp(shifted, sep)
                }
            }
        }
    }
}

/**
 * Chế độ Ban Đêm (Night Mode DRC):
 * Nén dải động (giảm tiếng nổ quá lớn, tăng rõ lời thoại) dùng AudioEffect tích hợp của Android SDK.
 */
class AudioDrcController {
    private var dynamicsProcessing: Any? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentSessionId: Int = 0

    fun apply(audioSessionId: Int, enabled: Boolean) {
        if (audioSessionId <= 0) return
        if (!enabled) {
            release()
            return
        }
        if (currentSessionId == audioSessionId && (dynamicsProcessing != null || loudnessEnhancer != null)) {
            return
        }
        release()
        currentSessionId = audioSessionId

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val ok = runCatching {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,
                    false, 0,
                    true, 3,
                    false, 0,
                    true
                ).build()
                val dp = DynamicsProcessing(0, audioSessionId, cfg).apply {
                    // Limiter gentle threshold (-6 dB) to prevent loud bursts at night
                    setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(
                            true,
                            true,
                            0,
                            2.5f,
                            45.0f,
                            3.5f,
                            -6.0f,
                            2.5f
                        )
                    )
                    this.enabled = true
                }
                dynamicsProcessing = dp
                true
            }.getOrDefault(false)
            if (ok) return
        }

        runCatching {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(350) // +3.5 dB clarity boost for dialogue
                this.enabled = true
            }
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { (dynamicsProcessing as? DynamicsProcessing)?.release() }
        }
        dynamicsProcessing = null
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        currentSessionId = 0
    }
}
