package com.hoathinh3d.donghua.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.core.ThemePalette
import com.hoathinh3d.donghua.data.model.CatalogAudioFilter
import com.hoathinh3d.donghua.data.model.CatalogSortMode
import com.hoathinh3d.donghua.data.model.CatalogStatusFilter
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile
import com.hoathinh3d.donghua.data.source.CatalogQueryEngine
import com.hoathinh3d.donghua.databinding.FragmentHomeBinding
import com.hoathinh3d.donghua.databinding.LayoutHomeFooterBinding
import com.hoathinh3d.donghua.databinding.LayoutHomeHeaderBinding
import com.hoathinh3d.donghua.ui.AppHost
import com.hoathinh3d.donghua.ui.ChromeInsetsListener
import com.hoathinh3d.donghua.ui.MainState
import com.hoathinh3d.donghua.ui.RefreshableTab
import com.hoathinh3d.donghua.ui.common.ContinueWatchingAdapter
import com.hoathinh3d.donghua.ui.common.GenreChipAdapter
import com.hoathinh3d.donghua.ui.common.MovieCardAdapter
import com.hoathinh3d.donghua.ui.common.MovieCardUiMeta
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), RefreshableTab, ChromeInsetsListener {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val host: AppHost? get() = activity as? AppHost

    private var headerBinding: LayoutHomeHeaderBinding? = null
    private var footerBinding: LayoutHomeFooterBinding? = null

    private lateinit var headerAdapter: HomeHeaderAdapter
    private lateinit var catalogAdapter: MovieCardAdapter
    private lateinit var footerAdapter: HomeFooterAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private var continueAdapter: ContinueWatchingAdapter? = null
    private var trendingAdapter: MovieCardAdapter? = null
    private var genreAdapter: GenreChipAdapter? = null

    private var heroIdx = 0
    private var heroTimerJob: Job? = null
    private var lastHeroSlides: List<DonghuaMovie> = emptyList()
    private var lastHeroSlidesKey: String? = null
    private var lastHeroImgUrl: String? = null
    private var lastTvCarouselsKey: String? = null
    private var lastTrendingRenderSig: String? = null
    private var lastCatalogRenderSig: String? = null
    private var lastGenreBarKey: String? = null
    private var lastFilterBarKey: String? = null
    private var lastSearchSourceKey: String? = null
    private val tvRowAdapters = mutableListOf<MovieCardAdapter>()
    private val sharedTvCardPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 12) }
    private var currentSpanCount = 2

    private var lastFilteredCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val h = host ?: return

        headerAdapter = HomeHeaderAdapter()
        catalogAdapter = MovieCardAdapter(
            fixedWidthDp = null,
            onSelect = { h.openMovieDetail(it) },
            onToggleFavorite = { h.toggleFavorite(it.id) }
        )
        footerAdapter = HomeFooterAdapter()
        concatAdapter = ConcatAdapter(headerAdapter, catalogAdapter, footerAdapter)

        currentSpanCount = DeviceProfileResolver.gridSpanCount(h.getState().resolvedDevice)
        val glm = GridLayoutManager(requireContext(), currentSpanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val lastPos = concatAdapter.itemCount - 1
                    return if (position == 0 || position == lastPos) currentSpanCount else 1
                }
            }
        }

        val isUltraLow = DeviceProfileResolver.isUltraLowRamDevice(requireContext())
        val isLite = DeviceProfileResolver.shouldUseReducedMotion(
            requireContext(),
            h.getState().resolvedDevice,
            h.getState().settings.reducedMotion
        )
        binding.rvHomeFeed.layoutManager = glm
        binding.rvHomeFeed.adapter = concatAdapter
        binding.rvHomeFeed.setItemViewCacheSize(if (isUltraLow) 4 else if (isLite) 8 else 16)

        binding.rvHomeFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                host?.onMainContentScroll(dy, recyclerView.computeVerticalScrollOffset())
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible >= concatAdapter.itemCount - (currentSpanCount * 2 + 1)) {
                        host?.loadMoreCatalog()
                    }
                }
            }
        })

        refreshUi()
    }

    override fun onChromeInsetsChanged(topPx: Int, bottomPx: Int) {
        val b = _binding ?: return
        val h = host ?: return
        val isTv = h.getState().resolvedDevice == ResolvedDeviceProfile.TV
        val hPad = ThemeManager.dp(requireContext(), if (isTv) 16f else 14f)
        b.rvHomeFeed.setPadding(hPad, topPx, hPad, bottomPx)
    }

    override fun refreshUi() {
        val b = _binding ?: return
        val h = host ?: return
        val st = h.getState()
        val p = h.getPalette()
        val isTv = st.resolvedDevice == ResolvedDeviceProfile.TV
        val isLite = DeviceProfileResolver.shouldUseReducedMotion(
            requireContext(),
            st.resolvedDevice,
            st.settings.reducedMotion
        )

        val hPad = ThemeManager.dp(requireContext(), if (isTv) 16f else 14f)
        val (chromeTop, chromeBot) = h.getChromeInsets()
        b.rvHomeFeed.setPadding(hPad, chromeTop, hPad, chromeBot)

        val desiredSpan = DeviceProfileResolver.gridSpanCount(st.resolvedDevice)
        if (desiredSpan != currentSpanCount) {
            currentSpanCount = desiredSpan
            (b.rvHomeFeed.layoutManager as? GridLayoutManager)?.spanCount = desiredSpan
        }

        val filtered = CatalogQueryEngine.filterAndSort(
            allMovies = st.allMovies,
            activeGenreMovies = st.activeGenreMovies,
            remoteSearchResults = st.remoteSearchResults,
            selectedGenre = st.selectedGenre,
            searchQuery = st.searchQuery,
            audioFilter = st.catalogAudioFilter,
            sortMode = st.catalogSortMode,
            statusFilter = st.catalogStatusFilter,
            searchSourceFilter = st.searchSourceFilter
        )
        lastFilteredCount = filtered.size

        val metaRenderSig = buildString {
            append(p.preset.wireValue).append('|')
            append(p.isDark).append('|')
            append(p.glassOpacityPct).append('|')
            append(p.glassBlurPx).append('|')
            append(isTv).append('|')
            append(isLite).append('|')
            append(st.favorites.joinToString(",")).append('|')
            append(st.watchHistory.entries.joinToString(",") { "${it.key}:${it.value.progressPct}" })
        }

        bindHeaderContent(st, p, isTv, filtered.size, metaRenderSig)

        catalogAdapter.palette = p
        catalogAdapter.isTvMode = isTv
        catalogAdapter.reducedMotion = isLite
        catalogAdapter.metaProvider = { m, _ ->
            MovieCardUiMeta(
                isFavorite = st.favorites.contains(m.id),
                progressPct = st.watchHistory[m.id]?.progressPct
            )
        }
        catalogAdapter.submitList(filtered)
        if (metaRenderSig != lastCatalogRenderSig) {
            lastCatalogRenderSig = metaRenderSig
            catalogAdapter.notifyDataSetChanged()
        }

        bindFooterContent(p, st, filtered.size, st.searchQuery.trim().isNotEmpty())
    }

    private fun bindHeaderContent(
        st: MainState,
        p: ThemePalette,
        isTv: Boolean,
        filteredCount: Int,
        metaRenderSig: String
    ) {
        val b = headerBinding ?: return
        val h = host ?: return
        val ctx = b.root.context

        val desiredHeroH = ThemeManager.dp(
            ctx,
            when (st.resolvedDevice) {
                ResolvedDeviceProfile.MOBILE -> 200f
                ResolvedDeviceProfile.TABLET -> 230f
                ResolvedDeviceProfile.TV -> 244f
            }
        )
        if (b.heroCard.layoutParams.height != desiredHeroH) {
            b.heroCard.layoutParams = b.heroCard.layoutParams.apply { height = desiredHeroH }
        }

        val isSearchActive = st.searchQuery.trim().isNotEmpty()
        b.txtLoadingFeedBanner.visibility = View.GONE

        if (isSearchActive) {
            b.searchStatusPanel.visibility = View.VISIBLE
            b.searchStatusPanel.background = ThemeManager.glassPanelDrawable(ctx, p, 20f)
            b.txtSearchHeaderTitle.text = "Kết quả tìm kiếm: “${st.searchQuery.trim()}”"
            b.txtSearchHeaderTitle.setTextColor(p.textMain)
            val srcNote = st.searchSourceFilter?.let { " • Nguồn: $it" } ?: ""
            b.txtSearchHeaderSub.text = if (st.isSearching) {
                "Đang quét đồng thời trên tất cả các nguồn phim..."
            } else {
                "Tìm thấy $filteredCount phim khớp nhất$srcNote (hỗ trợ có dấu, không dấu & viết tắt)"
            }
            b.txtSearchHeaderSub.setTextColor(p.textMuted)
            b.btnHomeClearSearch.backgroundTintList = null
            b.btnHomeClearSearch.background = ThemeManager.tintedFocusDrawable(
                ctx,
                Color.parseColor("#33F43F5E"),
                12f
            )
            renderMultiSearchSourceFilter(b, st, p)
        } else {
            lastSearchSourceKey = null
            b.searchStatusPanel.visibility = View.GONE
        }

        val trending = if (st.trendingTop10.isNotEmpty()) {
            st.trendingTop10.take(10)
        } else {
            st.allMovies.sortedByDescending { it.viewsNum }.take(10)
        }
        val heroSlides = trending.take(5)
        lastHeroSlides = heroSlides

        if (st.settings.showHomeHero && !isSearchActive && heroSlides.isNotEmpty()) {
            b.heroCard.visibility = View.VISIBLE
            val heroKey = "${p.preset.wireValue}_${p.isDark}_${heroSlides.joinToString(",") { "${it.id}:${it.latestEp}" }}"
            if (heroKey != lastHeroSlidesKey) {
                lastHeroSlidesKey = heroKey
                b.btnHeroPlay.background = ThemeManager.accentGradientDrawable(ctx, p, 16f)
                val frostedDarkHero = p.copy(
                    isDark = true,
                    glassSurface = Color.argb(115, 15, 23, 42)
                )
                b.btnHeroDetail.background = ThemeManager.liquidGlassSheetDrawable(ctx, frostedDarkHero, 16f)
                renderHeroSlide(heroSlides[heroIdx % heroSlides.size], heroSlides.size)
            }
            if (heroTimerJob?.isActive != true) {
                startHeroAutoRotate()
            }
        } else {
            b.heroCard.visibility = View.GONE
            lastHeroSlidesKey = null
            heroTimerJob?.cancel()
        }

        val continueList = st.watchHistory.values.sortedByDescending { it.updatedAt }
        if (st.settings.showHomeContinue && !isSearchActive && continueList.isNotEmpty()) {
            b.continuePanel.visibility = View.VISIBLE
            b.continuePanel.background = ThemeManager.glassPanelDrawable(ctx, p, 20f)
            b.txtContinueHeading.text = "Tiếp tục xem (${continueList.size} phim)"
            b.txtContinueHeading.setTextColor(p.textMain)
            b.btnContinueClear.setTextColor(p.textMuted)
            b.btnContinueClear.background = ThemeManager.navTabDrawable(ctx, p, false, 10f)
            continueAdapter?.apply {
                palette = p
                isTvMode = isTv
                reducedMotion = DeviceProfileResolver.shouldUseReducedMotion(
                    requireContext(),
                    st.resolvedDevice,
                    st.settings.reducedMotion
                )
                submitList(continueList.take(8))
            }
        } else {
            b.continuePanel.visibility = View.GONE
        }

        if (st.settings.showHomeTrending && !isSearchActive && trending.isNotEmpty() && st.selectedGenre == "Tất cả") {
            b.trendingSection.visibility = View.VISIBLE
            b.txtTrendingHeading.text = "Top 10 BXH Thịnh Hành"
            b.txtTrendingHeading.setTextColor(p.textMain)
            trendingAdapter?.let { ta ->
                ta.palette = p
                ta.isTvMode = isTv
                ta.reducedMotion = DeviceProfileResolver.shouldUseReducedMotion(
                    requireContext(),
                    st.resolvedDevice,
                    st.settings.reducedMotion
                )
                ta.metaProvider = { m, pos ->
                    MovieCardUiMeta(
                        isFavorite = st.favorites.contains(m.id),
                        progressPct = st.watchHistory[m.id]?.progressPct,
                        rankBadge = pos + 1
                    )
                }
                ta.submitList(trending)
                if (metaRenderSig != lastTrendingRenderSig) {
                    lastTrendingRenderSig = metaRenderSig
                    ta.notifyDataSetChanged()
                }
            }
        } else {
            b.trendingSection.visibility = View.GONE
        }

        if (isTv && !isSearchActive && st.selectedGenre == "Tất cả") {
            val tvLite = DeviceProfileResolver.shouldUseReducedMotion(ctx, st.resolvedDevice, st.settings.reducedMotion)
            val isUltraLow = DeviceProfileResolver.isUltraLowRamDevice(ctx)
            val maxRowItems = if (isUltraLow) 6 else if (tvLite) 8 else 12
            val tvKey = "${p.preset.wireValue}_${p.isDark}_${tvLite}_${maxRowItems}_${st.allMovies.take(24).joinToString(",") { it.id }}"
            if (tvKey != lastTvCarouselsKey) {
                lastTvCarouselsKey = tvKey
                b.tvCarouselsContainer.removeAllViews()
                tvRowAdapters.clear()
                val rowDefs = listOf(
                    Triple("🎙 Thuyết Minh & Lồng Tiếng Hot", "D-Pad Trái/Phải") { m: DonghuaMovie ->
                        CatalogQueryEngine.matchesAudioFilter(m, CatalogAudioFilter.DUBBED)
                    },
                    Triple("✨ Tu Tiên • Tiên Hiệp Đỉnh Cao", "D-Pad Trái/Phải") { m: DonghuaMovie ->
                        m.genres.any { it.lowercase().contains("tu tiên") || it.lowercase().contains("tiên hiệp") }
                    },
                    Triple("🐉 Huyền Huyễn • Nghịch Thiên", "D-Pad Trái/Phải") { m: DonghuaMovie ->
                        m.genres.any { it.lowercase().contains("huyền huyễn") || it.lowercase().contains("trùng sinh") }
                    }
                )
                var anyTvRow = false
                for ((title, hint, pred) in rowDefs) {
                    val items = st.allMovies.filter(pred).take(maxRowItems)
                    if (items.size < 3) continue
                    anyTvRow = true
                    val header = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, ThemeManager.dp(context, 8f), 0, ThemeManager.dp(context, 6f))
                    }
                    val titleTv = TextView(ctx).apply {
                        text = title
                        textSize = 15f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(p.textMain)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val hintTv = TextView(ctx).apply {
                        text = "$hint • ${items.size} phim"
                        textSize = 12f
                        setTextColor(p.textMuted)
                    }
                    header.addView(titleTv)
                    header.addView(hintTv)

                    val rowAdapter = MovieCardAdapter(
                        fixedWidthDp = 176,
                        onSelect = { h.openMovieDetail(it) },
                        onToggleFavorite = { h.toggleFavorite(it.id) }
                    ).apply {
                        palette = p
                        isTvMode = true
                        reducedMotion = tvLite
                        metaProvider = { m, _ ->
                            val curSt = h.getState()
                            MovieCardUiMeta(
                                isFavorite = curSt.favorites.contains(m.id),
                                progressPct = curSt.watchHistory[m.id]?.progressPct
                            )
                        }
                        submitList(items)
                    }
                    tvRowAdapters.add(rowAdapter)

                    val rv = RecyclerView(ctx).apply {
                        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                        setRecycledViewPool(sharedTvCardPool)
                        setHasFixedSize(true)
                        isNestedScrollingEnabled = false
                        adapter = rowAdapter
                    }
                    b.tvCarouselsContainer.addView(header)
                    b.tvCarouselsContainer.addView(rv)
                }
                b.tvCarouselsContainer.visibility = if (anyTvRow) View.VISIBLE else View.GONE
            } else {
                tvRowAdapters.forEach { adapter ->
                    adapter.palette = p
                    adapter.reducedMotion = tvLite
                    adapter.notifyDataSetChanged()
                }
            }
        } else {
            lastTvCarouselsKey = null
            tvRowAdapters.clear()
            b.tvCarouselsContainer.removeAllViews()
            b.tvCarouselsContainer.visibility = View.GONE
        }

        b.rvGenres.visibility = if (isSearchActive) View.GONE else View.VISIBLE
        val genreBarKey = "${p.preset.wireValue}_${p.isDark}_${p.glassOpacityPct}_${st.selectedGenre}_${st.genres.joinToString(",") { it.id }}"
        if (genreBarKey != lastGenreBarKey) {
            lastGenreBarKey = genreBarKey
            genreAdapter?.let { ga ->
                ga.palette = p
                ga.selectedId = st.selectedGenre
                ga.submitList(st.genres)
                ga.notifyDataSetChanged()
            }
        }

        val activeSrcName = st.sources.firstOrNull { it.id == st.activeSourceId }?.name ?: st.activeSourceId
        b.txtCatalogTitle.setTextColor(p.textMain)
        b.txtCatalogSourceBadge.text = if (st.selectedGenre != "Tất cả" && !isSearchActive) {
            "$activeSrcName • ${st.selectedGenre}"
        } else activeSrcName
        b.txtCatalogSourceBadge.background = ThemeManager.pillDrawable(ctx, p, false, 12f)
        b.txtCatalogSourceBadge.setTextColor(p.accentFrom)
        b.txtCatalogCount.text = when {
            st.isLoadingGenre -> "(Đang lọc...)"
            st.activeCatalogTotalPages > 1 && !isSearchActive ->
                "($filteredCount phim • Trang ${st.activeCatalogPage}/${st.activeCatalogTotalPages})"
            else -> "($filteredCount kết quả)"
        }
        b.txtCatalogCount.setTextColor(p.textMuted)

        renderFilterAndSortBar(p, st)

        if (filteredCount == 0) {
            b.emptyCatalogBox.visibility = View.VISIBLE
            b.txtEmptyCatalog.setTextColor(p.textMuted)
            val hasCustomFilter = st.catalogAudioFilter != CatalogAudioFilter.ALL ||
                st.catalogStatusFilter != CatalogStatusFilter.ALL ||
                st.catalogSortMode != CatalogSortMode.DEFAULT ||
                st.selectedGenre != "Tất cả"
            b.btnResetCatalogFilters.visibility = if (hasCustomFilter && !isSearchActive) View.VISIBLE else View.GONE
            b.btnResetCatalogFilters.backgroundTintList = null
            b.btnResetCatalogFilters.background = ThemeManager.pillDrawable(ctx, p, true, 14f)
            b.btnResetCatalogFilters.setTextColor(Color.WHITE)
        } else {
            b.emptyCatalogBox.visibility = View.GONE
        }
    }

    private fun renderMultiSearchSourceFilter(
        b: LayoutHomeHeaderBinding,
        st: MainState,
        p: ThemePalette
    ) {
        val h = host ?: return
        val ctx = b.root.context
        val hits = CatalogQueryEngine.countSearchHitsBySource(
            allMovies = st.allMovies,
            remoteSearchResults = st.remoteSearchResults,
            searchQuery = st.searchQuery,
            audioFilter = st.catalogAudioFilter,
            statusFilter = st.catalogStatusFilter
        )
        val totalAll = CatalogQueryEngine.filterAndSort(
            allMovies = st.allMovies,
            activeGenreMovies = null,
            remoteSearchResults = st.remoteSearchResults,
            selectedGenre = st.selectedGenre,
            searchQuery = st.searchQuery,
            audioFilter = st.catalogAudioFilter,
            sortMode = st.catalogSortMode,
            statusFilter = st.catalogStatusFilter,
            searchSourceFilter = null
        ).size

        val twoCol = st.resolvedDevice != ResolvedDeviceProfile.MOBILE
        val sig = "${p.preset.wireValue}_${p.isDark}_${p.glassOpacityPct}_${twoCol}_${st.searchSourceFilter}_${totalAll}_${hits.joinToString(",") { "${it.first}:${it.second}" }}"
        if (sig == lastSearchSourceKey) return
        lastSearchSourceKey = sig

        val entries = listOf(null to "Tất cả nguồn ($totalAll)") +
            hits.map { (src, cnt) -> src to "$src ($cnt)" }

        b.multiSearchLeftPanel.removeAllViews()
        b.multiSearchMobileChips.removeAllViews()

        if (hits.size <= 1 && st.searchSourceFilter == null) {
            b.multiSearchLeftPanel.visibility = View.GONE
            b.multiSearchMobileScroll.visibility = View.GONE
            return
        }

        if (twoCol) {
            b.multiSearchMobileScroll.visibility = View.GONE
            b.multiSearchLeftPanel.visibility = View.VISIBLE
            b.multiSearchLeftPanel.background = ThemeManager.glassPanelDrawable(ctx, p, 16f, isCard = true)
            val pad = ThemeManager.dp(ctx, 10f)
            b.multiSearchLeftPanel.setPadding(pad, pad, pad, pad)
            b.multiSearchLeftPanel.addView(
                TextView(ctx).apply {
                    text = "Lọc theo nguồn phim"
                    textSize = 11.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(p.accentFrom)
                    setPadding(0, 0, 0, ThemeManager.dp(ctx, 6f))
                }
            )
            entries.forEachIndexed { idx, (srcVal, label) ->
                val active = (srcVal == null && st.searchSourceFilter == null) ||
                    (srcVal != null && srcVal.equals(st.searchSourceFilter, ignoreCase = true))
                val itemTv = TextView(ctx).apply {
                    text = label
                    textSize = 11.5f
                    setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(if (active) Color.WHITE else p.textMain)
                    background = ThemeManager.pillDrawable(ctx, p, active = active, radiusDp = 12f)
                    isFocusable = true
                    isClickable = true
                    setPadding(ThemeManager.dp(ctx, 10f), ThemeManager.dp(ctx, 7f), ThemeManager.dp(ctx, 10f), ThemeManager.dp(ctx, 7f))
                    setOnClickListener { h.setSearchSourceFilter(srcVal) }
                }
                b.multiSearchLeftPanel.addView(
                    itemTv,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { if (idx > 0) topMargin = ThemeManager.dp(ctx, 6f) }
                )
            }
        } else {
            b.multiSearchLeftPanel.visibility = View.GONE
            b.multiSearchMobileScroll.visibility = View.VISIBLE
            entries.forEachIndexed { idx, (srcVal, label) ->
                val active = (srcVal == null && st.searchSourceFilter == null) ||
                    (srcVal != null && srcVal.equals(st.searchSourceFilter, ignoreCase = true))
                val chip = TextView(ctx).apply {
                    text = label
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (active) Color.WHITE else p.textMain)
                    background = ThemeManager.pillDrawable(ctx, p, active = active, radiusDp = 16f)
                    isFocusable = true
                    isClickable = true
                    setPadding(ThemeManager.dp(ctx, 11f), ThemeManager.dp(ctx, 6f), ThemeManager.dp(ctx, 11f), ThemeManager.dp(ctx, 6f))
                    setOnClickListener { h.setSearchSourceFilter(srcVal) }
                }
                b.multiSearchMobileChips.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { if (idx > 0) marginStart = ThemeManager.dp(ctx, 6f) }
                )
            }
        }
    }

    private fun renderFilterAndSortBar(p: ThemePalette, st: MainState) {
        val b = headerBinding ?: return
        val ctx = b.root.context
        val filterBarKey = "${p.preset.wireValue}_${p.isDark}_${p.glassOpacityPct}_${st.catalogStatusFilter.wireValue}_${st.catalogAudioFilter.wireValue}_${st.catalogSortMode.wireValue}"
        if (filterBarKey == lastFilterBarKey) return
        lastFilterBarKey = filterBarKey

        b.catalogStatusDivider.setBackgroundColor(p.glassSubtleBorder)
        b.catalogFilterDivider.setBackgroundColor(p.glassSubtleBorder)
        val pills = listOf(
            b.chipStatusAll to (st.catalogStatusFilter == CatalogStatusFilter.ALL),
            b.chipStatusOngoing to (st.catalogStatusFilter == CatalogStatusFilter.ONGOING),
            b.chipStatusCompleted to (st.catalogStatusFilter == CatalogStatusFilter.COMPLETED),
            b.chipAudioAll to (st.catalogAudioFilter == CatalogAudioFilter.ALL),
            b.chipAudioDub to (st.catalogAudioFilter == CatalogAudioFilter.DUBBED),
            b.chipAudioSub to (st.catalogAudioFilter == CatalogAudioFilter.SUB),
            b.chipSortDefault to (st.catalogSortMode == CatalogSortMode.DEFAULT),
            b.chipSortEpisodes to (st.catalogSortMode == CatalogSortMode.MOST_EPISODES),
            b.chipSortRating to (st.catalogSortMode == CatalogSortMode.TOP_RATED),
            b.chipSortTitleAz to (st.catalogSortMode == CatalogSortMode.TITLE_AZ)
        )
        for ((pill, active) in pills) {
            pill.background = ThemeManager.pillDrawable(ctx, p, active = active, radiusDp = 18f)
            pill.setTextColor(if (active) Color.WHITE else p.textMain)
        }
    }

    private fun bindFooterContent(
        p: ThemePalette,
        st: MainState,
        visibleCount: Int,
        isSearchActive: Boolean
    ) {
        val b = footerBinding ?: return
        val ctx = b.root.context
        if (isSearchActive || visibleCount == 0) {
            b.catalogFooterContainer.visibility = View.GONE
            return
        }
        val hasMore = st.hasMoreCatalogPages
        val showFooter = hasMore || st.isLoadingMore || st.activeCatalogTotalPages > 1
        if (!showFooter) {
            b.catalogFooterContainer.visibility = View.GONE
            return
        }
        b.catalogFooterContainer.visibility = View.VISIBLE
        if (hasMore || st.isLoadingMore) {
            b.btnLoadMoreCatalog.visibility = View.VISIBLE
            b.btnLoadMoreCatalog.isEnabled = !st.isLoadingMore
            b.btnLoadMoreCatalog.text = if (st.isLoadingMore) {
                "⏳ Đang tải trang ${st.activeCatalogPage + 1}/${st.activeCatalogTotalPages}..."
            } else {
                "⬇ Tải thêm phim (Trang ${st.activeCatalogPage}/${st.activeCatalogTotalPages})"
            }
            b.btnLoadMoreCatalog.backgroundTintList = null
            b.btnLoadMoreCatalog.background = ThemeManager.pillDrawable(ctx, p, active = true, radiusDp = 18f)
            b.btnLoadMoreCatalog.setTextColor(Color.WHITE)
            b.txtCatalogFooterInfo.text = "Đang hiển thị $visibleCount phim • Cuộn xuống hoặc bấm để nạp tiếp"
        } else {
            b.btnLoadMoreCatalog.visibility = View.GONE
            b.txtCatalogFooterInfo.text = "✓ Đã hiển thị toàn bộ $visibleCount phim (${st.activeCatalogPage}/${st.activeCatalogTotalPages} trang)"
        }
        b.txtCatalogFooterInfo.setTextColor(p.textMuted)
    }

    private fun renderHeroSlide(hero: DonghuaMovie, totalSlides: Int) {
        val b = headerBinding ?: return
        val h = host ?: return
        b.txtHeroTitle.text = hero.title
        b.txtHeroSub.text = "${hero.originalTitle} • ${hero.studio}"
        b.txtHeroQuality.text = hero.quality
        b.txtHeroRating.text = "★ ${hero.rating} • Tập ${hero.latestEp}"
        val imgUrl = hero.thumb ?: hero.poster
        if (lastHeroImgUrl != imgUrl) {
            lastHeroImgUrl = imgUrl
            val isUltraLow = DeviceProfileResolver.isUltraLowRamDevice(b.root.context)
            val heroW = if (isUltraLow) 640 else 960
            val heroH = if (isUltraLow) 360 else 540
            val optHero = com.hoathinh3d.donghua.data.source.MovieRepository.getOptimizedPosterUrl(imgUrl, if (isUltraLow) 640 else 800)
            val req = Glide.with(b.imgHeroBg)
                .load(optHero)
                .override(heroW, heroH)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                .error(
                    Glide.with(b.imgHeroBg)
                        .load(hero.poster)
                        .override(heroW, heroH)
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                )
            if (isUltraLow) req.dontAnimate().into(b.imgHeroBg) else req.into(b.imgHeroBg)
        }

        b.btnHeroPlay.setOnClickListener { h.quickPlayMovie(hero, 1) }
        b.btnHeroDetail.setOnClickListener { h.openMovieDetail(hero) }

        b.heroDotsContainer.removeAllViews()
        for (i in 0 until totalSlides) {
            val dot = View(b.root.context).apply {
                val active = (heroIdx % totalSlides) == i
                val dp = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams(
                    ((if (active) 22 else 8) * dp).toInt(),
                    (8 * dp).toInt()
                ).apply { marginStart = (5 * dp).toInt() }
                background = GradientDrawable().apply {
                    cornerRadius = 4 * dp
                    setColor(if (active) Color.parseColor("#22D3EE") else Color.parseColor("#66FFFFFF"))
                }
                setOnClickListener {
                    heroIdx = i
                    renderHeroSlide(lastHeroSlides[i], totalSlides)
                }
            }
            b.heroDotsContainer.addView(dot)
        }
    }

    private fun startHeroAutoRotate() {
        heroTimerJob?.cancel()
        if (lastHeroSlides.size <= 1) return
        heroTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(7000L)
                if (lastHeroSlides.size > 1 && headerBinding != null) {
                    heroIdx = (heroIdx + 1) % lastHeroSlides.size
                    renderHeroSlide(lastHeroSlides[heroIdx], lastHeroSlides.size)
                }
            }
        }
    }

    private inner class HomeHeaderAdapter : RecyclerView.Adapter<HomeHeaderAdapter.VH>() {
        inner class VH(val b: LayoutHomeHeaderBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount(): Int = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = LayoutHomeHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            headerBinding = b
            val h = host
            val ctx = parent.context

            continueAdapter = ContinueWatchingAdapter { prog ->
                val activeHost = host ?: return@ContinueWatchingAdapter
                val matched = activeHost.resolveMovieForProgress(prog)
                val matchedSrvIdx = matched.serverGroups
                    ?.indexOfFirst { it.serverName.equals(prog.server, ignoreCase = true) }
                    ?.takeIf { it >= 0 } ?: -1
                activeHost.quickPlayMovie(matched, prog.episode, matchedSrvIdx, prog.currentTime)
            }
            b.rvContinue.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            b.rvContinue.adapter = continueAdapter

            trendingAdapter = MovieCardAdapter(
                fixedWidthDp = 152,
                onSelect = { host?.openMovieDetail(it) },
                onToggleFavorite = { host?.toggleFavorite(it.id) }
            )
            b.rvTrending.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            b.rvTrending.setRecycledViewPool(sharedTvCardPool)
            b.rvTrending.adapter = trendingAdapter

            genreAdapter = GenreChipAdapter { g -> host?.selectGenre(g.id) }
            b.rvGenres.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            b.rvGenres.adapter = genreAdapter

            b.btnHomeClearSearch.setOnClickListener { host?.clearSearch() }
            b.btnContinueClear.setOnClickListener { host?.clearHistory() }
            b.btnResetCatalogFilters.setOnClickListener { host?.resetCatalogFilters() }

            b.chipStatusAll.setOnClickListener { host?.setCatalogStatusFilter(CatalogStatusFilter.ALL) }
            b.chipStatusOngoing.setOnClickListener { host?.setCatalogStatusFilter(CatalogStatusFilter.ONGOING) }
            b.chipStatusCompleted.setOnClickListener { host?.setCatalogStatusFilter(CatalogStatusFilter.COMPLETED) }

            b.chipAudioAll.setOnClickListener { host?.setCatalogAudioFilter(CatalogAudioFilter.ALL) }
            b.chipAudioDub.setOnClickListener { host?.setCatalogAudioFilter(CatalogAudioFilter.DUBBED) }
            b.chipAudioSub.setOnClickListener { host?.setCatalogAudioFilter(CatalogAudioFilter.SUB) }

            b.chipSortDefault.setOnClickListener { host?.setCatalogSortMode(CatalogSortMode.DEFAULT) }
            b.chipSortEpisodes.setOnClickListener { host?.setCatalogSortMode(CatalogSortMode.MOST_EPISODES) }
            b.chipSortRating.setOnClickListener { host?.setCatalogSortMode(CatalogSortMode.TOP_RATED) }
            b.chipSortTitleAz.setOnClickListener { host?.setCatalogSortMode(CatalogSortMode.TITLE_AZ) }

            ThemeManager.applyRoundedOutline(b.heroCard, 24f)
            if (h != null) {
                lastGenreBarKey = null
                lastFilterBarKey = null
                lastHeroSlidesKey = null
                lastTrendingRenderSig = null
            }
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val h = host ?: return
            val st = h.getState()
            val p = h.getPalette()
            val isTv = st.resolvedDevice == ResolvedDeviceProfile.TV
            val metaRenderSig = buildString {
                append(p.preset.wireValue).append('|')
                append(p.isDark).append('|')
                append(p.glassOpacityPct).append('|')
                append(p.glassBlurPx).append('|')
                append(isTv).append('|')
                append(st.favorites.joinToString(",")).append('|')
                append(st.watchHistory.entries.joinToString(",") { "${it.key}:${it.value.progressPct}" })
            }
            bindHeaderContent(st, p, isTv, lastFilteredCount, metaRenderSig)
        }
    }

    private inner class HomeFooterAdapter : RecyclerView.Adapter<HomeFooterAdapter.VH>() {
        inner class VH(val b: LayoutHomeFooterBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount(): Int = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = LayoutHomeFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            footerBinding = b
            b.btnLoadMoreCatalog.setOnClickListener { host?.loadMoreCatalog() }
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val h = host ?: return
            val st = h.getState()
            bindFooterContent(h.getPalette(), st, lastFilteredCount, st.searchQuery.trim().isNotEmpty())
        }
    }

    override fun onDestroyView() {
        heroTimerJob?.cancel()
        lastTvCarouselsKey = null
        lastHeroSlidesKey = null
        lastHeroImgUrl = null
        lastTrendingRenderSig = null
        lastCatalogRenderSig = null
        lastGenreBarKey = null
        lastFilterBarKey = null
        lastSearchSourceKey = null
        tvRowAdapters.clear()
        headerBinding = null
        footerBinding = null
        continueAdapter = null
        trendingAdapter = null
        genreAdapter = null
        _binding = null
        super.onDestroyView()
    }
}
