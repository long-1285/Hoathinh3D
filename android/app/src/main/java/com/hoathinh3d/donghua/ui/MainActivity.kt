package com.hoathinh3d.donghua.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hoathinh3d.donghua.BuildConfig
import com.hoathinh3d.donghua.R
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.LanSyncManager
import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.core.ThemePalette
import com.hoathinh3d.donghua.data.model.AppSettings
import com.hoathinh3d.donghua.data.model.CatalogAudioFilter
import com.hoathinh3d.donghua.data.model.CatalogSortMode
import com.hoathinh3d.donghua.data.model.CatalogStatusFilter
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.DonghuaSource
import com.hoathinh3d.donghua.data.model.FavoriteEpisodeMeta
import com.hoathinh3d.donghua.data.model.GenreItem
import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.model.OfflineEpisodeRecord
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile
import com.hoathinh3d.donghua.data.model.ServerGroup
import com.hoathinh3d.donghua.data.model.SettingsSubtab
import com.hoathinh3d.donghua.data.model.UserProfile
import com.hoathinh3d.donghua.data.model.WatchProgress
import com.hoathinh3d.donghua.data.offline.HlsOfflineManager
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.data.source.VietnameseSearch
import com.hoathinh3d.donghua.databinding.ActivityMainBinding
import com.hoathinh3d.donghua.player.PlayerActivity
import com.hoathinh3d.donghua.ui.detail.MovieDetailActivity
import com.hoathinh3d.donghua.ui.favorites.FavoritesFragment
import com.hoathinh3d.donghua.ui.history.HistoryFragment
import com.hoathinh3d.donghua.ui.home.HomeFragment
import com.hoathinh3d.donghua.ui.schedule.ScheduleFragment
import com.hoathinh3d.donghua.ui.settings.SettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab { HOME, SCHEDULE, HISTORY, FAVORITES, SETTINGS }

interface AppHost {
    fun getState(): MainState
    fun getPalette(): ThemePalette
    fun getChromeInsets(): Pair<Int, Int>
    fun onMainContentScroll(dy: Int, scrollY: Int)
    fun selectGenre(genreId: String)
    fun openGenrePickerOverlay()
    fun syncWebGenresNow()
    fun setCatalogStatusFilter(filter: CatalogStatusFilter)
    fun setCatalogAudioFilter(filter: CatalogAudioFilter)
    fun setCatalogSortMode(mode: CatalogSortMode)
    fun resetCatalogFilters()
    fun setSearchSourceFilter(sourceName: String?)
    fun loadMoreCatalog()
    fun selectSource(sourceId: String)
    fun openMovieDetail(movie: DonghuaMovie)
    fun quickPlayMovie(movie: DonghuaMovie, episode: Int = 1, serverIdx: Int = -1, resumeTimeSec: Double = -1.0)
    fun resolveMovieForProgress(progress: WatchProgress): DonghuaMovie
    fun toggleFavorite(movieId: String)
    fun checkFavoritesNewEpisodes()
    fun playOfflineRecord(record: OfflineEpisodeRecord)
    fun removeOfflineRecord(key: String)
    fun removeHistoryItem(donghuaId: String)
    fun clearHistory()
    fun clearSearch()
    fun updateSettings(transform: (AppSettings) -> AppSettings)
    fun refreshSources()
    fun refreshCustomMovies()
    fun reloadAllPersistedState()
    fun reloadActiveSourceFeed()
    fun updateUser(user: UserProfile?)
    fun openGoogleLoginDialog()
    fun checkAppUpdate(manual: Boolean = false)
    fun navigateTab(tab: MainTab)
    fun setSettingsSubtab(subtab: SettingsSubtab)
    fun closeSettingsDetail()
    fun openSamplePlayer()
    fun showToast(msg: String)
}

data class MainState(
    val activeTab: MainTab = MainTab.HOME,
    val settingsSubtab: SettingsSubtab = SettingsSubtab.PLAYER,
    val isSettingsDetailOpen: Boolean = false,
    val sources: List<DonghuaSource> = emptyList(),
    val activeSourceId: String = "YanHH3DNow",
    val genres: List<GenreItem> = InitialData.GENRES_DATA,
    val selectedGenre: String = "Tất cả",
    val genresLastSyncAtMs: Long = 0L,
    val searchQuery: String = "",
    val favorites: List<String> = emptyList(),
    val favoriteMetaMap: Map<String, FavoriteEpisodeMeta> = emptyMap(),
    val offlineEpisodes: Map<String, OfflineEpisodeRecord> = emptyMap(),
    val watchHistory: Map<String, WatchProgress> = emptyMap(),
    val allMovies: List<DonghuaMovie> = emptyList(),
    val trendingTop10: List<DonghuaMovie> = emptyList(),
    val activeGenreMovies: List<DonghuaMovie>? = null,
    val remoteSearchResults: List<DonghuaMovie> = emptyList(),
    val catalogStatusFilter: CatalogStatusFilter = CatalogStatusFilter.ALL,
    val catalogAudioFilter: CatalogAudioFilter = CatalogAudioFilter.ALL,
    val catalogSortMode: CatalogSortMode = CatalogSortMode.DEFAULT,
    val searchSourceFilter: String? = null,
    val catalogPage: Int = 1,
    val catalogTotalPages: Int = 1,
    val genrePage: Int = 1,
    val genreTotalPages: Int = 1,
    val isLoadingFeed: Boolean = false,
    val isLoadingGenre: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSearching: Boolean = false,
    val isCheckingFavUpdates: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val user: UserProfile? = null,
    val resolvedDevice: ResolvedDeviceProfile = ResolvedDeviceProfile.MOBILE
) {
    val activeCatalogPage: Int
        get() = if (selectedGenre == "Tất cả") catalogPage else genrePage
    val activeCatalogTotalPages: Int
        get() = if (selectedGenre == "Tất cả") catalogTotalPages else genreTotalPages
    val hasMoreCatalogPages: Boolean
        get() = searchQuery.trim().isEmpty() && activeCatalogPage < activeCatalogTotalPages
}

class MainActivity : AppCompatActivity(), AppHost {

    private lateinit var binding: ActivityMainBinding
    private var state = MainState()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var toastHideRunnable: Runnable? = null
    private var searchJob: Job? = null
    private var genreJob: Job? = null
    private var loadMoreJob: Job? = null
    private var realtimeTrendingJob: Job? = null
    private var insetTopPx = 0
    private var insetBottomPx = 0
    private var insetLeftPx = 0
    private var insetRightPx = 0
    private var chromeTopPx = 0
    private var chromeBottomPx = 0
    private var isChromeCollapsed = false
    private var isSearchOverlayOpen = false
    private var isSourcePickerOpen = false
    private var isGenrePickerOpen = false
    private var lastSourceTriggerView: View? = null
    private var lastRealtimeSyncMs: Long = 0L
    private var pendingInstallManifest: com.hoathinh3d.donghua.core.UpdateManifest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val loadedSources = PrefsStore.getSources()
        val savedSettings = PrefsStore.getSettings()
        val preferredOk = loadedSources.firstOrNull { it.id == savedSettings.preferredSource && it.enabled }
        val initialSource = preferredOk?.id
            ?: loadedSources.firstOrNull { it.enabled }?.id
            ?: loadedSources.firstOrNull()?.id
            ?: "YanHH3DNow"

        state = state.copy(
            sources = loadedSources,
            activeSourceId = initialSource,
            settings = savedSettings,
            favorites = PrefsStore.getFavorites(),
            favoriteMetaMap = PrefsStore.getFavoriteMetaMap(),
            offlineEpisodes = PrefsStore.getOfflineEpisodes(),
            watchHistory = PrefsStore.getHistory(),
            allMovies = PrefsStore.getAllMovies(),
            user = PrefsStore.getUser(),
            resolvedDevice = DeviceProfileResolver.resolve(this, savedSettings.deviceMode)
        )

        setupHeaderAndNav()
        ThemeManager.applyRoundedOutline(binding.imgHeaderAvatar, 10f)
        if (!DeviceProfileResolver.shouldUseReducedMotion(this, state.resolvedDevice, savedSettings.reducedMotion)) {
            ThemeManager.applyRoundedOutline(binding.headerContainer, 24f)
            ThemeManager.applyRoundedOutline(binding.headerSourcePill, 18f)
            ThemeManager.applyRoundedOutline(binding.floatingSearchContainer, 24f)
            ThemeManager.applyRoundedOutline(binding.bottomNavContainer, 30f)
            ThemeManager.applyRoundedOutline(binding.tvNavRail, 24f)
            ThemeManager.applyRoundedOutline(binding.floatingSourcePickerContainer, 22f)
            ThemeManager.applyRoundedOutline(binding.floatingGenrePickerContainer, 22f)
            ThemeManager.applyRoundedOutline(binding.btnCollapsedSearch, 24f)
            ThemeManager.applyRoundedOutline(binding.btnCollapsedSource, 24f)
        }

        val updateChromeMeasure = {
            val isTv = state.resolvedDevice == ResolvedDeviceProfile.TV
            if (!isChromeCollapsed &&
                binding.headerContainer.visibility == View.VISIBLE &&
                (isTv || binding.bottomNavContainer.visibility == View.VISIBLE)
            ) {
                val topMeasured = if (binding.headerContainer.bottom > 0) {
                    binding.headerContainer.bottom + ThemeManager.dp(this, 10f)
                } else {
                    insetTopPx + ThemeManager.dp(this, 72f)
                }
                val botMeasured = if (isTv) {
                    insetBottomPx + ThemeManager.dp(this, 22f)
                } else if (binding.bottomNavContainer.height > 0 && binding.rootContainer.height > 0) {
                    (binding.rootContainer.height - binding.bottomNavContainer.top) + ThemeManager.dp(this, 10f)
                } else {
                    insetBottomPx + ThemeManager.dp(this, 92f)
                }
                if (topMeasured != chromeTopPx || botMeasured != chromeBottomPx) {
                    chromeTopPx = topMeasured
                    chromeBottomPx = botMeasured
                    val toastLp = binding.toastBanner.layoutParams as FrameLayout.LayoutParams
                    toastLp.topMargin = chromeTopPx + ThemeManager.dp(this, 4f)
                    binding.toastBanner.layoutParams = toastLp
                    val current = supportFragmentManager.findFragmentById(R.id.contentContainer)
                    (current as? ChromeInsetsListener)?.onChromeInsetsChanged(chromeTopPx, chromeBottomPx)
                }
            }
        }
        binding.headerContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateChromeMeasure() }
        binding.bottomNavContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateChromeMeasure() }

        ThemeManager.applyEdgeToEdgePadding(binding.rootContainer) { top, bottom, left, right ->
            insetTopPx = top
            insetBottomPx = bottom
            insetLeftPx = left
            insetRightPx = right
            applySystemBarOffsets()
            binding.rootContainer.post { updateChromeMeasure() }
        }
        applyThemeAndDeviceLayout()
        showTabFragment(MainTab.HOME)
        loadLiveDonghuaFeed(initialSource, silentOnSuccess = true)
        startRealtimeTrendingSync()
        lifecycleScope.launch(Dispatchers.IO) {
            com.hoathinh3d.donghua.core.AppUpdateChecker.cleanupOldUpdates(this@MainActivity)
        }
        if (savedSettings.autoUpdateApp) {
            lifecycleScope.launch {
                delay(4500L)
                if (!isFinishing && !isDestroyed) {
                    checkAppUpdate(manual = false)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isGenrePickerOpen -> closeGenrePickerOverlay()
                    isSourcePickerOpen -> closeSourcePickerOverlay()
                    isSearchOverlayOpen -> closeSearchOverlay()
                    state.searchQuery.trim().isNotEmpty() -> clearSearch()
                    state.activeTab == MainTab.SETTINGS &&
                        state.resolvedDevice == ResolvedDeviceProfile.MOBILE &&
                        state.isSettingsDetailOpen -> closeSettingsDetail()
                    state.activeTab != MainTab.HOME -> navigateTab(MainTab.HOME)
                    else -> showExitConfirmDialog()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val freshHistory = PrefsStore.getHistory()
        val historyChanged = freshHistory != state.watchHistory
        state = state.copy(
            watchHistory = freshHistory,
            favorites = PrefsStore.getFavorites(),
            favoriteMetaMap = PrefsStore.getFavoriteMetaMap(),
            offlineEpisodes = PrefsStore.getOfflineEpisodes(),
            sources = PrefsStore.getSources()
        )
        if (historyChanged) maybeAutoCloudSync()
        setupSourceSpinner()
        updateBottomNavUi()
        notifyActiveFragment()
        if (!state.isLoadingFeed && System.currentTimeMillis() - lastRealtimeSyncMs >= 45_000L) {
            syncRealtimeTrendingNow(state.activeSourceId)
        }
        val pendingManifest = pendingInstallManifest
        if (pendingManifest != null &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls())
        ) {
            pendingInstallManifest = null
            startDownloadAndInstallUpdate(pendingManifest)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val resolved = DeviceProfileResolver.resolve(this, state.settings.deviceMode)
        if (resolved != state.resolvedDevice) {
            state = state.copy(resolvedDevice = resolved)
        }
        applyThemeAndDeviceLayout()
        notifyActiveFragment()
    }

    private fun setupHeaderAndNav() {
        binding.btnBrandHome.setOnClickListener { navigateTab(MainTab.HOME) }
        binding.btnQuickTheme.setOnClickListener { toggleQuickDarkMode() }
        binding.btnQuickTheme.setOnLongClickListener {
            showQuickThemePickerDialog()
            true
        }
        binding.headerSourcePill.setOnClickListener { openSourcePickerOverlay(binding.headerSourcePill) }
        binding.btnBottomSearch.setOnClickListener { openSearchOverlay() }
        binding.btnCollapsedSearch.setOnClickListener { openSearchOverlay() }
        binding.btnCloseSearchOverlay.setOnClickListener { closeSearchOverlay() }
        binding.btnCollapsedSource.setOnClickListener { openSourcePickerOverlay(binding.btnCollapsedSource) }
        binding.sourcePickerScrim.setOnClickListener { closeSourcePickerOverlay() }
        binding.btnCloseSourcePicker.setOnClickListener { closeSourcePickerOverlay() }
        binding.genrePickerScrim.setOnClickListener { closeGenrePickerOverlay() }
        binding.btnCloseGenrePicker.setOnClickListener { closeGenrePickerOverlay() }
        binding.btnSyncGenresNow.setOnClickListener { syncWebGenresNow() }

        binding.navHome.setOnClickListener { navigateTab(MainTab.HOME) }
        binding.navSchedule.setOnClickListener { navigateTab(MainTab.SCHEDULE) }
        binding.navHistory.setOnClickListener { navigateTab(MainTab.HISTORY) }
        binding.navFavorites.setOnClickListener { navigateTab(MainTab.FAVORITES) }
        binding.navSettings.setOnClickListener { navigateTab(MainTab.SETTINGS) }

        binding.tvRailHome.setOnClickListener { navigateTab(MainTab.HOME) }
        binding.tvRailSchedule.setOnClickListener { navigateTab(MainTab.SCHEDULE) }
        binding.tvRailHistory.setOnClickListener { navigateTab(MainTab.HISTORY) }
        binding.tvRailFavorites.setOnClickListener { navigateTab(MainTab.FAVORITES) }
        binding.tvRailSettings.setOnClickListener { navigateTab(MainTab.SETTINGS) }
        binding.tvRailSearch.setOnClickListener { openSearchOverlay() }
        binding.tvRailSource.setOnClickListener { openSourcePickerOverlay(binding.tvRailSource) }
        binding.tvRailTheme.setOnClickListener { toggleQuickDarkMode() }
        binding.tvRailTheme.setOnLongClickListener {
            showQuickThemePickerDialog()
            true
        }

        binding.btnClearSearch.setOnClickListener { clearSearch() }
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                binding.btnClearSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                binding.searchSuggestionsPanel.visibility = if (q.trim().isEmpty()) View.VISIBLE else View.GONE
                if (q.trim().isEmpty()) {
                    renderSearchSuggestions()
                }
                if (q == state.searchQuery) return
                state = state.copy(searchQuery = q)
                if (q.trim().isNotEmpty() && state.activeTab != MainTab.HOME) {
                    navigateTab(MainTab.HOME)
                }
                searchJob?.cancel()
                if (q.trim().isEmpty()) {
                    state = state.copy(
                        remoteSearchResults = emptyList(),
                        searchSourceFilter = null,
                        isSearching = false
                    )
                    notifyActiveFragment()
                } else {
                    searchJob = lifecycleScope.launch {
                        delay(350L)
                        runLiveSearch(q.trim())
                    }
                }
            }
        })
        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = binding.editSearch.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) {
                    if (state.activeTab != MainTab.HOME) navigateTab(MainTab.HOME)
                    searchJob?.cancel()
                    lifecycleScope.launch { runLiveSearch(q) }
                }
                true
            } else false
        }

        setupSourceSpinner()
    }

    private fun formatSourceCountLabel(enabledCount: Int, totalCount: Int): String =
        if (enabledCount == totalCount) "$totalCount nguồn" else "$enabledCount/$totalCount nguồn"

    private fun setupSourceSpinner() {
        val enabled = state.sources.filter { it.enabled }
        val activeSrc = enabled.firstOrNull { it.id == state.activeSourceId } ?: enabled.firstOrNull()
        val p = getPalette()
        binding.txtHeaderSourceName.text = activeSrc?.name ?: state.activeSourceId
        binding.txtHeaderSourceName.setTextColor(p.textMain)
        binding.imgHeaderSourceChevron.imageTintList = ThemeManager.tintColorState(p.textMuted)
        val countLabel = formatSourceCountLabel(enabled.size, state.sources.size)
        binding.txtHeaderSourceCount.text = countLabel
        binding.txtSearchSourceCount.text = countLabel
        if (isSourcePickerOpen) {
            renderSourcePickerContent()
        }
    }

    private fun applySystemBarOffsets() {
        val isTv = state.resolvedDevice == ResolvedDeviceProfile.TV
        val sideMargin = ThemeManager.dp(this, if (isTv) 20f else 12f)
        val tvRailOffset = if (isTv) ThemeManager.dp(this, 174f) else 0

        val contentLp = binding.contentContainer.layoutParams as FrameLayout.LayoutParams
        if (contentLp.leftMargin != tvRailOffset + (if (isTv) insetLeftPx else 0)) {
            contentLp.leftMargin = tvRailOffset + (if (isTv) insetLeftPx else 0)
            binding.contentContainer.layoutParams = contentLp
        }

        val railLp = binding.tvNavRail.layoutParams as FrameLayout.LayoutParams
        railLp.leftMargin = ThemeManager.dp(this, 12f) + insetLeftPx
        binding.tvNavRail.layoutParams = railLp

        val hdrLp = binding.headerContainer.layoutParams as FrameLayout.LayoutParams
        hdrLp.leftMargin = (if (isTv) tvRailOffset else sideMargin) + insetLeftPx
        hdrLp.rightMargin = sideMargin + insetRightPx
        hdrLp.topMargin = ThemeManager.dp(this, 8f) + insetTopPx
        binding.headerContainer.layoutParams = hdrLp

        val searchOvLp = binding.floatingSearchContainer.layoutParams as FrameLayout.LayoutParams
        searchOvLp.leftMargin = (if (isTv) tvRailOffset else sideMargin) + insetLeftPx
        searchOvLp.rightMargin = sideMargin + insetRightPx
        searchOvLp.topMargin = ThemeManager.dp(this, 8f) + insetTopPx
        binding.floatingSearchContainer.layoutParams = searchOvLp

        val sourcePillLp = binding.btnCollapsedSource.layoutParams as FrameLayout.LayoutParams
        sourcePillLp.topMargin = ThemeManager.dp(this, 10f) + insetTopPx
        sourcePillLp.rightMargin = sideMargin + insetRightPx
        binding.btnCollapsedSource.layoutParams = sourcePillLp

        val navLp = binding.bottomNavContainer.layoutParams as FrameLayout.LayoutParams
        val navSide = ThemeManager.dp(this, if (isTv) 36f else 14f)
        navLp.leftMargin = navSide + insetLeftPx
        navLp.rightMargin = navSide + insetRightPx
        navLp.bottomMargin = ThemeManager.dp(this, if (isTv) 14f else 12f) + insetBottomPx
        binding.bottomNavContainer.layoutParams = navLp

        val searchPillLp = binding.btnCollapsedSearch.layoutParams as FrameLayout.LayoutParams
        searchPillLp.bottomMargin = ThemeManager.dp(this, if (isTv) 14f else 12f) + insetBottomPx
        searchPillLp.rightMargin = (if (isTv) sideMargin else navSide) + insetRightPx
        binding.btnCollapsedSearch.layoutParams = searchPillLp

        val toastLp = binding.toastBanner.layoutParams as FrameLayout.LayoutParams
        toastLp.topMargin = maxOf(chromeTopPx, ThemeManager.dp(this, 76f) + insetTopPx)
        binding.toastBanner.layoutParams = toastLp
    }

    private fun applyThemeAndDeviceLayout() {
        val p = getPalette()
        ThemeManager.applySystemBars(window, p)
        binding.rootContainer.background = ThemeManager.rootBackground(p)
        applySystemBarOffsets()

        val isLite = DeviceProfileResolver.shouldUseReducedMotion(
            this,
            state.resolvedDevice,
            state.settings.reducedMotion
        )
        binding.orbsContainer.visibility = if (state.settings.ambientOrbs && !isLite) View.VISIBLE else View.GONE
        if (state.settings.ambientOrbs && !isLite) {
            ThemeManager.applyOrbBlur(binding.orb1, state.settings.glassBlur)
            ThemeManager.applyOrbBlur(binding.orb2, state.settings.glassBlur)
            ThemeManager.applyOrbBlur(binding.orb3, state.settings.glassBlur)
        }

        binding.headerContainer.background = ThemeManager.liquidGlassSheetDrawable(this, p, 24f, liteRender = isLite)
        binding.btnBrandHome.background = ThemeManager.navTabDrawable(this, p, active = false, 16f, liteRender = isLite)
        binding.txtHeaderBrand.setTextColor(p.accentFrom)
        binding.txtHeaderBadge.background = ThemeManager.accentGradientDrawable(this, p, 8f)
        binding.txtHeaderSub.setTextColor(p.textMuted)
        binding.txtHeaderSub.visibility =
            if (state.resolvedDevice == ResolvedDeviceProfile.MOBILE) View.GONE else View.VISIBLE

        binding.floatingSearchContainer.background = ThemeManager.liquidGlassSheetDrawable(this, p, 24f, liteRender = isLite)
        binding.searchBarBox.background = ThemeManager.glassPanelDrawable(this, p, 16f, isCard = true, liteRender = isLite)
        binding.imgSearchIcon.imageTintList = ThemeManager.tintColorState(p.textMuted)
        binding.editSearch.setTextColor(p.textMain)
        binding.editSearch.setHintTextColor(p.textMuted)
        binding.txtSearchSourceCount.setTextColor(p.accentFrom)
        binding.btnClearSearch.setTextColor(p.textMuted)
        binding.btnClearSearch.background = ThemeManager.navTabDrawable(this, p, active = false, 10f)
        binding.btnCloseSearchOverlay.setTextColor(p.accentFrom)
        binding.btnCloseSearchOverlay.background = ThemeManager.pillDrawable(this, p, active = false, 14f)
        renderSearchSuggestions()

        binding.btnQuickTheme.background = ThemeManager.pillDrawable(this, p, active = false, 17f)
        binding.imgQuickThemeIcon.setImageResource(if (p.isDark) R.drawable.ic_theme_moon else R.drawable.ic_theme_sun)
        binding.imgQuickThemeIcon.imageTintList = ThemeManager.tintColorState(p.accentFrom)
        binding.headerSourcePill.background = ThemeManager.pillDrawable(this, p, active = false, 18f)
        binding.txtHeaderSourceCount.background = ThemeManager.navTabDrawable(this, p, active = true, 10f)
        binding.txtHeaderSourceCount.setTextColor(p.accentFrom)
        setupSourceSpinner()

        binding.btnBottomSearch.background = ThemeManager.pillDrawable(this, p, active = false, 16f)
        binding.imgBottomSearchIcon.imageTintList = ThemeManager.tintColorState(p.accentFrom)
        binding.btnCollapsedSearch.background = ThemeManager.liquidGlassSheetDrawable(this, p, 24f, liteRender = isLite)
        binding.imgCollapsedSearchIcon.imageTintList = ThemeManager.tintColorState(p.accentFrom)
        binding.btnCollapsedSource.background = ThemeManager.liquidGlassSheetDrawable(this, p, 24f, liteRender = isLite)
        binding.imgCollapsedSourceIcon.imageTintList = ThemeManager.tintColorState(p.accentFrom)

        binding.bottomNavContainer.background = ThemeManager.liquidGlassSheetDrawable(this, p, 30f, liteRender = isLite)
        binding.tvNavRail.background = ThemeManager.liquidGlassSheetDrawable(this, p, 24f, liteRender = isLite)
        binding.tvRailDivider.setBackgroundColor(p.glassSubtleBorder)
        binding.tvRailSearch.background = ThemeManager.navTabDrawable(this, p, active = false, radiusDp = 16f, liteRender = isLite)
        binding.tvRailSearchIcon.imageTintList = ThemeManager.tintColorState(p.accentFrom)
        binding.tvRailSearchLabel.setTextColor(p.textMain)
        binding.tvRailSource.background = ThemeManager.navTabDrawable(this, p, active = false, radiusDp = 16f, liteRender = isLite)
        binding.tvRailSourceIcon.imageTintList = ThemeManager.tintColorState(p.accentFrom)
        binding.tvRailSourceLabel.setTextColor(p.textMain)
        binding.tvRailTheme.background = ThemeManager.navTabDrawable(this, p, active = false, radiusDp = 16f, liteRender = isLite)
        binding.tvRailThemeIcon.setImageResource(if (p.isDark) R.drawable.ic_theme_moon else R.drawable.ic_theme_sun)
        binding.tvRailThemeIcon.imageTintList = ThemeManager.tintColorState(p.accentFrom)
        binding.tvRailThemeLabel.text = if (p.isDark) "Hoàng Hôn Đêm" else "Sáng Ngọc Trai"
        binding.tvRailThemeLabel.setTextColor(p.textMain)

        val isTv = state.resolvedDevice == ResolvedDeviceProfile.TV
        if (isTv) {
            isChromeCollapsed = false
            binding.tvNavRail.visibility = View.VISIBLE
            binding.bottomNavContainer.visibility = View.GONE
            binding.btnCollapsedSearch.visibility = View.GONE
            binding.btnCollapsedSource.visibility = View.GONE
            binding.headerContainer.visibility = View.VISIBLE
            binding.headerContainer.alpha = 1f
            binding.headerContainer.translationY = 0f
        } else {
            binding.tvNavRail.visibility = View.GONE
            if (!isChromeCollapsed) {
                binding.bottomNavContainer.visibility = View.VISIBLE
            }
        }

        binding.toastBanner.background = ThemeManager.liquidGlassSheetDrawable(this, p, 22f)
        binding.toastBanner.setTextColor(p.textMain)

        updateBottomNavUi()
    }

    private fun featureAccentForTab(tab: MainTab, p: ThemePalette): Int {
        if (p.preset == com.hoathinh3d.donghua.data.model.ThemePreset.DARK) {
            return when (tab) {
                MainTab.HOME -> p.accentFrom
                MainTab.SCHEDULE -> Color.parseColor("#E8BA6B")
                MainTab.HISTORY -> Color.parseColor("#D99B52")
                MainTab.FAVORITES -> Color.parseColor("#E87478")
                MainTab.SETTINGS -> Color.parseColor("#D1A35E")
            }
        }
        return when (tab) {
            MainTab.HOME -> p.accentFrom
            MainTab.SCHEDULE -> Color.parseColor(if (p.isDark) "#4CC9D0" else "#2A93AE")
            MainTab.HISTORY -> Color.parseColor(if (p.isDark) "#A78BFA" else "#7459CF")
            MainTab.FAVORITES -> Color.parseColor(if (p.isDark) "#FB7185" else "#DF4E6C")
            MainTab.SETTINGS -> Color.parseColor(if (p.isDark) "#5AD1A4" else "#299D77")
        }
    }

    private fun updateBottomNavUi() {
        val p = getPalette()
        val enableTapFx = !DeviceProfileResolver.shouldUseReducedMotion(
            this,
            state.resolvedDevice,
            state.settings.reducedMotion
        )
        val tabs = listOf(
            Triple(MainTab.HOME, binding.navHome, binding.navHomeIcon to binding.navHomeLabel),
            Triple(MainTab.SCHEDULE, binding.navSchedule, binding.navScheduleIcon to binding.navScheduleLabel),
            Triple(MainTab.HISTORY, binding.navHistory, binding.navHistoryIcon to binding.navHistoryLabel),
            Triple(MainTab.FAVORITES, binding.navFavorites, binding.navFavoritesIcon to binding.navFavoritesLabel),
            Triple(MainTab.SETTINGS, binding.navSettings, binding.navSettingsIcon to binding.navSettingsLabel)
        )
        for ((tab, tabView, iconAndLabel) in tabs) {
            val active = state.activeTab == tab
            val (icon, lbl) = iconAndLabel
            val featCol = featureAccentForTab(tab, p)
            tabView.background = ThemeManager.navTabDrawable(this, p, active = active, radiusDp = 24f, liteRender = !enableTapFx)
            icon.background = ThemeManager.navFeatureIconDrawable(this, p, featCol, active = active, radiusDp = 12f)
            icon.imageTintList = ThemeManager.tintColorState(if (active) Color.WHITE else featCol)
            val restScale = if (active) 1.04f else 1.0f
            icon.scaleX = restScale
            icon.scaleY = restScale
            lbl.setTextColor(if (active) featCol else p.textMain)
            lbl.alpha = if (active) 1f else 0.88f
            lbl.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            ThemeManager.attachLiquidNavTapEffect(tabView, icon, enableTapFx)
        }
        ThemeManager.attachLiquidNavTapEffect(binding.btnBottomSearch, binding.imgBottomSearchIcon, enableTapFx)

        val railTabs = listOf(
            Triple(MainTab.HOME, binding.tvRailHome, binding.tvRailHomeIcon to binding.tvRailHomeLabel),
            Triple(MainTab.SCHEDULE, binding.tvRailSchedule, binding.tvRailScheduleIcon to binding.tvRailScheduleLabel),
            Triple(MainTab.HISTORY, binding.tvRailHistory, binding.tvRailHistoryIcon to binding.tvRailHistoryLabel),
            Triple(MainTab.FAVORITES, binding.tvRailFavorites, binding.tvRailFavoritesIcon to binding.tvRailFavoritesLabel),
            Triple(MainTab.SETTINGS, binding.tvRailSettings, binding.tvRailSettingsIcon to binding.tvRailSettingsLabel)
        )
        for ((tab, railView, iconAndLabel) in railTabs) {
            val active = state.activeTab == tab
            val (icon, lbl) = iconAndLabel
            val featCol = featureAccentForTab(tab, p)
            railView.background = ThemeManager.navTabDrawable(this, p, active = active, radiusDp = 16f, liteRender = !enableTapFx)
            icon.background = ThemeManager.navFeatureIconDrawable(this, p, featCol, active = active, radiusDp = 9f)
            icon.imageTintList = ThemeManager.tintColorState(if (active) Color.WHITE else featCol)
            lbl.setTextColor(if (active) featCol else p.textMain)
            lbl.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            ThemeManager.attachLiquidNavTapEffect(railView, icon, enableTapFx)
        }
        ThemeManager.attachLiquidNavTapEffect(binding.tvRailSearch, binding.tvRailSearchIcon, enableTapFx)
        ThemeManager.attachLiquidNavTapEffect(binding.tvRailSource, binding.tvRailSourceIcon, enableTapFx)
        ThemeManager.attachLiquidNavTapEffect(binding.tvRailTheme, binding.tvRailThemeIcon, enableTapFx)
    }

    private fun animDuration(normalMs: Long): Long {
        return if (DeviceProfileResolver.shouldUseReducedMotion(this, state.resolvedDevice, state.settings.reducedMotion)) {
            0L
        } else {
            normalMs
        }
    }

    private fun openSearchOverlay() {
        if (isSourcePickerOpen) closeSourcePickerOverlay()
        if (isGenrePickerOpen) closeGenrePickerOverlay()
        isSearchOverlayOpen = true
        renderSearchSuggestions()
        binding.searchSuggestionsPanel.visibility =
            if (binding.editSearch.text.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.floatingSearchContainer.animate().cancel()
        val dur = animDuration(180L)
        if (dur == 0L) {
            binding.floatingSearchContainer.apply {
                visibility = View.VISIBLE
                alpha = 1f
                translationY = 0f
            }
        } else {
            binding.floatingSearchContainer.apply {
                visibility = View.VISIBLE
                alpha = 0f
                translationY = -ThemeManager.dp(this@MainActivity, 14f).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(dur).start()
            }
        }
        binding.editSearch.post {
            binding.editSearch.requestFocus()
            binding.editSearch.setSelection(binding.editSearch.text?.length ?: 0)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearchOverlay() {
        if (!isSearchOverlayOpen && !binding.editSearch.hasFocus() &&
            binding.floatingSearchContainer.visibility != View.VISIBLE
        ) {
            return
        }
        isSearchOverlayOpen = false
        binding.editSearch.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
        binding.floatingSearchContainer.animate().cancel()
        val dur = animDuration(160L)
        if (dur == 0L) {
            binding.floatingSearchContainer.visibility = View.GONE
            binding.floatingSearchContainer.alpha = 1f
            binding.floatingSearchContainer.translationY = 0f
            return
        }
        binding.floatingSearchContainer.animate()
            .alpha(0f)
            .translationY(-ThemeManager.dp(this, 14f).toFloat())
            .setDuration(dur)
            .withEndAction {
                if (!isSearchOverlayOpen) binding.floatingSearchContainer.visibility = View.GONE
            }
            .start()
    }

    private fun renderSearchSuggestions() {
        val p = getPalette()
        binding.txtSearchHotHeader.setTextColor(p.accentFrom)
        binding.txtSearchHistoryHeader.setTextColor(p.accentFrom)
        binding.btnClearSearchHistory.setTextColor(p.textMuted)
        binding.btnClearSearchHistory.background = ThemeManager.pillDrawable(this, p, active = false, 10f)

        // 1. Hot keywords chips
        binding.searchHotChipsContainer.removeAllViews()
        val chipMargin = ThemeManager.dp(this, 3f)
        for (kw in HOT_KEYWORDS) {
            val chip = TextView(this).apply {
                text = "🔥 $kw"
                textSize = 11.5f
                setTextColor(p.textMain)
                background = ThemeManager.pillDrawable(this@MainActivity, p, active = false, 14f)
                setPadding(ThemeManager.dp(this@MainActivity, 10f), ThemeManager.dp(this@MainActivity, 5f), ThemeManager.dp(this@MainActivity, 10f), ThemeManager.dp(this@MainActivity, 5f))
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(chipMargin, 0, chipMargin, 0)
                }
                setOnClickListener {
                    binding.editSearch.setText(kw)
                    binding.editSearch.setSelection(kw.length)
                    PrefsStore.addSearchHistory(kw)
                    if (state.activeTab != MainTab.HOME) navigateTab(MainTab.HOME)
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch { runLiveSearch(kw) }
                }
            }
            binding.searchHotChipsContainer.addView(chip)
        }

        // 2. Search History chips
        val history = PrefsStore.getSearchHistory()
        binding.searchHistoryChipsContainer.removeAllViews()
        if (history.isEmpty()) {
            binding.searchHistoryHeaderBox.visibility = View.GONE
            binding.searchHistoryChipsScroll.visibility = View.GONE
        } else {
            binding.searchHistoryHeaderBox.visibility = View.VISIBLE
            binding.searchHistoryChipsScroll.visibility = View.VISIBLE
            for (hItem in history) {
                val chipBox = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ThemeManager.pillDrawable(this@MainActivity, p, active = false, 14f)
                    setPadding(ThemeManager.dp(this@MainActivity, 9f), ThemeManager.dp(this@MainActivity, 3f), ThemeManager.dp(this@MainActivity, 6f), ThemeManager.dp(this@MainActivity, 3f))
                    isFocusable = true
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(chipMargin, 0, chipMargin, 0)
                    }
                }
                val txtLabel = TextView(this).apply {
                    text = "🕒 $hItem"
                    textSize = 11.5f
                    setTextColor(p.textMain)
                }
                val btnDel = TextView(this).apply {
                    text = " ✕"
                    textSize = 11f
                    setTextColor(p.textMuted)
                    setPadding(ThemeManager.dp(this@MainActivity, 4f), 0, ThemeManager.dp(this@MainActivity, 4f), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        PrefsStore.removeSearchHistoryItem(hItem)
                        renderSearchSuggestions()
                    }
                }
                chipBox.addView(txtLabel)
                chipBox.addView(btnDel)
                chipBox.setOnClickListener {
                    binding.editSearch.setText(hItem)
                    binding.editSearch.setSelection(hItem.length)
                    PrefsStore.addSearchHistory(hItem)
                    if (state.activeTab != MainTab.HOME) navigateTab(MainTab.HOME)
                    searchJob?.cancel()
                    lifecycleScope.launch { runLiveSearch(hItem) }
                }
                binding.searchHistoryChipsContainer.addView(chipBox)
            }
        }

        binding.btnClearSearchHistory.setOnClickListener {
            PrefsStore.clearSearchHistory()
            renderSearchSuggestions()
        }
    }

    override fun onMainContentScroll(dy: Int, scrollY: Int) {
        val collapseAt = ThemeManager.dp(this, 36f)
        val topResetAt = ThemeManager.dp(this, 12f)
        if (isSearchOverlayOpen || binding.editSearch.hasFocus()) {
            if (kotlin.math.abs(dy) > 4 || scrollY > collapseAt) {
                closeSearchOverlay()
                setChromeCollapsed(true)
            }
            return
        }
        when {
            scrollY <= topResetAt -> setChromeCollapsed(false)
            dy > 8 && scrollY > collapseAt -> setChromeCollapsed(true)
            dy < -18 -> setChromeCollapsed(false)
        }
    }

    private fun setChromeCollapsed(collapsed: Boolean) {
        if (state.resolvedDevice == ResolvedDeviceProfile.TV) return
        if (collapsed && (isSearchOverlayOpen || binding.editSearch.hasFocus())) return
        if (isChromeCollapsed == collapsed) return
        isChromeCollapsed = collapsed

        val dur = animDuration(210L)
        val collapseOutDur = animDuration(140L)
        val shiftPx = ThemeManager.dp(this, 22f).toFloat()
        if (collapsed) {
            binding.headerContainer.animate().cancel()
            binding.bottomNavContainer.animate().cancel()
            binding.btnCollapsedSearch.animate().cancel()
            binding.btnCollapsedSource.animate().cancel()
            if (dur == 0L) {
                binding.headerContainer.visibility = View.GONE
                binding.bottomNavContainer.visibility = View.GONE
                binding.btnCollapsedSearch.apply {
                    alpha = 1f; scaleX = 1f; scaleY = 1f; visibility = View.VISIBLE
                }
                binding.btnCollapsedSource.apply {
                    alpha = 1f; scaleX = 1f; scaleY = 1f; visibility = View.VISIBLE
                }
                return
            }
            binding.headerContainer.animate()
                .alpha(0f)
                .translationY(-shiftPx)
                .setDuration(dur)
                .withEndAction {
                    if (isChromeCollapsed) binding.headerContainer.visibility = View.GONE
                }
                .start()

            binding.bottomNavContainer.animate()
                .alpha(0f)
                .translationY(shiftPx)
                .setDuration(dur)
                .withEndAction {
                    if (isChromeCollapsed) binding.bottomNavContainer.visibility = View.GONE
                }
                .start()

            binding.btnCollapsedSearch.apply {
                alpha = 0f
                scaleX = 0.78f
                scaleY = 0.78f
                visibility = View.VISIBLE
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(dur).start()
            }

            binding.btnCollapsedSource.apply {
                alpha = 0f
                scaleX = 0.78f
                scaleY = 0.78f
                visibility = View.VISIBLE
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(dur).start()
            }
        } else {
            binding.btnCollapsedSearch.animate().cancel()
            binding.btnCollapsedSource.animate().cancel()
            binding.headerContainer.animate().cancel()
            binding.bottomNavContainer.animate().cancel()
            if (dur == 0L) {
                binding.btnCollapsedSearch.visibility = View.GONE
                binding.btnCollapsedSource.visibility = View.GONE
                binding.headerContainer.apply { visibility = View.VISIBLE; alpha = 1f; translationY = 0f }
                binding.bottomNavContainer.apply { visibility = View.VISIBLE; alpha = 1f; translationY = 0f }
                return
            }
            binding.btnCollapsedSearch.animate()
                .alpha(0f)
                .scaleX(0.78f)
                .scaleY(0.78f)
                .setDuration(collapseOutDur)
                .withEndAction {
                    if (!isChromeCollapsed) binding.btnCollapsedSearch.visibility = View.GONE
                }
                .start()

            binding.btnCollapsedSource.animate()
                .alpha(0f)
                .scaleX(0.78f)
                .scaleY(0.78f)
                .setDuration(collapseOutDur)
                .withEndAction {
                    if (!isChromeCollapsed) binding.btnCollapsedSource.visibility = View.GONE
                }
                .start()

            binding.headerContainer.apply {
                visibility = View.VISIBLE
                alpha = 0f
                translationY = -shiftPx
                animate().alpha(1f).translationY(0f).setDuration(dur).start()
            }

            binding.bottomNavContainer.apply {
                visibility = View.VISIBLE
                alpha = 0f
                translationY = shiftPx
                animate().alpha(1f).translationY(0f).setDuration(dur).start()
            }
        }
    }

    private fun openSourcePickerOverlay(triggerView: View? = null) {
        val enabled = state.sources.filter { it.enabled }
        if (enabled.isEmpty()) {
            showToast("Chưa có nguồn phim nào được bật!")
            return
        }
        if (isSearchOverlayOpen) closeSearchOverlay()
        if (isGenrePickerOpen) closeGenrePickerOverlay()
        if (isSourcePickerOpen) {
            closeSourcePickerOverlay()
            return
        }
        lastSourceTriggerView = triggerView
        isSourcePickerOpen = true

        val p = getPalette()
        val isTv = state.resolvedDevice == ResolvedDeviceProfile.TV
        binding.sourcePickerScrim.setBackgroundColor(
            Color.argb(if (p.isDark) 76 else 32, 10, 15, 28)
        )
        binding.floatingSourcePickerContainer.background = ThemeManager.liquidGlassSheetDrawable(this, p, 22f)
        binding.txtSourcePickerTitle.setTextColor(p.textMain)
        binding.txtSourcePickerCount.background = ThemeManager.navTabDrawable(this, p, active = true, 10f)
        binding.txtSourcePickerCount.setTextColor(p.accentFrom)
        binding.btnCloseSourcePicker.background = ThemeManager.navTabDrawable(this, p, active = false, 12f)
        binding.btnCloseSourcePicker.setTextColor(p.textMuted)

        val lp = binding.floatingSourcePickerContainer.layoutParams as FrameLayout.LayoutParams
        val maxAvailW = (resources.displayMetrics.widthPixels - ThemeManager.dp(this, 24f)).coerceAtLeast(ThemeManager.dp(this, 240f))
        if (isTv) {
            lp.width = minOf(ThemeManager.dp(this, 360f), maxAvailW)
            lp.gravity = android.view.Gravity.CENTER
            lp.topMargin = 0
            lp.rightMargin = 0
        } else {
            lp.width = minOf(ThemeManager.dp(this, 310f), maxAvailW)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            lp.rightMargin = ThemeManager.dp(this, 12f) + insetRightPx
            lp.topMargin = if (isChromeCollapsed) {
                insetTopPx + ThemeManager.dp(this, 64f)
            } else {
                maxOf(chromeTopPx, insetTopPx + ThemeManager.dp(this, 74f))
            }
        }
        binding.floatingSourcePickerContainer.layoutParams = lp

        val focusTarget = renderSourcePickerContent()
        binding.floatingSourcePickerOverlay.visibility = View.VISIBLE
        binding.floatingSourcePickerContainer.animate().cancel()
        val dur = animDuration(175L)
        if (dur == 0L) {
            binding.floatingSourcePickerOverlay.alpha = 1f
            binding.floatingSourcePickerContainer.apply {
                alpha = 1f; scaleX = 1f; scaleY = 1f; translationY = 0f
            }
        } else {
            binding.floatingSourcePickerOverlay.alpha = 0f
            binding.floatingSourcePickerOverlay.animate().alpha(1f).setDuration(dur).start()
            binding.floatingSourcePickerContainer.apply {
                pivotX = if (isTv) lp.width * 0.5f else lp.width.toFloat()
                pivotY = if (isTv) ThemeManager.dp(this@MainActivity, 90f).toFloat() else 0f
                alpha = 0f
                scaleX = 0.88f
                scaleY = 0.88f
                translationY = if (isTv) 0f else -ThemeManager.dp(this@MainActivity, 8f).toFloat()
                animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(dur).start()
            }
        }
        focusTarget?.post { focusTarget.requestFocus() }
    }

    private fun renderSourcePickerContent(): View? {
        val enabled = state.sources.filter { it.enabled }
        val p = getPalette()
        val countLabel = formatSourceCountLabel(enabled.size, state.sources.size)
        binding.txtSourcePickerCount.text = countLabel
        binding.sourcePickerListContainer.removeAllViews()
        var activeRowView: View? = null

        enabled.forEachIndexed { idx, src ->
            val selected = src.id == state.activeSourceId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                background = ThemeManager.navTabDrawable(this@MainActivity, p, active = selected, radiusDp = 16f)
                val padH = ThemeManager.dp(context, 12f)
                val padV = ThemeManager.dp(context, 10f)
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    closeSourcePickerOverlay()
                    if (src.id != state.activeSourceId) {
                        selectSource(src.id)
                    }
                }
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTv = TextView(this).apply {
                text = src.name
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (selected) p.accentFrom else p.textMain)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val roleTag = if (src.isCustom) "Tùy chỉnh" else if (idx == 0) "Nguồn chính" else "Dự phòng"
            val metaTv = TextView(this).apply {
                text = "${src.quality} • ${src.ping} • $roleTag"
                textSize = 11f
                setTextColor(p.textMuted)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textCol.addView(nameTv)
            textCol.addView(metaTv)
            row.addView(textCol)

            if (selected) {
                val badge = TextView(this).apply {
                    text = "✓ Đang dùng"
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = ThemeManager.accentGradientDrawable(this@MainActivity, p, 10f)
                    setPadding(ThemeManager.dp(context, 8f), ThemeManager.dp(context, 3f), ThemeManager.dp(context, 8f), ThemeManager.dp(context, 3f))
                }
                row.addView(badge)
                activeRowView = row
            }

            binding.sourcePickerListContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (idx > 0) topMargin = ThemeManager.dp(this@MainActivity, 8f) }
            )
        }

        binding.sourcePickerFooterRow.removeAllViews()
        if (isChromeCollapsed) {
            binding.sourcePickerFooterRow.addView(
                TextView(this).apply {
                    text = "Mở thanh điều hướng"
                    textSize = 11.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(p.accentFrom)
                    gravity = android.view.Gravity.CENTER
                    isFocusable = true
                    background = ThemeManager.navTabDrawable(this@MainActivity, p, active = false, 12f)
                    setPadding(ThemeManager.dp(context, 10f), ThemeManager.dp(context, 7f), ThemeManager.dp(context, 10f), ThemeManager.dp(context, 7f))
                    setOnClickListener {
                        closeSourcePickerOverlay()
                        setChromeCollapsed(false)
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = ThemeManager.dp(this@MainActivity, 6f)
                }
            )
        }
        binding.sourcePickerFooterRow.addView(
            TextView(this).apply {
                text = "⚙ Quản lý nguồn Web"
                textSize = 11.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(p.textMain)
                gravity = android.view.Gravity.CENTER
                isFocusable = true
                background = ThemeManager.navTabDrawable(this@MainActivity, p, active = false, 12f)
                setPadding(ThemeManager.dp(context, 10f), ThemeManager.dp(context, 7f), ThemeManager.dp(context, 10f), ThemeManager.dp(context, 7f))
                setOnClickListener {
                    closeSourcePickerOverlay()
                    navigateTab(MainTab.SETTINGS)
                    setSettingsSubtab(SettingsSubtab.SOURCES)
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return activeRowView ?: binding.sourcePickerListContainer.getChildAt(0)
    }

    private fun closeSourcePickerOverlay() {
        if (!isSourcePickerOpen && binding.floatingSourcePickerOverlay.visibility != View.VISIBLE) return
        isSourcePickerOpen = false
        val restoreView = lastSourceTriggerView
        lastSourceTriggerView = null
        val dur = animDuration(135L)
        binding.floatingSourcePickerOverlay.animate().cancel()
        binding.floatingSourcePickerContainer.animate().cancel()
        if (dur == 0L) {
            binding.floatingSourcePickerOverlay.visibility = View.GONE
            binding.floatingSourcePickerOverlay.alpha = 1f
            binding.floatingSourcePickerContainer.apply {
                alpha = 1f; scaleX = 1f; scaleY = 1f; translationY = 0f
            }
            if (state.resolvedDevice == ResolvedDeviceProfile.TV) restoreView?.requestFocus()
            return
        }
        binding.floatingSourcePickerContainer.animate()
            .alpha(0f)
            .scaleX(0.90f)
            .scaleY(0.90f)
            .setDuration(dur)
            .start()
        binding.floatingSourcePickerOverlay.animate()
            .alpha(0f)
            .setDuration(dur)
            .withEndAction {
                if (!isSourcePickerOpen) {
                    binding.floatingSourcePickerOverlay.visibility = View.GONE
                    binding.floatingSourcePickerOverlay.alpha = 1f
                    binding.floatingSourcePickerContainer.apply {
                        alpha = 1f; scaleX = 1f; scaleY = 1f; translationY = 0f
                    }
                    if (state.resolvedDevice == ResolvedDeviceProfile.TV) restoreView?.requestFocus()
                }
            }
            .start()
    }

    private fun showTabFragment(tab: MainTab) {
        val fragment: Fragment = when (tab) {
            MainTab.HOME -> HomeFragment()
            MainTab.SCHEDULE -> ScheduleFragment()
            MainTab.HISTORY -> HistoryFragment()
            MainTab.FAVORITES -> FavoritesFragment()
            MainTab.SETTINGS -> SettingsFragment()
        }
        val tx = supportFragmentManager.beginTransaction()
        if (!DeviceProfileResolver.shouldUseReducedMotion(this, state.resolvedDevice, state.settings.reducedMotion)) {
            tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        tx.replace(R.id.contentContainer, fragment).commit()
    }

    private fun notifyActiveFragment() {
        val current = supportFragmentManager.findFragmentById(R.id.contentContainer)
        (current as? RefreshableTab)?.refreshUi()
    }

    private fun startRealtimeTrendingSync() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(60_000L)
                    if (!state.isLoadingFeed) {
                        syncRealtimeTrendingNow(state.activeSourceId)
                    }
                }
            }
        }
    }

    private fun syncRealtimeTrendingNow(sourceId: String) {
        realtimeTrendingJob?.cancel()
        realtimeTrendingJob = lifecycleScope.launch {
            lastRealtimeSyncMs = System.currentTimeMillis()
            val activeSelected = state.selectedGenre
            val genreObj = if (activeSelected != "Tất cả") {
                state.genres.firstOrNull { it.id == activeSelected }
            } else null

            val (freshTop10, freshCats, freshGenreRes) = coroutineScope {
                val topDef = async {
                    runCatching { MovieRepository.fetchRealtimeTrendingTop10(sourceId) }.getOrDefault(emptyList())
                }
                val catDef = async {
                    runCatching { MovieRepository.fetchCategoriesForSource(sourceId, forceRefresh = true) }.getOrDefault(emptyList())
                }
                val genreDef = async {
                    if (genreObj != null) {
                        runCatching {
                            MovieRepository.fetchGenrePage(sourceId, genreObj, 1, 30, forceRefresh = true)
                        }.getOrNull()
                    } else null
                }
                Triple(topDef.await(), catDef.await(), genreDef.await())
            }
            var changed = false
            val nowMs = System.currentTimeMillis()
            if (freshTop10.isNotEmpty() && freshTop10 != state.trendingTop10) {
                state = state.copy(trendingTop10 = freshTop10)
                changed = true
            }
            if (freshCats.isNotEmpty()) {
                val catsChanged = freshCats != state.genres
                state = state.copy(genres = freshCats, genresLastSyncAtMs = nowMs)
                if (catsChanged) changed = true
            }
            if (freshGenreRes != null && freshGenreRes.movies.isNotEmpty() && state.selectedGenre == activeSelected && state.genrePage == 1) {
                if (freshGenreRes.movies != state.activeGenreMovies) {
                    state = state.copy(
                        activeGenreMovies = freshGenreRes.movies,
                        genreTotalPages = maxOf(state.genreTotalPages, freshGenreRes.totalPages)
                    )
                    changed = true
                }
            }
            if (isGenrePickerOpen) {
                renderGenrePickerContent()
            }
            if (changed && state.activeTab == MainTab.HOME) {
                notifyActiveFragment()
            }
        }
    }

    private fun loadLiveDonghuaFeed(sourceId: String, retryAttempt: Int = 0, silentOnSuccess: Boolean = false) {
        loadMoreJob?.cancel()
        state = state.copy(isLoadingFeed = true, isLoadingMore = false)
        notifyActiveFragment()
        lifecycleScope.launch {
            try {
                val (pageRes, catsTask, top10Task) = coroutineScope {
                    val mDef = async { MovieRepository.fetchDonghuaPage(1, 30, sourceId) }
                    val cDef = async { MovieRepository.fetchCategoriesForSource(sourceId, forceRefresh = true) }
                    val tDef = async { MovieRepository.fetchRealtimeTrendingTop10(sourceId) }
                    Triple(mDef.await(), cDef.await(), tDef.await())
                }
                val genres = catsTask.ifEmpty { InitialData.GENRES_DATA }
                val moviesTask = pageRes.movies

                val mergedCatalog = if (moviesTask.isNotEmpty()) {
                    val customOnly = PrefsStore.getCustomMovies().filter {
                        !MovieRepository.isDeadOrBlankPoster(it.poster) || !it.customWebUrl.isNullOrBlank()
                    }
                    MovieRepository.mergeCatalogPages(customOnly, moviesTask)
                } else state.allMovies

                val nowMs = System.currentTimeMillis()
                lastRealtimeSyncMs = nowMs
                state = state.copy(
                    genres = genres,
                    genresLastSyncAtMs = nowMs,
                    allMovies = mergedCatalog,
                    trendingTop10 = if (top10Task.isNotEmpty()) top10Task else state.trendingTop10,
                    activeGenreMovies = null,
                    catalogPage = pageRes.page,
                    catalogTotalPages = pageRes.totalPages,
                    genrePage = 1,
                    genreTotalPages = 1,
                    isLoadingFeed = false,
                    isLoadingMore = false
                )
                notifyActiveFragment()

                val srcLabel = state.sources.firstOrNull { it.id == sourceId }?.name ?: sourceId
                if (moviesTask.isNotEmpty()) {
                    if (!silentOnSuccess) {
                        showToast("Đã nạp ${moviesTask.size} phim từ nguồn $srcLabel")
                    }

                    val favs = PrefsStore.getFavorites()
                    if (favs.isNotEmpty()) {
                        val favCheck = MovieRepository.checkFavoritesNewEpisodes(favs, mergedCatalog, 3)
                        state = state.copy(favoriteMetaMap = favCheck.metaMap)
                        if (favCheck.newlyUpdatedTitles.isNotEmpty()) {
                            showToast("Yêu thích có tập mới: ${favCheck.newlyUpdatedTitles.take(2).joinToString(", ")}")
                        }
                        notifyActiveFragment()
                    }
                } else if (retryAttempt == 0 && state.activeSourceId == sourceId) {
                    delay(3500L)
                    if (state.activeSourceId == sourceId && !state.isLoadingFeed) {
                        loadLiveDonghuaFeed(sourceId, retryAttempt = 1, silentOnSuccess = silentOnSuccess)
                    }
                } else {
                    showToast("Chưa kết nối được nguồn $srcLabel — vui lòng kiểm tra lại kết nối mạng")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                com.hoathinh3d.donghua.core.AppDiagnostics.log("Feed", "Lỗi nạp feed ($sourceId): ${t.javaClass.simpleName}: ${t.message}")
                state = state.copy(isLoadingFeed = false, isLoadingMore = false)
                notifyActiveFragment()
            }
        }
    }

    private suspend fun runLiveSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            PrefsStore.addSearchHistory(trimmed)
        }
        state = state.copy(isSearching = true)
        notifyActiveFragment()
        try {
            val results = MovieRepository.searchDonghua(query)
            val mergedCatalog = MovieRepository.mergeCatalogPages(state.allMovies, results)
            state = state.copy(
                remoteSearchResults = results,
                allMovies = mergedCatalog,
                isSearching = false
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            state = state.copy(isSearching = false)
        }
        notifyActiveFragment()
    }

    // =====================================================
    // AppHost implementation
    // =====================================================
    override fun getState(): MainState = state
    override fun getPalette(): ThemePalette = ThemeManager.palette(
        themeWire = state.settings.theme,
        glassBlurPx = state.settings.glassBlur,
        glassOpacityPct = state.settings.glassOpacity,
        darkModeWire = state.settings.darkMode,
        isSystemDark = ThemeManager.isSystemDarkMode(this),
        liteRender = DeviceProfileResolver.shouldUseReducedMotion(
            this,
            state.resolvedDevice,
            state.settings.reducedMotion
        )
    )
    override fun getChromeInsets(): Pair<Int, Int> {
        val fallbackTop = insetTopPx + ThemeManager.dp(this, 72f)
        val fallbackBot = insetBottomPx + ThemeManager.dp(this, 92f)
        return (if (chromeTopPx > 0) chromeTopPx else fallbackTop) to
            (if (chromeBottomPx > 0) chromeBottomPx else fallbackBot)
    }

    override fun selectGenre(genreId: String) {
        if (isGenrePickerOpen) closeGenrePickerOverlay()
        state = state.copy(selectedGenre = genreId, isLoadingMore = false)
        genreJob?.cancel()
        loadMoreJob?.cancel()
        if (genreId == "Tất cả") {
            state = state.copy(
                activeGenreMovies = null,
                isLoadingGenre = false,
                genrePage = 1,
                genreTotalPages = 1
            )
            notifyActiveFragment()
            return
        }
        val genreObj = state.genres.firstOrNull { it.id == genreId }
        val hasRemoteSpec = genreObj != null && (
            genreObj.sourceSlug != null || genreObj.wpCategoryId != null ||
                genreObj.wpStatusId != null || genreObj.yanCategoryId != null || genreObj.yanStatusId != null
            )
        if (!hasRemoteSpec) {
            state = state.copy(
                activeGenreMovies = null,
                isLoadingGenre = false,
                genrePage = 1,
                genreTotalPages = 1
            )
            notifyActiveFragment()
            return
        }
        state = state.copy(isLoadingGenre = true, activeGenreMovies = null, genrePage = 1, genreTotalPages = 1)
        notifyActiveFragment()
        genreJob = lifecycleScope.launch {
            val pageRes = MovieRepository.fetchGenrePage(state.activeSourceId, genreObj, 1, 30, forceRefresh = true)
            state = state.copy(
                activeGenreMovies = pageRes.movies,
                genrePage = pageRes.page,
                genreTotalPages = pageRes.totalPages,
                isLoadingGenre = false
            )
            notifyActiveFragment()
        }
    }

    override fun openGenrePickerOverlay() {
        if (isSearchOverlayOpen) closeSearchOverlay()
        if (isSourcePickerOpen) closeSourcePickerOverlay()
        if (isGenrePickerOpen) {
            closeGenrePickerOverlay()
            return
        }
        isGenrePickerOpen = true

        val p = getPalette()
        val isTv = state.resolvedDevice == ResolvedDeviceProfile.TV
        binding.genrePickerScrim.setBackgroundColor(
            Color.argb(if (p.isDark) 82 else 36, 10, 15, 28)
        )
        binding.floatingGenrePickerContainer.background = ThemeManager.liquidGlassSheetDrawable(this, p, 22f)
        binding.txtGenrePickerTitle.setTextColor(p.textMain)
        binding.txtGenrePickerSyncStatus.setTextColor(p.accentFrom)
        binding.btnSyncGenresNow.background = ThemeManager.pillDrawable(this, p, active = false, radiusDp = 12f)
        binding.btnSyncGenresNow.setTextColor(p.accentFrom)
        binding.btnCloseGenrePicker.background = ThemeManager.navTabDrawable(this, p, active = false, radiusDp = 12f)
        binding.btnCloseGenrePicker.setTextColor(p.textMuted)

        val dm = resources.displayMetrics
        val maxAvailW = (dm.widthPixels - ThemeManager.dp(this, 24f)).coerceAtLeast(ThemeManager.dp(this, 260f))
        val targetW = when (state.resolvedDevice) {
            ResolvedDeviceProfile.MOBILE -> minOf(ThemeManager.dp(this, 390f), maxAvailW)
            ResolvedDeviceProfile.TABLET -> minOf(ThemeManager.dp(this, 500f), maxAvailW)
            ResolvedDeviceProfile.TV -> minOf(ThemeManager.dp(this, 600f), maxAvailW)
        }
        val maxH = (dm.heightPixels * 0.78f).toInt()
        val lp = binding.floatingGenrePickerContainer.layoutParams as FrameLayout.LayoutParams
        lp.width = targetW
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.gravity = android.view.Gravity.CENTER
        binding.floatingGenrePickerContainer.layoutParams = lp
        (binding.genrePickerGridContainer.parent as? android.widget.ScrollView)?.let { sv ->
            sv.layoutParams = sv.layoutParams.apply {
                height = minOf(maxH, ThemeManager.dp(this@MainActivity, 440f))
            }
        }

        val focusTarget = renderGenrePickerContent()
        binding.floatingGenrePickerOverlay.visibility = View.VISIBLE
        binding.floatingGenrePickerContainer.animate().cancel()
        val dur = animDuration(165L)
        if (dur == 0L) {
            binding.floatingGenrePickerOverlay.alpha = 1f
            binding.floatingGenrePickerContainer.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
        } else {
            binding.floatingGenrePickerOverlay.alpha = 0f
            binding.floatingGenrePickerOverlay.animate().alpha(1f).setDuration(dur).start()
            binding.floatingGenrePickerContainer.apply {
                alpha = 0f
                scaleX = 0.92f
                scaleY = 0.92f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(dur).start()
            }
        }
        if (isTv) focusTarget?.post { focusTarget.requestFocus() }
        // Đồng bộ nền theo thời gian thực ngay khi mở bảng Thể loại phim
        syncRealtimeTrendingNow(state.activeSourceId)
    }

    private fun renderGenrePickerContent(): View? {
        val p = getPalette()
        val srcName = state.sources.firstOrNull { it.id == state.activeSourceId }?.name ?: state.activeSourceId
        val syncTimeStr = if (state.genresLastSyncAtMs > 0L) {
            android.text.format.DateFormat.format("HH:mm:ss", java.util.Date(state.genresLastSyncAtMs)).toString()
        } else {
            "Đang kết nối"
        }
        binding.txtGenrePickerSyncStatus.text = "🟢 Đồng bộ từ $srcName • $syncTimeStr"

        val cols = when (state.resolvedDevice) {
            ResolvedDeviceProfile.MOBILE -> 2
            ResolvedDeviceProfile.TABLET -> 3
            ResolvedDeviceProfile.TV -> 4
        }
        val gap = ThemeManager.dp(this, 7f)
        val container = binding.genrePickerGridContainer
        container.removeAllViews()

        val list = state.genres.ifEmpty { InitialData.GENRES_DATA }
        var focusTarget: View? = null
        list.chunked(cols).forEachIndexed { rIdx, rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (rIdx > 0) topMargin = gap }
            }
            for (c in 0 until cols) {
                val item = rowItems.getOrNull(c)
                if (item != null) {
                    val active = state.selectedGenre == item.id
                    val displayLabel = if (item.id == "Tất cả") "Tất cả phim" else item.label
                    val chip = TextView(this).apply {
                        text = if (active) "✓ $displayLabel" else displayLabel
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        setTextColor(if (active) Color.WHITE else p.textMain)
                        background = ThemeManager.pillDrawable(this@MainActivity, p, active = active, radiusDp = 14f)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        isFocusable = true
                        isClickable = true
                        val padH = ThemeManager.dp(context, 10f)
                        val padV = ThemeManager.dp(context, 9f)
                        setPadding(padH, padV, padH, padV)
                        setOnClickListener { selectGenre(item.id) }
                    }
                    if (active) focusTarget = chip
                    if (focusTarget == null) focusTarget = chip
                    row.addView(
                        chip,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (c > 0) marginStart = gap
                        }
                    )
                } else {
                    row.addView(
                        View(this),
                        LinearLayout.LayoutParams(0, 0, 1f).apply {
                            if (c > 0) marginStart = gap
                        }
                    )
                }
            }
            container.addView(row)
        }
        return focusTarget
    }

    private fun closeGenrePickerOverlay() {
        if (!isGenrePickerOpen && binding.floatingGenrePickerOverlay.visibility != View.VISIBLE) return
        isGenrePickerOpen = false
        val dur = animDuration(130L)
        binding.floatingGenrePickerOverlay.animate().cancel()
        binding.floatingGenrePickerContainer.animate().cancel()
        if (dur == 0L) {
            binding.floatingGenrePickerOverlay.visibility = View.GONE
            binding.floatingGenrePickerOverlay.alpha = 1f
            binding.floatingGenrePickerContainer.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
            return
        }
        binding.floatingGenrePickerContainer.animate()
            .alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(dur).start()
        binding.floatingGenrePickerOverlay.animate()
            .alpha(0f)
            .setDuration(dur)
            .withEndAction {
                if (!isGenrePickerOpen) {
                    binding.floatingGenrePickerOverlay.visibility = View.GONE
                    binding.floatingGenrePickerOverlay.alpha = 1f
                    binding.floatingGenrePickerContainer.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
                }
            }
            .start()
    }

    override fun syncWebGenresNow() {
        val srcName = state.sources.firstOrNull { it.id == state.activeSourceId }?.name ?: state.activeSourceId
        binding.txtGenrePickerSyncStatus.text = "⏳ Đang đồng bộ từ $srcName..."
        lifecycleScope.launch {
            val freshCats = runCatching {
                MovieRepository.fetchCategoriesForSource(state.activeSourceId, forceRefresh = true)
            }.getOrDefault(emptyList())
            if (freshCats.isNotEmpty()) {
                state = state.copy(genres = freshCats, genresLastSyncAtMs = System.currentTimeMillis())
                notifyActiveFragment()
            }
            val selectedObj = state.genres.firstOrNull { it.id == state.selectedGenre }
            if (state.selectedGenre != "Tất cả" && selectedObj != null) {
                val freshPage = runCatching {
                    MovieRepository.fetchGenrePage(state.activeSourceId, selectedObj, 1, 30, forceRefresh = true)
                }.getOrNull()
                if (freshPage != null && freshPage.movies.isNotEmpty()) {
                    state = state.copy(
                        activeGenreMovies = freshPage.movies,
                        genrePage = freshPage.page,
                        genreTotalPages = freshPage.totalPages
                    )
                    notifyActiveFragment()
                }
            }
            if (isGenrePickerOpen) renderGenrePickerContent()
            showToast("Đã đồng bộ thể loại & phim mới từ $srcName")
        }
    }

    override fun setCatalogStatusFilter(filter: CatalogStatusFilter) {
        if (state.catalogStatusFilter == filter) return
        state = state.copy(catalogStatusFilter = filter)
        notifyActiveFragment()
    }

    override fun setCatalogAudioFilter(filter: CatalogAudioFilter) {
        if (state.catalogAudioFilter == filter) return
        state = state.copy(catalogAudioFilter = filter)
        notifyActiveFragment()
    }

    override fun setCatalogSortMode(mode: CatalogSortMode) {
        if (state.catalogSortMode == mode) return
        state = state.copy(catalogSortMode = mode)
        notifyActiveFragment()
    }

    override fun resetCatalogFilters() {
        val genreChanged = state.selectedGenre != "Tất cả"
        state = state.copy(
            catalogStatusFilter = CatalogStatusFilter.ALL,
            catalogAudioFilter = CatalogAudioFilter.ALL,
            catalogSortMode = CatalogSortMode.DEFAULT,
            searchSourceFilter = null
        )
        if (genreChanged) {
            selectGenre("Tất cả")
        } else {
            notifyActiveFragment()
        }
    }

    override fun setSearchSourceFilter(sourceName: String?) {
        val next = sourceName?.takeIf { it.isNotBlank() }
        if (state.searchSourceFilter == next) return
        state = state.copy(searchSourceFilter = next)
        notifyActiveFragment()
    }

    override fun loadMoreCatalog() {
        if (state.isLoadingFeed || state.isLoadingGenre || state.isLoadingMore || state.isSearching) return
        if (!state.hasMoreCatalogPages) return

        val snapshotSourceId = state.activeSourceId
        val snapshotGenre = state.selectedGenre
        val nextPage = state.activeCatalogPage + 1

        state = state.copy(isLoadingMore = true)
        notifyActiveFragment()

        loadMoreJob?.cancel()
        loadMoreJob = lifecycleScope.launch {
            try {
                if (snapshotGenre == "Tất cả") {
                    val pageRes = MovieRepository.fetchDonghuaPage(nextPage, 30, snapshotSourceId)
                    if (state.activeSourceId != snapshotSourceId || state.selectedGenre != snapshotGenre) return@launch
                    val merged = MovieRepository.mergeCatalogPages(state.allMovies, pageRes.movies)
                    val newTotalPages = if (pageRes.movies.isNotEmpty()) {
                        maxOf(state.catalogTotalPages, pageRes.totalPages)
                    } else {
                        state.catalogPage
                    }
                    state = state.copy(
                        allMovies = merged,
                        catalogPage = if (pageRes.movies.isNotEmpty()) nextPage else state.catalogPage,
                        catalogTotalPages = newTotalPages,
                        isLoadingMore = false
                    )
                } else {
                    val genreObj = state.genres.firstOrNull { it.id == snapshotGenre }
                    if (genreObj == null) {
                        state = state.copy(isLoadingMore = false)
                        notifyActiveFragment()
                        return@launch
                    }
                    val pageRes = MovieRepository.fetchGenrePage(snapshotSourceId, genreObj, nextPage, 30)
                    if (state.activeSourceId != snapshotSourceId || state.selectedGenre != snapshotGenre) return@launch
                    val merged = MovieRepository.mergeCatalogPages(state.activeGenreMovies.orEmpty(), pageRes.movies)
                    val newTotalPages = if (pageRes.movies.isNotEmpty()) {
                        maxOf(state.genreTotalPages, pageRes.totalPages)
                    } else {
                        state.genrePage
                    }
                    state = state.copy(
                        activeGenreMovies = merged,
                        genrePage = if (pageRes.movies.isNotEmpty()) nextPage else state.genrePage,
                        genreTotalPages = newTotalPages,
                        isLoadingMore = false
                    )
                }
            } catch (_: Exception) {
                state = state.copy(isLoadingMore = false)
            }
            notifyActiveFragment()
        }
    }

    override fun selectSource(sourceId: String) {
        genreJob?.cancel()
        loadMoreJob?.cancel()
        state = state.copy(
            activeSourceId = sourceId,
            selectedGenre = "Tất cả",
            activeGenreMovies = null,
            catalogPage = 1,
            catalogTotalPages = 1,
            genrePage = 1,
            genreTotalPages = 1,
            isLoadingGenre = false,
            isLoadingMore = false
        )
        updateSettings { it.copy(preferredSource = sourceId) }
        setupSourceSpinner()
        val srcName = state.sources.firstOrNull { it.id == sourceId }?.name ?: sourceId
        showToast("Đã kết nối nguồn ưu tiên: $srcName")
        loadLiveDonghuaFeed(sourceId)
    }

    override fun openMovieDetail(movie: DonghuaMovie) {
        startActivity(MovieDetailActivity.createIntent(this, movie))
    }

    override fun quickPlayMovie(movie: DonghuaMovie, episode: Int, serverIdx: Int, resumeTimeSec: Double) {
        startActivity(PlayerActivity.createIntent(this, movie, episode, serverIdx, resumeTimeSec))
    }

    override fun resolveMovieForProgress(progress: WatchProgress): DonghuaMovie {
        state.allMovies.firstOrNull { it.id == progress.donghuaId }?.let { return it }
        val normTitle = VietnameseSearch.removeVietnameseTones(progress.title)
        state.allMovies.firstOrNull {
            it.title.equals(progress.title, ignoreCase = true) ||
                (normTitle.isNotBlank() && VietnameseSearch.removeVietnameseTones(it.title) == normTitle)
        }?.let { return it }
        val safePoster = if (MovieRepository.isDeadOrBlankPoster(progress.poster)) "" else progress.poster
        val guessedSlug = when {
            progress.donghuaId.startsWith("yannow_") -> progress.donghuaId.removePrefix("yannow_")
            progress.donghuaId.startsWith("yan_") -> progress.donghuaId.removePrefix("yan_")
            else -> VietnameseSearch.removeVietnameseTones(progress.title).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
        return DonghuaMovie(
            id = progress.donghuaId,
            slug = guessedSlug.ifBlank { null },
            title = progress.title.ifBlank { "HoatHinh3D" },
            originalTitle = "${progress.server} • ${progress.audio}",
            studio = progress.server.ifBlank { "HoatHinh3D" },
            latestEp = progress.episode.coerceAtLeast(1),
            totalEp = progress.episode.coerceAtLeast(1),
            rating = 9.7,
            views = "Đã xem",
            viewsNum = 1.0,
            quality = "1080p",
            audioModes = listOf(progress.audio.ifBlank { "Thuyết Minh" }),
            sources = listOf(state.activeSourceId),
            genres = listOf("Tu Tiên"),
            scheduleDay = "Hàng ngày",
            scheduleTime = "10:00",
            poster = safePoster,
            thumb = safePoster,
            streamIndex = 0
        )
    }

    override fun toggleFavorite(movieId: String) {
        val isFav = PrefsStore.toggleFavorite(movieId)
        val favs = PrefsStore.getFavorites()
        var metaMap = state.favoriteMetaMap
        if (isFav) {
            val m = state.allMovies.firstOrNull { it.id == movieId }
            metaMap = PrefsStore.markFavoriteEpisodeSeen(movieId, m?.latestEp ?: 1)
        }
        state = state.copy(favorites = favs, favoriteMetaMap = metaMap)
        maybeAutoCloudSync()
        updateBottomNavUi()
        notifyActiveFragment()
        showToast(if (isFav) "Đã thêm vào Yêu thích ❤️" else "Đã xóa khỏi Yêu thích")
    }

    override fun checkFavoritesNewEpisodes() {
        if (state.favorites.isEmpty()) return
        state = state.copy(isCheckingFavUpdates = true)
        notifyActiveFragment()
        lifecycleScope.launch {
            try {
                val res = MovieRepository.checkFavoritesNewEpisodes(state.favorites, state.allMovies, 6)
                val updatedMap = state.allMovies.map { m ->
                    res.updatedMovies.firstOrNull { it.id == m.id } ?: m
                }
                state = state.copy(
                    favoriteMetaMap = res.metaMap,
                    allMovies = updatedMap,
                    isCheckingFavUpdates = false
                )
                val totalNew = res.metaMap.values.sumOf { it.newEpCount }
                when {
                    res.newlyUpdatedTitles.isNotEmpty() ->
                        showToast("Có tập mới: ${res.newlyUpdatedTitles.take(2).joinToString(", ")}")
                    totalNew > 0 ->
                        showToast("Mục Yêu thích đang có $totalNew tập mới chưa xem!")
                    else ->
                        showToast("Tất cả phim yêu thích đã cập nhật mới nhất!")
                }
            } catch (_: Exception) {
                state = state.copy(isCheckingFavUpdates = false)
            }
            notifyActiveFragment()
        }
    }

    override fun playOfflineRecord(record: OfflineEpisodeRecord) {
        val existing = state.allMovies.firstOrNull { it.id == record.donghuaId }
        val offlineServer = ServerGroup(
            serverName = "${record.serverName} (Offline)",
            episodes = listOf(
                com.hoathinh3d.donghua.data.model.EpisodeItem(
                    name = record.episodeName,
                    slug = "tap-${record.episodeNum}",
                    linkM3u8 = record.streamUrl
                )
            )
        )
        val movieToPlay = existing?.copy(serverGroups = listOf(offlineServer)) ?: DonghuaMovie(
            id = record.donghuaId,
            title = record.title,
            originalTitle = "Phát từ bộ nhớ đệm Offline",
            studio = "Offline Cache",
            latestEp = record.episodeNum,
            totalEp = record.episodeNum,
            rating = 9.8,
            views = "Offline",
            viewsNum = 0.0,
            quality = "Offline HLS",
            audioModes = listOf("Thuyết Minh"),
            sources = listOf("Offline"),
            genres = listOf("Tu Tiên"),
            scheduleDay = "Chủ Nhật",
            scheduleTime = "00:00",
            poster = record.poster,
            streamIndex = 0,
            serverGroups = listOf(offlineServer)
        )
        quickPlayMovie(movieToPlay, record.episodeNum, 0)
    }

    override fun removeOfflineRecord(key: String) {
        HlsOfflineManager.removeOfflineEpisode(this, key)
        state = state.copy(offlineEpisodes = PrefsStore.getOfflineEpisodes())
        notifyActiveFragment()
        showToast("Đã xóa bản lưu offline")
    }

    override fun removeHistoryItem(donghuaId: String) {
        PrefsStore.removeHistoryItem(donghuaId)
        state = state.copy(watchHistory = PrefsStore.getHistory())
        maybeAutoCloudSync()
        notifyActiveFragment()
        showToast("Đã xóa phim khỏi lịch sử xem")
    }

    override fun clearHistory() {
        PrefsStore.clearHistory()
        state = state.copy(watchHistory = emptyMap())
        maybeAutoCloudSync()
        notifyActiveFragment()
        showToast("Đã xóa toàn bộ lịch sử xem")
    }

    override fun clearSearch() {
        searchJob?.cancel()
        binding.editSearch.setText("")
        state = state.copy(
            searchQuery = "",
            remoteSearchResults = emptyList(),
            searchSourceFilter = null,
            isSearching = false
        )
        notifyActiveFragment()
    }

    private fun toggleQuickDarkMode() {
        val isCurrentlyDark = getPalette().isDark
        val nextPreset = if (isCurrentlyDark) {
            com.hoathinh3d.donghua.data.model.ThemePreset.PEARL
        } else {
            com.hoathinh3d.donghua.data.model.ThemePreset.DARK
        }
        val nextDarkWire = if (nextPreset == com.hoathinh3d.donghua.data.model.ThemePreset.DARK) "dark" else "light"
        updateSettings { it.copy(theme = nextPreset.wireValue, darkMode = nextDarkWire) }
        showToast("Đã bật: ${nextPreset.displayName}")
    }

    private fun showQuickThemePickerDialog() {
        val presets = com.hoathinh3d.donghua.data.model.ThemePreset.entries
        val labels = presets.map { "${it.displayName} — ${it.desc}" }.toTypedArray()
        val curIdx = if (getPalette().isDark) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Chọn Chế Độ Giao Diện")
            .setSingleChoiceItems(labels, curIdx) { dlg, which ->
                val picked = presets[which]
                val nextDark = if (picked == com.hoathinh3d.donghua.data.model.ThemePreset.DARK) "dark" else "light"
                updateSettings { it.copy(theme = picked.wireValue, darkMode = nextDark) }
                showToast("Đã chuyển giao diện: ${picked.displayName}")
                dlg.dismiss()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showExitConfirmDialog() {
        val p = getPalette()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ThemeManager.dp(context, 18f)
            setPadding(pad, pad, pad, pad)
            background = ThemeManager.liquidGlassSheetDrawable(context, p, 22f)
        }
        val titleTv = TextView(this).apply {
            text = "Thoát HoatHinh3D Universal?"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(p.textMain)
        }
        val msgTv = TextView(this).apply {
            text = "Tiến độ xem, lịch sử và bộ nhớ đệm HLS của bạn đã được lưu an toàn."
            textSize = 12.5f
            setTextColor(p.textMuted)
            setPadding(0, ThemeManager.dp(context, 6f), 0, ThemeManager.dp(context, 14f))
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val dialog = AlertDialog.Builder(this).setView(box).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        val stayBtn = android.widget.Button(this).apply {
            text = "Ở lại"
            isAllCaps = false
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = ThemeManager.accentGradientDrawable(context, p, 12f)
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        val exitBtn = android.widget.Button(this).apply {
            text = "Thoát ứng dụng"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.parseColor("#F43F5E"))
            background = ThemeManager.tintedFocusDrawable(context, Color.parseColor("#26F43F5E"), 12f, Color.parseColor("#F43F5E"))
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                finish()
            }
        }
        btnRow.addView(
            exitBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ThemeManager.dp(this, 38f)).apply {
                marginEnd = ThemeManager.dp(this@MainActivity, 8f)
            }
        )
        btnRow.addView(
            stayBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ThemeManager.dp(this@MainActivity, 38f))
        )
        box.addView(titleTv)
        box.addView(msgTv)
        box.addView(btnRow)
        dialog.show()
        stayBtn.post { stayBtn.requestFocus() }
    }

    private fun maybeAutoCloudSync() {
        val u = state.user ?: return
        if (!state.settings.cloudAutoSync) return
        val ts = PrefsStore.saveCloudSnapshot()
        val synced = u.copy(lastSync = ts)
        PrefsStore.saveUser(synced)
        state = state.copy(user = synced)
    }

    override fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = PrefsStore.saveSettings(transform(state.settings))
        val nextDevice = DeviceProfileResolver.resolve(this, next.deviceMode)
        state = state.copy(settings = next, resolvedDevice = nextDevice)
        maybeAutoCloudSync()
        applyThemeAndDeviceLayout()
        notifyActiveFragment()
    }

    override fun refreshSources() {
        val updatedSources = PrefsStore.getSources()
        val activeStillEnabled = updatedSources.any { it.id == state.activeSourceId && it.enabled }
        state = state.copy(sources = updatedSources)
        maybeAutoCloudSync()
        if (!activeStillEnabled) {
            val fallbackSrc = updatedSources.firstOrNull { it.enabled }?.id
            if (fallbackSrc != null && fallbackSrc != state.activeSourceId) {
                selectSource(fallbackSrc)
                return
            }
        }
        setupSourceSpinner()
        notifyActiveFragment()
    }

    override fun refreshCustomMovies() {
        val custom = PrefsStore.getCustomMovies()
        val merged = MovieRepository.mergeCatalogPages(custom, state.allMovies)
        state = state.copy(allMovies = merged)
        maybeAutoCloudSync()
        notifyActiveFragment()
    }

    override fun reloadAllPersistedState() {
        val loadedSources = PrefsStore.getSources()
        val savedSettings = PrefsStore.getSettings()
        val preferredOk = loadedSources.firstOrNull { it.id == savedSettings.preferredSource && it.enabled }
        val targetSource = preferredOk?.id
            ?: loadedSources.firstOrNull { it.enabled }?.id
            ?: state.activeSourceId
        val sourceChanged = targetSource != state.activeSourceId

        state = state.copy(
            sources = loadedSources,
            activeSourceId = targetSource,
            settings = savedSettings,
            favorites = PrefsStore.getFavorites(),
            favoriteMetaMap = PrefsStore.getFavoriteMetaMap(),
            offlineEpisodes = PrefsStore.getOfflineEpisodes(),
            watchHistory = PrefsStore.getHistory(),
            allMovies = MovieRepository.mergeCatalogPages(PrefsStore.getCustomMovies(), state.allMovies),
            user = PrefsStore.getUser(),
            resolvedDevice = DeviceProfileResolver.resolve(this, savedSettings.deviceMode)
        )
        applyThemeAndDeviceLayout()
        updateBottomNavUi()
        notifyActiveFragment()
        if (sourceChanged) {
            loadLiveDonghuaFeed(targetSource)
        }
    }

    override fun reloadActiveSourceFeed() {
        loadLiveDonghuaFeed(state.activeSourceId)
    }

    override fun updateUser(user: UserProfile?) {
        PrefsStore.saveUser(user)
        state = state.copy(user = user)
        notifyActiveFragment()
    }

    override fun openGoogleLoginDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = ThemeManager.dp(context, 16f)
            setPadding(p, p, p, p)
        }
        val current = state.user
        val nameInput = EditText(this).apply {
            hint = "Tên hiển thị (VD: Đạo Hữu Tu Tiên)"
            if (current != null) setText(current.name)
        }
        val emailInput = EditText(this).apply {
            hint = "Email đồng bộ (VD: tenban@gmail.com)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            if (current != null) setText(current.email)
        }
        box.addView(nameInput)
        box.addView(emailInput)

        AlertDialog.Builder(this)
            .setTitle(if (current == null) "Liên kết Email Đồng Bộ" else "Cập nhật Email Đồng Bộ")
            .setMessage("Sao lưu lịch sử xem, yêu thích, cài đặt & gửi mã đồng bộ qua tài khoản Email")
            .setView(box)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu & Đồng bộ") { _, _ ->
                val finalName = nameInput.text.toString().trim().ifEmpty { "Đạo Hữu Tu Tiên" }
                val rawEmail = emailInput.text.toString().trim()
                val finalEmail = if (rawEmail.contains("@") && rawEmail.contains(".")) {
                    rawEmail
                } else if (rawEmail.isEmpty()) {
                    "daohuu.donghua3d@gmail.com"
                } else {
                    "$rawEmail@gmail.com"
                }
                val newUser = UserProfile(
                    name = finalName,
                    email = finalEmail,
                    avatar = finalName.first().uppercaseChar().toString(),
                    lastSync = System.currentTimeMillis()
                )
                PrefsStore.saveUser(newUser)
                val ts = PrefsStore.saveCloudSnapshot()
                val synced = newUser.copy(lastSync = ts)
                PrefsStore.saveUser(synced)
                state = state.copy(user = synced)
                notifyActiveFragment()
                showToast("Đã liên kết Email ${synced.email} & sao lưu dữ liệu!")
            }
            .show()
    }

    override fun checkAppUpdate(manual: Boolean) {
        val manifestUrl = com.hoathinh3d.donghua.core.AppUpdateChecker.resolveEffectiveManifestUrl(
            state.settings.updateManifestUrl
        )

        if (manifestUrl.substringBefore('?').endsWith(".apk", ignoreCase = true)) {
            if (manual) {
                val directManifest = com.hoathinh3d.donghua.core.UpdateManifest(
                    versionCode = BuildConfig.VERSION_CODE + 1,
                    versionName = "Mới (APK trực tiếp)",
                    apkUrl = manifestUrl,
                    changelog = "Tải và cài đặt trực tiếp từ URL đã cấu hình."
                )
                showAvailableUpdateDialog(directManifest)
            }
            return
        }

        if (manual) {
            showToast("Đang kiểm tra phiên bản mới trên GitHub...")
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.hoathinh3d.donghua.core.AppUpdateChecker.checkForUpdate(manifestUrl)
            }
            if (isFinishing || isDestroyed) return@launch
            when (result) {
                is com.hoathinh3d.donghua.core.UpdateCheckResult.Available -> {
                    showAvailableUpdateDialog(result.manifest)
                }
                is com.hoathinh3d.donghua.core.UpdateCheckResult.UpToDate -> {
                    if (manual) {
                        showToast("Bạn đang dùng phiên bản mới nhất trên Git (v${result.currentVersion}, build ${BuildConfig.VERSION_CODE})")
                    }
                }
                is com.hoathinh3d.donghua.core.UpdateCheckResult.NotConfigured -> {
                    if (manual) showToast(result.reason)
                }
                is com.hoathinh3d.donghua.core.UpdateCheckResult.Error -> {
                    if (manual) showToast(result.reason)
                }
            }
        }
    }

    private fun showAvailableUpdateDialog(manifest: com.hoathinh3d.donghua.core.UpdateManifest) {
        if (isFinishing || isDestroyed) return
        val note = manifest.changelog.ifBlank { "Cải thiện hiệu năng & sửa lỗi." }
        val shaNote = if (manifest.sha256.isNotBlank()) {
            "\n• Xác thực SHA-256: Bật (${manifest.sha256.take(12)}...)"
        } else ""
        AlertDialog.Builder(this)
            .setTitle("Có phiên bản mới: v${manifest.versionName}")
            .setMessage(
                "Hiện tại: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                    "Mới nhất: v${manifest.versionName} (build ${manifest.versionCode})$shaNote\n\n$note"
            )
            .setNegativeButton("Để sau", null)
            .setPositiveButton("Tải & Cài đặt") { _, _ ->
                startDownloadAndInstallUpdate(manifest)
            }
            .show()
    }

    private fun startDownloadAndInstallUpdate(manifest: com.hoathinh3d.donghua.core.UpdateManifest) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallManifest = manifest
            AlertDialog.Builder(this)
                .setTitle("Cấp quyền cài đặt APK")
                .setMessage("Android yêu cầu bật quyền 'Cài đặt ứng dụng không rõ nguồn gốc' cho HoatHinh3D để tự cập nhật. Sau khi bật và quay lại, app sẽ tự tải về và cài đặt ngay.")
                .setNegativeButton("Hủy") { _, _ -> pendingInstallManifest = null }
                .setPositiveButton("Mở Cài đặt") { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    }.onFailure {
                        pendingInstallManifest = null
                        showToast("Vui lòng bật quyền Cài đặt nguồn không xác định trong Cài đặt hệ thống")
                    }
                }
                .show()
            return
        }

        pendingInstallManifest = null
        val statusTv = android.widget.TextView(this).apply {
            text = "Đang tải v${manifest.versionName} từ GitHub..."
            val p = ThemeManager.dp(context, 16f)
            setPadding(p, p, p, p)
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Đang tải bản cập nhật")
            .setView(statusTv)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val apkFile = withContext(Dispatchers.IO) {
                com.hoathinh3d.donghua.core.AppUpdateChecker.downloadApkToCache(
                    this@MainActivity,
                    manifest.apkUrl
                ) { pct, downloaded, total ->
                    val dlMb = String.format(java.util.Locale.US, "%.1f", downloaded / (1024.0 * 1024.0))
                    val line = if (pct >= 0 && total > 0L) {
                        val totalMb = String.format(java.util.Locale.US, "%.1f", total / (1024.0 * 1024.0))
                        "Đang tải v${manifest.versionName}... $pct% ($dlMb / $totalMb MB)"
                    } else {
                        "Đang tải v${manifest.versionName}... $dlMb MB"
                    }
                    runOnUiThread {
                        if (progressDialog.isShowing) {
                            statusTv.text = line
                        }
                    }
                }
            }
            if (apkFile == null || !apkFile.exists()) {
                if (progressDialog.isShowing) progressDialog.dismiss()
                showToast("Tải APK cập nhật thất bại!")
                return@launch
            }
            if (manifest.sha256.isNotBlank()) {
                if (progressDialog.isShowing) {
                    statusTv.text = "Đang xác thực toàn vẹn SHA-256..."
                }
                val validSha = withContext(Dispatchers.IO) {
                    com.hoathinh3d.donghua.core.AppUpdateChecker.verifySha256(apkFile, manifest.sha256)
                }
                if (!validSha) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    runCatching { apkFile.delete() }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Lỗi bảo mật cập nhật")
                        .setMessage("Tệp APK tải về không khớp mã kiểm tra toàn vẹn SHA-256. Đã hủy cài đặt để đảm bảo an toàn.")
                        .setPositiveButton("Đóng", null)
                        .show()
                    return@launch
                }
            }
            if (progressDialog.isShowing) {
                progressDialog.dismiss()
            }
            runCatching {
                val installIntent = com.hoathinh3d.donghua.core.AppUpdateChecker.buildInstallIntent(
                    this@MainActivity,
                    apkFile
                )
                startActivity(installIntent)
            }.onFailure { err ->
                showToast("Không mở được trình cài đặt APK: ${err.message ?: "Lỗi"}")
            }
        }
    }

    override fun navigateTab(tab: MainTab) {
        closeSearchOverlay()
        closeGenrePickerOverlay()
        if (state.activeTab == tab) {
            setChromeCollapsed(false)
            if (tab == MainTab.SETTINGS &&
                state.resolvedDevice == ResolvedDeviceProfile.MOBILE &&
                state.isSettingsDetailOpen
            ) {
                closeSettingsDetail()
            }
            return
        }
        state = state.copy(
            activeTab = tab,
            isSettingsDetailOpen = if (tab == MainTab.SETTINGS) false else state.isSettingsDetailOpen
        )
        setChromeCollapsed(false)
        updateBottomNavUi()
        showTabFragment(tab)
    }

    override fun setSettingsSubtab(subtab: SettingsSubtab) {
        state = state.copy(settingsSubtab = subtab, isSettingsDetailOpen = true)
        notifyActiveFragment()
    }

    override fun closeSettingsDetail() {
        if (!state.isSettingsDetailOpen) return
        state = state.copy(isSettingsDetailOpen = false)
        notifyActiveFragment()
    }

    override fun openSamplePlayer() {
        val latestProg = state.watchHistory.values.maxByOrNull { it.updatedAt }
        if (latestProg != null) {
            quickPlayMovie(resolveMovieForProgress(latestProg), latestProg.episode, -1, latestProg.currentTime)
            return
        }
        val first = state.allMovies.firstOrNull()
        if (first != null) {
            quickPlayMovie(first, 1, -1)
        } else {
            showToast("Đang nạp kho phim, vui lòng thử lại sau giây lát!")
        }
    }

    override fun showToast(msg: String) {
        binding.toastBanner.text = "✓ $msg"
        binding.toastBanner.visibility = View.VISIBLE
        toastHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { binding.toastBanner.visibility = View.GONE }
        toastHideRunnable = r
        mainHandler.postDelayed(r, 3000L)
    }

    override fun onDestroy() {
        searchJob?.cancel()
        genreJob?.cancel()
        loadMoreJob?.cancel()
        realtimeTrendingJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        LanSyncManager.stopLanSyncServer()
        if (isFinishing && state.settings.autoClearCacheOnExit) {
            val appCtx = applicationContext
            runCatching { com.bumptech.glide.Glide.get(appCtx).clearMemory() }
            Thread {
                runCatching { com.hoathinh3d.donghua.player.HlsCacheProvider.clearCache(appCtx) }
                runCatching { com.hoathinh3d.donghua.core.AppUpdateChecker.cleanupOldUpdates(appCtx) }
                runCatching { com.bumptech.glide.Glide.get(appCtx).clearDiskCache() }
            }.start()
        }
        super.onDestroy()
    }

    companion object {
        val HOT_KEYWORDS = listOf(
            "Đấu Phá Thương Khung",
            "Tiên Nghịch",
            "Thế Giới Hoàn Mỹ",
            "Thôn Phệ Tinh Không",
            "Già Thiên",
            "Phàm Nhân Tu Tiên",
            "Bách Luyện Thành Thần",
            "Vạn Giới Độc Tôn",
            "Tuyệt Thế Đường Môn",
            "Nhất Niệm Vĩnh Hằng"
        )
    }
}

interface RefreshableTab {
    fun refreshUi()
}

interface ChromeInsetsListener {
    fun onChromeInsetsChanged(topPx: Int, bottomPx: Int)
}
