package com.hoathinh3d.donghua.core

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.hoathinh3d.donghua.data.model.DeviceMode
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile

object DeviceProfileResolver {

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
            ResolvedDeviceProfile.MOBILE -> 2
            ResolvedDeviceProfile.TABLET -> 3
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
    ): Boolean = explicitReducedMotion || (resolved == ResolvedDeviceProfile.TV && isLowRamDevice(context))

    fun capMaxVideoHeightForDevice(
        presetMaxHeight: Int?,
        totalRamMb: Long,
        screenHeightPx: Int
    ): Int? {
        if (totalRamMb in 1..2560L && screenHeightPx in 1..1088) {
            return minOf(presetMaxHeight ?: 1080, 1080)
        }
        return presetMaxHeight
    }

    fun recommendedMaxVideoHeight(context: Context, presetMaxHeight: Int?): Int? {
        val dm = context.resources.displayMetrics
        val maxScreenDim = maxOf(dm.widthPixels, dm.heightPixels)
        val minScreenDim = minOf(dm.widthPixels, dm.heightPixels)
        val effectiveHeight = if (maxScreenDim <= 1920 && minScreenDim <= 1080) 1080 else dm.heightPixels
        return capMaxVideoHeightForDevice(presetMaxHeight, totalMemoryMb(context), effectiveHeight)
    }

    fun physicalDeviceLabel(context: Context): String {
        return when (detectPhysicalDevice(context)) {
            ResolvedDeviceProfile.MOBILE -> "Điện thoại màn hình nhỏ"
            ResolvedDeviceProfile.TABLET -> "Máy tính bảng"
            ResolvedDeviceProfile.TV -> "Tivi màn hình lớn"
        }
    }
}
