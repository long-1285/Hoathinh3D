package com.hoathinh3d.donghua.ui.detail

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile
import com.hoathinh3d.donghua.data.model.ServerGroup
import com.hoathinh3d.donghua.data.offline.HlsOfflineManager
import com.hoathinh3d.donghua.data.offline.OfflineDownloadService
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.data.source.YanHh3dNowScraper
import com.hoathinh3d.donghua.databinding.ActivityMovieDetailBinding
import com.hoathinh3d.donghua.player.HlsCacheProvider
import com.hoathinh3d.donghua.player.PlayerActivity
import com.hoathinh3d.donghua.player.PlayerUtils
import kotlinx.coroutines.launch

class MovieDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MOVIE = "extra_movie"

        fun createIntent(context: Context, movie: DonghuaMovie): Intent {
            return Intent(context, MovieDetailActivity::class.java).apply {
                putExtra(EXTRA_MOVIE, movie)
            }
        }
    }

    private lateinit var binding: ActivityMovieDetailBinding
    private var movie: DonghuaMovie = InitialData.INITIAL_DONGHUA_CATALOG.first()
    private var serverGroups: List<ServerGroup> = emptyList()
    private var activeServerIdx = 0
    private var epRangeIdx = 0
    private var isDownloadingOffline = false
    private var downloadPct = 0
    private var isDescriptionExpanded = false
    private var pendingFocusTag: String? = null
    private var insetTopPx = 0
    private var insetBottomPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("DEPRECATION")
        val m = intent.getSerializableExtra(EXTRA_MOVIE) as? DonghuaMovie
        if (m != null) movie = m

        val settings = PrefsStore.getSettings()
        serverGroups = movie.serverGroups.orEmpty()
        activeServerIdx = MovieRepository.selectPreferredServerIdx(serverGroups, settings.defaultAudio)

        val resolvedDevice = DeviceProfileResolver.resolve(this, settings.deviceMode)
        binding.episodeGridContainer.columnCount = when (resolvedDevice) {
            ResolvedDeviceProfile.MOBILE -> 4
            ResolvedDeviceProfile.TABLET -> 6
            ResolvedDeviceProfile.TV -> 8
        }
        val backdropFrame = binding.imgDetailBackdrop.parent as? android.view.ViewGroup
        backdropFrame?.layoutParams = backdropFrame?.layoutParams?.apply {
            height = ThemeManager.dp(
                this@MovieDetailActivity,
                when (resolvedDevice) {
                    ResolvedDeviceProfile.MOBILE -> 216f
                    ResolvedDeviceProfile.TABLET -> 250f
                    ResolvedDeviceProfile.TV -> 266f
                }
            )
        }
        val posterSizeDp = when (resolvedDevice) {
            ResolvedDeviceProfile.MOBILE -> 104f to 144f
            ResolvedDeviceProfile.TABLET, ResolvedDeviceProfile.TV -> 118f to 162f
        }
        binding.imgDetailPoster.layoutParams = binding.imgDetailPoster.layoutParams.apply {
            width = ThemeManager.dp(this@MovieDetailActivity, posterSizeDp.first)
            height = ThemeManager.dp(this@MovieDetailActivity, posterSizeDp.second)
        }

        binding.btnCloseDetail.setOnClickListener { finish() }
        ThemeManager.applyEdgeToEdgePadding(binding.detailRoot) { top, bottom, left, right ->
            insetTopPx = top
            insetBottomPx = bottom
            val closeLp = binding.btnCloseDetail.layoutParams as android.widget.FrameLayout.LayoutParams
            closeLp.topMargin = ThemeManager.dp(this, 14f) + top
            closeLp.marginEnd = ThemeManager.dp(this, 14f) + right
            binding.btnCloseDetail.layoutParams = closeLp
            binding.detailContentCol.setPadding(
                left,
                0,
                right,
                ThemeManager.dp(this, 28f) + bottom
            )
        }
        binding.btnDetailFavorite.setOnClickListener {
            val isFav = PrefsStore.toggleFavorite(movie.id)
            if (isFav) PrefsStore.markFavoriteEpisodeSeen(movie.id, movie.latestEp)
            renderUi()
            Toast.makeText(
                this,
                if (isFav) "Đã thêm vào Yêu thích ❤️" else "Đã xóa khỏi Yêu thích",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (!DeviceProfileResolver.shouldUseReducedMotion(this, resolvedDevice, settings.reducedMotion)) {
            ThemeManager.applyRoundedOutline(binding.imgDetailPoster, 16f)
            ThemeManager.applyRoundedOutline(binding.detailInfoGlassPanel, 20f)
            ThemeManager.applyRoundedOutline(binding.detailEpisodesGlassPanel, 20f)
        }
        binding.btnDetailReadMore.setOnClickListener {
            isDescriptionExpanded = !isDescriptionExpanded
            val curSettings = PrefsStore.getSettings()
            val curDev = DeviceProfileResolver.resolve(this, curSettings.deviceMode)
            applyDescriptionState(
                ThemeManager.palette(
                    themeWire = curSettings.theme,
                    glassBlurPx = curSettings.glassBlur,
                    glassOpacityPct = curSettings.glassOpacity,
                    darkModeWire = curSettings.darkMode,
                    isSystemDark = ThemeManager.isSystemDarkMode(this),
                    liteRender = DeviceProfileResolver.shouldUseReducedMotion(this, curDev, curSettings.reducedMotion)
                )
            )
        }
        renderUi()
        if (DeviceProfileResolver.isTvHardware(this) || resolvedDevice == ResolvedDeviceProfile.TV) {
            binding.btnDetailPlay.requestFocus()
        }

        loadFullDetail()
        observeOfflineServiceProgress()
    }

    private fun observeOfflineServiceProgress() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasActiveForThisMovie = false
                OfflineDownloadService.progressState.collect { dl ->
                    val activeForMe = dl.activeDonghuaId == movie.id && dl.activeEpisodeNum > 0
                    if (activeForMe) {
                        wasActiveForThisMovie = true
                        isDownloadingOffline = true
                        downloadPct = dl.progressPct
                        binding.btnDetailOffline.text = if (dl.queuedCount > 0) {
                            "⏳ Tập ${dl.activeEpisodeNum}: ${dl.progressPct}% (+${dl.queuedCount})"
                        } else {
                            "⏳ Tập ${dl.activeEpisodeNum}: ${dl.progressPct}%"
                        }
                        binding.btnDetailOffline.isEnabled = false
                    } else if (wasActiveForThisMovie) {
                        wasActiveForThisMovie = false
                        isDownloadingOffline = false
                        downloadPct = 0
                        renderUi()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderUi()
    }

    private fun loadFullDetail() {
        if (movie.slug.isNullOrBlank() && movie.yanUrl.isNullOrBlank() && movie.customWebUrl.isNullOrBlank()) {
            return
        }
        binding.txtDetailLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val detail = runCatching {
                MovieRepository.fetchMovieDetailFast(movie.slug.orEmpty(), movie) { fast ->
                    runOnUiThread {
                        val settings = PrefsStore.getSettings()
                        if (serverGroups.isEmpty() && fast.serverGroups.isNotEmpty()) {
                            serverGroups = fast.serverGroups
                            activeServerIdx = MovieRepository.selectPreferredServerIdx(serverGroups, settings.defaultAudio)
                            epRangeIdx = 0
                            val freshPoster = fast.poster?.takeUnless { MovieRepository.isDeadOrBlankPoster(it) }
                            movie = movie.copy(
                                description = fast.description ?: movie.description,
                                poster = freshPoster ?: movie.poster,
                                thumb = freshPoster ?: movie.thumb,
                                latestEp = fast.latestEp ?: movie.latestEp,
                                totalEp = fast.totalEp ?: movie.totalEp,
                                serverGroups = serverGroups
                            )
                            renderUi()
                        }
                    }
                }
            }.getOrNull()
            binding.txtDetailLoading.visibility = View.GONE
            if (detail != null) {
                val settings = PrefsStore.getSettings()
                if (detail.serverGroups.isNotEmpty()) {
                    serverGroups = detail.serverGroups
                    activeServerIdx = MovieRepository.selectPreferredServerIdx(serverGroups, settings.defaultAudio)
                    epRangeIdx = 0
                }
                val freshPoster = detail.poster?.takeUnless { MovieRepository.isDeadOrBlankPoster(it) }
                movie = movie.copy(
                    description = detail.description ?: movie.description,
                    poster = freshPoster ?: movie.poster,
                    thumb = freshPoster ?: movie.thumb,
                    latestEp = detail.latestEp ?: movie.latestEp,
                    totalEp = detail.totalEp ?: movie.totalEp,
                    serverGroups = if (serverGroups.isNotEmpty()) serverGroups else movie.serverGroups
                )
                if (PrefsStore.getFavorites().contains(movie.id)) {
                    PrefsStore.markFavoriteEpisodeSeen(movie.id, movie.latestEp)
                }
                renderUi()
                prewarmTargetEpisodeStream()
            }
        }
    }

    private fun prewarmTargetEpisodeStream() {
        val srv = serverGroups.getOrNull(activeServerIdx) ?: return
        val targetEpNum = PrefsStore.getHistory()[movie.id]?.episode ?: 1
        val epIdx = PlayerUtils.findEpisodeIdxByNumber(srv.episodes, targetEpNum)
        val ep = srv.episodes.getOrNull(epIdx) ?: return
        lifecycleScope.launch {
            runCatching {
                val m3u8 = when {
                    ep.linkM3u8.contains(".m3u8", ignoreCase = true) -> ep.linkM3u8
                    ep.wpServerType?.startsWith("now:") == true && !ep.wpPageUrl.isNullOrBlank() -> {
                        YanHh3dNowScraper.resolveStream(
                            watchPageUrl = ep.wpPageUrl,
                            serverKey = ep.wpServerType,
                            refererBase = ep.wpBaseUrl ?: YanHh3dNowScraper.YAN_NOW_BASE
                        ).linkM3u8
                    }
                    else -> ""
                }
                if (m3u8.isNotBlank()) {
                    HlsCacheProvider.prefetchHlsStart(this@MovieDetailActivity, m3u8)
                }
            }
        }
    }

    private fun renderUi() {
        val settings = PrefsStore.getSettings()
        val resolvedDev = DeviceProfileResolver.resolve(this, settings.deviceMode)
        val useLite = DeviceProfileResolver.shouldUseReducedMotion(this, resolvedDev, settings.reducedMotion)
        val p = ThemeManager.palette(
            themeWire = settings.theme,
            glassBlurPx = settings.glassBlur,
            glassOpacityPct = settings.glassOpacity,
            darkModeWire = settings.darkMode,
            isSystemDark = ThemeManager.isSystemDarkMode(this),
            liteRender = useLite
        )
        ThemeManager.applySystemBars(window, p)
        binding.detailRoot.background = ThemeManager.rootBackground(p)

        val isLite = useLite || DeviceProfileResolver.isLowRamDevice(this)
        val isUltraLow = DeviceProfileResolver.isUltraLowRamDevice(this)
        val decodeFmt = if (isLite) com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565 else com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888
        val safeBackdropUrl = (movie.thumb ?: movie.poster).takeUnless { MovieRepository.isDeadOrBlankPoster(it) }.orEmpty()
        val safePosterUrl = movie.poster.takeUnless { MovieRepository.isDeadOrBlankPoster(it) }.orEmpty()
        if (safeBackdropUrl.isBlank()) {
            Glide.with(this).clear(binding.imgDetailBackdrop)
            binding.imgDetailBackdrop.setImageResource(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
        } else {
            Glide.with(this)
                .load(safeBackdropUrl)
                .placeholder(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
                .override(if (isUltraLow) 640 else 960, if (isUltraLow) 360 else 540)
                .format(decodeFmt)
                .error(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
                .into(binding.imgDetailBackdrop)
        }
        if (safePosterUrl.isBlank()) {
            Glide.with(this).clear(binding.imgDetailPoster)
            binding.imgDetailPoster.setImageResource(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
        } else {
            Glide.with(this)
                .load(safePosterUrl)
                .placeholder(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
                .override(if (isUltraLow) 260 else 360, if (isUltraLow) 368 else 520)
                .format(decodeFmt)
                .error(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
                .into(binding.imgDetailPoster)
        }

        binding.txtDetailQuality.text = movie.quality
        binding.txtDetailEpBadge.text = "Tập ${movie.latestEp}/${movie.totalEp}"
        binding.txtDetailRating.text = "★ ${movie.rating}"
        binding.txtDetailTitle.text = movie.title
        binding.txtDetailOrig.text = movie.originalTitle

        val prog = PrefsStore.getHistory()[movie.id]
        binding.btnCloseDetail.backgroundTintList = null
        val frostedDark = p.copy(isDark = true, glassSurface = Color.argb(140, 12, 18, 30))
        binding.btnCloseDetail.background = ThemeManager.liquidGlassSheetDrawable(this, frostedDark, 19f)
        binding.btnDetailPlay.background = ThemeManager.accentGradientDrawable(this, p, 16f)
        binding.txtDetailPlayLabel.text = if (prog != null) {
            val tsLabel = PlayerUtils.formatPlayerTime(prog.currentTime.toLong())
            "Tiếp tục Tập ${prog.episode} • $tsLabel (${prog.progressPct}%)"
        } else {
            "Xem Ngay Tập 1"
        }
        binding.btnDetailPlay.setOnClickListener {
            openPlayer(prog?.episode ?: 1, activeServerIdx, prog?.currentTime ?: -1.0)
        }

        val isFav = PrefsStore.getFavorites().contains(movie.id)
        binding.btnDetailFavorite.text = if (isFav) "♥ Đã thích" else "♡ Yêu thích"
        binding.btnDetailFavorite.background = ThemeManager.pillDrawable(this, p, active = false, 14f)
        binding.btnDetailFavorite.setTextColor(if (isFav) Color.parseColor("#F43F5E") else p.textMain)

        binding.btnDetailShare.background = ThemeManager.pillDrawable(this, p, active = false, 14f)
        binding.btnDetailShare.setTextColor(p.textMain)
        binding.btnDetailShare.setOnClickListener {
            val title = movie.title.ifBlank { "Hoạt Hình 3D" }
            val text = PlayerUtils.formatShareText(title)
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Chia sẻ phim qua"))
        }

        // Offline download CTA
        val targetEpNum = prog?.episode ?: 1
        val currentSrv = serverGroups.getOrNull(activeServerIdx) ?: serverGroups.firstOrNull()
        val srvOrder = listOfNotNull(currentSrv) + serverGroups
        var candidateSrvName = ""
        var candidateEp: EpisodeItem? = null
        var candidateEpNum = targetEpNum
        for (srv in srvOrder) {
            val found = srv.episodes.firstOrNull { ep ->
                val idx = srv.episodes.indexOf(ep)
                PlayerUtils.extractEpisodeNumber(ep, idx) == targetEpNum
            } ?: srv.episodes.firstOrNull() ?: continue
            val isDirectHls = found.linkM3u8.contains(".m3u8", ignoreCase = true) ||
                (found.wpServerType?.startsWith("now:") == true && found.wpServerSv == "hls" && !found.wpPageUrl.isNullOrBlank())
            if (isDirectHls) {
                candidateSrvName = srv.serverName
                candidateEp = found
                candidateEpNum = PlayerUtils.extractEpisodeNumber(found, 0)
                break
            }
        }

        val offlineMap = PrefsStore.getOfflineEpisodes()
        val offlineKey = HlsOfflineManager.buildEpisodeKey(movie.id, candidateEpNum)
        val isSavedOffline = offlineMap.containsKey(offlineKey)

        // Chỉ hiện nút Lưu Offline khi có luồng .m3u8 trực tiếp hoặc đã lưu
        if (candidateEp == null && !isSavedOffline && !isDownloadingOffline) {
            binding.btnDetailOffline.visibility = View.GONE
        } else {
            binding.btnDetailOffline.visibility = View.VISIBLE
            binding.btnDetailOffline.isEnabled = !isDownloadingOffline
            binding.btnDetailOffline.background = ThemeManager.pillDrawable(this, p, false, 14f)
            binding.btnDetailOffline.text = when {
                isDownloadingOffline -> "⏳ Đang lưu $downloadPct%"
                isSavedOffline -> "✓ Đã lưu Tập $candidateEpNum"
                else -> "⬇ Lưu Offline Tập $candidateEpNum"
            }
            binding.btnDetailOffline.setTextColor(
                if (isSavedOffline) Color.parseColor("#10B981") else p.accentFrom
            )
            binding.btnDetailOffline.setOnClickListener {
                val candidateSrv = srvOrder.firstOrNull { it.serverName == candidateSrvName } ?: currentSrv
                val hlsEps = candidateSrv?.episodes?.filter { ep ->
                    ep.linkM3u8.contains(".m3u8", ignoreCase = true) ||
                        (ep.wpServerType?.startsWith("now:") == true && ep.wpServerSv == "hls" && !ep.wpPageUrl.isNullOrBlank())
                }.orEmpty()

                val options = mutableListOf<Pair<String, () -> Unit>>()
                if (!isSavedOffline && candidateEp != null) {
                    options.add("⬇ Tải Tập $candidateEpNum (Chạy nền)" to {
                        enqueueOfflineDownloads(candidateSrvName, listOf(candidateEpNum to candidateEp))
                    })
                }
                val startIdx = if (candidateEp != null) hlsEps.indexOf(candidateEp).coerceAtLeast(0) else 0
                val next3 = hlsEps.drop(startIdx).take(3).mapIndexed { i, ep ->
                    PlayerUtils.extractEpisodeNumber(ep, startIdx + i) to ep
                }.filter { (epN, _) -> !offlineMap.containsKey(HlsOfflineManager.buildEpisodeKey(movie.id, epN)) }
                if (next3.size > 1) {
                    options.add("📦 Tải ${next3.size} tập tiếp theo (Chạy nền)" to {
                        enqueueOfflineDownloads(candidateSrvName, next3)
                    })
                }
                val next5 = hlsEps.drop(startIdx).take(5).mapIndexed { i, ep ->
                    PlayerUtils.extractEpisodeNumber(ep, startIdx + i) to ep
                }.filter { (epN, _) -> !offlineMap.containsKey(HlsOfflineManager.buildEpisodeKey(movie.id, epN)) }
                if (next5.size > 3) {
                    options.add("📦 Tải ${next5.size} tập tiếp theo (Chạy nền)" to {
                        enqueueOfflineDownloads(candidateSrvName, next5)
                    })
                }
                if (isSavedOffline) {
                    options.add("🗑 Xóa bản lưu Tập $candidateEpNum" to {
                        HlsOfflineManager.removeOfflineEpisode(this, offlineKey)
                        renderUi()
                        Toast.makeText(this, "Đã xóa bản lưu offline Tập $candidateEpNum", Toast.LENGTH_SHORT).show()
                    })
                }
                if (options.size == 1) {
                    options[0].second.invoke()
                } else if (options.isNotEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Lưu xem Offline • ${movie.title}")
                        .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                            options.getOrNull(which)?.second?.invoke()
                        }
                        .setNegativeButton("Đóng", null)
                        .show()
                }
            }
        }

        binding.detailInfoGlassPanel.background = ThemeManager.glassPanelDrawable(this, p, 20f, isCard = true)
        binding.detailEpisodesGlassPanel.background = ThemeManager.glassPanelDrawable(this, p, 20f, isCard = true)
        binding.txtDetailMetaInfo.text = "📅 ${movie.scheduleDay} • ${movie.scheduleTime} • ${movie.studio}"
        binding.txtDetailMetaInfo.setTextColor(p.textMuted)
        binding.txtDetailGenres.text = movie.genres.joinToString("  •  ")
        binding.txtDetailGenres.setTextColor(p.accentFrom)
        binding.txtDetailDescription.text = movie.description?.takeIf { it.isNotBlank() }
            ?: "${movie.title} (${movie.originalTitle}) — Bộ hoạt hình 3D đặc sắc do ${movie.studio} sản xuất, hỗ trợ chất lượng ${movie.quality} cùng đa máy chủ Vietsub và Thuyết Minh."
        binding.txtDetailDescription.setTextColor(p.textMuted)
        applyDescriptionState(p)

        val eps = currentSrv?.episodes.orEmpty()
        val totalEpCount = if (eps.isNotEmpty()) eps.size else maxOf(movie.latestEp, 1)
        binding.txtServerHeading.text = "Chọn Máy Chủ & Danh Sách Tập ($totalEpCount tập)"
        binding.txtServerHeading.setTextColor(p.textMain)
        binding.txtDetailLoading.setTextColor(p.accentFrom)

        // Server chips (ẩn server chỉ có Embed khi đã có máy chủ HLS/MP4 trực tiếp)
        binding.serverChipsContainer.removeAllViews()
        val visibleServerIndices = MovieRepository.visibleServerIndices(serverGroups, activeServerIdx)
        for (idx in visibleServerIndices) {
            val srv = serverGroups[idx]
            val active = idx == activeServerIdx
            val srvTag = "detail_srv_$idx"
            val btn = Button(this).apply {
                tag = srvTag
                text = "${srv.serverName} (${srv.episodes.size})"
                isAllCaps = false
                textSize = 11f
                minHeight = 0
                minimumHeight = 0
                setPadding(
                    ThemeManager.dp(context, 12f),
                    ThemeManager.dp(context, 7f),
                    ThemeManager.dp(context, 12f),
                    ThemeManager.dp(context, 7f)
                )
                background = ThemeManager.pillDrawable(context, p, active = active, radiusDp = 12f)
                setTextColor(if (active) Color.WHITE else p.textMain)
                isFocusable = true
                setOnClickListener {
                    pendingFocusTag = srvTag
                    activeServerIdx = idx
                    epRangeIdx = 0
                    renderUi()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = ThemeManager.dp(this@MovieDetailActivity, 8f) }
            binding.serverChipsContainer.addView(btn, lp)
        }

        // Hiển thị số tập mới nhất trước theo thứ tự giảm dần (Tập N -> Tập 1)
        val displayIndices = PlayerUtils.descendingEpisodeDisplayIndices(eps, totalEpCount)
        val ranges = PlayerUtils.buildDescendingEpisodeRanges(displayIndices, eps, PlayerUtils.EP_PAGE_SIZE)
        if (epRangeIdx !in ranges.indices) epRangeIdx = 0

        binding.epRangeContainer.removeAllViews()
        if (ranges.size > 1) {
            binding.epRangeScroll.visibility = View.VISIBLE
            ranges.forEachIndexed { idx, slice ->
                val active = idx == epRangeIdx
                val rngTag = "detail_rng_$idx"
                val btn = Button(this).apply {
                    tag = rngTag
                    text = slice.label
                    isAllCaps = false
                    textSize = 11f
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(
                        ThemeManager.dp(context, 10f),
                        ThemeManager.dp(context, 5f),
                        ThemeManager.dp(context, 10f),
                        ThemeManager.dp(context, 5f)
                    )
                    background = ThemeManager.pillDrawable(context, p, active = active, radiusDp = 10f)
                    setTextColor(if (active) Color.WHITE else p.textMain)
                    isFocusable = true
                    setOnClickListener {
                        pendingFocusTag = rngTag
                        epRangeIdx = idx
                        renderUi()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = ThemeManager.dp(this@MovieDetailActivity, 6f) }
                binding.epRangeContainer.addView(btn, lp)
            }
        } else {
            binding.epRangeScroll.visibility = View.GONE
        }

        // Episode grid (mới nhất trước; đánh dấu tập đang xem, đã lưu offline & đã xem qua)
        val slice = ranges.getOrNull(epRangeIdx)
        val rStart = slice?.startPos ?: 0
        val rEnd = slice?.endExclusive ?: displayIndices.size
        val watchedEps = PrefsStore.getWatchedEpisodes(movie.id)
        binding.episodeGridContainer.removeAllViews()
        for (pos in rStart until rEnd) {
            val idx = displayIndices[pos]
            val ep = eps.getOrNull(idx)
            val epNum = PlayerUtils.extractEpisodeNumber(ep, idx)
            val isWatching = prog?.episode == epNum
            val isWatched = watchedEps.contains(epNum)
            val isOffline = offlineMap.containsKey(HlsOfflineManager.buildEpisodeKey(movie.id, epNum))
            val baseLabel = when {
                ep == null || ep.name.isBlank() -> "Tập $epNum"
                ep.name.trim().all { it.isDigit() } -> "Tập ${ep.name.trim()}"
                else -> ep.name
            }
            val label = if (isWatched && !isWatching) "$baseLabel ✓" else baseLabel
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                isSingleLine = true
                minHeight = 0
                minimumHeight = ThemeManager.dp(context, 38f)
                background = when {
                    isWatching -> ThemeManager.accentGradientDrawable(context, p, 12f)
                    isOffline -> GradientDrawable().apply {
                        cornerRadius = ThemeManager.dp(context, 12f).toFloat()
                        setColor(Color.argb(40, 16, 185, 129))
                        setStroke(2, Color.parseColor("#10B981"))
                    }
                    isWatched -> GradientDrawable().apply {
                        cornerRadius = ThemeManager.dp(context, 12f).toFloat()
                        setColor(if (p.isDark) Color.argb(38, 148, 163, 184) else Color.argb(36, 100, 116, 139))
                        setStroke(1, p.glassSubtleBorder)
                    }
                    else -> ThemeManager.glassPanelDrawable(context, p, 12f, isCard = true)
                }
                setTextColor(
                    when {
                        isWatching -> Color.WHITE
                        isOffline -> Color.parseColor("#10B981")
                        isWatched -> p.textMuted
                        else -> p.textMain
                    }
                )
                isFocusable = true
                setOnClickListener { openPlayer(epNum, activeServerIdx) }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                val m = ThemeManager.dp(this@MovieDetailActivity, 4f)
                setMargins(m, m, m, m)
            }
            binding.episodeGridContainer.addView(btn, lp)
        }

        val restoreTag = pendingFocusTag
        if (restoreTag != null) {
            pendingFocusTag = null
            binding.detailRoot.post {
                binding.detailRoot.findViewWithTag<View>(restoreTag)?.requestFocus()
            }
        }
    }

    private fun applyDescriptionState(p: com.hoathinh3d.donghua.core.ThemePalette) {
        binding.txtDetailDescription.maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 3
        binding.btnDetailReadMore.background = ThemeManager.navTabDrawable(this, p, active = false, radiusDp = 10f)
        binding.btnDetailReadMore.setTextColor(p.accentFrom)
        binding.btnDetailReadMore.text = if (isDescriptionExpanded) "Thu gọn -" else "Xem thêm +"
        binding.txtDetailDescription.post {
            val layout = binding.txtDetailDescription.layout
            val truncated = layout != null && layout.lineCount > 0 &&
                (layout.lineCount > 3 || layout.getEllipsisCount(layout.lineCount - 1) > 0)
            binding.btnDetailReadMore.visibility =
                if (isDescriptionExpanded || truncated) View.VISIBLE else View.GONE
        }
    }

    private fun enqueueOfflineDownloads(serverName: String, items: List<Pair<Int, EpisodeItem>>) {
        if (items.isEmpty()) return
        val payloads = items.map { (epNum, ep) ->
            OfflineDownloadService.DownloadTaskPayload(
                donghuaId = movie.id,
                title = movie.title,
                poster = movie.poster,
                episodeNum = epNum,
                serverName = serverName,
                episode = ep
            )
        }
        OfflineDownloadService.enqueue(this, payloads)
        Toast.makeText(
            this,
            "Đã thêm ${payloads.size} tập vào hàng đợi tải nền Offline!",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openPlayer(episode: Int, serverIdx: Int, resumeTimeSec: Double = -1.0) {
        val enriched = movie.copy(
            serverGroups = if (serverGroups.isNotEmpty()) serverGroups else movie.serverGroups
        )
        startActivity(PlayerActivity.createIntent(this, enriched, episode, serverIdx, resumeTimeSec))
    }
}
