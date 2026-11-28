package com.hoathinh3d.donghua.core

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaCodecList
import com.hoathinh3d.donghua.data.model.DeviceMode
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile

data class VideoDecoderCapability(
    val maxSupportedHeight: Int,
    val hasHevcHardware: Boolean,
    val summaryLabel: String
)

object DeviceProfileResolver {

    @Volatile
    private var cachedDecoderCap: VideoDecoderCapability? = null

    private fun isSoftwareCodecName(name: String): Boolean {
        val low = name.lowercase()
        return low.startsWith("omx.google.") ||
            low.startsWith("c2.android.") ||
            low.contains(".sw.") ||
            low.contains("ffmpeg")
    }

    /**
     * Dò khả năng giải mã phần cứng AVC/HEVC (tương thích API 23+) để tránh ép 4K lên TV/máy chiếu yếu.
     */
    fun detectVideoDecoderCapability(): VideoDecoderCapability {
        cachedDecoderCap?.let { return it }
        val result = runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            var maxAvcH = 0
            var maxHevcH = 0
            for (info in codecs) {
                if (info.isEncoder || isSoftwareCodecName(info.name)) continue
                for (type in info.supportedTypes) {
                    val mime = type.lowercase()
                    if (mime != "video/avc" && mime != "video/hevc") continue
                    val upperH = runCatching {
                        info.getCapabilitiesForType(type)?.videoCapabilities?.supportedHeights?.upper ?: 0
                    }.getOrDefault(0)
                    if (mime == "video/avc" && upperH > maxAvcH) maxAvcH = upperH
                    if (mime == "video/hevc" && upperH > maxHevcH) maxHevcH = upperH
                }
            }
            val bestH = maxOf(maxAvcH, maxHevcH).let { if (it > 0) it else 1080 }
            val hasHevc = maxHevcH >= 1080
            val tier = when {
                bestH >= 2160 -> "4K (2160p)"
                bestH >= 1440 -> "2K (1440p)"
                bestH >= 1080 -> "Full HD (1080p)"
                else -> "${bestH}p"
            }
            val hevcTag = if (hasHevc) "H.265/HEVC phần cứng" else "H.264 phần cứng"
            VideoDecoderCapability(bestH, hasHevc, "$tier • $hevcTag")
        }.getOrElse {
            VideoDecoderCapability(1080, false, "Full HD (1080p) • Mặc định an toàn")
        }
        cachedDecoderCap = result
        return result
    }

    fun isTvHardware(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val noTouch = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return isTvMode || hasLeanback || noTouch
    }

    fun detectPhysicalDevice(context: Context): ResolvedDeviceProfile {
        if (isTvHardware(context)) return ResolvedDeviceProfile.TV
        val cfg = context.resources.configuration
        val swDp = cfg.smallestScreenWidthDp
        val widthDp = cfg.screenWidthDp
        return when {
            swDp >= 600 || widthDp >= 900 -> ResolvedDeviceProfile.TABLET
            else -> ResolvedDeviceProfile.MOBILE
        }
    }

    fun resolve(context: Context, explicitModeWire: String?): ResolvedDeviceProfile {
        return when (DeviceMode.fromWire(explicitModeWire)) {
            DeviceMode.MOBILE -> ResolvedDeviceProfile.MOBILE
            DeviceMode.TABLET -> ResolvedDeviceProfile.TABLET
            DeviceMode.TV -> ResolvedDeviceProfile.TV
            DeviceMode.AUTO -> detectPhysicalDevice(context)
        }
    }

    fun gridSpanCount(profile: ResolvedDeviceProfile): Int {
        return when (profile) {
            ResolvedDeviceProfile.MOBILE -> 3
            ResolvedDeviceProfile.TABLET -> 4
            ResolvedDeviceProfile.TV -> 5
        }
    }

    fun totalMemoryMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 2048L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }

    fun isLowRamDevice(context: Context): Boolean = totalMemoryMb(context) <= 2560L

    fun isUltraLowRamMb(totalRamMb: Long): Boolean = totalRamMb in 1..1800L

    fun isUltraLowRamDevice(context: Context): Boolean = isUltraLowRamMb(totalMemoryMb(context))

    fun shouldUseReducedMotion(
        context: Context,
        resolved: ResolvedDeviceProfile,
        explicitReducedMotion: Boolean
    ): Boolean = explicitReducedMotion || resolved == ResolvedDeviceProfile.TV || isUltraLowRamDevice(context)

    fun capMaxVideoHeightForDevice(
        presetMaxHeight: Int?,
        totalRamMb: Long,
        screenHeightPx: Int,
        hardwareMaxHeight: Int? = null
    ): Int? {
        var cap = presetMaxHeight
        if (totalRamMb in 1..2560L && screenHeightPx in 1..1088) {
            cap = minOf(cap ?: 1080, 1080)
        }
        if (hardwareMaxHeight != null && hardwareMaxHeight > 0 && hardwareMaxHeight < 2160) {
            cap = minOf(cap ?: hardwareMaxHeight, hardwareMaxHeight)
        }
        return cap
    }

    fun recommendedMaxVideoHeight(context: Context, presetMaxHeight: Int?): Int? {
        val dm = context.resources.displayMetrics
        val maxScreenDim = maxOf(dm.widthPixels, dm.heightPixels)
        val minScreenDim = minOf(dm.widthPixels, dm.heightPixels)
        val effectiveHeight = if (maxScreenDim <= 1920 && minScreenDim <= 1080) 1080 else dm.heightPixels
        val hwCap = detectVideoDecoderCapability().maxSupportedHeight
        return capMaxVideoHeightForDevice(presetMaxHeight, totalMemoryMb(context), effectiveHeight, hwCap)
    }

    fun physicalDeviceLabel(context: Context): String {
        return when (detectPhysicalDevice(context)) {
            ResolvedDeviceProfile.MOBILE -> "Điện thoại màn hình nhỏ"
            ResolvedDeviceProfile.TABLET -> "Máy tính bảng"
            ResolvedDeviceProfile.TV -> "Tivi màn hình lớn"
        }
    }
}
