package com.hoathinh3d.donghua.ui.settings

import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.hoathinh3d.donghua.BuildConfig
import com.hoathinh3d.donghua.R
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.core.LanSyncManager
import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.core.ThemePalette
import com.hoathinh3d.donghua.data.model.DarkModePreference
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.DonghuaSource
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile
import com.hoathinh3d.donghua.data.model.SettingsSubtab
import com.hoathinh3d.donghua.data.model.ThemePreset
import com.hoathinh3d.donghua.data.source.CustomSourceParser
import com.hoathinh3d.donghua.databinding.FragmentSettingsBinding
import com.hoathinh3d.donghua.player.HlsCacheProvider
import com.hoathinh3d.donghua.ui.AppHost
import com.hoathinh3d.donghua.ui.ChromeInsetsListener
import com.hoathinh3d.donghua.ui.MainTab
import com.hoathinh3d.donghua.ui.RefreshableTab
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment(), RefreshableTab, ChromeInsetsListener {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val host: AppHost? get() = activity as? AppHost
    private var pendingFocusTag: String? = null
    private var lastMobileDetailOpen: Boolean? = null
    private var lastMobileSubtab: SettingsSubtab? = null

    private data class MobileSettingsMenuItem(
        val subtab: SettingsSubtab,
        val title: String,
        val iconRes: Int,
        val accentColor: Int
    )

    // Custom source form state
    private var draftUrl = ""
    private var draftName = ""
    private var draftType = "Smart Scraper"
    private var draftQuality = "4K 60FPS"
    private var draftImportMovies = true
    private var draftSetPreferred = false
    private var isProbingSource = false
    private var isAddingSource = false
    private var isMeasuringPings = false
    private var lastProbeResult: CustomSourceParser.ProbeResult? = null

    private val exportJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                os.write(PrefsStore.exportFullBackup().toByteArray(Charsets.UTF_8))
            }
            host?.showToast("Đã xuất file sao lưu JSON")
        }.onFailure {
            host?.showToast("Không thể xuất file JSON!")
        }
    }

    private val importJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val content = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()
            val ok = PrefsStore.importFullBackup(content)
            if (ok) {
                host?.reloadAllPersistedState()
                host?.showToast("Khôi phục dữ liệu thành công!")
            } else {
                host?.showToast("File JSON không hợp lệ!")
            }
        }.onFailure {
            host?.showToast("Lỗi đọc file JSON!")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.settingsScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            host?.onMainContentScroll(scrollY - oldScrollY, scrollY)
        }
        refreshUi()
    }

    private fun syncCloudNow() {
        val h = host ?: return
        val user = h.getState().user
        if (user == null) {
            h.openGoogleLoginDialog()
            return
        }
        val ts = PrefsStore.saveCloudSnapshot()
        h.updateUser(user.copy(lastSync = ts))
        val customCount = h.getState().sources.count { it.isCustom }
        h.showToast("Đã lưu bản đồng bộ Email (${user.email}): Lịch sử, Yêu thích & $customCount Nguồn Web!")
    }

    override fun onChromeInsetsChanged(topPx: Int, bottomPx: Int) {
        val b = _binding ?: return
        val h = host ?: return
        val hPad = ThemeManager.dp(
            requireContext(),
            when (h.getState().resolvedDevice) {
                ResolvedDeviceProfile.TV -> 22f
                ResolvedDeviceProfile.TABLET -> 18f
                ResolvedDeviceProfile.MOBILE -> 14f
            }
        )
        b.settingsScroll.setPadding(hPad, topPx, hPad, bottomPx)
    }

    override fun refreshUi() {
        val b = _binding ?: return
        val h = host ?: return
        val st = h.getState()
        val p = h.getPalette()
        val isTv = st.resolvedDevice == ResolvedDeviceProfile.TV
        val isMobile = st.resolvedDevice == ResolvedDeviceProfile.MOBILE
        val hPad = ThemeManager.dp(
            requireContext(),
            when (st.resolvedDevice) {
                ResolvedDeviceProfile.TV -> 22f
                ResolvedDeviceProfile.TABLET -> 18f
                ResolvedDeviceProfile.MOBILE -> 14f
            }
        )
        val (chromeTop, chromeBot) = h.getChromeInsets()
        b.settingsScroll.setPadding(hPad, chromeTop, hPad, chromeBot)

        val subtabsRow = b.settingsSubtabsContainer.parent as? View
        val contentLp = b.settingsSubtabContent.layoutParams as? ViewGroup.MarginLayoutParams
        b.settingsSubtabsContainer.removeAllViews()
        b.settingsSubtabContent.removeAllViews()

        if (isMobile) {
            b.settingsBannerPanel.visibility = View.GONE
            subtabsRow?.visibility = View.GONE
            contentLp?.topMargin = ThemeManager.dp(requireContext(), 4f)

            val shouldResetScroll = lastMobileDetailOpen != st.isSettingsDetailOpen ||
                (st.isSettingsDetailOpen && lastMobileSubtab != st.settingsSubtab)
            lastMobileDetailOpen = st.isSettingsDetailOpen
            lastMobileSubtab = st.settingsSubtab

            if (!st.isSettingsDetailOpen) {
                renderMobileSettingsHub(h, p, !st.settings.reducedMotion)
            } else {
                renderMobileSubtabHeader(
                    title = mobileSubtabDisplayTitle(st.settingsSubtab),
                    p = p
                ) {
                    h.closeSettingsDetail()
                }
                renderSelectedSubtabContent(st.settingsSubtab, p)
            }
            if (shouldResetScroll) {
                b.settingsScroll.post { _binding?.settingsScroll?.scrollTo(0, 0) }
            }
        } else {
            lastMobileDetailOpen = null
            lastMobileSubtab = null
            b.settingsBannerPanel.visibility = View.VISIBLE
            subtabsRow?.visibility = View.VISIBLE
            contentLp?.topMargin = ThemeManager.dp(requireContext(), 14f)

            b.settingsBannerPanel.background = ThemeManager.glassPanelDrawable(requireContext(), p, 22f)
            b.txtSettingsBannerTitle.setTextColor(p.textMain)
            b.txtSettingsBannerSub.setTextColor(p.textMuted)

            for (sub in SettingsSubtab.entries) {
                val active = st.settingsSubtab == sub
                val tagStr = "subtab_${sub.wireValue}"
                val btn = Button(requireContext()).apply {
                    tag = tagStr
                    text = sub.label
                    isAllCaps = false
                    textSize = 12f
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(
                        ThemeManager.dp(context, 14f),
                        ThemeManager.dp(context, 8f),
                        ThemeManager.dp(context, 14f),
                        ThemeManager.dp(context, 8f)
                    )
                    background = ThemeManager.pillDrawable(context, p, active = active, radiusDp = 16f)
                    setTextColor(if (active) Color.WHITE else p.textMain)
                    isFocusable = true
                    setOnClickListener {
                        pendingFocusTag = tagStr
                        h.setSettingsSubtab(sub)
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = ThemeManager.dp(requireContext(), 8f) }
                b.settingsSubtabsContainer.addView(btn, lp)
            }

            renderSelectedSubtabContent(st.settingsSubtab, p)
        }

        val restoreTag = pendingFocusTag
        if (restoreTag != null) {
            pendingFocusTag = null
            b.root.post {
                _binding?.root?.findViewWithTag<View>(restoreTag)?.requestFocus()
            }
        }
    }

    private fun renderSelectedSubtabContent(subtab: SettingsSubtab, p: ThemePalette) {
        when (subtab) {
            SettingsSubtab.PLAYER -> renderPlayerTab(p)
            SettingsSubtab.SOURCES -> renderSourcesTab(p)
            SettingsSubtab.APPEARANCE -> renderAppearanceTab(p)
            SettingsSubtab.BACKUP -> renderBackupTab(p)
            SettingsSubtab.EXTENSIONS -> renderExtensionsTab(p)
        }
    }

    private fun mobileSubtabDisplayTitle(subtab: SettingsSubtab): String = when (subtab) {
        SettingsSubtab.PLAYER -> "Trình phát tích hợp"
        SettingsSubtab.SOURCES -> "Nguồn phim (+Web)"
        SettingsSubtab.APPEARANCE -> "Giao diện"
        SettingsSubtab.BACKUP -> "Cập nhật và Sao lưu"
        SettingsSubtab.EXTENSIONS -> "Tiện ích mở rộng"
    }

    private fun renderMobileSettingsHub(h: AppHost, p: ThemePalette, enableAnimation: Boolean) {
        val ctx = requireContext()
        renderMobileSubtabHeader(
            title = "Thiết lập",
            p = p,
            trailingBadge = "v${BuildConfig.VERSION_NAME}",
            onBack = { h.navigateTab(MainTab.HOME) }
        )

        val experienceItems = listOf(
            MobileSettingsMenuItem(
                subtab = SettingsSubtab.PLAYER,
                title = "Trình phát tích hợp",
                iconRes = R.drawable.ic_cs_play,
                accentColor = p.accentFrom
            ),
            MobileSettingsMenuItem(
                subtab = SettingsSubtab.APPEARANCE,
                title = "Giao diện",
                iconRes = if (p.isDark) R.drawable.ic_theme_moon else R.drawable.ic_theme_sun,
                accentColor = Color.parseColor("#E08B2B")
            ),
            MobileSettingsMenuItem(
                subtab = SettingsSubtab.EXTENSIONS,
                title = "Tiện ích mở rộng",
                iconRes = R.drawable.ic_cs_sub_tune,
                accentColor = Color.parseColor("#8B5CF6")
            )
        )

        val systemItems = listOf(
            MobileSettingsMenuItem(
                subtab = SettingsSubtab.SOURCES,
                title = "Nguồn phim (+Web)",
                iconRes = R.drawable.ic_lg_sources,
                accentColor = Color.parseColor("#0EA5E9")
            ),
            MobileSettingsMenuItem(
                subtab = SettingsSubtab.BACKUP,
                title = "Cập nhật và Sao lưu",
                iconRes = R.drawable.ic_cs_replay,
                accentColor = Color.parseColor("#10B981")
            )
        )

        addMobileSectionHeader("TRẢI NGHIỆM & PHÁT", p, topMarginDp = 6f)
        addMobileGroupedMenuCard(experienceItems, p, enableAnimation) { item ->
            h.setSettingsSubtab(item.subtab)
        }

        addMobileSectionHeader("HỆ THỐNG & DỮ LIỆU", p, topMarginDp = 16f)
        addMobileGroupedMenuCard(systemItems, p, enableAnimation) { item ->
            h.setSettingsSubtab(item.subtab)
        }

        val infoNote = mutedText(
            "Chạm vào từng mục để tùy chỉnh Trình phát, Nguồn phim Web, Giao diện Liquid Glass và Cập nhật OTA.",
            p,
            11f
        ).apply {
            setPadding(ThemeManager.dp(ctx, 4f), ThemeManager.dp(ctx, 2f), ThemeManager.dp(ctx, 4f), 0)
            alpha = 0.85f
        }
        binding.settingsSubtabContent.addView(infoNote)
    }

    private fun renderMobileSubtabHeader(
        title: String,
        p: ThemePalette,
        trailingBadge: String? = null,
        onBack: () -> Unit
    ) {
        val ctx = requireContext()
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ThemeManager.dp(ctx, 2f), 0, ThemeManager.dp(ctx, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onBack() }
        }
        val backBtn = FrameLayout(ctx).apply {
            background = ThemeManager.navTabDrawable(ctx, p, active = false, radiusDp = 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "Quay lại"
            setOnClickListener { onBack() }
            val arrow = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_cs_back)
                imageTintList = ThemeManager.tintColorState(p.textMain)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val iconSize = ThemeManager.dp(ctx, 20f)
            addView(arrow, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }
        val backBtnSize = ThemeManager.dp(ctx, 36f)
        headerRow.addView(backBtn, LinearLayout.LayoutParams(backBtnSize, backBtnSize).apply {
            marginEnd = ThemeManager.dp(ctx, 10f)
        })

        val titleView = TextView(ctx).apply {
            text = title
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(p.textMain)
            maxLines = 1
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (!trailingBadge.isNullOrBlank()) {
            val badge = TextView(ctx).apply {
                text = trailingBadge
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(p.textMuted)
                background = ThemeManager.pillDrawable(ctx, p, active = false, radiusDp = 11f)
                setPadding(
                    ThemeManager.dp(ctx, 10f),
                    ThemeManager.dp(ctx, 4f),
                    ThemeManager.dp(ctx, 10f),
                    ThemeManager.dp(ctx, 4f)
                )
            }
            headerRow.addView(badge)
        }
        binding.settingsSubtabContent.addView(headerRow)
    }

    private fun addMobileSectionHeader(label: String, p: ThemePalette, topMarginDp: Float) {
        val ctx = requireContext()
        val tv = TextView(ctx).apply {
            text = label
            textSize = 11.5f
            letterSpacing = 0.06f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(p.textMuted)
            setPadding(ThemeManager.dp(ctx, 4f), 0, ThemeManager.dp(ctx, 4f), 0)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = ThemeManager.dp(ctx, topMarginDp)
            bottomMargin = ThemeManager.dp(ctx, 8f)
        }
        binding.settingsSubtabContent.addView(tv, lp)
    }

    private fun addMobileGroupedMenuCard(
        items: List<MobileSettingsMenuItem>,
        p: ThemePalette,
        enableAnimation: Boolean,
        onItemClick: (MobileSettingsMenuItem) -> Unit
    ) {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val vPad = ThemeManager.dp(ctx, 4f)
            setPadding(0, vPad, 0, vPad)
            background = ThemeManager.glassPanelDrawable(ctx, p, radiusDp = 18f, isCard = false)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ThemeManager.dp(ctx, 6f) }
        }

        items.forEachIndexed { idx, item ->
            if (idx > 0) {
                val divider = View(ctx).apply {
                    setBackgroundColor(p.glassSubtleBorder)
                }
                val divLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = ThemeManager.dp(ctx, 64f)
                    marginEnd = ThemeManager.dp(ctx, 14f)
                }
                card.addView(divider, divLp)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ThemeManager.dp(ctx, 56f)
                setPadding(
                    ThemeManager.dp(ctx, 14f),
                    ThemeManager.dp(ctx, 12f),
                    ThemeManager.dp(ctx, 14f),
                    ThemeManager.dp(ctx, 12f)
                )
                background = ThemeManager.navTabDrawable(ctx, p, active = false, radiusDp = 14f)
                isClickable = true
                isFocusable = true
                setOnClickListener { onItemClick(item) }
            }

            val iconBoxSize = ThemeManager.dp(ctx, 36f)
            val iconBox = FrameLayout(ctx).apply {
                background = ThemeManager.navFeatureIconDrawable(
                    ctx,
                    p,
                    item.accentColor,
                    active = false,
                    radiusDp = 10f
                )
                val iconImg = ImageView(ctx).apply {
                    setImageResource(item.iconRes)
                    imageTintList = ThemeManager.tintColorState(
                        if (p.isDark) Color.argb(242, 245, 245, 250) else item.accentColor
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val innerSize = ThemeManager.dp(ctx, 18f)
                addView(iconImg, FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER))
            }
            row.addView(iconBox, LinearLayout.LayoutParams(iconBoxSize, iconBoxSize))

            val labelTv = TextView(ctx).apply {
                text = item.title
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.NORMAL)
                setTextColor(p.textMain)
                maxLines = 2
            }
            row.addView(
                labelTv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = ThemeManager.dp(ctx, 14f)
                    marginEnd = ThemeManager.dp(ctx, 10f)
                }
            )

            val chevron = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_down)
                rotation = -90f
                imageTintList = ThemeManager.tintColorState(p.textMuted)
                alpha = 0.72f
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val chevronSize = ThemeManager.dp(ctx, 14f)
            row.addView(chevron, LinearLayout.LayoutParams(chevronSize, chevronSize))

            if (enableAnimation) {
                row.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.animate().cancel()
                            iconBox.animate().cancel()
                            v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(80L).start()
                            iconBox.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80L).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().cancel()
                            iconBox.animate().cancel()
                            v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                            iconBox.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                        }
                    }
                    false
                }
            }

            card.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        binding.settingsSubtabContent.addView(card)
    }

    private fun cardContainer(p: ThemePalette, radiusDp: Float = 20f, isCard: Boolean = false): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ThemeManager.dp(context, 16f)
            setPadding(pad, pad, pad, pad)
            background = ThemeManager.glassPanelDrawable(context, p, radiusDp, isCard = isCard)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ThemeManager.dp(context, 12f) }
        }
    }

    private fun headingText(text: String, p: ThemePalette, sizeSp: Float = 15f) = TextView(requireContext()).apply {
        this.text = text
        textSize = sizeSp
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(p.textMain)
    }

    private fun mutedText(text: String, p: ThemePalette, sizeSp: Float = 11.5f) = TextView(requireContext()).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(p.textMuted)
    }

    private fun styledButton(label: String, p: ThemePalette, active: Boolean = false, onClick: () -> Unit): Button {
        return Button(requireContext()).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = ThemeManager.dp(context, 36f)
            setPadding(
                ThemeManager.dp(context, 14f),
                ThemeManager.dp(context, 6f),
                ThemeManager.dp(context, 14f),
                ThemeManager.dp(context, 6f)
            )
            background = if (active) {
                ThemeManager.accentGradientDrawable(context, p, 12f)
            } else {
                ThemeManager.pillDrawable(context, p, false, 12f)
            }
            setTextColor(if (active) Color.WHITE else p.textMain)
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun buttonGrid(
        buttons: List<Button>,
        columns: Int = 2,
        topMarginDp: Float = 10f
    ): LinearLayout {
        val ctx = requireContext()
        val gap = ThemeManager.dp(ctx, 8f)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ThemeManager.dp(ctx, topMarginDp), 0, 0)
            val cols = columns.coerceAtLeast(1)
            buttons.chunked(cols).forEachIndexed { rIdx, rowButtons ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (rIdx > 0) topMargin = gap
                    }
                }
                for (c in 0 until cols) {
                    val btn = rowButtons.getOrNull(c)
                    if (btn != null) {
                        btn.maxLines = 1
                        btn.ellipsize = android.text.TextUtils.TruncateAt.END
                        btn.layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            if (c > 0) marginStart = gap
                        }
                        row.addView(btn)
                    } else {
                        val spacer = View(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, 1f).apply {
                                if (c > 0) marginStart = gap
                            }
                        }
                        row.addView(spacer)
                    }
                }
                addView(row)
            }
        }
    }

    private fun addResponsiveRows(
        container: LinearLayout,
        items: List<View>,
        isWide: Boolean,
        topMarginDp: Float = 8f
    ) {
        val ctx = requireContext()
        val gap = ThemeManager.dp(ctx, 8f)
        val topPx = ThemeManager.dp(ctx, topMarginDp)
        if (!isWide || items.size <= 1) {
            items.forEachIndexed { idx, v ->
                v.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (idx == 0) topPx else gap }
                container.addView(v)
            }
            return
        }
        items.chunked(2).forEachIndexed { rIdx, pair ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (rIdx == 0) topPx else gap }
            }
            pair.forEachIndexed { cIdx, v ->
                v.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    if (cIdx > 0) marginStart = gap
                }
                row.addView(v)
            }
            if (pair.size == 1) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap }
                })
            }
            container.addView(row)
        }
    }

    // =====================================================
    // 1. Trình phát tích hợp
    // =====================================================
    private fun renderPlayerTab(p: ThemePalette) {
        val h = host ?: return
        val st = h.getState()
        val s = st.settings
        val isWide = st.resolvedDevice != ResolvedDeviceProfile.MOBILE
        val box = cardContainer(p)
        box.addView(headingText("Cấu Hình Trình Phát Tích Hợp (Media3 ExoPlayer)", p, 15f))

        val spinners = listOf(
            spinnerRow(
                "Chất lượng mặc định",
                listOf("4K 60FPS", "2K HDR", "1080p Pro", "Auto"),
                s.defaultQuality,
                p
            ) { picked ->
                h.updateSettings { it.copy(defaultQuality = picked) }
                h.showToast("Đã đặt chất lượng mặc định: $picked")
            },
            spinnerRow(
                "Âm thanh / Phụ đề ưu tiên",
                listOf("Thuyết Minh", "Vietsub"),
                s.defaultAudio,
                p
            ) { picked ->
                h.updateSettings { it.copy(defaultAudio = picked) }
                h.showToast("Đã đặt âm thanh ưu tiên: $picked")
            },
            spinnerRow(
                "Bước nhảy tua nhanh",
                listOf("5 giây", "10 giây", "15 giây", "30 giây"),
                "${s.seekStep} giây",
                p
            ) { picked ->
                val v = picked.substringBefore(" ").toIntOrNull() ?: 10
                h.updateSettings { it.copy(seekStep = v) }
                h.showToast("Đã đặt bước tua: $v giây")
            },
            spinnerRow(
                "Tốc độ phát mặc định",
                com.hoathinh3d.donghua.player.PlayerUtils.SPEED_OPTIONS.map { "${it}x" },
                "${s.playbackSpeed}x",
                p
            ) { picked ->
                val v = picked.removeSuffix("x").toFloatOrNull() ?: 1.0f
                h.updateSettings { it.copy(playbackSpeed = v) }
                h.showToast("Đã đặt tốc độ phát mặc định: ${v}x")
            }
        )
        addResponsiveRows(box, spinners, isWide)

        val aspectLabels = listOf(
            "16:9" to "Vừa khung (16:9)",
            "cover" to "Lấp đầy (Cover)",
            "stretch" to "Kéo giãn (Stretch)",
            "4:3" to "Tỷ lệ 4:3"
        )
        val curAspectLabel = aspectLabels.firstOrNull { it.first == s.aspectRatio }?.second ?: "Vừa khung (16:9)"
        box.addView(
            spinnerRow(
                "Tỷ lệ khung hình mặc định",
                aspectLabels.map { it.second },
                curAspectLabel,
                p
            ) { pickedLabel ->
                val wire = aspectLabels.firstOrNull { it.second == pickedLabel }?.first ?: "16:9"
                h.updateSettings { it.copy(aspectRatio = wire) }
                h.showToast("Đã đặt tỷ lệ khung hình: $pickedLabel")
            }
        )

        val switches = listOf(
            switchRow("Tự động bỏ qua Mở đầu (+85s)", "Tự nhảy +85s khi vào đoạn nhạc dạo đầu tập", s.autoSkipIntro, p) { checked ->
                h.updateSettings { it.copy(autoSkipIntro = checked) }
                h.showToast(if (checked) "Đã bật Auto Skip Intro (+85s)" else "Đã tắt Auto Skip Intro")
            },
            switchRow("Tự động chuyển tập tiếp theo", "Đếm ngược 5s khi kết thúc tập", s.autoNext, p) { checked ->
                h.updateSettings { it.copy(autoNext = checked) }
                h.showToast(if (checked) "Đã bật tự động chuyển tập tiếp theo" else "Đã tắt tự động chuyển tập")
            },
            switchRow(
                "🌙 Chế độ Ban Đêm (Night Mode DRC)",
                "Cân bằng âm lượng tiếng nổ lớn & lời thoại nhỏ khi xem đêm",
                s.nightModeDrc,
                p
            ) { checked ->
                h.updateSettings { it.copy(nightModeDrc = checked) }
                h.showToast(if (checked) "Đã bật Chế độ Ban Đêm (DRC)" else "Đã tắt Chế độ Ban Đêm")
            }
        )
        addResponsiveRows(box, switches, isWide)

        binding.settingsSubtabContent.addView(box)
    }

    // =====================================================
    // 4. Nguồn phim (+Web)
    // =====================================================
    private fun renderSourcesTab(p: ThemePalette) {
        val h = host ?: return
        val st = h.getState()

        val formBox = cardContainer(p)
        formBox.addView(headingText("Thêm Nguồn Phim Từ Trang Web Bất Kỳ", p, 15f))
        formBox.addView(mutedText("Nhập URL trang web xem hoạt hình 3D hoặc link M3U8 / Embed / JSON API", p))

        // Quick presets
        val presetRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ThemeManager.dp(context, 8f), 0, ThemeManager.dp(context, 8f))
        }
        presetRow.addView(
            styledButton("🚀 yanhh3d.now (4K)", p, false) {
                draftUrl = "https://yanhh3d.now"
                draftName = "YanHH3D Now"
                draftType = "Laravel Scraper + StreamRPT"
                draftQuality = "4K HDR"
                lastProbeResult = null
                refreshUi()
                h.showToast("Đã điền mẫu nguồn: yanhh3d.now")
            }
        )
        presetRow.addView(
            styledButton("👑 yanhh3d.ee", p, false) {
                draftUrl = "https://yanhh3d.ee"
                draftName = "YanHH3D VIP"
                draftType = "WP Scraper + AJAX"
                draftQuality = "4K 60FPS"
                lastProbeResult = null
                refreshUi()
                h.showToast("Đã điền mẫu nguồn: yanhh3d.ee")
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = ThemeManager.dp(context, 8f) }
            }
        )
        formBox.addView(presetRow)

        val urlInput = EditText(requireContext()).apply {
            hint = "https://ten-trang-web-bat-ky.com/..."
            setText(draftUrl)
            setTextColor(p.textMain)
            setHintTextColor(p.textMuted)
            background = ThemeManager.glassPanelDrawable(context, p, 12f, isCard = true)
            val pad = ThemeManager.dp(context, 10f)
            setPadding(pad, pad, pad, pad)
        }
        val nameInput = EditText(requireContext()).apply {
            hint = "Tên hiển thị nguồn (VD: Web Phim 3D VIP)"
            setText(draftName)
            setTextColor(p.textMain)
            setHintTextColor(p.textMuted)
            background = ThemeManager.glassPanelDrawable(context, p, 12f, isCard = true)
            val pad = ThemeManager.dp(context, 10f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ThemeManager.dp(context, 8f) }
        }
        formBox.addView(urlInput)
        formBox.addView(nameInput)

        formBox.addView(
            spinnerRow(
                "Loại trình phân tích",
                listOf("Smart Scraper", "Laravel Scraper + StreamRPT", "WP Scraper + AJAX", "M3U8 Stream", "JSON / CMS API"),
                draftType,
                p
            ) { picked ->
                draftType = picked
            }
        )
        formBox.addView(
            spinnerRow(
                "Chất lượng gắn nhãn",
                listOf("4K 60FPS", "4K HDR", "1080p"),
                draftQuality,
                p
            ) { picked ->
                draftQuality = picked
            }
        )

        val cbImport = CheckBox(requireContext()).apply {
            text = "Tự động quét & thêm phim từ trang web này vào Kho Phim"
            isChecked = draftImportMovies
            setTextColor(p.textMain)
            setOnCheckedChangeListener { _, isChecked -> draftImportMovies = isChecked }
        }
        val cbPrefer = CheckBox(requireContext()).apply {
            text = "Đặt làm nguồn phát ưu tiên"
            isChecked = draftSetPreferred
            setTextColor(p.textMain)
            setOnCheckedChangeListener { _, isChecked -> draftSetPreferred = isChecked }
        }
        formBox.addView(cbImport)
        formBox.addView(cbPrefer)

        val actionRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ThemeManager.dp(context, 8f), 0, 0)
        }
        actionRow.addView(
            styledButton(if (isProbingSource) "⏳ Đang quét..." else "📡 Quét & Kiểm tra", p, false) {
                if (isProbingSource) return@styledButton
                draftUrl = urlInput.text.toString()
                draftName = nameInput.text.toString()
                val normalized = CustomSourceParser.normalizeUrl(draftUrl)
                if (normalized.isBlank()) {
                    h.showToast("Vui lòng nhập URL trang web trước khi quét!")
                    return@styledButton
                }
                isProbingSource = true
                refreshUi()
                viewLifecycleOwner.lifecycleScope.launch {
                    val res = CustomSourceParser.probeCustomSource(normalized, draftType, draftQuality)
                    isProbingSource = false
                    lastProbeResult = res
                    if (res.ok) {
                        h.showToast("Quét thành công ${res.hostname} (${res.pingMs}ms)!")
                    } else {
                        h.showToast("Không kết nối được tới ${res.hostname}: ${res.note}")
                    }
                    refreshUi()
                }
            }
        )
        actionRow.addView(
            styledButton(if (isAddingSource) "⏳ Đang nạp..." else "➕ Thêm Nguồn Web", p, true) {
                if (isAddingSource) return@styledButton
                draftUrl = urlInput.text.toString()
                draftName = nameInput.text.toString()
                val normalized = CustomSourceParser.normalizeUrl(draftUrl)
                if (normalized.isBlank()) {
                    h.showToast("Vui lòng nhập URL của trang web nguồn phim!")
                    return@styledButton
                }
                val hostName = CustomSourceParser.extractHost(normalized)
                if (hostName.isBlank()) {
                    h.showToast("URL không hợp lệ! Hãy nhập dạng https://ten-web.com")
                    return@styledButton
                }
                val displayName = draftName.trim().ifEmpty { hostName.substringBefore('.').uppercase() }
                val idSlug = "Web_${displayName.replace(Regex("[^a-zA-Z0-9]"), "").take(12)}_${(100..999).random()}"
                val newSrc = DonghuaSource(
                    id = idSlug,
                    name = displayName,
                    url = normalized,
                    hostname = hostName,
                    type = lastProbeResult?.detectedType ?: draftType,
                    ping = if (lastProbeResult?.hostname == hostName && lastProbeResult!!.pingMs > 0) {
                        "${lastProbeResult!!.pingMs}ms"
                    } else {
                        "..."
                    },
                    quality = draftQuality,
                    desc = "Nguồn Web tùy chỉnh ($hostName • $draftType)",
                    enabled = true,
                    isCustom = true,
                    addedAt = System.currentTimeMillis()
                )
                isAddingSource = true
                refreshUi()
                viewLifecycleOwner.lifecycleScope.launch {
                    val measuredPing = HttpClientProvider.measureLatencyMs(normalized)
                    val finalizedSrc = newSrc.copy(ping = if (measuredPing > 0) "${measuredPing}ms" else "Timeout")
                    PrefsStore.addCustomSource(finalizedSrc)

                    var importedCount = 0
                    if (draftImportMovies) {
                        val scraped = runCatching { CustomSourceParser.fetchFromCustomSource(finalizedSrc) }
                            .getOrDefault(emptyList())
                        if (scraped.isNotEmpty()) {
                            PrefsStore.addCustomMovies(scraped)
                            importedCount = scraped.size
                            h.refreshCustomMovies()
                        } else {
                            val directGrp = runCatching {
                                CustomSourceParser.extractDirectStreamsFromWebUrl(normalized, displayName)
                            }.getOrNull()
                            if (directGrp != null && directGrp.episodes.any { it.linkM3u8.isNotBlank() || it.linkEmbed.isNotBlank() }) {
                                val singleMovie = DonghuaMovie(
                                    id = "custom_dh_${System.currentTimeMillis()}",
                                    slug = idSlug.lowercase(Locale.ROOT),
                                    title = displayName,
                                    originalTitle = "Luồng trực tiếp từ $hostName",
                                    studio = displayName,
                                    latestEp = 1,
                                    totalEp = 1,
                                    rating = 9.6,
                                    views = "Custom",
                                    viewsNum = 1.0,
                                    quality = draftQuality,
                                    audioModes = listOf("Vietsub", "Thuyết Minh"),
                                    sources = listOf(idSlug),
                                    genres = listOf("Tu Tiên"),
                                    scheduleDay = "Hàng ngày",
                                    scheduleTime = "10:00",
                                    poster = "",
                                    streamIndex = 0,
                                    serverGroups = listOf(directGrp),
                                    customWebUrl = normalized
                                )
                                PrefsStore.addCustomMovie(singleMovie)
                                importedCount = 1
                                h.refreshCustomMovies()
                            }
                        }
                    }

                    isAddingSource = false
                    if (draftSetPreferred) h.selectSource(idSlug)
                    h.refreshSources()
                    draftUrl = ""
                    draftName = ""
                    lastProbeResult = null
                    val msg = if (importedCount > 0) {
                        "Đã thêm \"$displayName\" & quét được $importedCount phim thật!"
                    } else {
                        "Đã thêm nguồn web \"$displayName\" ($hostName)!"
                    }
                    h.showToast(msg)
                    refreshUi()
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = ThemeManager.dp(context, 8f) }
            }
        )
        formBox.addView(actionRow)

        lastProbeResult?.let { pr ->
            formBox.addView(
                mutedText(
                    if (pr.ok) "✓ Kết nối: ${pr.hostname} • Ping: ${pr.pingMs}ms • ${pr.detectedType} • ${pr.note}"
                    else "⚠ Lỗi kết nối ${pr.hostname}: ${pr.note}",
                    p
                ).apply {
                    setTextColor(Color.parseColor(if (pr.ok) "#10B981" else "#F43F5E"))
                    setPadding(0, ThemeManager.dp(context, 8f), 0, 0)
                }
            )
        }
        binding.settingsSubtabContent.addView(formBox)

        // Existing Sources List
        val listBox = cardContainer(p)
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            headingText("Danh Sách Nguồn Phim (${st.sources.size})", p, 15f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        headerRow.addView(
            styledButton(if (isMeasuringPings) "⏳ Đang đo..." else "⚡ Đo Ping Tất Cả", p, false) {
                if (isMeasuringPings) return@styledButton
                isMeasuringPings = true
                refreshUi()
                viewLifecycleOwner.lifecycleScope.launch {
                    val updated = st.sources.map { src ->
                        async {
                            val ms = HttpClientProvider.measureLatencyMs(src.url)
                            src.copy(ping = if (ms > 0) "${ms}ms" else "Lỗi")
                        }
                    }.awaitAll()
                    isMeasuringPings = false
                    PrefsStore.saveSources(updated)
                    h.refreshSources()
                    h.showToast("Đã cập nhật độ trễ (Ping) cho toàn bộ máy chủ!")
                }
            }
        )
        listBox.addView(headerRow)

        for (src in st.sources) {
            val isPreferred = st.settings.preferredSource == src.id
            val item = cardContainer(p, 14f, isCard = true)
            val srcHeader = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val srcTitleCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            srcTitleCol.addView(headingText("${src.name} (${src.ping}) • ${src.quality}", p, 13.5f))
            srcTitleCol.addView(mutedText(src.url.ifBlank { src.desc }, p, 11f))
            val sw = SwitchCompat(requireContext()).apply {
                tag = "src_sw_${src.id}"
                isChecked = src.enabled
                text = if (src.enabled) "Bật" else "Tắt"
                setTextColor(p.textMain)
                isFocusable = true
                setOnCheckedChangeListener { _, _ ->
                    pendingFocusTag = "src_sw_${src.id}"
                    val res = PrefsStore.toggleSourceEnabled(src.id)
                    if (!res.ok && res.reason != null) {
                        h.showToast(res.reason)
                        refreshUi()
                    } else {
                        h.refreshSources()
                        h.showToast("${if (!src.enabled) "Đã bật" else "Đã tắt"} nguồn ${src.name}")
                    }
                }
            }
            srcHeader.addView(srcTitleCol)
            srcHeader.addView(sw)
            item.addView(srcHeader)

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, ThemeManager.dp(context, 8f), 0, 0)
            }
            row.addView(
                styledButton(if (isPreferred) "Đang ưu tiên ✓" else "Đặt ưu tiên", p, isPreferred) {
                    pendingFocusTag = "src_pref_${src.id}"
                    h.selectSource(src.id)
                }.apply { tag = "src_pref_${src.id}" }
            )
            if (src.isCustom) {
                row.addView(
                    styledButton("🗑 Xóa Web", p, false) {
                        PrefsStore.deleteSource(src.id)
                        h.refreshSources()
                        h.showToast("Đã xóa nguồn web: ${src.name}")
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = ThemeManager.dp(context, 8f) }
                    }
                )
            }
            item.addView(row)
            listBox.addView(item)
        }
        binding.settingsSubtabContent.addView(listBox)
    }

    // =====================================================
    // 3. Giao diện (4 Devices + Sáng/Tối + 3 Tông màu dịu nhẹ)
    // =====================================================
    private fun renderAppearanceTab(p: ThemePalette) {
        val h = host ?: return
        val st = h.getState()
        val isWide = st.resolvedDevice != ResolvedDeviceProfile.MOBILE

        val devBox = cardContainer(p)
        devBox.addView(headingText("Lựa Chọn Thiết Bị Phù Hợp", p, 15f))
        devBox.addView(
            mutedText(
                "Tự nhận diện hiện tại: ${DeviceProfileResolver.physicalDeviceLabel(requireContext())}",
                p
            )
        )
        val devModes = listOf(
            "auto" to "✨ Tự động (Auto)",
            "mobile" to "📱 Điện thoại (3 Cột)",
            "tablet" to "📲 Tablet / PC (4 Cột)",
            "tv" to "📺 Smart TV (5 Cột)"
        )
        val devButtons = devModes.map { (mode, label) ->
            val active = st.settings.deviceMode == mode
            val tagStr = "dev_$mode"
            styledButton(if (active) "$label ✓" else label, p, active) {
                pendingFocusTag = tagStr
                h.updateSettings { it.copy(deviceMode = mode) }
                h.showToast("Đã chuyển chế độ thiết bị: $label")
            }.apply { tag = tagStr }
        }
        devBox.addView(buttonGrid(devButtons, columns = if (isWide) 2 else 1, topMarginDp = 10f))
        binding.settingsSubtabContent.addView(devBox)

        val themeBox = cardContainer(p)
        themeBox.addView(headingText("Chế Độ Sáng / Tối & Tông Màu Liquid Glass", p, 15f))
        themeBox.addView(
            mutedText(
                "Chọn nền Sáng thanh thoát hoặc Chế độ Tối dịu mắt (kính trong suốt 52% • mờ nền 32%).",
                p
            )
        )

        val darkModeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, ThemeManager.dp(context, 10f), 0, ThemeManager.dp(context, 4f))
        }
        for ((idx, dm) in DarkModePreference.entries.withIndex()) {
            val active = st.settings.darkMode == dm.wireValue
            val tagStr = "darkmode_${dm.wireValue}"
            darkModeRow.addView(
                styledButton(if (active) "${dm.label} ✓" else dm.label, p, active) {
                    pendingFocusTag = tagStr
                    h.updateSettings { cur ->
                        val nextTheme = if (dm == DarkModePreference.LIGHT && cur.theme == ThemePreset.DARK.wireValue) {
                            ThemePreset.PEARL.wireValue
                        } else {
                            cur.theme
                        }
                        cur.copy(darkMode = dm.wireValue, theme = nextTheme)
                    }
                    h.showToast("Chế độ hiển thị: ${dm.label}")
                }.apply {
                    tag = tagStr
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (idx > 0) marginStart = ThemeManager.dp(context, 8f)
                    }
                }
            )
        }
        themeBox.addView(darkModeRow)

        themeBox.addView(
            headingText("Tông Màu Chủ Đề Liquid Glass (3 Chủ Đề Dịu Nhẹ)", p, 13.5f).apply {
                setPadding(0, ThemeManager.dp(context, 12f), 0, ThemeManager.dp(context, 2f))
            }
        )
        val themeButtons = ThemePreset.entries.map { preset ->
            val active = st.settings.theme == preset.wireValue
            val tagStr = "theme_${preset.wireValue}"
            val darkTag = if (p.isDark && preset != ThemePreset.DARK) " • Tối" else ""
            styledButton(
                if (active) "${preset.displayName}$darkTag ✓" else "${preset.displayName} — ${preset.desc}",
                p,
                active
            ) {
                pendingFocusTag = tagStr
                h.updateSettings { cur ->
                    val nextDarkMode = if (preset == ThemePreset.DARK) {
                        DarkModePreference.DARK.wireValue
                    } else {
                        cur.darkMode
                    }
                    cur.copy(theme = preset.wireValue, darkMode = nextDarkMode)
                }
                h.showToast("Đã áp dụng tông màu: ${preset.displayName}")
            }.apply { tag = tagStr }
        }
        themeBox.addView(buttonGrid(themeButtons, columns = if (isWide) 3 else 1, topMarginDp = 8f))

        val isLowRam = DeviceProfileResolver.isLowRamDevice(requireContext())
        val liteHint = if (isLowRam) {
            "Tự bật trên TV Box RAM <= 2.5 GB để cuộn mượt"
        } else {
            "Giảm tải hiệu ứng thu phóng & hoạt ảnh trên máy cấu hình thấp"
        }
        themeBox.addView(
            switchRow("🚀 Siêu tối ưu TV Box (Reduced Motion)", liteHint, st.settings.reducedMotion || isLowRam, p) { checked ->
                h.updateSettings { it.copy(reducedMotion = checked) }
                h.showToast(if (checked) "Đã bật chế độ Siêu tối ưu Reduced Motion" else "Đã tắt Reduced Motion")
            }
        )
        binding.settingsSubtabContent.addView(themeBox)
    }

    // =====================================================
    // 6. Cập nhật & Sao lưu
    // =====================================================
    private fun renderBackupTab(p: ThemePalette) {
        val h = host ?: return
        val st = h.getState()
        val cacheBytes = HlsCacheProvider.getCacheSizeBytes(requireContext())
        val offlineBytes = st.offlineEpisodes.values.sumOf { it.approxBytes }
        val cacheMb = String.format(Locale.US, "%.1f MB", cacheBytes / (1024.0 * 1024.0))
        val offlineMb = String.format(Locale.US, "%.1f MB", offlineBytes / (1024.0 * 1024.0))

        val isWide = st.resolvedDevice != ResolvedDeviceProfile.MOBILE
        val infoBox = cardContainer(p)
        infoBox.addView(headingText("Thông Tin Gói APK Native (v${BuildConfig.VERSION_NAME})", p, 15f))
        infoBox.addView(
            mutedText(
                "• Package: ${BuildConfig.APPLICATION_ID} (build ${BuildConfig.VERSION_CODE}) • Min SDK 23 → Target SDK 35\n" +
                    "• Media3 ExoPlayer 1.5.1 (ClosestSync + SimpleCache) • Bộ nhớ đệm HLS: $cacheMb • Offline: ${st.offlineEpisodes.size} tập ($offlineMb)",
                p,
                12f
            )
        )
        val diagResultTv = mutedText("Nhấn kiểm tra để đo kết nối trực tiếp tới các nguồn phim hiện tại", p, 11f).apply {
            setPadding(0, ThemeManager.dp(context, 8f), 0, 0)
        }
        val diagButtons = listOf(
            styledButton("🔄 Kiểm tra hệ thống", p, true) {
                diagResultTv.text = "⏳ Đang kiểm tra kết nối các nguồn phim..."
                viewLifecycleOwner.lifecycleScope.launch {
                    val enabled = st.sources.filter { it.enabled }
                    val results = enabled.map { src ->
                        async {
                            val ms = HttpClientProvider.measureLatencyMs(src.url)
                            src.name to ms
                        }
                    }.awaitAll()
                    val summary = results.joinToString(" • ") { (name, ms) ->
                        if (ms > 0) "$name: ${ms}ms" else "$name: Lỗi"
                    }
                    diagResultTv.text = "✓ v${BuildConfig.VERSION_NAME} sẵn sàng • $summary"
                    diagResultTv.setTextColor(Color.parseColor("#10B981"))
                    h.showToast("Kiểm tra hoàn tất: $summary")
                }
            },
            styledButton("🗑 Dọn Cache ($cacheMb)", p, false) {
                val ok = HlsCacheProvider.clearCache(requireContext())
                h.showToast(if (ok) "Đã dọn sạch bộ nhớ đệm HLS!" else "Không thể dọn cache")
                refreshUi()
            },
            styledButton("⬆ Kiểm tra cập nhật", p, true) {
                h.checkAppUpdate(manual = true)
            }
        )
        infoBox.addView(buttonGrid(diagButtons, columns = if (isWide) 3 else 2, topMarginDp = 10f))
        infoBox.addView(diagResultTv)
        val updateSub = "Tự kiểm tra & tải phiên bản mới nhất trên GitHub khi mở app"
        addResponsiveRows(
            infoBox,
            listOf(
                switchRow(
                    "Auto xóa Cache khi thoát App",
                    "Giải phóng bộ nhớ đệm HLS & ảnh Glide khi đóng app",
                    st.settings.autoClearCacheOnExit,
                    p
                ) { checked ->
                    h.updateSettings { it.copy(autoClearCacheOnExit = checked) }
                    h.showToast(
                        if (checked) "Đã bật tự xóa Cache khi thoát App"
                        else "Đã tắt tự xóa Cache khi thoát App"
                    )
                },
                switchRow(
                    "Auto Update App khi có bản mới",
                    updateSub,
                    st.settings.autoUpdateApp,
                    p
                ) { checked ->
                    h.updateSettings { it.copy(autoUpdateApp = checked) }
                    h.showToast(
                        if (checked) "Đã bật Auto Update App khi có phiên bản mới"
                        else "Đã tắt Auto Update App"
                    )
                }
            ),
            isWide
        )
        binding.settingsSubtabContent.addView(infoBox)

        val backupBox = cardContainer(p)
        backupBox.addView(headingText("Sao Lưu, Khôi Phục & Đặt Lại", p, 15f))
        backupBox.addView(mutedText("Bao gồm Lịch sử xem (${st.watchHistory.size}), Yêu thích (${st.favorites.size}), Cài đặt & Nguồn Web tự thêm", p))
        val backupButtons = listOf(
            styledButton("📤 Xuất JSON", p, true) {
                exportJsonLauncher.launch("hoathinh3d_backup.json")
            },
            styledButton("📥 Nhập JSON", p, false) {
                importJsonLauncher.launch(arrayOf("application/json", "*/*"))
            },
            styledButton("↺ Mặc định", p, false) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Đặt lại cài đặt mặc định?")
                    .setMessage("Khôi phục giao diện, trình phát và tiện ích về mặc định (giữ nguyên Lịch sử và Yêu thích).")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Đặt lại") { _, _ ->
                        PrefsStore.resetSettingsToDefaults()
                        h.reloadAllPersistedState()
                        h.showToast("Đã khôi phục cài đặt mặc định!")
                    }
                    .show()
            }
        )
        backupBox.addView(buttonGrid(backupButtons, columns = if (isWide) 3 else 2, topMarginDp = 10f))

        val syncBox = cardContainer(p)
        syncBox.addView(headingText("Đồng Bộ Nhanh Điện Thoại ↔ Android TV", p, 15f))
        syncBox.addView(
            mutedText(
                "Chuyển toàn bộ Lịch sử xem, Tập đã xem, Yêu thích & Cài đặt qua Wi-Fi gia đình (LAN) hoặc Mã đồng bộ gọn nhẹ trong 1 giây.",
                p
            )
        )
        val syncButtons = listOf(
            styledButton("📡 Phát đồng bộ LAN", p, true) {
                val session = LanSyncManager.startLanSyncServer()
                if (session == null) {
                    h.showToast("Hãy kết nối Wi-Fi để bật phát đồng bộ LAN!")
                    return@styledButton
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Đang phát đồng bộ qua Wi-Fi LAN")
                    .setMessage(
                        "Trên thiết bị thứ 2 (Android TV / Điện thoại cùng Wi-Fi), vào Cài đặt → Cập nhật & Sao lưu → '📲 Nhận đồng bộ LAN' và nhập:\n\n" +
                            "• Địa chỉ: ${session.url.substringBefore("/sync").removePrefix("http://")}\n" +
                            "• Mã PIN: ${session.pin}\n\n" +
                            "(Giữ hộp thoại này mở cho đến khi TV báo nhận thành công)"
                    )
                    .setPositiveButton("Dừng phát") { _, _ ->
                        LanSyncManager.stopLanSyncServer()
                        h.showToast("Đã tắt máy chủ đồng bộ LAN")
                    }
                    .setOnDismissListener { LanSyncManager.stopLanSyncServer() }
                    .show()
            },
            styledButton("📲 Nhận đồng bộ LAN", p, false) {
                val box = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = ThemeManager.dp(context, 16f)
                    setPadding(pad, pad, pad, pad)
                }
                val peersTitle = mutedText("🔍 Đang tự tìm thiết bị đang phát trên Wi-Fi LAN...", p, 12f)
                val peersContainer = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, ThemeManager.dp(context, 6f), 0, ThemeManager.dp(context, 8f))
                }
                val hostInput = EditText(requireContext()).apply {
                    hint = "IP:Cổng (VD: 192.168.1.15:8765)"
                }
                val pinInput = EditText(requireContext()).apply {
                    hint = "Mã PIN 4 số (VD: 4821)"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                box.addView(peersTitle)
                box.addView(peersContainer)
                box.addView(hostInput)
                box.addView(pinInput)
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle("Nhận dữ liệu từ thiết bị LAN")
                    .setView(box)
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Đồng bộ ngay") { _, _ ->
                        val addr = hostInput.text.toString()
                        val pin = pinInput.text.toString()
                        viewLifecycleOwner.lifecycleScope.launch {
                            val ok = LanSyncManager.pullAndImportFromLan(addr, pin)
                            if (ok) {
                                h.reloadAllPersistedState()
                                h.showToast("Đồng bộ LAN thành công!")
                                refreshUi()
                            } else {
                                h.showToast("Không kết nối được hoặc sai mã PIN!")
                            }
                        }
                    }
                    .show()
                val scanJob = viewLifecycleOwner.lifecycleScope.launch {
                    val peers = LanSyncManager.discoverLanPeers(2200L)
                    if (!dialog.isShowing) return@launch
                    peersContainer.removeAllViews()
                    if (peers.isEmpty()) {
                        peersTitle.text = "Không thấy thiết bị tự phát. Nhập thủ công IP:Cổng & PIN bên dưới:"
                    } else {
                        peersTitle.text = "Chạm để chọn thiết bị LAN (${peers.size}):"
                        for (peer in peers) {
                            val addr = "${peer.ip}:${peer.port}"
                            peersContainer.addView(
                                styledButton("📱 ${peer.deviceName} • $addr", p, true) {
                                    hostInput.setText(addr)
                                    pinInput.requestFocus()
                                }.apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply { topMargin = ThemeManager.dp(context, 4f) }
                                }
                            )
                        }
                    }
                }
                dialog.setOnDismissListener { scanJob.cancel() }
            },
            styledButton("🔑 Tạo & Copy Mã", p, false) {
                val code = PrefsStore.exportCompactSyncCode()
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("HH3D Sync Code", code))
                h.showToast("Đã sao chép Mã Đồng Bộ (${code.length} ký tự)!")
            },
            styledButton("📋 Nhập Mã Đồng Bộ", p, false) {
                val input = EditText(requireContext()).apply {
                    hint = "Dán mã HH3D1:... vào đây"
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Nhập Mã Đồng Bộ")
                    .setView(input)
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Khôi phục") { _, _ ->
                        val ok = PrefsStore.importCompactSyncCode(input.text.toString())
                        if (ok) {
                            h.reloadAllPersistedState()
                            h.showToast("Đã nhập dữ liệu từ Mã Đồng Bộ!")
                            refreshUi()
                        } else {
                            h.showToast("Mã đồng bộ không hợp lệ!")
                        }
                    }
                    .show()
            }
        )
        syncBox.addView(buttonGrid(syncButtons, columns = if (isWide) 4 else 2, topMarginDp = 10f))

        val emailSyncCard = cardContainer(p, 14f, isCard = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ThemeManager.dp(context, 10f) }
        }
        val user = st.user
        val snapStats = PrefsStore.getCloudSnapshotStats()
        val lastTs = user?.lastSync ?: snapStats?.timestamp?.takeIf { it > 0L }
        val syncStr = if (lastTs != null) DateFormat.format("HH:mm dd/MM", Date(lastTs)).toString() else "Chưa sao lưu"
        val snapDesc = if (snapStats != null) {
            "Bản lưu gần nhất ($syncStr): ${snapStats.historyCount} lịch sử • ${snapStats.favoritesCount} yêu thích • ${snapStats.customSourcesCount} web"
        } else {
            "Chưa có bản sao lưu Email nào"
        }
        emailSyncCard.addView(headingText("📧 Đồng Bộ Qua Tài Khoản Email", p, 14f))
        emailSyncCard.addView(
            mutedText(
                if (user != null) {
                    "${user.name} (${user.email})\n$snapDesc"
                } else {
                    "Liên kết Email để sao lưu, gửi mã đồng bộ qua Email & khôi phục dữ liệu giữa Điện thoại và TV"
                },
                p,
                11f
            )
        )
        val emailButtons = if (user != null) {
            listOf(
                styledButton("☁ Sao lưu Email", p, true) {
                    syncCloudNow()
                    refreshUi()
                },
                styledButton("✉ Gửi mã qua Email", p, false) {
                    val code = PrefsStore.exportCompactSyncCode()
                    val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("HH3D Email Sync Code", code))
                    val mailIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:${android.net.Uri.encode(user.email)}")
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "[HoatHinh3D] Mã đồng bộ dữ liệu (${user.email})")
                        putExtra(
                            android.content.Intent.EXTRA_TEXT,
                            "Tài khoản: ${user.name} (${user.email})\n" +
                                "Dữ liệu: ${st.watchHistory.size} lịch sử, ${st.favorites.size} yêu thích.\n" +
                                "Dán mã sau vào HoatHinh3D -> Cài đặt -> 4. Cập nhật & Sao lưu -> Nhập Mã Đồng Bộ:\n\n$code"
                        )
                    }
                    runCatching { startActivity(mailIntent) }
                        .onFailure {
                            h.showToast("Đã copy mã đồng bộ cho ${user.email} (${code.length} ký tự)!")
                        }
                },
                styledButton("📥 Khôi phục", p, false) {
                    val ok = PrefsStore.restoreCloudSnapshot()
                    if (!ok) {
                        h.showToast("Chưa có bản sao lưu Email nào trên máy!")
                    } else {
                        h.reloadAllPersistedState()
                        h.showToast("Đã khôi phục dữ liệu đồng bộ cho ${user.email}!")
                        refreshUi()
                    }
                },
                styledButton("✏ Đổi Email", p, false) { h.openGoogleLoginDialog() },
                styledButton("Ngắt liên kết", p, false) {
                    h.updateUser(null)
                    h.showToast("Đã ngắt liên kết Email đồng bộ")
                }
            )
        } else {
            buildList {
                add(styledButton("📧 Liên kết Email đồng bộ", p, true) { h.openGoogleLoginDialog() })
                if (snapStats != null) {
                    add(
                        styledButton("📥 Khôi phục bản lưu", p, false) {
                            if (PrefsStore.restoreCloudSnapshot()) {
                                h.reloadAllPersistedState()
                                h.showToast("Đã khôi phục bản sao lưu!")
                                refreshUi()
                            }
                        }
                    )
                }
            }
        }
        emailSyncCard.addView(buttonGrid(emailButtons, columns = if (isWide) 3 else 2, topMarginDp = 10f))
        emailSyncCard.addView(
            switchRow(
                "Tự động sao lưu qua Email",
                "Tự lưu snapshot mỗi khi đổi cài đặt, yêu thích hoặc nguồn phim",
                st.settings.cloudAutoSync,
                p
            ) { checked ->
                h.updateSettings { it.copy(cloudAutoSync = checked) }
                h.showToast(if (checked) "Đã bật tự động sao lưu Email" else "Đã tắt tự động sao lưu Email")
            }
        )
        syncBox.addView(emailSyncCard)

        binding.settingsSubtabContent.addView(backupBox)
        binding.settingsSubtabContent.addView(syncBox)
    }

    // =====================================================
    // 5. Tiện ích mở rộng & Sleep Timer
    // =====================================================
    private fun renderExtensionsTab(p: ThemePalette) {
        val h = host ?: return
        val st = h.getState()
        val ext = st.settings.extensions
        val isWide = st.resolvedDevice != ResolvedDeviceProfile.MOBILE
        val box = cardContainer(p)
        box.addView(headingText("Kho Tiện Ích Mở Rộng (Plugins)", p, 15f))

        val hdrSub = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Tăng cường tương phản & bão hòa màu RenderEffect trên ExoPlayer (Android 12+)"
        } else {
            "Tăng cường độ nét rạp phim qua tương phản viền (RenderEffect đầy đủ từ Android 12+)"
        }
        addResponsiveRows(
            box,
            listOf(
                switchRow("AdBlock Shield Pro (Chặn Popup)", "Lọc sạch banner, tracker & chuyển hướng khi trích xuất luồng", ext.adblock, p) { c ->
                    h.updateSettings { it.copy(extensions = it.extensions.copy(adblock = c)) }
                    h.showToast(if (c) "Đã kích hoạt AdBlock Shield Pro" else "Đã tắt AdBlock Shield Pro")
                },
                switchRow("AI HDR Upscale (Tăng Nét 3D)", hdrSub, ext.aiUpscale, p) { c ->
                    h.updateSettings { it.copy(extensions = it.extensions.copy(aiUpscale = c)) }
                    h.showToast(if (c) "Đã kích hoạt AI HDR Upscale trên trình phát" else "Đã tắt AI HDR Upscale")
                },
                switchRow("Phụ Đề Song Ngữ Việt - Trung", "Bật track phụ đề nhúng & hiển thị thanh song ngữ", ext.bilingualSub, p) { c ->
                    h.updateSettings { it.copy(bilingualSub = c, extensions = it.extensions.copy(bilingualSub = c)) }
                    h.showToast(if (c) "Đã bật phụ đề song ngữ Việt - Trung" else "Đã tắt phụ đề song ngữ")
                }
            ),
            isWide
        )
        binding.settingsSubtabContent.addView(box)
    }

    private fun switchRow(
        title: String,
        sub: String,
        checked: Boolean,
        p: ThemePalette,
        onToggle: (Boolean) -> Unit
    ): View {
        val rowTag = "sw_${title.hashCode()}"
        val row = cardContainer(p, 14f, isCard = true).apply {
            tag = rowTag
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ThemeManager.dp(context, 8f) }
        }
        val textCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(headingText(title, p, 13f))
        textCol.addView(mutedText(sub, p, 11f))
        val sw = SwitchCompat(requireContext()).apply {
            isChecked = checked
            isFocusable = false
            isFocusableInTouchMode = false
            setOnCheckedChangeListener { _, isChecked ->
                pendingFocusTag = rowTag
                onToggle(isChecked)
            }
        }
        row.setOnClickListener { sw.toggle() }
        row.addView(textCol)
        row.addView(sw)
        return row
    }

    private fun spinnerRow(
        title: String,
        options: List<String>,
        currentValue: String,
        p: ThemePalette,
        onSelected: (String) -> Unit
    ): View {
        val rowTag = "sp_${title.hashCode()}"
        val row = cardContainer(p, 14f, isCard = true).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ThemeManager.dp(context, 8f) }
        }
        row.addView(
            headingText(title, p, 12.5f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, ThemeManager.dp(context, 8f), 0)
            }
        )
        val spinner = Spinner(requireContext()).apply {
            tag = rowTag
            isFocusable = true
            background = ThemeManager.pillDrawable(context, p, active = false, radiusDp = 10f)
            setPadding(
                ThemeManager.dp(context, 10f),
                ThemeManager.dp(context, 6f),
                ThemeManager.dp(context, 10f),
                ThemeManager.dp(context, 6f)
            )
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, options) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(p.textMain)
                    v.textSize = 12f
                    return v
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent) as TextView
                    v.setTextColor(p.textMain)
                    v.setBackgroundColor(if (p.isDark) Color.parseColor("#111827") else Color.WHITE)
                    val padH = ThemeManager.dp(context, 14f)
                    val padV = ThemeManager.dp(context, 10f)
                    v.setPadding(padH, padV, padH, padV)
                    return v
                }
            }
            val sel = options.indexOfFirst { it == currentValue }.coerceAtLeast(0)
            setSelection(sel, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                var initialized = false
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!initialized) {
                        initialized = true
                        return
                    }
                    pendingFocusTag = rowTag
                    onSelected(options[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.addView(spinner)
        return row
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
