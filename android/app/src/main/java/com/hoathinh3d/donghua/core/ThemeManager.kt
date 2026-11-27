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
    val glassOpacityPct: Int = 52
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
        @Suppress("UNUSED_PARAMETER") isSystemDark: Boolean = false
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
        val effectiveDark = when (darkModeWire) {
            "dark" -> true
            "light" -> false
            else -> rawPreset == ThemePreset.DARK
        }
        val preset = if (!effectiveDark && rawPreset == ThemePreset.DARK) {
            ThemePreset.PEARL
        } else {
            rawPreset
        }

        if (effectiveDark) {
            val (bg1, bg2, acc1, acc2, rimBase) = when (preset) {
                ThemePreset.SAKURA -> arrayOf(
                    Color.parseColor("#160F19"),
                    Color.parseColor("#231727"),
                    Color.parseColor("#DC82A6"),
                    Color.parseColor("#AE8FD8"),
                    Color.argb(54, 244, 182, 208)
                )
                ThemePreset.PEARL -> arrayOf(
                    Color.parseColor("#0B111E"),
                    Color.parseColor("#141E30"),
                    Color.parseColor("#739EF5"),
                    Color.parseColor("#54C9B3"),
                    Color.argb(54, 226, 232, 240)
                )
                ThemePreset.DARK -> arrayOf(
                    Color.parseColor("#040507"),
                    Color.parseColor("#0C0E14"),
                    Color.parseColor("#E2AE58"),
                    Color.parseColor("#D1844B"),
                    Color.argb(62, 226, 174, 88)
                )
            }
            val isSunsetDark = preset == ThemePreset.DARK
            return ThemePalette(
                preset = preset,
                isDark = true,
                bgPrimary = bg1,
                bgSecondary = bg2,
                textMain = Color.parseColor(if (isSunsetDark) "#F7F3EA" else "#F1F5F9"),
                textMuted = Color.parseColor(if (isSunsetDark) "#A39A8B" else "#94A3B8"),
                glassSurface = Color.argb(
                    surfaceAlpha,
                    if (isSunsetDark) 11 else 19,
                    if (isSunsetDark) 13 else 27,
                    if (isSunsetDark) 19 else 44
                ),
                glassCard = Color.argb(
                    cardAlpha,
                    if (isSunsetDark) 18 else 28,
                    if (isSunsetDark) 20 else 39,
                    if (isSunsetDark) 29 else 59
                ),
                glassBorder = rimBase,
                glassSubtleBorder = if (isSunsetDark) Color.argb(38, 226, 174, 88) else Color.argb(34, 255, 255, 255),
                accentFrom = acc1,
                accentTo = acc2,
                inputBg = Color.argb(
                    inputAlpha,
                    if (isSunsetDark) 22 else 32,
                    if (isSunsetDark) 25 else 44,
                    if (isSunsetDark) 35 else 66
                ),
                glassBlurPx = blur,
                glassOpacityPct = opacityPct
            )
        }

        return when (preset) {
            ThemePreset.PEARL, ThemePreset.DARK -> ThemePalette(
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
                glassOpacityPct = opacityPct
            )
            ThemePreset.SAKURA -> ThemePalette(
                preset = preset,
                isDark = false,
                bgPrimary = Color.parseColor("#FDF7F9"),
                bgSecondary = Color.parseColor("#F7EBF1"),
                textMain = Color.parseColor("#312538"),
                textMuted = Color.parseColor("#786A7D"),
                glassSurface = Color.argb(surfaceAlpha, 255, 255, 255),
                glassCard = Color.argb(cardAlpha, 255, 255, 255),
                glassBorder = Color.argb(232, 255, 255, 255),
                glassSubtleBorder = Color.argb(48, 201, 100, 138),
                accentFrom = Color.parseColor("#C9648A"),
                accentTo = Color.parseColor("#9B78C4"),
                inputBg = Color.argb(inputAlpha, 255, 255, 255),
                glassBlurPx = blur,
                glassOpacityPct = opacityPct
            )
        }
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
        if (palette.preset == ThemePreset.DARK) palette.accentFrom else Color.parseColor("#47B8C4")

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
        val normal: Drawable = if (liteRender) {
            baseNormal
        } else {
            LayerDrawable(arrayOf(baseNormal, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        }

        val baseFocused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(palette.glassSurface)
            setStroke(dp(context, 2.5f).coerceAtLeast(3), focusStrokeColor(palette))
        }
        val focused: Drawable = if (liteRender) {
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
        if (!isCard) return liquidGlassSheetDrawable(context, palette, radiusDp)
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
            setColor(palette.glassCard)
            setStroke(dp(context, 2.5f).coerceAtLeast(3), focusStrokeColor(palette))
        }
        val normal: Drawable = if (liteRender) {
            normalBase
        } else {
            LayerDrawable(arrayOf(normalBase, specularSheenLayer(palette, radiusPx, strongSpecular = false)))
        }
        val focused: Drawable = if (liteRender) {
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
        radiusDp: Float = 16f
    ): StateListDrawable {
        val radiusPx = dp(context, radiusDp).toFloat()
        val normal = if (active) {
            val grad = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.accentFrom, palette.accentTo)
            ).apply {
                cornerRadius = radiusPx
                setStroke(dp(context, 1f).coerceAtLeast(1), Color.argb(140, 255, 255, 255))
            }
            LayerDrawable(arrayOf(grad, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        } else {
            val base = GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(palette.glassCard)
                setStroke(dp(context, 1f).coerceAtLeast(1), palette.glassBorder)
            }
            LayerDrawable(arrayOf(base, specularSheenLayer(palette, radiusPx, strongSpecular = false)))
        }
        val focused = GradientDrawable().apply {
            if (active) {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(palette.accentFrom, palette.accentTo)
            } else {
                setColor(palette.glassCard)
            }
            cornerRadius = radiusPx
            setStroke(dp(context, 2.5f).coerceAtLeast(3), focusStrokeColor(palette))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun accentGradientDrawable(
        context: Context,
        palette: ThemePalette,
        radiusDp: Float = 16f
    ): StateListDrawable {
        val radiusPx = dp(context, radiusDp).toFloat()
        val baseNormal = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(palette.accentFrom, palette.accentTo)
        ).apply {
            cornerRadius = radiusPx
            setStroke(dp(context, 1f).coerceAtLeast(1), Color.argb(120, 255, 255, 255))
        }
        val normal = LayerDrawable(arrayOf(baseNormal, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        val focused = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(palette.accentFrom, palette.accentTo)
        ).apply {
            cornerRadius = radiusPx
            setStroke(dp(context, 2.5f).coerceAtLeast(3), focusStrokeColor(palette))
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
            if (liteRender) lens else LayerDrawable(arrayOf(lens, specularSheenLayer(palette, radiusPx, strongSpecular = true)))
        } else {
            GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(Color.TRANSPARENT)
            }
        }
        val focused = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(Color.argb(if (palette.isDark) 58 else 32, Color.red(focusCol), Color.green(focusCol), Color.blue(focusCol)))
            setStroke(dp(context, 2f).coerceAtLeast(2), focusCol)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        if (liteRender) return states
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
        focusStrokeColor: Int = Color.parseColor("#47B8C4")
    ): StateListDrawable {
        val radiusPx = dp(context, radiusDp).toFloat()
        val normal = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(bgColor)
        }
        val focused = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(bgColor)
            setStroke(dp(context, 2.5f).coerceAtLeast(3), focusStrokeColor)
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
