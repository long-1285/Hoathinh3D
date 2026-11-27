package com.hoathinh3d.donghua

import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.player.AspectMode
import com.hoathinh3d.donghua.player.PlayerUtils
import com.hoathinh3d.donghua.player.SubtitleOffsetHelper
import com.hoathinh3d.donghua.player.ZeroDelaySeekCoalescer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHelpersTest {

    @Test
    fun aspectMode_cyclesAndParsesSafely() {
        assertEquals(AspectMode.FIT, AspectMode.fromWire("unknown"))
        assertEquals(AspectMode.COVER, AspectMode.FIT.next())
        assertEquals(AspectMode.STRETCH, AspectMode.COVER.next())
        assertEquals(AspectMode.RATIO_4_3, AspectMode.STRETCH.next())
        assertEquals(AspectMode.FIT, AspectMode.RATIO_4_3.next())
        assertEquals(2160, PlayerUtils.maxHeightForQualityPreset("4K 60FPS"))
        assertEquals(1440, PlayerUtils.maxHeightForQualityPreset("2K HDR"))
        assertEquals(1080, PlayerUtils.maxHeightForQualityPreset("1080p Pro"))
        assertEquals(null, PlayerUtils.maxHeightForQualityPreset("Auto"))
    }

    @Test
    fun episodeNumberAndIndexMapping_matchesCorrectly() {
        val eps = listOf(
            EpisodeItem("Tập 01", "tap-01"),
            EpisodeItem("Tập 02", "tap-02"),
            EpisodeItem("Tập 10", "tap-10"),
            EpisodeItem("Tập 21-25", "tap-21-25")
        )
        assertEquals(10, PlayerUtils.extractEpisodeNumber(eps[2], 2))
        assertEquals(2, PlayerUtils.findEpisodeIdxByNumber(eps, 10))
        assertEquals(3, PlayerUtils.findEpisodeIdxByNumber(eps, 23))
        assertEquals("01:25", PlayerUtils.formatPlayerTime(85))
        assertEquals("1:01:05", PlayerUtils.formatPlayerTime(3665))
        assertEquals("https://embed.test/v?id=1&autoplay=1", PlayerUtils.appendAutoplayParam("https://embed.test/v?id=1"))
    }

    @Test
    fun zeroDelaySeekCoalescer_firesFirstPressImmediately() {
        var clock = 1000L
        val committed = mutableListOf<Long>()
        val previews = mutableListOf<Pair<Long, Long>>()
        val coalescer = ZeroDelaySeekCoalescer(
            rapidWindowMs = 150L,
            coalesceDelayMs = 100L,
            clockMs = { clock },
            onPreview = { target, delta -> previews.add(target to delta) },
            onCommitSeek = { target -> committed.add(target) }
        )

        // First press at t=1000ms must commit immediately (0ms delay)
        coalescer.seekBy(10_000L, 20_000L, 120_000L)
        assertEquals(listOf(30_000L), committed)
        assertEquals(listOf(30_000L to 10_000L), previews)

        // Rapid second press at t=1080ms (<= 150ms) should preview immediately and defer commit
        clock = 1080L
        coalescer.seekBy(10_000L, 30_000L, 120_000L)
        assertEquals(1, committed.size) // Still 1 until coalesce timer fires
        assertEquals(40_000L to 20_000L, previews.last())
        coalescer.release()
    }

    @Test
    fun subtitleOffsetHelper_shiftsSrtAndVttAndClampsAtZero() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,250
            Xin chào tu tiên giới
        """.trimIndent()

        val plus500 = SubtitleOffsetHelper.shiftSrtOrVtt(srt, 500L)
        assertTrue(plus500.contains("00:00:01,500 --> 00:00:03,750"))

        val minus1500 = SubtitleOffsetHelper.shiftSrtOrVtt(srt, -1500L)
        assertTrue(minus1500.contains("00:00:00,000 --> 00:00:01,750"))

        val vtt = """
            WEBVTT

            00:02:10.100 --> 00:02:14.900
            Kiếm Khí Tung Hoành
        """.trimIndent()
        val vttShifted = SubtitleOffsetHelper.shiftSrtOrVtt(vtt, 1200L)
        assertTrue(vttShifted.contains("00:02:11.300 --> 00:02:16.100"))
    }

    @Test
    fun bufferedAheadAndSpeedFormatting_workAccurately() {
        assertTrue(PlayerUtils.SPEED_OPTIONS.contains(0.25f))
        assertEquals("0.25x", PlayerUtils.formatSpeedChip(0.25f))
        assertEquals("1x", PlayerUtils.formatSpeedChip(1.0f))
        assertEquals("1.5x", PlayerUtils.formatSpeedChip(1.5f))

        assertEquals(0L, PlayerUtils.computeBufferedAheadSec(10_000L, 10_000L))
        assertEquals(0L, PlayerUtils.computeBufferedAheadSec(15_000L, 5_000L))
        assertEquals(25L, PlayerUtils.computeBufferedAheadSec(10_000L, 35_400L))
    }

    @Test
    fun deviceProfileResolver_capsMaxVideoHeightForMiTv4LowRam1080p() {
        // MiTV4: 1.6 GB RAM (~1600 MB), 1080p display -> capped at 1080p even when preset is 4K (2160)
        assertEquals(
            1080,
            com.hoathinh3d.donghua.core.DeviceProfileResolver.capMaxVideoHeightForDevice(2160, 1600L, 1080)
        )
        assertEquals(
            720,
            com.hoathinh3d.donghua.core.DeviceProfileResolver.capMaxVideoHeightForDevice(720, 1600L, 1080)
        )
        // High-RAM 4K TV -> keeps 2160p
        assertEquals(
            2160,
            com.hoathinh3d.donghua.core.DeviceProfileResolver.capMaxVideoHeightForDevice(2160, 4096L, 2160)
        )
        assertTrue(com.hoathinh3d.donghua.core.DeviceProfileResolver.isUltraLowRamMb(1600L))
        assertTrue(!com.hoathinh3d.donghua.core.DeviceProfileResolver.isUltraLowRamMb(3072L))
    }

    @Test
    fun formatContinueMeta_formatsExactMinuteSecondAndPercent() {
        assertEquals("Tập 49 • 02:22 (14%)", PlayerUtils.formatContinueMeta(49, 142.8, 14))
        assertEquals("Tập 180 • 00:08 (1%)", PlayerUtils.formatContinueMeta(180, 8.0, 1))
        assertEquals("Tập 1 • 0%", PlayerUtils.formatContinueMeta(1, 0.0, 0))
    }

    @Test
    fun storyboardParser_parsesVttAndXywh_andFindsCueAndFormatsDelta() {
        val sampleVtt = """
            WEBVTT

            00:00.000 --> 00:05.000
            sprite_0.jpg#xywh=0,0,160,90

            00:05.000 --> 00:12.500
            https://cdn.example.com/sprite_1.jpg#xywh=160,90,160,90
        """.trimIndent()
        val cues = com.hoathinh3d.donghua.player.StoryboardParser.parseVtt(sampleVtt, "https://cdn.example.com/thumbs/list.vtt")
        assertEquals(2, cues.size)
        assertEquals("https://cdn.example.com/thumbs/sprite_0.jpg", cues[0].imageUrl)
        assertEquals(0, cues[0].x)
        assertEquals(160, cues[0].w)
        assertEquals(90, cues[0].h)
        assertEquals(160, cues[1].x)
        assertEquals(90, cues[1].y)

        assertEquals(cues[0], com.hoathinh3d.donghua.player.StoryboardParser.findCueAt(cues, 2_500L))
        assertEquals(cues[1], com.hoathinh3d.donghua.player.StoryboardParser.findCueAt(cues, 5_000L))
        assertEquals(null, com.hoathinh3d.donghua.player.StoryboardParser.findCueAt(cues, 15_000L))

        assertEquals("+15s", PlayerUtils.formatSeekDelta(15_000L))
        assertEquals("-10s", PlayerUtils.formatSeekDelta(-10_000L))
        assertEquals("+01:25", PlayerUtils.formatSeekDelta(85_000L))
        assertEquals("0s", PlayerUtils.formatSeekDelta(0L))
    }

    @Test
    fun descendingEpisodeSortAndDeviceSpans_matchNewestFirstAnd3ColMobile() {
        val eps = (1..120).map { EpisodeItem("Tập $it", "tap-$it") }
        val descIndices = PlayerUtils.descendingEpisodeDisplayIndices(eps, eps.size)
        assertEquals(119, descIndices.first())
        assertEquals(0, descIndices.last())

        val ranges = PlayerUtils.buildDescendingEpisodeRanges(descIndices, eps, 50)
        assertEquals(3, ranges.size)
        assertEquals("Tập 120 - 71", ranges[0].label)
        assertEquals("Tập 70 - 21", ranges[1].label)
        assertEquals("Tập 20 - 1", ranges[2].label)

        val fallbackDesc = PlayerUtils.descendingEpisodeDisplayIndices(emptyList(), 5)
        assertEquals(listOf(4, 3, 2, 1, 0), fallbackDesc)

        assertEquals(
            3,
            com.hoathinh3d.donghua.core.DeviceProfileResolver.gridSpanCount(
                com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile.MOBILE
            )
        )
        assertEquals(
            4,
            com.hoathinh3d.donghua.core.DeviceProfileResolver.gridSpanCount(
                com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile.TABLET
            )
        )
        assertEquals(
            5,
            com.hoathinh3d.donghua.core.DeviceProfileResolver.gridSpanCount(
                com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile.TV
            )
        )
    }
}
