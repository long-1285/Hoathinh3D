package com.hoathinh3d.donghua.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.hoathinh3d.donghua.core.DeviceProfileResolver

object InstantLoadControl {

    @OptIn(UnstableApi::class)
    fun create(context: Context): DefaultLoadControl {
        val totalRamMb = DeviceProfileResolver.totalMemoryMb(context)
        val isUltraLowRam = totalRamMb <= 1800L
        val isLowRam = totalRamMb <= 2560L

        val minBufferMs = if (isUltraLowRam) 8_000 else 10_000
        val maxBufferMs = when {
            isUltraLowRam -> 20_000
            isLowRam -> 32_000
            else -> 48_000
        }
        val backBufferMs = if (isUltraLowRam) 12_000 else 25_000
        val targetBufferBytes = when {
            isUltraLowRam -> 20 * 1024 * 1024 // 20 MB safe for 1.6GB 32-bit MiTV4
            isLowRam -> 34 * 1024 * 1024      // 34 MB safe for 2.5GB Android TV Box
            else -> 72 * 1024 * 1024          // 72 MB
        }

        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                500,    // bufferForPlaybackMs (instant start)
                500     // bufferForPlaybackAfterRebufferMs (instant exit from buffering on seek)
            )
            .setBackBuffer(backBufferMs, true)
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
}
