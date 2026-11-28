package com.hoathinh3d.donghua.core

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.OvershootInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.hoathinh3d.donghua.data.model.ThemePreset

data class ThemePalette(
    val preset: ThemePreset,
    val isDark: Boolean,
    val bgPrimary: Int,
    val bgSecondary: Int,
    val textMain: Int,
    val textMuted: Int,
    val glassSurface: Int,
    val glassCard: Int,
    val glassBorder: Int,
    val glassSubtleBorder: Int,
    val accentFrom: Int,
    val accentTo: Int,
    val inputBg: Int,
    val glassBlurPx: Int = 32,
    val glassOpacityPct: Int = 52,
    val liteRender: Boolean = false
)

object ThemeManager {

    fun isSystemDarkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun palette(
        themeWire: String?,
        glassBlurPx: Int = 32,
        glassOpacityPct: Int = 52,
        darkModeWire: String? = null,
        @Suppress("UNUSED_PARAMETER") isSystemDark: Boolean = false,
        liteRender: Boolean = false
    ): ThemePalette {
        val blur = glassBlurPx.coerceIn(8, 40)
        val opacityPct = glassOpacityPct.coerceIn(10, 90)
        // Map user-facing Liquid Glass Transparency % (10% = opaque frosted -> 90% = ultra-clear glass)
        val transparency = opacityPct / 100f
        val blurShift = ((blur - 24) * 0.45f).toInt()
        val surfaceAlpha = (242f - transparency * 195f + blurShift).toInt().coerceIn(38, 238)
        val cardAlpha = (248f - transparency * 172f + blurShift).toInt().coerceIn(62, 244)
        val inputAlpha = (244f - transparency * 165f + blurShift).toInt().coerceIn(68, 240)

        val rawPreset = ThemePreset.fromWire(themeWire)
        val isSunsetDark = rawPreset == ThemePreset.DARK || (themeWire.isNullOrBlank() && darkModeWire == "dark")

        if (isSunsetDark) {
            return ThemePalette(
                preset = ThemePreset.DARK,
                isDark = true,
                bgPrimary = Color.parseColor("#040507"),
                bgSecondary = Color.parseColor("#0C0E14"),
                textMain = Color.parseColor("#F7F3EA"),
                textMuted = Color.parseColor("#A39A8B"),
                glassSurface = Color.argb(surfaceAlpha, 11, 13, 19),
                glassCard = Color.argb(cardAlpha, 18, 20, 29),
                glassBorder = Color.argb(62, 226, 174, 88),
                glassSubtleBorder = Color.argb(38, 226, 174, 88),
                accentFrom = Color.parseColor("#E2AE58"),
                accentTo = Color.parseColor("#D1844B"),
                inputBg = Color.argb(inputAlpha, 22, 25, 35),
                glassBlurPx = blur,
                glassOpacityPct = opacityPct,
                liteRender = liteRender
            )
        }

        return ThemePalette(
            preset = ThemePreset.PEARL,
            isDark = false,
            bgPrimary = Color.parseColor("#F6F8FC"),
            bgSecondary = Color.parseColor("#ECF2F9"),
            textMain = Color.parseColor("#1E293B"),
            textMuted = Color.parseColor("#64748B"),
            glassSurface = Color.argb(surfaceAlpha, 255, 255, 255),
            glassCard = Color.argb(cardAlpha, 255, 255, 255),
            glassBorder = Color.argb(232, 255, 255, 255),
            glassSubtleBorder = Color.argb(46, 148, 163, 184),
            accentFrom = Color.parseColor("#5C6BC0"),
            accentTo = Color.parseColor("#43A6B8"),
            inputBg = Color.argb(inputAlpha, 255, 255, 255),
            glassBlurPx = blur,
            glassOpacityPct = opacityPct,
            liteRender = liteRender
        )
    }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun rootBackground(palette: ThemePalette): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.bgPrimary, palette.bgSecondary)
        )
    }

    private fun specularSheenLayer(
        palette: ThemePalette,
        radiusPx: Float,
        strongSpecular: Boolean
    ): Drawable {
        val topHighlight = if (palette.isDark) {
            Color.argb(if (strongSpecular) 38 else 22, 255, 255, 255)
        } else {
            Color.argb(if (strongSpecular) 115 else 72, 255, 255, 255)
        }
        val midTransparent = Color.TRANSPARENT
        val bottomRefraction = if (palette.isDark) {
            Color.argb(if (strongSpecular) 20 else 10, Color.red(palette.accentTo), Color.green(palette.accentTo), Color.blue(palette.accentTo))
        } else {
            Color.argb(if (strongSpecular) 24 else 12, Color.red(palette.accentTo), Color.green(palette.accentTo), Color.blue(palette.accentTo))
        }
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(topHighlight, midTransparent, bottomRefraction)
        ).apply {
            cornerRadius = radiusPx
        }
    }

    private fun focusStrokeColor(palette: ThemePalette): Int =
        if (palette.isDark) Color.WHITE else Color.parseColor("#0284C7")

    private fun focusFillOverlayColor(palette: ThemePalette, active: Boolean): Int = when {
        active && palette.isDark -> Color.argb(235, Color.red(palette.accentFrom), Color.green(palette.accentFrom), Color.blue(palette.accentFrom))
        active -> Color.argb(235, Color.red(palette.accentFrom), Color.green(palette.accentFrom), Color.blue(palette.accentFrom))
        palette.isDark -> Color.argb(72, 56, 189, 248)
        else -> Color.argb(38, 2, 132, 199)
    }

    /**
     * Apple iOS 27 Liquid Glass Floating Sheet (for Header, Tab Capsule, Control Panels):
     * Multi-pass optical feel with translucent glass base + diagonal specular sheen + crisp beveled rim.
     */
    fun liquidGlassSheetDrawable(
        context: Context,
        palette: ThemePalette,
        radiusDp: Float = 26f,
        liteRender: Boolean = false
    ): StateListDrawable {
        val useLite = liteRender || palette.liteRender
        val radiusPx = dp(context, radiusDp).toFloat()
        val rimColor = when {
            palette.preset == ThemePreset.DARK -> Color.argb(72, 226, 174, 88)
            palette.isDark -> Color.argb(72, 255, 255, 255)
            else -> Color.argb(245, 255, 255, 255)
        }
        val baseNormal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(palette.glassSurface)
            setStroke(dp(context, 1.25f).coerceAtLeast(1), rimColor)
        }
        val normal: Drawable = if (useLite) {
            baseNormal
        } else {
            LayerDrawable(arrayOf(baseNormal, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        }

        val baseFocused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(palette.glassSurface)
            setStroke(dp(context, 3f).coerceAtLeast(3), focusStrokeColor(palette))
        }
        val focused: Drawable = if (useLite) {
            baseFocused
        } else {
            LayerDrawable(arrayOf(baseFocused, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun glassPanelDrawable(
        context: Context,
        palette: ThemePalette,
        radiusDp: Float = 24f,
        isCard: Boolean = false,
        liteRender: Boolean = false
    ): StateListDrawable {
        val useLite = liteRender || palette.liteRender
        if (!isCard) return liquidGlassSheetDrawable(context, palette, radiusDp, liteRender = useLite)
        val radiusPx = dp(context, radiusDp).toFloat()
        // Content Layer uses cleaner Apple Thin System Material so floating glass controls stand out
        val normalBase = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(palette.glassCard)
            setStroke(dp(context, 1f).coerceAtLeast(1), palette.glassSubtleBorder)
        }
        val focusedBase = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(if (palette.isDark) Color.argb(225, 28, 34, 48) else Color.argb(245, 232, 244, 255))
            setStroke(dp(context, 3f).coerceAtLeast(3), focusStrokeColor(palette))
        }
        val normal: Drawable = if (useLite) {
            normalBase
        } else {
            LayerDrawable(arrayOf(normalBase, specularSheenLayer(palette, radiusPx, strongSpecular = false)))
        }
        val focused: Drawable = if (useLite) {
            focusedBase
        } else {
            LayerDrawable(arrayOf(focusedBase, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun pillDrawable(
        context: Context,
        palette: ThemePalette,
        active: Boolean,
        radiusDp: Float = 16f,
        liteRender: Boolean = false
    ): StateListDrawable {
        val useLite = liteRender || palette.liteRender
        val radiusPx = dp(context, radiusDp).toFloat()
        val normal: Drawable = if (active) {
            val grad = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.accentFrom, palette.accentTo)
            ).apply {
                cornerRadius = radiusPx
                setStroke(dp(context, 1f).coerceAtLeast(1), Color.argb(140, 255, 255, 255))
            }
            if (useLite) grad else LayerDrawable(arrayOf(grad, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        } else {
            val base = GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(palette.glassCard)
                setStroke(dp(context, 1f).coerceAtLeast(1), palette.glassBorder)
            }
            if (useLite) base else LayerDrawable(arrayOf(base, specularSheenLayer(palette, radiusPx, strongSpecular = false)))
        }
        val focused = GradientDrawable().apply {
            if (active) {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(palette.accentFrom, palette.accentTo)
            } else {
                setColor(focusFillOverlayColor(palette, active = false))
            }
            cornerRadius = radiusPx
            setStroke(dp(context, 3f).coerceAtLeast(3), focusStrokeColor(palette))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun accentGradientDrawable(
        context: Context,
        palette: ThemePalette,
        radiusDp: Float = 16f,
        liteRender: Boolean = false
    ): StateListDrawable {
        val useLite = liteRender || palette.liteRender
        val radiusPx = dp(context, radiusDp).toFloat()
        val baseNormal = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(palette.accentFrom, palette.accentTo)
        ).apply {
            cornerRadius = radiusPx
            setStroke(dp(context, 1f).coerceAtLeast(1), Color.argb(120, 255, 255, 255))
        }
        val normal: Drawable = if (useLite) {
            baseNormal
        } else {
            LayerDrawable(arrayOf(baseNormal, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        }
        val focused = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(palette.accentFrom, palette.accentTo)
        ).apply {
            cornerRadius = radiusPx
            setStroke(dp(context, 3f).coerceAtLeast(3), focusStrokeColor(palette))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    /**
     * iOS 27 Liquid Glass Tab Lens with bounded RippleDrawable for Bottom Nav & TV Left Rail.
     */
    fun navTabDrawable(
        context: Context,
        palette: ThemePalette,
        active: Boolean,
        radiusDp: Float = 22f,
        liteRender: Boolean = false
    ): Drawable {
        val useLite = liteRender || palette.liteRender
        val radiusPx = dp(context, radiusDp).toFloat()
        val focusCol = focusStrokeColor(palette)
        val normal: Drawable = if (active) {
            val lensFill = if (palette.isDark) {
                Color.argb(72, Color.red(palette.accentFrom), Color.green(palette.accentFrom), Color.blue(palette.accentFrom))
            } else {
                Color.argb(42, Color.red(palette.accentFrom), Color.green(palette.accentFrom), Color.blue(palette.accentFrom))
            }
            val lensRim = if (palette.preset == ThemePreset.DARK) {
                Color.argb(145, Color.red(palette.accentFrom), Color.green(palette.accentFrom), Color.blue(palette.accentFrom))
            } else if (palette.isDark) {
                Color.argb(115, 255, 255, 255)
            } else {
                Color.argb(235, 255, 255, 255)
            }
            val lens = GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(lensFill)
                setStroke(dp(context, 1.25f).coerceAtLeast(1), lensRim)
            }
            if (useLite) lens else LayerDrawable(arrayOf(lens, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        } else {
            GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(Color.TRANSPARENT)
            }
        }
        val focused = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(
                if (active) {
                    Color.argb(if (palette.isDark) 96 else 64, Color.red(palette.accentFrom), Color.green(palette.accentFrom), Color.blue(palette.accentFrom))
                } else {
                    focusFillOverlayColor(palette, active = false)
                }
            )
            setStroke(dp(context, 2.5f).coerceAtLeast(3), focusCol)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        if (useLite) return states
        val rippleTint = Color.argb(
            if (palette.isDark) 82 else 68,
            Color.red(palette.accentFrom),
            Color.green(palette.accentFrom),
            Color.blue(palette.accentFrom)
        )
        val mask = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleTint), states, mask)
    }

    /**
     * Luminous frosted mini-pill backdrop behind navigation feature icons so they pop over 52% translucent glass.
     */
    fun navFeatureIconDrawable(
        context: Context,
        palette: ThemePalette,
        featureAccent: Int,
        active: Boolean,
        radiusDp: Float = 12f
    ): Drawable {
        val radiusPx = dp(context, radiusDp).toFloat()
        return if (active) {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(featureAccent, palette.accentTo)
            ).apply {
                cornerRadius = radiusPx
                setStroke(dp(context, 1f).coerceAtLeast(1), Color.argb(175, 255, 255, 255))
            }
        } else {
            val fill = if (palette.isDark) {
                Color.argb(42, Color.red(featureAccent), Color.green(featureAccent), Color.blue(featureAccent))
            } else {
                Color.argb(34, Color.red(featureAccent), Color.green(featureAccent), Color.blue(featureAccent))
            }
            val rim = if (palette.isDark) {
                Color.argb(88, Color.red(featureAccent), Color.green(featureAccent), Color.blue(featureAccent))
            } else {
                Color.argb(190, 255, 255, 255)
            }
            GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(fill)
                setStroke(dp(context, 1f).coerceAtLeast(1), rim)
            }
        }
    }

    fun attachLiquidNavTapEffect(
        tabView: View,
        iconView: View,
        enableAnimation: Boolean
    ) {
        if (!enableAnimation) {
            tabView.animate().cancel()
            iconView.animate().cancel()
            tabView.scaleX = 1f
            tabView.scaleY = 1f
            iconView.scaleX = 1f
            iconView.scaleY = 1f
            tabView.setOnTouchListener(null)
            return
        }
        tabView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    iconView.animate().cancel()
                    v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(85L).start()
                    iconView.animate().scaleX(0.86f).scaleY(0.86f).setDuration(85L).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().cancel()
                    iconView.animate().cancel()
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .setDuration(220L)
                        .start()
                    iconView.animate()
                        .scaleX(1.16f)
                        .scaleY(1.16f)
                        .setDuration(110L)
                        .withEndAction {
                            iconView.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(140L)
                                .start()
                        }
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    iconView.animate().cancel()
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120L).start()
                    iconView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120L).start()
                }
            }
            false
        }
    }

    fun applyRoundedOutline(view: View, radiusDp: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                if (v.width <= 0 || v.height <= 0) return
                outline.setRoundRect(0, 0, v.width, v.height, dp(v.context, radiusDp).toFloat())
            }
        }
        view.clipToOutline = true
    }

    fun tintedFocusDrawable(
        context: Context,
        bgColor: Int,
        radiusDp: Float = 14f,
        focusStrokeColor: Int = Color.parseColor("#0284C7")
    ): StateListDrawable {
        val radiusPx = dp(context, radiusDp).toFloat()
        val normal = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(bgColor)
        }
        val focused = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(bgColor)
            setStroke(dp(context, 3f).coerceAtLeast(3), focusStrokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun attachTvCardFocusEffect(view: View, enableScale: Boolean) {
        if (!enableScale) {
            view.animate().cancel()
            view.scaleX = 1.0f
            view.scaleY = 1.0f
            view.elevation = 0f
            view.onFocusChangeListener = null
            return
        }
        view.setOnFocusChangeListener { v, hasFocus ->
            val targetScale = if (hasFocus) 1.035f else 1.0f
            v.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(120L)
                .start()
            v.elevation = if (hasFocus) dp(v.context, 8f).toFloat() else 0f
        }
    }

    fun applyEdgeToEdgePadding(
        root: View,
        onInsets: (top: Int, bottom: Int, left: Int, right: Int) -> Unit
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            onInsets(bars.top, bars.bottom, bars.left, bars.right)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun applyOrbBlur(view: View, blurPx: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = (blurPx.coerceIn(8, 40) * 1.8f)
            view.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
        }
    }

    fun applySystemBars(window: Window, palette: ThemePalette) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    fun tintColorState(color: Int): ColorStateList = ColorStateList.valueOf(color)
}
