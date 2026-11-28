package com.hoathinh3d.donghua.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import com.hoathinh3d.donghua.R
import com.hoathinh3d.donghua.core.AppDiagnostics
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.data.model.AppSettings
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile
import com.hoathinh3d.donghua.data.model.ServerGroup
import com.hoathinh3d.donghua.data.model.StreamResult
import com.hoathinh3d.donghua.data.model.WatchProgress
import com.hoathinh3d.donghua.data.offline.HlsOfflineManager
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.data.source.VsmovSourceProvider
import com.hoathinh3d.donghua.data.source.YanHh3dNowScraper
import com.hoathinh3d.donghua.data.source.YanHh3dScraper
import com.hoathinh3d.donghua.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MOVIE = "extra_movie"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_SERVER_IDX = "extra_server_idx"
        private const val EXTRA_RESUME_SEC = "extra_resume_sec"
        private const val EXTRA_STORYBOARD_URL = "extra_storyboard_url"

        fun createIntent(
            context: Context,
            movie: DonghuaMovie,
            episode: Int = 1,
            serverIdx: Int = -1,
            resumeTimeSec: Double = -1.0,
            storyboardUrl: String? = null
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_MOVIE, movie)
                putExtra(EXTRA_EPISODE, episode)
                putExtra(EXTRA_SERVER_IDX, serverIdx)
                putExtra(EXTRA_RESUME_SEC, resumeTimeSec)
                if (!storyboardUrl.isNullOrBlank()) {
                    putExtra(EXTRA_STORYBOARD_URL, storyboardUrl)
                }
            }
        }
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var settings: AppSettings
    private lateinit var deviceProfile: ResolvedDeviceProfile
    private lateinit var audioManager: AudioManager

    private var movie: DonghuaMovie = InitialData.INITIAL_DONGHUA_CATALOG.first()
    private var serverGroups: List<ServerGroup> = emptyList()
    private var activeServerIdx: Int = 0
    private var currentEpIdx: Int = 0
    private var epRangeIdx: Int = -1
    private var initialEpisode: Int = 1
    private var initialServerIdx: Int = -1
    private var initialResumeSec: Double = -1.0
    private var pendingResumeSeekMs: Long = -1L
    private var suppressAutoSkipForResume: Boolean = false

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var seekCoalescer: ZeroDelaySeekCoalescer? = null

    private var isTvProfile = false
    private var isLocked = false
    private var isPortraitLocked = false
    private var isFullscreen = true
    private var showHud = true
    private var activeDrawer = PlayerDrawerType.NONE
    private var aspectMode = AspectMode.FIT
    private var playbackSpeed = 1.0f
    private var seekStep = 10
    private var hasSkippedIntro = false
    private var autoSkippedEpKey: String? = null
    private var skipIntroShownEpKey: String? = null
    private var brightness = 1.0f
    private var lastSeekBarCommitMs = 0L
    private var isUserDraggingSeekBar = false
    private var currentActiveM3u8 = ""
    private var retriedCurrentStreamOnce = false
    private var steppedDownCodecOnError = false
    private var skipIntroSec = 85
    private var lastProgressPersistMs = 0L
    private var lastWatchedMarkedEp = -1
    private var externalSubtitleUri: Uri? = null
    private var externalSubtitleMime: String? = null
    private var isCurrentStreamOffline = false
    private var lastRxBytes = -1L
    private var lastRxSampleMs = 0L
    private var cachedSpeedLabel = ""
    private var baseExternalSubtitleUri: Uri? = null
    private var subtitleOffsetMs = 0L
    private var externalSubtitleRawText: String? = null
    private var externalSubtitleIsVtt = false
    private val audioDrc = AudioDrcController()

    private val openSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        attachExternalSubtitle(uri)
    }

    private val failedServers = HashSet<Int>()
    private val failedStreamUrls = HashSet<String>()
    private val resolvedStreams = HashMap<String, StreamResult>()
    private val qualityOptions = mutableListOf<QualityTrackOption>()
    private var activeQualityIdx = -1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hudHideRunnable: Runnable? = null
    private var gestureFadeRunnable: Runnable? = null
    private var skipIntroHideRunnable: Runnable? = null
    private var seekThumbHideRunnable: Runnable? = null
    private var sleepTimerMinutes: Int = 0
    private var sleepTimerEndAtMs: Long = 0L
    private var sleepTimerRunnable: Runnable? = null
    private var storyboardCues: List<StoryboardCue> = emptyList()
    private val storyboardSprites = android.util.LruCache<String, android.graphics.Bitmap>(2)
    private val storyboardRequestedUrls = HashSet<String>()
    private var seekBarDragOriginMs = 0L
    private var lastSeekPosterLoaded = ""
    private var autoNextJob: Job? = null
    private var loadEpisodeJob: Job? = null
    private var lastFocusedBeforeDrawer: View? = null
    private var cutoutLeftPx = 0
    private var cutoutRightPx = 0
    private var cutoutTopPx = 0
    private var cutoutBottomPx = 0

    // Touch gesture tracking
    private var gestureActive = false
    private var gestureAxis = 0 // 0=none, 1=vertical, 2=horizontal
    private var gestureLeftSide = true
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureStartBrightness = 1.0f
    private var gestureStartVolume = 0
    private var gestureStartVideoMs = 0L
    private var gesturePendingSeekMs = -1L

    private val progressTicker = object : Runnable {
        override fun run() {
            updatePlaybackProgressUi()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        val extraMovie = intent.getSerializableExtra(EXTRA_MOVIE) as? DonghuaMovie
        if (extraMovie != null) movie = extraMovie
        initialEpisode = intent.getIntExtra(EXTRA_EPISODE, 1).coerceAtLeast(1)
        initialServerIdx = intent.getIntExtra(EXTRA_SERVER_IDX, -1)
        initialResumeSec = intent.getDoubleExtra(EXTRA_RESUME_SEC, -1.0)

        settings = PrefsStore.getSettings()
        deviceProfile = DeviceProfileResolver.resolve(this, settings.deviceMode)
        isTvProfile = deviceProfile == ResolvedDeviceProfile.TV
        seekStep = settings.seekStep.coerceAtLeast(5)
        playbackSpeed = settings.playbackSpeed.coerceIn(0.25f, 2.0f)
        aspectMode = AspectMode.fromWire(settings.aspectRatio)
        skipIntroSec = PrefsStore.getMovieSkipIntroSec(movie.id)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        intent.getStringExtra(EXTRA_STORYBOARD_URL)?.trim()?.takeIf { it.isNotEmpty() }?.let { sbUrl ->
            loadStoryboardVtt(sbUrl)
        }

        if (deviceProfile == ResolvedDeviceProfile.MOBILE && !isPortraitLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        enterImmersiveMode()
        ThemeManager.applyEdgeToEdgePadding(binding.playerRoot) { top, bottom, left, right ->
            cutoutTopPx = top
            cutoutBottomPx = bottom
            cutoutLeftPx = left
            cutoutRightPx = right
            applyPlayerCutoutPadding()
        }
        setupDeviceAdaptiveUi()
        initSeekCoalescer()
        initExoPlayer()
        setupControls()
        setupGestures()

        serverGroups = movie.serverGroups.orEmpty()
        if (serverGroups.isNotEmpty()) {
            activeServerIdx = if (initialServerIdx in serverGroups.indices) {
                initialServerIdx
            } else {
                MovieRepository.selectPreferredServerIdx(serverGroups, settings.defaultAudio)
            }
            val eps = serverGroups.getOrNull(activeServerIdx)?.episodes.orEmpty()
            currentEpIdx = PlayerUtils.findEpisodeIdxByNumber(eps, initialEpisode)
            epRangeIdx = currentEpIdx / PlayerUtils.EP_PAGE_SIZE
            loadCurrentEpisodeStream()
        } else {
            currentEpIdx = (initialEpisode - 1).coerceAtLeast(0)
            fetchMovieServersAndStart()
        }

        wakeHud()
        mainHandler.post(progressTicker)
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupDeviceAdaptiveUi() {
        binding.bilingualSubBanner.visibility = View.GONE
        binding.txtSkipIntroSec.text = "+${skipIntroSec}s"

        if (settings.extensions.aiUpscale && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = ColorMatrix().apply {
                setSaturation(1.16f)
                val contrast = 1.08f
                val translate = (-0.5f * contrast + 0.5f) * 255f
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            binding.playerView.setRenderEffect(
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm))
            )
        }

        binding.txtRewindSec.text = seekStep.toString()
        binding.txtForwardSec.text = seekStep.toString()
        binding.btnQuickSpeed.text = PlayerUtils.formatSpeedChip(playbackSpeed)
        binding.btnQuickQuality.text = if (settings.defaultQuality.equals("Auto", ignoreCase = true)) {
            "Auto"
        } else {
            movie.quality.ifBlank { "Auto" }.substringBefore(" ")
        }
        binding.txtActionSubtitles.text = if (settings.extensions.bilingualSub || settings.bilingualSub) {
            "Phụ đề: Bật"
        } else {
            "Phụ đề"
        }

        if (isTvProfile) {
            val dp = resources.displayMetrics.density
            binding.btnPlayPause.layoutParams = binding.btnPlayPause.layoutParams.apply {
                width = (92 * dp).toInt()
                height = (92 * dp).toInt()
            }
            binding.btnRewind.layoutParams = binding.btnRewind.layoutParams.apply {
                width = (82 * dp).toInt()
                height = (82 * dp).toInt()
            }
            binding.btnForward.layoutParams = binding.btnForward.layoutParams.apply {
                width = (82 * dp).toInt()
                height = (82 * dp).toInt()
            }
            binding.playerDrawer.layoutParams = binding.playerDrawer.layoutParams.apply {
                width = (390 * dp).toInt()
            }
        }

        applyAspectRatioToPlayerView()
        applyResponsivePlayerBars()
        applyPlayerCutoutPadding()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsivePlayerBars()
        applyPlayerCutoutPadding()
    }

    private fun applyPlayerCutoutPadding() {
        val dp = resources.displayMetrics.density
        val sideBase = (12 * dp).toInt()
        val bottomSide = (16 * dp).toInt()
        val topPad = if (isTvProfile) (10 * dp).toInt() else maxOf(0, cutoutTopPx / 3)
        val botPad = (10 * dp).toInt() + if (isTvProfile) (8 * dp).toInt() else maxOf(0, cutoutBottomPx / 3)
        binding.topBar.setPadding(sideBase + cutoutLeftPx, topPad, sideBase + cutoutRightPx, 0)
        binding.bottomBar.setPadding(
            bottomSide + cutoutLeftPx,
            (6 * dp).toInt(),
            bottomSide + cutoutRightPx,
            botPad
        )
        val drawerPad = (16 * dp).toInt()
        binding.playerDrawer.setPadding(drawerPad, drawerPad + topPad, drawerPad + cutoutRightPx, drawerPad + botPad)
    }

    private fun applyResponsivePlayerBars() {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val compactVertical = !isTvProfile && (isPortrait || resources.configuration.screenWidthDp < 520)
        val isTabletPc = deviceProfile == ResolvedDeviceProfile.TABLET
        val dp = resources.displayMetrics.density

        // Cân chỉnh cụm nút Play / Tua ở giữa cho từng thiết bị (Điện thoại dọc, Ngang/PC, TV)
        val playSize = ((if (isTvProfile) 88 else if (compactVertical) 64 else if (isTabletPc) 78 else 72) * dp).toInt()
        val seekSize = ((if (isTvProfile) 78 else if (compactVertical) 56 else if (isTabletPc) 68 else 62) * dp).toInt()
        val playMarginH = ((if (isTvProfile) 56 else if (compactVertical) 24 else if (isTabletPc) 48 else 38) * dp).toInt()
        binding.btnPlayPause.layoutParams = (binding.btnPlayPause.layoutParams as ViewGroup.MarginLayoutParams).apply {
            width = playSize
            height = playSize
            marginStart = playMarginH
            marginEnd = playMarginH
        }
        binding.btnRewind.layoutParams = binding.btnRewind.layoutParams.apply {
            width = seekSize
            height = seekSize
        }
        binding.btnForward.layoutParams = binding.btnForward.layoutParams.apply {
            width = seekSize
            height = seekSize
        }
        binding.txtPlayerTitle.textSize = if (compactVertical) 13.5f else if (isTvProfile) 16.5f else if (isTabletPc) 15.5f else 14.5f

        val row = binding.bottomActionsRow
        for (i in 0 until row.childCount) {
            val btn = row.getChildAt(i) as? LinearLayout ?: continue
            btn.orientation = if (compactVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            btn.layoutParams = btn.layoutParams.apply {
                height = ((if (compactVertical) 48 else if (isTvProfile) 42 else if (isTabletPc) 40 else 38) * dp).toInt()
            }
            val padH = ((if (compactVertical) 2 else if (isTabletPc) 8 else 6) * dp).toInt()
            btn.setPadding(padH, 0, padH, 0)
            val icon = btn.getChildAt(0) as? ImageView
            val label = btn.getChildAt(1) as? TextView
            icon?.layoutParams = icon?.layoutParams?.apply {
                val sz = ((if (compactVertical) 16 else if (isTvProfile) 19 else 18) * dp).toInt()
                width = sz
                height = sz
            }
            label?.let { tv ->
                tv.textSize = if (compactVertical) 9f else if (isTvProfile) 12.5f else if (isTabletPc) 12f else 11.5f
                tv.layoutParams = (tv.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = if (compactVertical) 0 else (6 * dp).toInt()
                    topMargin = if (compactVertical) (2 * dp).toInt() else 0
                }
            }
        }
    }

    private fun initSeekCoalescer() {
        seekCoalescer = ZeroDelaySeekCoalescer(
            rapidWindowMs = 150L,
            coalesceDelayMs = 100L,
            onPreview = { targetMs, deltaMs ->
                val secStr = PlayerUtils.formatPlayerTimeMs(targetMs)
                val deltaStr = PlayerUtils.formatSeekDelta(deltaMs)
                binding.seekPreviewTime.text = secStr
                binding.seekPreviewDelta.text = deltaStr
                bindStoryboardFrameTo(binding.imgSeekPreviewCenter, targetMs, showFallbackPoster = false)
                binding.seekPreviewPill.visibility = View.VISIBLE
                binding.txtCurrentTime.text = secStr
                val dur = exoPlayer?.duration ?: 0L
                if (dur > 0L) {
                    binding.seekBar.progress = ((targetMs * 1000L) / dur).toInt().coerceIn(0, 1000)
                }
                if (showHud || currentFocus == binding.seekBar) {
                    showSeekThumbPreview(targetMs, deltaMs, autoHide = true)
                }
                scheduleGestureOverlayHide()
            },
            onCommitSeek = { targetMs ->
                exoPlayer?.seekTo(targetMs)
            }
        )
    }

    private fun applySubtitleStyle() {
        binding.playerView.subtitleView?.apply {
            setStyle(
                CaptionStyleCompat(
                    settings.subtitleTextColor,
                    Color.argb(settings.subtitleBgAlpha.coerceIn(0, 240), 8, 12, 22),
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                    Color.BLACK,
                    null
                )
            )
            setFractionalTextSize(settings.subtitleScale.coerceIn(0.035f, 0.095f))
            setBottomPaddingFraction(settings.subtitleBottomPct.coerceIn(0.04f, 0.30f))
        }
    }

    private fun initExoPlayer() {
        val biOn = settings.extensions.bilingualSub || settings.bilingualSub
        val adaptiveFactory = AdaptiveTrackSelection.Factory(
            4_000,  // Tăng độ nét nhanh hơn khi mạng ổn (4s)
            10_000, // Hạ mượt khi băng thông giảm
            10_000,
            0.75f
        )
        val ts = DefaultTrackSelector(this, adaptiveFactory).apply {
            val builder = buildUponParameters()
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !biOn)
            val presetMaxH = PlayerUtils.maxHeightForQualityPreset(settings.defaultQuality)
            val deviceCapH = DeviceProfileResolver.recommendedMaxVideoHeight(this@PlayerActivity, presetMaxH)
            deviceCapH?.let { maxH ->
                builder.setMaxVideoSize(Int.MAX_VALUE, maxH)
                if (presetMaxH != null && maxH < presetMaxH) {
                    showToast("Tối ưu giải mã mượt tới ${maxH}p cho cấu hình thiết bị này")
                }
            }
            setParameters(builder)
        }
        trackSelector = ts

        applySubtitleStyle()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(ts)
            .setLoadControl(InstantLoadControl.create(this))
            .setMediaSourceFactory(HlsCacheProvider.buildMediaSourceFactory(this))
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                playbackParameters = PlaybackParameters(playbackSpeed)
                playWhenReady = true
            }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        binding.loadingOverlay.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        retriedCurrentStreamOnce = false
                        steppedDownCodecOnError = false
                        binding.loadingOverlay.visibility = View.GONE
                        binding.errorOverlay.visibility = View.GONE
                        audioDrc.apply(player.audioSessionId, settings.nightModeDrc)
                        if (pendingResumeSeekMs > 0L) {
                            val target = pendingResumeSeekMs
                            pendingResumeSeekMs = -1L
                            val dur = player.duration
                            val clamped = if (dur > 0L) target.coerceIn(0L, (dur - 2000L).coerceAtLeast(0L)) else target
                            if (abs(player.currentPosition - clamped) > 2_500L) {
                                player.seekTo(clamped)
                            }
                            if (clamped >= 30_000L) {
                                hasSkippedIntro = true
                            }
                            showToast("Phát tiếp từ ${PlayerUtils.formatPlayerTimeMs(clamped)}")
                        } else {
                            maybeAutoSkipIntro()
                        }
                        updatePlayPauseButton()
                        preResolveNextEpisode()
                    }
                    Player.STATE_ENDED -> {
                        val durMs = player.duration.coerceAtLeast(0L)
                        if (durMs > 0L) {
                            val epNum = currentEpisodeNum()
                            if (lastWatchedMarkedEp != epNum) {
                                lastWatchedMarkedEp = epNum
                                PrefsStore.markEpisodeWatched(movie.id, epNum)
                            }
                            persistWatchProgressNow()
                        }
                        updatePlayPauseButton()
                        if (settings.autoNext && currentEpIdx < totalEpisodesCount() - 1) {
                            startAutoNextCountdown()
                        }
                    }
                    else -> Unit
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                audioDrc.apply(audioSessionId, settings.nightModeDrc)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateQualityLevelsFromTracks(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                AppDiagnostics.log(
                    "Player",
                    "${error.errorCodeName}: ${error.message.orEmpty().take(90)}"
                )
                val isDecoderErr = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                )
                if (isDecoderErr && !steppedDownCodecOnError && currentActiveM3u8.isNotBlank()) {
                    steppedDownCodecOnError = true
                    trackSelector?.let { ts ->
                        ts.setParameters(ts.buildUponParameters().setMaxVideoSize(1280, 720))
                    }
                    val resumePosMs = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
                    mainHandler.postDelayed({
                        val p = exoPlayer ?: return@postDelayed
                        p.prepare()
                        if (resumePosMs > 1000L) p.seekTo(resumePosMs)
                        p.playWhenReady = true
                    }, 300L)
                    showToast("TV đang hạ về 720p để giải mã mượt hơn...")
                    return
                }
                val isTransientNetworkErr = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
                if (isTransientNetworkErr && !retriedCurrentStreamOnce && currentActiveM3u8.isNotBlank()) {
                    retriedCurrentStreamOnce = true
                    val resumePosMs = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
                    mainHandler.postDelayed({
                        val p = exoPlayer ?: return@postDelayed
                        p.prepare()
                        if (resumePosMs > 1000L) p.seekTo(resumePosMs)
                        p.playWhenReady = true
                    }, 400L)
                    return
                }
                binding.loadingOverlay.visibility = View.GONE
                if (currentActiveM3u8.isNotBlank()) {
                    failedStreamUrls.add(currentActiveM3u8)
                }
                if (!tryAutoFailover()) {
                    showNoDirectStreamError(
                        title = "Không thể phát luồng trực tiếp (HLS/MP4)",
                        subtitle = "Các máy chủ phát trực tiếp hiện không khả dụng (${error.errorCodeName})."
                    )
                }
            }
        })

        exoPlayer = player
        binding.playerView.player = player
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener {
            wakeHud()
            togglePlayPause()
        }
        binding.btnRewind.setOnClickListener {
            wakeHud()
            seekByStep(-seekStep)
        }
        binding.btnForward.setOnClickListener {
            wakeHud()
            seekByStep(seekStep)
        }
        binding.btnPrevEp.setOnClickListener {
            wakeHud()
            if (safeEpIdx() > 0) {
                switchToEpisode(safeEpIdx() - 1)
            }
        }
        binding.btnQuickQuality.setOnClickListener {
            wakeHud()
            toggleDrawer(PlayerDrawerType.QUALITY)
        }
        binding.btnQuickSpeed.setOnClickListener {
            wakeHud()
            toggleDrawer(PlayerDrawerType.SPEED)
        }
        binding.btnLockScreen.setOnClickListener {
            isLocked = true
            hideSkipIntroPillImmediate()
            closeDrawer()
            binding.hudContainer.visibility = View.GONE
            resetUnlockHideTimer()
            if (isTvProfile) {
                binding.btnUnlockScreen.post { binding.btnUnlockScreen.requestFocus() }
            }
            showToast("Đã khóa cảm ứng màn hình")
        }
        binding.btnUnlockScreen.setOnClickListener {
            isLocked = false
            cancelUnlockHideTimer()
            binding.btnUnlockScreen.visibility = View.GONE
            binding.hudContainer.visibility = View.VISIBLE
            wakeHud()
            showToast("Đã mở khóa màn hình trình phát")
        }
        binding.btnPipOrSpeed.setOnClickListener {
            wakeHud()
            if (isTvProfile || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                toggleDrawer(PlayerDrawerType.SPEED)
            } else {
                enterPipSafe()
            }
        }
        binding.btnFullscreen.setOnClickListener {
            wakeHud()
            isFullscreen = !isFullscreen
            if (isFullscreen) enterImmersiveMode()
            else WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())
        }
        binding.btnSkipIntro.setOnClickListener {
            wakeHud()
            performSkipIntro()
        }
        binding.btnCancelAutoNext.setOnClickListener {
            cancelAutoNextCountdown()
        }
        binding.btnPlayNextNow.setOnClickListener {
            cancelAutoNextCountdown()
            if (currentEpIdx < totalEpisodesCount() - 1) {
                switchToEpisode(currentEpIdx + 1)
            }
        }
        binding.btnRetryStream.setOnClickListener {
            binding.errorOverlay.visibility = View.GONE
            wakeHud()
            failedServers.clear()
            failedStreamUrls.clear()
            retriedCurrentStreamOnce = false
            steppedDownCodecOnError = false
            loadCurrentEpisodeStream()
        }
        binding.btnCloseError.setOnClickListener { finish() }
        binding.btnCloseDrawer.setOnClickListener { closeDrawer() }

        // Bottom CloudStream 6 actions
        binding.btnActionAspect.setOnClickListener {
            wakeHud()
            aspectMode = aspectMode.next()
            applyAspectRatioToPlayerView()
            settings = PrefsStore.saveSettings(settings.copy(aspectRatio = aspectMode.wireValue))
            showToast(aspectMode.label)
        }
        binding.btnActionSubtitles.setOnClickListener {
            wakeHud()
            val curDisabled = trackSelector?.parameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
            val nextEnable = curDisabled || !(settings.extensions.bilingualSub || settings.bilingualSub)
            binding.bilingualSubBanner.visibility = View.GONE
            binding.txtActionSubtitles.text = if (nextEnable) "Phụ đề: Bật" else "Phụ đề"
            settings = PrefsStore.saveSettings(
                settings.copy(
                    bilingualSub = nextEnable,
                    extensions = settings.extensions.copy(bilingualSub = nextEnable)
                )
            )
            trackSelector?.let { ts ->
                val b = ts.buildUponParameters()
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !nextEnable)
                ts.setParameters(b)
            }
            if (nextEnable && externalSubtitleUri == null) {
                showToast("Đã bật track phụ đề (có thể nạp file .srt/.vtt trong Chỉnh phụ đề)")
            } else {
                showToast(if (nextEnable) "Đã bật Phụ đề" else "Đã tắt Phụ đề")
            }
        }
        binding.btnActionSubTune.setOnClickListener {
            wakeHud()
            toggleDrawer(PlayerDrawerType.PLAYBACK_SETTINGS)
        }
        binding.btnActionSources.setOnClickListener {
            wakeHud()
            toggleDrawer(PlayerDrawerType.SOURCES)
        }
        binding.btnActionAudio.setOnClickListener {
            wakeHud()
            toggleAudioQuick()
        }
        binding.btnActionNextEp.setOnClickListener {
            wakeHud()
            if (currentEpIdx < totalEpisodesCount() - 1) {
                switchToEpisode(currentEpIdx + 1)
            }
        }
        binding.btnActionEpisodes.setOnClickListener {
            wakeHud()
            toggleDrawer(PlayerDrawerType.EPISODES)
        }

        // SeekBar leading-edge throttle (~80ms) + floating storyboard/thumbnail preview
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                wakeHud()
                val dur = exoPlayer?.duration ?: 0L
                if (dur <= 0L) return
                val targetMs = (dur * progress) / 1000L
                binding.txtCurrentTime.text = PlayerUtils.formatPlayerTimeMs(targetMs)
                showSeekThumbPreview(targetMs, targetMs - seekBarDragOriginMs, autoHide = false)
                val now = System.currentTimeMillis()
                if (now - lastSeekBarCommitMs >= 80L) {
                    lastSeekBarCommitMs = now
                    exoPlayer?.seekTo(targetMs)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserDraggingSeekBar = true
                seekBarDragOriginMs = exoPlayer?.currentPosition ?: 0L
                wakeHud()
                val dur = exoPlayer?.duration ?: 0L
                if (dur > 0L && sb != null) {
                    val targetMs = (dur * sb.progress) / 1000L
                    showSeekThumbPreview(targetMs, 0L, autoHide = false)
                }
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserDraggingSeekBar = false
                hideSeekThumbPreview()
                val dur = exoPlayer?.duration ?: 0L
                if (dur > 0L && sb != null) {
                    val targetMs = (dur * sb.progress) / 1000L
                    exoPlayer?.seekTo(targetMs)
                }
            }
        })
    }

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (activeDrawer != PlayerDrawerType.NONE) {
                    closeDrawer()
                    return true
                }
                if (isLocked) {
                    resetUnlockHideTimer()
                    return true
                }
                showHud = !showHud
                if (showHud) wakeHud() else hideHud()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return false
                val w = binding.gestureStage.width.coerceAtLeast(1)
                val rel = e.x / w.toFloat()
                when {
                    rel < 0.34f -> {
                        seekByStep(-seekStep)
                        showDoubleTapRipple(left = true)
                    }
                    rel > 0.66f -> {
                        seekByStep(seekStep)
                        showDoubleTapRipple(left = false)
                    }
                    else -> togglePlayPause()
                }
                return true
            }
        })

        binding.gestureStage.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            if (activeDrawer != PlayerDrawerType.NONE) return@setOnTouchListener false
            if (isLocked) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    resetUnlockHideTimer()
                }
                return@setOnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureActive = true
                    gestureAxis = 0
                    gestureLeftSide = event.x < (v.width / 2f)
                    gestureStartX = event.x
                    gestureStartY = event.y
                    gestureStartBrightness = brightness
                    gestureStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    gestureStartVideoMs = exoPlayer?.currentPosition ?: 0L
                    gesturePendingSeekMs = -1L
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) return@setOnTouchListener false
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY
                    val dp = resources.displayMetrics.density
                    if (gestureAxis == 0) {
                        if (abs(dy) > 14f * dp && abs(dy) > abs(dx) * 1.2f) {
                            gestureAxis = 1
                        } else if (abs(dx) > 18f * dp && abs(dx) > abs(dy) * 1.2f && (exoPlayer?.duration ?: 0L) > 0L) {
                            gestureAxis = 2
                        }
                    }

                    if (gestureAxis == 1) {
                        val sens = (v.height * 0.65f).coerceAtLeast(180f * dp)
                        val delta = -dy / sens
                        if (gestureLeftSide) {
                            val nextB = (gestureStartBrightness + delta).coerceIn(0.15f, 1.0f)
                            applyBrightness(nextB)
                            showVerticalGestureHud(isBrightness = true, pct = (nextB * 100).toInt())
                        } else {
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val targetVol = (gestureStartVolume + delta * maxVol).toInt().coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            val pct = ((targetVol.toFloat() / maxVol) * 100).toInt()
                            showVerticalGestureHud(isBrightness = false, pct = pct)
                        }
                    } else if (gestureAxis == 2) {
                        val dur = exoPlayer?.duration ?: 0L
                        if (dur > 0L) {
                            val seekSpanMs = minOf(180_000L, maxOf(60_000L, (dur * 0.15).toLong()))
                            val ratio = dx / maxOf(220f * dp, v.width * 0.75f)
                            val deltaMs = (ratio * seekSpanMs).toLong()
                            val targetMs = (gestureStartVideoMs + deltaMs).coerceIn(0L, dur)
                            gesturePendingSeekMs = targetMs
                            binding.seekPreviewTime.text = PlayerUtils.formatPlayerTimeMs(targetMs)
                            binding.seekPreviewDelta.text = PlayerUtils.formatSeekDelta(deltaMs)
                            bindStoryboardFrameTo(binding.imgSeekPreviewCenter, targetMs, showFallbackPoster = false)
                            binding.seekPreviewPill.visibility = View.VISIBLE
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureAxis == 2 && gesturePendingSeekMs >= 0L) {
                        // Commit seek immediately (0ms) on horizontal swipe release!
                        exoPlayer?.seekTo(gesturePendingSeekMs)
                    }
                    gestureActive = false
                    gestureAxis = 0
                    gesturePendingSeekMs = -1L
                    scheduleGestureOverlayHide()
                }
            }
            true
        }
    }

    private fun fetchMovieServersAndStart() {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingText.text = "Đang nạp máy chủ & danh sách tập..."
        var fastStarted = false
        lifecycleScope.launch {
            val detail = runCatching {
                MovieRepository.fetchMovieDetailFast(movie.slug.orEmpty(), movie) { fast ->
                    if (!fastStarted && fast.serverGroups.isNotEmpty()) {
                        fastStarted = true
                        runOnUiThread {
                            serverGroups = fast.serverGroups
                            activeServerIdx = if (initialServerIdx in serverGroups.indices) {
                                initialServerIdx
                            } else {
                                MovieRepository.selectPreferredServerIdx(serverGroups, settings.defaultAudio)
                            }
                            val eps = serverGroups[activeServerIdx].episodes
                            currentEpIdx = PlayerUtils.findEpisodeIdxByNumber(eps, initialEpisode)
                            loadCurrentEpisodeStream()
                        }
                    }
                }
            }.getOrNull()
            if (detail != null && detail.serverGroups.isNotEmpty()) {
                if (!fastStarted) {
                    serverGroups = detail.serverGroups
                    activeServerIdx = if (initialServerIdx in serverGroups.indices) {
                        initialServerIdx
                    } else {
                        MovieRepository.selectPreferredServerIdx(serverGroups, settings.defaultAudio)
                    }
                    val eps = serverGroups[activeServerIdx].episodes
                    currentEpIdx = PlayerUtils.findEpisodeIdxByNumber(eps, initialEpisode)
                    loadCurrentEpisodeStream()
                } else {
                    // Nền nạp thêm máy chủ dự phòng: giữ nguyên máy chủ & tập đang phát
                    val currentSrvName = serverGroups.getOrNull(activeServerIdx)?.serverName
                    serverGroups = detail.serverGroups
                    if (!currentSrvName.isNullOrBlank()) {
                        val keptIdx = serverGroups.indexOfFirst { it.serverName == currentSrvName }
                        if (keptIdx >= 0) activeServerIdx = keptIdx
                    }
                }
            } else if (!fastStarted) {
                loadCurrentEpisodeStream()
            }
        }
    }

    private fun currentServer(): ServerGroup? = serverGroups.getOrNull(activeServerIdx) ?: serverGroups.firstOrNull()
    private fun episodesList(): List<EpisodeItem> = currentServer()?.episodes.orEmpty()
    private fun safeEpIdx(): Int {
        val eps = episodesList()
        return if (eps.isNotEmpty()) currentEpIdx.coerceIn(0, eps.size - 1) else currentEpIdx.coerceAtLeast(0)
    }
    private fun totalEpisodesCount(): Int {
        val eps = episodesList()
        return if (eps.isNotEmpty()) eps.size else movie.latestEp.coerceAtLeast(24)
    }
    private fun currentEpisodeItem(): EpisodeItem? = episodesList().getOrNull(safeEpIdx())
    private fun currentEpisodeNum(): Int = PlayerUtils.extractEpisodeNumber(currentEpisodeItem(), safeEpIdx())

    private fun loadCurrentEpisodeStream() {
        loadEpisodeJob?.cancel()
        cancelAutoNextCountdown()

        val epItem = currentEpisodeItem()
        val epNum = currentEpisodeNum()
        val srv = currentServer()
        val epTitle = epItem?.name?.ifBlank { "Tập $epNum" } ?: "Tập $epNum"
        binding.txtPlayerTitle.text = "${movie.title} - $epTitle"
        binding.bilingualSubTitleZh.text = "${movie.title} • $epTitle • ${srv?.serverName ?: "ExoPlayer"}"
        binding.txtActionAudio.text =
            if (MovieRepository.isDubbedServerName(srv?.serverName)) "Thuyết minh" else "Vietsub"
        binding.btnPrevEp.visibility = if (totalEpisodesCount() > 1) View.VISIBLE else View.GONE
        binding.btnPrevEp.isEnabled = safeEpIdx() > 0
        binding.btnPrevEp.alpha = if (safeEpIdx() > 0) 1f else 0.35f
        binding.btnActionNextEp.isEnabled = currentEpIdx < totalEpisodesCount() - 1
        binding.btnActionNextEp.alpha = if (currentEpIdx < totalEpisodesCount() - 1) 1f else 0.4f

        hasSkippedIntro = false
        skipIntroShownEpKey = null
        hideSkipIntroPillImmediate()
        epRangeIdx = -1

        // Check offline file first!
        val offlineKey = HlsOfflineManager.buildEpisodeKey(movie.id, epNum)
        val offlineRec = PrefsStore.getOfflineEpisodes()[offlineKey]
        val localFile = offlineRec?.localM3u8Path?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (localFile != null && localFile.exists()) {
            currentActiveM3u8 = Uri.fromFile(localFile).toString()
            playHlsWithExoPlayer(currentActiveM3u8, isOffline = true)
            return
        }

        val wpPostId = epItem?.wpPostId ?: epItem?.yanPostId
        val wpEpSlug = epItem?.wpEpSlug ?: epItem?.yanEpSlug
        val wpServerType = epItem?.wpServerType ?: epItem?.yanServerType
        val wpBaseUrl = epItem?.wpBaseUrl ?: YanHh3dScraper.YAN_BASE
        val wpServerSv = epItem?.wpServerSv ?: "1"
        val wpPageUrl = epItem?.wpPageUrl ?: epItem?.yanPageUrl
        val isVsmovServer = wpServerType == VsmovSourceProvider.SERVER_TYPE_VSMOV &&
            (!epItem?.linkEmbed.isNullOrBlank() || !wpPageUrl.isNullOrBlank())
        val isNowServer = !wpServerType.isNullOrBlank() && wpServerType.startsWith("now:") && !wpPageUrl.isNullOrBlank()
        val wpKey = when {
            isVsmovServer -> "vsmov_${epItem?.linkEmbed?.ifBlank { wpPageUrl }.orEmpty()}"
            isNowServer -> "${wpBaseUrl}_${wpPageUrl}_$wpServerType"
            wpPostId != null && !wpEpSlug.isNullOrBlank() && !wpServerType.isNullOrBlank() ->
                "${wpBaseUrl}_${wpPostId}_${wpEpSlug}_${wpServerType}_$wpServerSv"
            else -> ""
        }

        loadEpisodeJob = lifecycleScope.launch {
            binding.loadingOverlay.visibility = View.VISIBLE
            binding.errorOverlay.visibility = View.GONE
            binding.loadingText.text = "Đang tải: $epTitle • ${srv?.serverName ?: "Trình phát Tích hợp"}"

            var m3u8 = epItem?.linkM3u8.orEmpty()
            var embed = epItem?.linkEmbed.orEmpty()

            if (m3u8.isBlank() && isVsmovServer) {
                val cachedResolved = resolvedStreams[wpKey]
                val res = cachedResolved ?: runCatching {
                    val targetEmbed = embed.ifBlank { wpPageUrl.orEmpty() }
                    VsmovSourceProvider.resolveStreamResult(targetEmbed, context = this@PlayerActivity)
                }.getOrNull()
                if (res != null && (res.linkM3u8.isNotBlank() || res.linkEmbed.isNotBlank())) {
                    resolvedStreams[wpKey] = res
                    m3u8 = res.linkM3u8
                    embed = res.linkEmbed.ifBlank { embed }
                    if (m3u8.isNotBlank() && res.headers.isNotEmpty()) {
                        HlsCacheProvider.registerStreamHeaders(m3u8, res.headers)
                    }
                    if (externalSubtitleUri == null && !res.subtitleUrl.isNullOrBlank()) {
                        val subUrl = res.subtitleUrl
                        externalSubtitleUri = Uri.parse(subUrl)
                        externalSubtitleMime = if (subUrl.contains(".srt", true)) {
                            MimeTypes.APPLICATION_SUBRIP
                        } else {
                            MimeTypes.TEXT_VTT
                        }
                    }
                    if (!res.storyboardUrl.isNullOrBlank() && storyboardCues.isEmpty()) {
                        loadStoryboardVtt(res.storyboardUrl)
                    }
                }
            } else if (m3u8.isBlank() && embed.isBlank() && wpKey.isNotEmpty()) {
                val cachedResolved = resolvedStreams[wpKey]
                if (cachedResolved != null) {
                    m3u8 = cachedResolved.linkM3u8
                    embed = cachedResolved.linkEmbed
                } else {
                    val res = runCatching {
                        if (isNowServer) {
                            YanHh3dNowScraper.resolveStream(
                                watchPageUrl = wpPageUrl!!,
                                serverKey = wpServerType!!,
                                refererBase = wpBaseUrl
                            )
                        } else {
                            YanHh3dScraper.resolveStream(
                                baseUrl = wpBaseUrl,
                                postId = wpPostId!!,
                                epSlug = wpEpSlug!!,
                                serverType = wpServerType!!,
                                sv = wpServerSv,
                                refererUrl = wpPageUrl
                            )
                        }
                    }.getOrNull()
                    if (res != null && (res.linkM3u8.isNotBlank() || res.linkEmbed.isNotBlank())) {
                        resolvedStreams[wpKey] = res
                        m3u8 = res.linkM3u8
                        embed = res.linkEmbed
                    }
                }
            }

            currentActiveM3u8 = m3u8
            retriedCurrentStreamOnce = false
            if (m3u8.isNotBlank() && !failedStreamUrls.contains(m3u8)) {
                playHlsWithExoPlayer(m3u8, isOffline = false)
            } else if (!tryAutoFailover()) {
                val embedHost = runCatching { Uri.parse(embed).host }.getOrNull()
                val subtitle = if (!embedHost.isNullOrBlank()) {
                    "Tập này chỉ có liên kết nhúng ($embedHost) và không có luồng .m3u8/.mp4 trực tiếp cho ExoPlayer."
                } else {
                    "Không có luồng phát trực tiếp (.m3u8/.mp4) từ các máy chủ hiện có."
                }
                showNoDirectStreamError(
                    title = "Không có luồng phát trực tiếp",
                    subtitle = subtitle
                )
            }
        }
    }

    private fun playHlsWithExoPlayer(streamUrl: String, isOffline: Boolean, seekRestoreMs: Long = -1L) {
        isCurrentStreamOffline = isOffline
        lastRxBytes = -1L
        lastRxSampleMs = 0L
        cachedSpeedLabel = ""
        binding.playerView.visibility = View.VISIBLE
        binding.gestureStage.visibility = View.VISIBLE
        binding.centerControls.visibility = View.VISIBLE
        binding.timelineRow.visibility = View.VISIBLE
        updateHudFocusRouting()
        wakeHud()

        val qualityTag = when {
            isOffline -> "Offline HLS"
            activeQualityIdx < 0 || settings.defaultQuality.equals("Auto", ignoreCase = true) -> "Auto"
            else -> movie.quality.ifBlank { "Auto" }
        }
        binding.txtPlayerBadge.visibility = View.VISIBLE
        binding.txtPlayerBadge.text = "Trình phát Tích hợp • $qualityTag"

        // Tự động phát tiếp từ đúng phút/giây khi mở từ Tiếp tục xem / Lịch sử (không hiện popup Xem dở)
        val targetSeekMs = when {
            seekRestoreMs > 0L -> seekRestoreMs
            initialResumeSec > 5.0 -> {
                val ms = (initialResumeSec * 1000.0).toLong()
                initialResumeSec = -1.0
                ms
            }
            else -> -1L
        }
        pendingResumeSeekMs = targetSeekMs
        suppressAutoSkipForResume = targetSeekMs > 0L

        val player = exoPlayer ?: return
        val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)
        val subUri = externalSubtitleUri
        val subMime = externalSubtitleMime
        if (subUri != null && !subMime.isNullOrBlank()) {
            val subCfg = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(subMime)
                .setLanguage("vi")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subCfg))
        }
        val mediaItem = mediaItemBuilder.build()
        val isHls = !streamUrl.startsWith("file://") && (
            streamUrl.contains(".m3u8", ignoreCase = true) ||
                streamUrl.contains("/stream-plain", ignoreCase = true) ||
                streamUrl.contains("/master.m3u8", ignoreCase = true)
            )
        if (isHls) {
            val hlsSource = HlsCacheProvider.buildHlsMediaSourceFactory(this).createMediaSource(mediaItem)
            player.setMediaSource(hlsSource)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        if (targetSeekMs > 0L) {
            player.seekTo(targetSeekMs)
        }
        player.playWhenReady = true
    }

    private fun attachExternalSubtitle(uri: Uri) {
        val rawPath = uri.toString().lowercase()
        val mime = when {
            rawPath.contains(".vtt") -> MimeTypes.TEXT_VTT
            rawPath.contains(".ass") || rawPath.contains(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        baseExternalSubtitleUri = uri
        externalSubtitleUri = uri
        externalSubtitleMime = mime
        subtitleOffsetMs = 0L
        externalSubtitleRawText = null
        externalSubtitleIsVtt = mime == MimeTypes.TEXT_VTT
        trackSelector?.let { ts ->
            val b = ts.buildUponParameters()
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            ts.setParameters(b)
        }
        binding.txtActionSubtitles.text = "Phụ đề: Rời"
        settings = PrefsStore.saveSettings(
            settings.copy(
                bilingualSub = true,
                extensions = settings.extensions.copy(bilingualSub = true)
            )
        )
        val resumePos = exoPlayer?.currentPosition ?: -1L
        if (currentActiveM3u8.isNotBlank()) {
            playHlsWithExoPlayer(currentActiveM3u8, isOffline = isCurrentStreamOffline, seekRestoreMs = resumePos)
        }
        if (mime != MimeTypes.TEXT_SSA) {
            lifecycleScope.launch {
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            val bytes = stream.readBytes()
                            if (bytes.size <= 2 * 1024 * 1024) bytes.toString(Charsets.UTF_8) else null
                        }
                    }.getOrNull()
                }
                if (!raw.isNullOrBlank()) {
                    externalSubtitleRawText = raw
                    externalSubtitleIsVtt = raw.trimStart().startsWith("WEBVTT", ignoreCase = true) ||
                        mime == MimeTypes.TEXT_VTT
                    if (activeDrawer == PlayerDrawerType.PLAYBACK_SETTINGS) renderDrawer()
                }
            }
        }
        showToast("Đã nạp file phụ đề rời!")
    }

    private fun applyExternalSubtitleOffset(deltaMs: Long) {
        if (externalSubtitleRawText == null) return
        val nextOffset = if (deltaMs == 0L) 0L else (subtitleOffsetMs + deltaMs).coerceIn(-30_000L, 30_000L)
        if (nextOffset == subtitleOffsetMs && deltaMs != 0L) return
        subtitleOffsetMs = nextOffset
        val label = if (nextOffset == 0L) "0.0s" else String.format(Locale.US, "%+.1fs", nextOffset / 1000.0)
        if (nextOffset == 0L) {
            externalSubtitleUri = baseExternalSubtitleUri
            val pos = exoPlayer?.currentPosition ?: -1L
            if (currentActiveM3u8.isNotBlank()) {
                playHlsWithExoPlayer(currentActiveM3u8, isOffline = isCurrentStreamOffline, seekRestoreMs = pos)
            }
            renderDrawer()
            showToast("Đồng bộ phụ đề: $label")
            return
        }
        val raw = externalSubtitleRawText ?: return
        val isVtt = externalSubtitleIsVtt
        lifecycleScope.launch {
            val outFile = withContext(Dispatchers.IO) {
                runCatching {
                    val shifted = SubtitleOffsetHelper.shiftSrtOrVtt(raw, nextOffset)
                    val file = File(cacheDir, if (isVtt) "ext_sub_shifted.vtt" else "ext_sub_shifted.srt")
                    file.writeText(shifted, Charsets.UTF_8)
                    file
                }.getOrNull()
            }
            if (outFile != null) {
                externalSubtitleUri = Uri.fromFile(outFile)
                val pos = exoPlayer?.currentPosition ?: -1L
                if (currentActiveM3u8.isNotBlank()) {
                    playHlsWithExoPlayer(currentActiveM3u8, isOffline = isCurrentStreamOffline, seekRestoreMs = pos)
                }
                renderDrawer()
                showToast("Đồng bộ phụ đề: $label")
            }
        }
    }

    private fun showNoDirectStreamError(title: String, subtitle: String) {
        exoPlayer?.stop()
        binding.loadingOverlay.visibility = View.GONE
        binding.hudContainer.visibility = View.GONE
        binding.txtErrorTitle.text = title
        binding.txtErrorSubtitle.text = subtitle
        binding.errorOverlay.visibility = View.VISIBLE
        binding.btnRetryStream.post { binding.btnRetryStream.requestFocus() }
    }

    private fun isCandidateAlreadyFailedUrl(serverIdx: Int, targetEpNum: Int): Boolean {
        val eps = serverGroups.getOrNull(serverIdx)?.episodes.orEmpty()
        if (eps.isEmpty()) return false
        val epIdx = PlayerUtils.findEpisodeIdxByNumber(eps, targetEpNum)
        val ep = eps.getOrNull(epIdx) ?: return false
        val direct = ep.linkM3u8
        if (direct.isNotBlank() && failedStreamUrls.contains(direct)) return true
        return false
    }

    private fun tryAutoFailover(): Boolean {
        failedServers.add(activeServerIdx)
        val targetEpNum = currentEpisodeNum()
        val currentlyDubbed = MovieRepository.isDubbedServerName(currentServer()?.serverName)
        val candidates = MovieRepository.visibleServerIndices(serverGroups)
            .filter { !failedServers.contains(it) && !isCandidateAlreadyFailedUrl(it, targetEpNum) }
        val nextIdx = candidates.firstOrNull { idx ->
            MovieRepository.isDubbedServerName(serverGroups[idx].serverName) == currentlyDubbed
        } ?: candidates.firstOrNull() ?: -1

        if (nextIdx >= 0) {
            showToast("Đang chuyển sang máy chủ ổn định hơn: ${serverGroups[nextIdx].serverName}")
            switchToServer(nextIdx)
            return true
        }
        return false
    }

    private fun switchToServer(nextIdx: Int) {
        persistWatchProgressNow()
        val curPosMs = exoPlayer?.currentPosition ?: 0L
        if (curPosMs > 5_000L) {
            initialResumeSec = curPosMs / 1000.0
        }
        val targetEpNum = currentEpisodeNum()
        activeServerIdx = nextIdx
        val nextEps = serverGroups.getOrNull(nextIdx)?.episodes.orEmpty()
        currentEpIdx = if (nextEps.isNotEmpty()) {
            PlayerUtils.findEpisodeIdxByNumber(nextEps, targetEpNum)
        } else currentEpIdx
        loadCurrentEpisodeStream()
    }

    private fun switchToEpisode(nextIdx: Int) {
        persistWatchProgressNow()
        initialResumeSec = -1.0
        pendingResumeSeekMs = -1L
        suppressAutoSkipForResume = false
        failedServers.clear()
        failedStreamUrls.clear()
        retriedCurrentStreamOnce = false
        lastWatchedMarkedEp = -1
        currentEpIdx = nextIdx
        loadCurrentEpisodeStream()
        showToast("Đang phát ${currentEpisodeItem()?.name ?: "Tập ${nextIdx + 1}"}")
    }

    private fun maybeAutoSkipIntro() {
        val epKey = "${movie.id}_${currentEpIdx}_$activeServerIdx"
        if (settings.autoSkipIntro && !suppressAutoSkipForResume && autoSkippedEpKey != epKey) {
            autoSkippedEpKey = epKey
            exoPlayer?.seekTo(skipIntroSec * 1000L)
            hasSkippedIntro = true
            hideSkipIntroPillImmediate()
            showToast("Auto Skip Intro: Đã nhảy qua +${skipIntroSec}s mở đầu")
        }
    }

    private fun performSkipIntro() {
        val player = exoPlayer ?: return
        val skipMs = skipIntroSec * 1000L
        val target = maxOf(player.currentPosition + skipMs, skipMs)
            .coerceAtMost(if (player.duration > 0) player.duration else Long.MAX_VALUE)
        player.seekTo(target)
        hasSkippedIntro = true
        hideSkipIntroPillImmediate()
        showToast("Đã bỏ qua mở đầu (+${skipIntroSec}s)")
    }

    private fun hideSkipIntroPillImmediate() {
        skipIntroHideRunnable?.let { mainHandler.removeCallbacks(it) }
        skipIntroHideRunnable = null
        binding.btnSkipIntro.animate().cancel()
        binding.btnSkipIntro.visibility = View.GONE
        binding.btnSkipIntro.alpha = 1f
    }

    private fun maybeShowSkipIntroForFiveSeconds(posSec: Long) {
        val epKey = "${movie.id}_${currentEpIdx}_$activeServerIdx"
        if (isLocked ||
            hasSkippedIntro ||
            settings.autoSkipIntro ||
            suppressAutoSkipForResume ||
            skipIntroShownEpKey == epKey ||
            binding.loadingOverlay.visibility == View.VISIBLE ||
            exoPlayer?.isPlaying != true ||
            posSec !in 0L..179L
        ) {
            return
        }
        skipIntroShownEpKey = epKey
        binding.txtSkipIntroSec.text = "+${skipIntroSec}s"
        binding.btnSkipIntro.animate().cancel()
        val reduced = DeviceProfileResolver.shouldUseReducedMotion(this, deviceProfile, settings.reducedMotion)
        if (reduced) {
            binding.btnSkipIntro.alpha = 1f
            binding.btnSkipIntro.visibility = View.VISIBLE
        } else {
            binding.btnSkipIntro.alpha = 0f
            binding.btnSkipIntro.visibility = View.VISIBLE
            binding.btnSkipIntro.animate().alpha(1f).setDuration(180L).start()
        }

        skipIntroHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val hideTask = Runnable {
            if (reduced) {
                binding.btnSkipIntro.visibility = View.GONE
                binding.btnSkipIntro.alpha = 1f
            } else {
                binding.btnSkipIntro.animate()
                    .alpha(0f)
                    .setDuration(200L)
                    .withEndAction {
                        binding.btnSkipIntro.visibility = View.GONE
                        binding.btnSkipIntro.alpha = 1f
                    }
                    .start()
            }
        }
        skipIntroHideRunnable = hideTask
        mainHandler.postDelayed(hideTask, 5_000L)
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
        updatePlayPauseButton()
    }

    private fun preResolveNextEpisode() {
        val nextIdx = safeEpIdx() + 1
        val nextEp = episodesList().getOrNull(nextIdx) ?: return
        if (nextEp.linkM3u8.isNotBlank()) {
            lifecycleScope.launch {
                HlsCacheProvider.prefetchHlsStart(this@PlayerActivity, nextEp.linkM3u8)
            }
            return
        }

        val wpServerType = nextEp.wpServerType ?: nextEp.yanServerType ?: return
        val wpBaseUrl = nextEp.wpBaseUrl ?: YanHh3dScraper.YAN_BASE
        val wpServerSv = nextEp.wpServerSv ?: "1"
        val wpPageUrl = nextEp.wpPageUrl ?: nextEp.yanPageUrl
        if (wpServerType == VsmovSourceProvider.SERVER_TYPE_VSMOV) {
            val embedUrl = nextEp.linkEmbed.ifBlank { wpPageUrl.orEmpty() }
            if (embedUrl.isBlank()) return
            val vsKey = "vsmov_$embedUrl"
            val existing = resolvedStreams[vsKey]
            if (existing != null) {
                if (existing.linkM3u8.isNotBlank()) {
                    HlsCacheProvider.registerStreamHeaders(existing.linkM3u8, existing.headers)
                    lifecycleScope.launch {
                        HlsCacheProvider.prefetchHlsStart(this@PlayerActivity, existing.linkM3u8)
                    }
                }
                return
            }
            lifecycleScope.launch {
                runCatching {
                    val res = VsmovSourceProvider.resolveStreamResult(embedUrl)
                    if (res.linkM3u8.isNotBlank()) {
                        resolvedStreams[vsKey] = res
                        HlsCacheProvider.registerStreamHeaders(res.linkM3u8, res.headers)
                        HlsCacheProvider.prefetchHlsStart(this@PlayerActivity, res.linkM3u8)
                    }
                }
            }
            return
        }
        val isNowNext = wpServerType.startsWith("now:") && !wpPageUrl.isNullOrBlank()
        if (isNowNext) {
            val nowKey = "${wpBaseUrl}_${wpPageUrl}_$wpServerType"
            val existing = resolvedStreams[nowKey]
            if (existing != null) {
                if (existing.linkM3u8.isNotBlank()) {
                    lifecycleScope.launch {
                        HlsCacheProvider.prefetchHlsStart(this@PlayerActivity, existing.linkM3u8)
                    }
                }
                return
            }
            lifecycleScope.launch {
                runCatching {
                    val res = YanHh3dNowScraper.resolveStream(wpPageUrl!!, wpServerType, wpBaseUrl)
                    if (res.linkM3u8.isNotBlank()) {
                        resolvedStreams[nowKey] = res
                        HlsCacheProvider.prefetchHlsStart(this@PlayerActivity, res.linkM3u8)
                    }
                }
            }
            return
        }

        val wpPostId = nextEp.wpPostId ?: nextEp.yanPostId ?: return
        val wpEpSlug = nextEp.wpEpSlug ?: nextEp.yanEpSlug ?: return
        val wpKey = "${wpBaseUrl}_${wpPostId}_${wpEpSlug}_${wpServerType}_$wpServerSv"
        val existing = resolvedStreams[wpKey]
        if (existing != null) {
            if (existing.linkM3u8.isNotBlank()) {
                lifecycleScope.launch {
                    HlsCacheProvider.prefetchHlsStart(this@PlayerActivity, existing.linkM3u8)
                }
            }
            return
        }

        lifecycleScope.launch {
            runCatching {
                val res = YanHh3dScraper.resolveStream(
                    baseUrl = wpBaseUrl,
                    postId = wpPostId,
                    epSlug = wpEpSlug,
                    serverType = wpServerType,
                    sv = wpServerSv,
                    refererUrl = wpPageUrl
                )
                if (res.linkM3u8.isNotBlank()) {
                    resolvedStreams[wpKey] = res
                    HlsCacheProvider.prefetchHlsStart(this@PlayerActivity, res.linkM3u8)
                }
            }
        }
    }

    private fun updatePlayPauseButton() {
        val playing = exoPlayer?.isPlaying == true
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_cs_pause else R.drawable.ic_cs_play
        )
    }

    private fun seekByStep(deltaSec: Int) {
        val player = exoPlayer ?: return
        seekCoalescer?.seekBy(
            deltaMs = deltaSec * 1000L,
            currentPosMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0L)
        )
    }

    private fun persistWatchProgressNow() {
        val player = exoPlayer ?: return
        val posMs = player.currentPosition.coerceAtLeast(0L)
        val durMs = player.duration.coerceAtLeast(0L)
        if (posMs <= 5_000L || durMs <= 0L) return
        lastProgressPersistMs = System.currentTimeMillis()
        val epNum = currentEpisodeNum()
        val srvName = currentServer()?.serverName ?: "Trình phát Tích hợp"
        PrefsStore.saveProgress(
            WatchProgress(
                donghuaId = movie.id,
                title = movie.title,
                poster = movie.poster,
                episode = epNum,
                currentTime = posMs / 1000.0,
                duration = durMs / 1000.0,
                progressPct = ((posMs * 100L) / durMs).toInt().coerceIn(0, 100),
                server = srvName,
                audio = if (MovieRepository.isDubbedServerName(srvName)) "Thuyết Minh" else "Vietsub",
                updatedAt = lastProgressPersistMs
            )
        )
    }

    private fun updatePlaybackProgressUi() {
        val player = exoPlayer ?: return
        val posMs = player.currentPosition.coerceAtLeast(0L)
        val durMs = player.duration.coerceAtLeast(0L)
        val bufMs = player.bufferedPosition.coerceAtLeast(0L)

        if (!isUserDraggingSeekBar) {
            binding.txtCurrentTime.text = PlayerUtils.formatPlayerTimeMs(posMs)
            binding.txtDuration.text = PlayerUtils.formatPlayerTimeMs(durMs)
            if (durMs > 0L) {
                binding.seekBar.progress = ((posMs * 1000L) / durMs).toInt().coerceIn(0, 1000)
                binding.seekBar.secondaryProgress = ((bufMs * 1000L) / durMs).toInt().coerceIn(0, 1000)
            }
        }
        val bufferedAheadSec = PlayerUtils.computeBufferedAheadSec(posMs, bufMs)
        if (showHud && !isCurrentStreamOffline && bufferedAheadSec >= 2L) {
            binding.txtBufferedAhead.visibility = View.VISIBLE
            binding.txtBufferedAhead.text = "+${bufferedAheadSec}s đệm"
        } else {
            binding.txtBufferedAhead.visibility = View.GONE
        }

        val posSec = posMs / 1000L
        maybeShowSkipIntroForFiveSeconds(posSec)

        // Update live resolution + network speed badge when HUD is visible
        if (showHud) {
            val nowMs = System.currentTimeMillis()
            val curRx = TrafficStats.getUidRxBytes(Process.myUid())
            if (!isCurrentStreamOffline && curRx > 0L) {
                if (lastRxBytes > 0L && nowMs - lastRxSampleMs >= 900L) {
                    val deltaBytes = (curRx - lastRxBytes).coerceAtLeast(0L)
                    val dtSec = (nowMs - lastRxSampleMs) / 1000.0
                    val bps = if (dtSec > 0) deltaBytes / dtSec else 0.0
                    cachedSpeedLabel = when {
                        bps >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0))
                        bps >= 1024 -> "${(bps / 1024.0).toInt()} KB/s"
                        else -> ""
                    }
                    lastRxBytes = curRx
                    lastRxSampleMs = nowMs
                } else if (lastRxBytes < 0L) {
                    lastRxBytes = curRx
                    lastRxSampleMs = nowMs
                }
            }
            val fmtH = player.videoFormat?.height ?: 0
            val resLabel = when {
                fmtH >= 2000 -> "${fmtH}p 4K"
                fmtH > 0 -> "${fmtH}p"
                else -> movie.quality.ifBlank { "Auto" }
            }
            binding.txtPlayerBadge.text = when {
                isCurrentStreamOffline -> "$resLabel • Offline HLS"
                cachedSpeedLabel.isNotEmpty() -> "$resLabel • $cachedSpeedLabel"
                else -> "Trình phát Tích hợp • $resLabel"
            }
        }

        if (posSec > 5L && durMs > 0L) {
            val epNum = currentEpisodeNum()
            val pct = ((posMs * 100L) / durMs).toInt().coerceIn(0, 100)
            if (lastWatchedMarkedEp != epNum && (posSec >= 20L || pct >= 75)) {
                lastWatchedMarkedEp = epNum
                PrefsStore.markEpisodeWatched(movie.id, epNum)
            }
            val now = System.currentTimeMillis()
            if (now - lastProgressPersistMs >= 8_000L) {
                persistWatchProgressNow()
            }
        }
    }

    private fun updateQualityLevelsFromTracks(tracks: Tracks) {
        qualityOptions.clear()
        qualityOptions.add(QualityTrackOption(-1, "Tự động (ABR)", 0, 0))
        var idx = 0
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    if (fmt.height > 0) {
                        val label = "${fmt.height}p"
                        if (qualityOptions.none { it.height == fmt.height }) {
                            qualityOptions.add(QualityTrackOption(idx++, label, fmt.height, fmt.bitrate))
                        }
                    }
                }
            }
        }
        val activeLabel = qualityOptions.firstOrNull { it.index == activeQualityIdx }
            ?.label?.replace("Tự động (ABR)", "Auto")
            ?: if (settings.defaultQuality.equals("Auto", ignoreCase = true)) "Auto" else movie.quality.ifBlank { "Auto" }
        binding.btnQuickQuality.text = activeLabel.substringBefore(" ")
        binding.txtPlayerBadge.text = "Trình phát Tích hợp • $activeLabel"
    }

    private fun selectQuality(opt: QualityTrackOption) {
        val ts = trackSelector ?: return
        activeQualityIdx = opt.index
        val builder = ts.buildUponParameters()
        if (opt.index < 0 || opt.height <= 0) {
            if (DeviceProfileResolver.isLowRamDevice(this)) {
                builder.setMaxVideoSize(1920, 1080).setMinVideoSize(0, 0)
            } else {
                builder.clearVideoSizeConstraints()
            }
        } else {
            builder.setMaxVideoSize(Int.MAX_VALUE, opt.height)
            builder.setMinVideoSize(0, opt.height)
        }
        ts.setParameters(builder)
        val cleanLabel = opt.label.replace("Tự động (ABR)", "Auto")
        binding.btnQuickQuality.text = cleanLabel.substringBefore(" ")
        binding.txtPlayerBadge.text = "Trình phát Tích hợp • $cleanLabel"
        closeDrawer()
        showToast("Chất lượng: ${opt.label}")
    }

    private fun toggleAudioQuick() {
        val visibleIndices = MovieRepository.visibleServerIndices(serverGroups)
        if (visibleIndices.size <= 1) {
            val isDub = MovieRepository.isDubbedServerName(currentServer()?.serverName)
            showToast(if (isDub) "Đang phát: Thuyết Minh" else "Đang phát: Vietsub")
            return
        }
        val isDub = MovieRepository.isDubbedServerName(currentServer()?.serverName)
        val target = visibleIndices.firstOrNull { idx ->
            val name = serverGroups[idx].serverName
            if (isDub) !MovieRepository.isDubbedServerName(name)
            else MovieRepository.isDubbedServerName(name)
        }
        val nextIdx = target ?: run {
            val pos = visibleIndices.indexOf(activeServerIdx)
            if (pos >= 0) visibleIndices[(pos + 1) % visibleIndices.size] else visibleIndices.first()
        }
        switchToServer(nextIdx)
        showToast("Đã chuyển sang: ${serverGroups[nextIdx].serverName}")
    }

    private fun applyAspectRatioToPlayerView() {
        binding.playerView.resizeMode = when (aspectMode) {
            AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectMode.COVER -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
        }
        binding.txtActionAspect.text = aspectMode.label
    }

    private fun updateHudFocusRouting() {
        binding.btnBack.nextFocusDownId = R.id.btnPlayPause
        binding.btnQuickQuality.nextFocusDownId = R.id.btnForward
        binding.btnQuickSpeed.nextFocusDownId = R.id.btnForward
        binding.btnPrevEp.nextFocusDownId = R.id.btnForward
        binding.btnActionEpisodes.nextFocusDownId = R.id.btnForward
        binding.btnActionNextEp.nextFocusDownId = R.id.btnForward

        val row = binding.bottomActionsRow
        for (i in 0 until row.childCount) {
            row.getChildAt(i)?.nextFocusUpId = R.id.seekBar
        }
    }

    private fun drawerItemBackground(
        bgColor: Int,
        normalStrokeColor: Int? = null,
        radiusDp: Float = 12f
    ): android.graphics.drawable.StateListDrawable {
        val rPx = ThemeManager.dp(this, radiusDp).toFloat()
        val normal = GradientDrawable().apply {
            cornerRadius = rPx
            setColor(bgColor)
            if (normalStrokeColor != null) setStroke(2, normalStrokeColor)
        }
        val focused = GradientDrawable().apply {
            cornerRadius = rPx
            setColor(bgColor)
            setStroke(ThemeManager.dp(this@PlayerActivity, 2.5f).coerceAtLeast(3), Color.parseColor("#2CE59B"))
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    // =====================================================
    // Right Overlay Drawer (4 modes)
    // =====================================================
    private fun toggleDrawer(type: PlayerDrawerType) {
        if (activeDrawer == type) {
            closeDrawer()
        } else {
            if (activeDrawer == PlayerDrawerType.NONE) {
                val cur = currentFocus
                if (cur != null && cur.parent != binding.playerDrawer) {
                    lastFocusedBeforeDrawer = cur
                }
            }
            activeDrawer = type
            renderDrawer()
        }
    }

    private fun closeDrawer() {
        activeDrawer = PlayerDrawerType.NONE
        binding.playerDrawer.visibility = View.GONE
        binding.hudContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (isTvProfile) {
            val prev = lastFocusedBeforeDrawer
            lastFocusedBeforeDrawer = null
            binding.hudContainer.post {
                if (prev != null && prev.isShown && prev.isFocusable) {
                    prev.requestFocus()
                } else {
                    binding.btnPlayPause.requestFocus()
                }
            }
        }
    }

    private fun renderDrawer() {
        binding.playerDrawer.visibility = View.VISIBLE
        binding.hudContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.drawerContentContainer.removeAllViews()
        binding.drawerRangeContainer.removeAllViews()
        binding.drawerRangeScroll.visibility = View.GONE

        val focusTarget: View? = when (activeDrawer) {
            PlayerDrawerType.EPISODES -> renderEpisodesDrawer()
            PlayerDrawerType.SOURCES -> renderSourcesDrawer()
            PlayerDrawerType.QUALITY -> renderQualityDrawer()
            PlayerDrawerType.SPEED -> renderSpeedDrawer()
            PlayerDrawerType.PLAYBACK_SETTINGS -> renderPlaybackSettingsDrawer()
            PlayerDrawerType.NONE -> null
        }

        binding.playerDrawer.post {
            (focusTarget ?: binding.btnCloseDrawer).requestFocus()
        }
    }

    private fun renderEpisodesDrawer(): View? {
        val eps = episodesList()
        val total = totalEpisodesCount()
        binding.txtDrawerTitle.text = "Danh sách tập ($total tập)"

        // Hiển thị số tập mới nhất trước (đồng nhất với Chi tiết phim)
        val displayIndices = PlayerUtils.descendingEpisodeDisplayIndices(eps, total)
        val ranges = PlayerUtils.buildDescendingEpisodeRanges(displayIndices, eps, PlayerUtils.EP_PAGE_SIZE)
        if (epRangeIdx !in ranges.indices) {
            val curDisplayPos = displayIndices.indexOf(safeEpIdx()).coerceAtLeast(0)
            epRangeIdx = (curDisplayPos / PlayerUtils.EP_PAGE_SIZE).coerceIn(0, (ranges.size - 1).coerceAtLeast(0))
        }

        if (ranges.size > 1) {
            binding.drawerRangeScroll.visibility = View.VISIBLE
            ranges.forEachIndexed { idx, slice ->
                val active = idx == epRangeIdx
                val btn = Button(this).apply {
                    text = slice.label
                    isAllCaps = false
                    textSize = 11f
                    minHeight = 0
                    minimumHeight = 0
                    setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                    background = drawerItemBackground(
                        if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26"),
                        radiusDp = 10f
                    )
                    isFocusable = true
                    setOnClickListener {
                        epRangeIdx = idx
                        renderDrawer()
                    }
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    (32 * resources.displayMetrics.density).toInt()
                ).apply { marginEnd = (6 * resources.displayMetrics.density).toInt() }
                binding.drawerRangeContainer.addView(btn, lp)
            }
        }

        val slice = ranges.getOrNull(epRangeIdx)
        val rStart = slice?.startPos ?: 0
        val rEnd = slice?.endExclusive ?: displayIndices.size
        val cols = if (isTvProfile || deviceProfile == ResolvedDeviceProfile.TABLET) 5 else 4
        val grid = GridLayout(this).apply { columnCount = cols }
        val offlineMap = PrefsStore.getOfflineEpisodes()
        val watchedEps = PrefsStore.getWatchedEpisodes(movie.id)
        var focusTarget: View? = null
        var firstBtn: View? = null

        for (pos in rStart until rEnd) {
            val realIdx = displayIndices[pos]
            val ep = eps.getOrNull(realIdx)
            val epNum = PlayerUtils.extractEpisodeNumber(ep, realIdx)
            val isCurrent = realIdx == safeEpIdx()
            val isWatched = watchedEps.contains(epNum)
            val isOffline = offlineMap.containsKey(HlsOfflineManager.buildEpisodeKey(movie.id, epNum))
            val baseName = ep?.name?.ifBlank { "Tập ${realIdx + 1}" } ?: "Tập ${realIdx + 1}"
            val btn = Button(this).apply {
                text = if (isWatched && !isCurrent) "$baseName ✓" else baseName
                isAllCaps = false
                textSize = if (isTvProfile) 12f else 11f
                setTextColor(
                    when {
                        isCurrent -> Color.parseColor("#090D16")
                        isOffline -> Color.parseColor("#6EE7B7")
                        isWatched -> Color.parseColor("#94A3B8")
                        else -> Color.WHITE
                    }
                )
                background = drawerItemBackground(
                    bgColor = when {
                        isCurrent -> Color.parseColor("#2CE59B")
                        isOffline -> Color.parseColor("#14532D")
                        else -> Color.parseColor("#151B26")
                    },
                    normalStrokeColor = if (isOffline) Color.parseColor("#2CE59B") else null,
                    radiusDp = 12f
                )
                isFocusable = true
                setOnClickListener {
                    closeDrawer()
                    switchToEpisode(realIdx)
                }
            }
            if (firstBtn == null) firstBtn = btn
            if (isCurrent) focusTarget = btn
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                val m = (4 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            }
            grid.addView(btn, lp)
        }
        binding.drawerContentContainer.addView(grid)
        return focusTarget ?: firstBtn
    }

    private fun renderSourcesDrawer(): View? {
        binding.txtDrawerTitle.text = "Nguồn & Máy Chủ Phát"
        val epTabBtn = Button(this).apply {
            text = "☰ Mở Danh Sách Tập (${totalEpisodesCount()} tập)"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.parseColor("#090D16"))
            background = drawerItemBackground(Color.parseColor("#2CE59B"))
            isFocusable = true
            setOnClickListener {
                activeDrawer = PlayerDrawerType.EPISODES
                renderDrawer()
            }
        }
        val epLp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
        binding.drawerContentContainer.addView(epTabBtn, epLp)

        var focusTarget: View? = null
        val visibleIndices = MovieRepository.visibleServerIndices(serverGroups)
        if (visibleIndices.isEmpty()) {
            val emptyInfo = TextView(this).apply {
                text = "Không có máy chủ HLS/MP4 trực tiếp cho phim này."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12.5f
            }
            binding.drawerContentContainer.addView(emptyInfo)
            return epTabBtn
        }
        for (idx in visibleIndices) {
            val srv = serverGroups[idx]
            val isActive = idx == activeServerIdx
            val btn = Button(this).apply {
                text = "${srv.serverName} (${srv.episodes.size} tập)"
                isAllCaps = false
                textSize = 12f
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(
                    ThemeManager.dp(context, 12f),
                    ThemeManager.dp(context, 10f),
                    ThemeManager.dp(context, 12f),
                    ThemeManager.dp(context, 10f)
                )
                setTextColor(if (isActive) Color.parseColor("#2CE59B") else Color.WHITE)
                background = drawerItemBackground(
                    bgColor = if (isActive) Color.parseColor("#262CE59B") else Color.parseColor("#151B26"),
                    normalStrokeColor = if (isActive) Color.parseColor("#802CE59B") else null
                )
                isFocusable = true
                setOnClickListener {
                    failedServers.clear()
                    failedStreamUrls.clear()
                    retriedCurrentStreamOnce = false
                    closeDrawer()
                    switchToServer(idx)
                    showToast("Đã chuyển máy chủ: ${srv.serverName}")
                }
            }
            if (isActive) focusTarget = btn
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            binding.drawerContentContainer.addView(btn, lp)
        }
        return focusTarget ?: epTabBtn
    }

    private fun renderPlaybackSettingsDrawer(): View {
        binding.txtDrawerTitle.text = "Chỉnh Phụ Đề & Phát"
        val dp = resources.displayMetrics.density

        val subHeading = TextView(this).apply {
            text = "Phụ đề rời & Hiển thị"
            setTextColor(Color.parseColor("#2CE59B"))
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        binding.drawerContentContainer.addView(subHeading)

        val extSubBtn = Button(this).apply {
            text = if (externalSubtitleUri != null) {
                "✓ Đã nạp phụ đề rời (Đổi file .srt/.vtt)"
            } else {
                "📂 Nạp phụ đề rời (.srt / .vtt / .ass)"
            }
            isAllCaps = false
            setTextColor(if (externalSubtitleUri != null) Color.parseColor("#090D16") else Color.WHITE)
            background = drawerItemBackground(
                if (externalSubtitleUri != null) Color.parseColor("#2CE59B") else Color.parseColor("#151B26")
            )
            isFocusable = true
            setOnClickListener {
                openSubtitleLauncher.launch(arrayOf("*/*"))
            }
        }
        binding.drawerContentContainer.addView(
            extSubBtn,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() }
        )

        if (externalSubtitleUri != null) {
            val clearSubBtn = Button(this).apply {
                text = "✕ Gỡ phụ đề rời hiện tại"
                isAllCaps = false
                setTextColor(Color.parseColor("#FECDD3"))
                background = drawerItemBackground(Color.parseColor("#4C1D24"))
                isFocusable = true
                setOnClickListener {
                    baseExternalSubtitleUri = null
                    externalSubtitleUri = null
                    externalSubtitleMime = null
                    externalSubtitleRawText = null
                    subtitleOffsetMs = 0L
                    val pos = exoPlayer?.currentPosition ?: -1L
                    if (currentActiveM3u8.isNotBlank()) {
                        playHlsWithExoPlayer(currentActiveM3u8, isOffline = isCurrentStreamOffline, seekRestoreMs = pos)
                    }
                    closeDrawer()
                    showToast("Đã gỡ phụ đề rời")
                }
            }
            binding.drawerContentContainer.addView(
                clearSubBtn,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * dp).toInt() }
            )
        }

        val subScaleRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        val subScales = listOf(0.042f to "Nhỏ", 0.055f to "Vừa", 0.072f to "Lớn")
        for ((idx, pair) in subScales.withIndex()) {
            val (scaleVal, lbl) = pair
            val active = abs(settings.subtitleScale - scaleVal) < 0.006f
            val sBtn = Button(this).apply {
                text = "Chữ $lbl"
                isAllCaps = false
                textSize = 11.5f
                setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                background = drawerItemBackground(if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26"))
                isFocusable = true
                setOnClickListener {
                    settings = PrefsStore.saveSettings(settings.copy(subtitleScale = scaleVal))
                    applySubtitleStyle()
                    renderDrawer()
                    showToast("Cỡ phụ đề: $lbl")
                }
            }
            subScaleRow.addView(
                sBtn,
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = (6 * dp).toInt()
                }
            )
        }
        binding.drawerContentContainer.addView(subScaleRow)

        val bottomPct = kotlin.math.round(settings.subtitleBottomPct * 100f).toInt()
        val posHeading = TextView(this).apply {
            text = "Vị trí phụ đề từ đáy (${bottomPct}%) • Chỉnh trực tiếp"
            setTextColor(Color.parseColor("#2CE59B"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        binding.drawerContentContainer.addView(posHeading)
        val posRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val posActions = listOf(-0.02f to "Xuống -2%", 0.0f to "Chuẩn 8%", 0.02f to "Lên +2%")
        for ((idx, pair) in posActions.withIndex()) {
            val (deltaPct, lbl) = pair
            val active = deltaPct == 0.0f && abs(settings.subtitleBottomPct - 0.08f) < 0.005f
            val pBtn = Button(this).apply {
                text = lbl
                isAllCaps = false
                textSize = 11.5f
                setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                background = drawerItemBackground(if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26"))
                isFocusable = true
                setOnClickListener {
                    val nextPct = if (deltaPct == 0.0f) 0.08f else (settings.subtitleBottomPct + deltaPct).coerceIn(0.04f, 0.30f)
                    settings = PrefsStore.saveSettings(settings.copy(subtitleBottomPct = nextPct))
                    applySubtitleStyle()
                    renderDrawer()
                }
            }
            posRow.addView(
                pBtn,
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = (6 * dp).toInt()
                }
            )
        }
        binding.drawerContentContainer.addView(posRow)

        val styleHeading = TextView(this).apply {
            text = "Màu chữ & Độ mờ nền phụ đề"
            setTextColor(Color.parseColor("#2CE59B"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        binding.drawerContentContainer.addView(styleHeading)
        val styleRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val yellowColor = Color.parseColor("#FACC15")
        val styleOptions = listOf(
            Triple("Trắng", settings.subtitleTextColor == Color.WHITE) { settings.copy(subtitleTextColor = Color.WHITE) },
            Triple("Vàng", settings.subtitleTextColor == yellowColor) { settings.copy(subtitleTextColor = yellowColor) },
            Triple("Nền trong", settings.subtitleBgAlpha == 0) { settings.copy(subtitleBgAlpha = 0) },
            Triple("Nền đậm", settings.subtitleBgAlpha >= 175) { settings.copy(subtitleBgAlpha = if (settings.subtitleBgAlpha == 175) 235 else 175) }
        )
        for ((idx, opt) in styleOptions.withIndex()) {
            val (lbl, active, updater) = opt
            val stBtn = Button(this).apply {
                text = lbl
                isAllCaps = false
                textSize = 11f
                setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                background = drawerItemBackground(if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26"))
                isFocusable = true
                setOnClickListener {
                    settings = PrefsStore.saveSettings(updater())
                    applySubtitleStyle()
                    renderDrawer()
                }
            }
            styleRow.addView(
                stBtn,
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = (5 * dp).toInt()
                }
            )
        }
        binding.drawerContentContainer.addView(styleRow)

        if (externalSubtitleRawText != null) {
            val offsetText = if (subtitleOffsetMs == 0L) "0.0s" else String.format(Locale.US, "%+.1fs", subtitleOffsetMs / 1000.0)
            val syncHeading = TextView(this).apply {
                text = "Độ trễ phụ đề rời ($offsetText)"
                setTextColor(Color.parseColor("#2CE59B"))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
            }
            binding.drawerContentContainer.addView(syncHeading)
            val syncRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            val syncActions = listOf(-500L to "Sớm -0.5s", 0L to "Đặt lại 0s", 500L to "Trễ +0.5s")
            for ((idx, pair) in syncActions.withIndex()) {
                val (delta, lbl) = pair
                val active = delta == 0L && subtitleOffsetMs == 0L
                val sBtn = Button(this).apply {
                    text = lbl
                    isAllCaps = false
                    textSize = 11.5f
                    setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                    background = drawerItemBackground(if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26"))
                    isFocusable = true
                    setOnClickListener { applyExternalSubtitleOffset(delta) }
                }
                syncRow.addView(
                    sBtn,
                    android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = (6 * dp).toInt()
                    }
                )
            }
            binding.drawerContentContainer.addView(syncRow)
        }

        val drcBtn = Button(this).apply {
            val enabled = settings.nightModeDrc
            text = if (enabled) "🌙 Chế độ Ban Đêm (DRC âm thanh): ĐANG BẬT" else "🌙 Chế độ Ban Đêm (DRC âm thanh): Tắt"
            isAllCaps = false
            textSize = 12f
            setTextColor(if (enabled) Color.parseColor("#090D16") else Color.WHITE)
            background = drawerItemBackground(if (enabled) Color.parseColor("#2CE59B") else Color.parseColor("#151B26"))
            isFocusable = true
            setOnClickListener {
                val next = !settings.nightModeDrc
                settings = PrefsStore.saveSettings(settings.copy(nightModeDrc = next))
                exoPlayer?.audioSessionId?.let { audioDrc.apply(it, next) }
                renderDrawer()
                showToast(if (next) "Đã bật Chế độ Ban Đêm (Cân bằng âm lượng)" else "Đã tắt Chế độ Ban Đêm")
            }
        }
        binding.drawerContentContainer.addView(
            drcBtn,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
        )

        val skipHeading = TextView(this).apply {
            text = "Mốc Bỏ qua Mở đầu cho phim này (${skipIntroSec}s)"
            setTextColor(Color.parseColor("#2CE59B"))
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
        binding.drawerContentContainer.addView(skipHeading)
        val skipRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val skipPresets = listOf(60, 85, 105, 120)
        for ((idx, sec) in skipPresets.withIndex()) {
            val active = skipIntroSec == sec
            val sBtn = Button(this).apply {
                text = "${sec}s"
                isAllCaps = false
                textSize = 11.5f
                setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                background = drawerItemBackground(if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26"))
                isFocusable = true
                setOnClickListener {
                    skipIntroSec = sec
                    PrefsStore.setMovieSkipIntroSec(movie.id, sec)
                    binding.txtSkipIntroSec.text = "+${sec}s"
                    renderDrawer()
                    showToast("Đã đặt Skip Intro cho bộ này: +${sec}s")
                }
            }
            skipRow.addView(
                sBtn,
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = (5 * dp).toInt()
                }
            )
        }
        binding.drawerContentContainer.addView(skipRow)

        val remSleepMin = if (sleepTimerMinutes > 0 && sleepTimerEndAtMs > System.currentTimeMillis()) {
            (((sleepTimerEndAtMs - System.currentTimeMillis()) / 60_000L) + 1L).toInt()
        } else {
            0
        }
        val sleepHeading = TextView(this).apply {
            text = if (remSleepMin > 0) "⏰ Hẹn giờ tắt (Còn ~${remSleepMin} phút)" else "⏰ Hẹn giờ tắt (Đang tắt)"
            setTextColor(Color.parseColor("#2CE59B"))
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
        binding.drawerContentContainer.addView(sleepHeading)
        val sleepRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val sleepOptions = listOf(0 to "Tắt", 15 to "15p", 30 to "30p", 45 to "45p", 60 to "60p")
        for ((idx, pair) in sleepOptions.withIndex()) {
            val (mins, lbl) = pair
            val active = sleepTimerMinutes == mins
            val slBtn = Button(this).apply {
                text = lbl
                isAllCaps = false
                textSize = 11f
                setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                background = drawerItemBackground(if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26"))
                isFocusable = true
                setOnClickListener {
                    applySleepTimer(mins)
                    renderDrawer()
                }
            }
            sleepRow.addView(
                slBtn,
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = (4 * dp).toInt()
                }
            )
        }
        binding.drawerContentContainer.addView(sleepRow)

        if (!isTvProfile) {
            val rotBtn = Button(this).apply {
                text = if (isPortraitLocked) "✓ Đang khóa xoay dọc (Đổi xoay ngang)" else "Khóa màn hình dọc"
                isAllCaps = false
                setTextColor(if (isPortraitLocked) Color.parseColor("#090D16") else Color.WHITE)
                background = drawerItemBackground(
                    if (isPortraitLocked) Color.parseColor("#2CE59B") else Color.parseColor("#151B26")
                )
                isFocusable = true
                setOnClickListener {
                    isPortraitLocked = !isPortraitLocked
                    requestedOrientation = if (isPortraitLocked) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                    closeDrawer()
                }
            }
            binding.drawerContentContainer.addView(
                rotBtn,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * dp).toInt() }
            )
        }

        val qualHeading = TextView(this).apply {
            text = "Chất lượng Video"
            setTextColor(Color.parseColor("#2CE59B"))
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
        }
        binding.drawerContentContainer.addView(qualHeading)

        if (qualityOptions.isEmpty()) {
            binding.drawerContentContainer.addView(
                TextView(this).apply {
                    text = "Luồng hiện tại dùng 1 mức phân giải cố định."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12f
                }
            )
        } else {
            qualityOptions.forEach { opt ->
                val active = opt.index == activeQualityIdx
                val btn = Button(this).apply {
                    text = if (active) "${opt.label} ✓" else opt.label
                    isAllCaps = false
                    setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                    background = drawerItemBackground(
                        if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26")
                    )
                    isFocusable = true
                    setOnClickListener { selectQuality(opt) }
                }
                binding.drawerContentContainer.addView(
                    btn,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (6 * dp).toInt() }
                )
            }
        }

        val spdHeading = TextView(this).apply {
            text = "Tốc độ phát (${playbackSpeed}x)"
            setTextColor(Color.parseColor("#2CE59B"))
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
        }
        binding.drawerContentContainer.addView(spdHeading)

        PlayerUtils.SPEED_OPTIONS.forEach { spd ->
            val active = abs(playbackSpeed - spd) < 0.01f
            val btn = Button(this).apply {
                text = if (spd == 1.0f) "${spd}x (Chuẩn)" else "${spd}x"
                isAllCaps = false
                setTextColor(if (active) Color.parseColor("#090D16") else Color.WHITE)
                background = drawerItemBackground(
                    if (active) Color.parseColor("#2CE59B") else Color.parseColor("#151B26")
                )
                isFocusable = true
                setOnClickListener {
                    playbackSpeed = spd
                    exoPlayer?.playbackParameters = PlaybackParameters(spd)
                    binding.btnQuickSpeed.text = PlayerUtils.formatSpeedChip(spd)
                    closeDrawer()
                    showToast("Tốc độ phát: ${spd}x")
                }
            }
            binding.drawerContentContainer.addView(
                btn,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * dp).toInt() }
            )
        }
        return extSubBtn
    }

    private fun renderQualityDrawer(): View? {
        binding.txtDrawerTitle.text = "Chất Lượng Luồng Phát"
        if (qualityOptions.isEmpty()) {
            val info = TextView(this).apply {
                text = "Luồng phát hiện tại dùng một mức chất lượng cố định (Adaptive Auto)."
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 13f
            }
            binding.drawerContentContainer.addView(info)
            return null
        }
        var focusTarget: View? = null
        qualityOptions.forEach { opt ->
            val active = opt.index == activeQualityIdx
            val btn = Button(this).apply {
                text = if (active) "${opt.label} ✓" else opt.label
                isAllCaps = false
                setTextColor(if (active) Color.parseColor("#0F172A") else Color.WHITE)
                background = drawerItemBackground(
                    if (active) Color.parseColor("#2CE59B") else Color.parseColor("#1E293B")
                )
                isFocusable = true
                setOnClickListener { selectQuality(opt) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            binding.drawerContentContainer.addView(btn, lp)
            if (active) focusTarget = btn
        }
        return focusTarget
    }

    private fun renderSpeedDrawer(): View? {
        binding.txtDrawerTitle.text = "Tốc Độ Phát"
        var focusTarget: View? = null
        PlayerUtils.SPEED_OPTIONS.forEach { spd ->
            val active = abs(playbackSpeed - spd) < 0.01f
            val btn = Button(this).apply {
                text = if (spd == 1.0f) "${spd}x (Chuẩn)" else "${spd}x"
                isAllCaps = false
                setTextColor(if (active) Color.parseColor("#0F172A") else Color.WHITE)
                background = drawerItemBackground(
                    if (active) Color.parseColor("#2CE59B") else Color.parseColor("#1E293B")
                )
                isFocusable = true
                setOnClickListener {
                    playbackSpeed = spd
                    exoPlayer?.playbackParameters = PlaybackParameters(spd)
                    binding.btnQuickSpeed.text = PlayerUtils.formatSpeedChip(spd)
                    closeDrawer()
                    showToast("Tốc độ phát: ${spd}x")
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            binding.drawerContentContainer.addView(btn, lp)
            if (active) focusTarget = btn
        }
        return focusTarget
    }

    // =====================================================
    // TV Remote D-Pad & Keyboard Shortcuts
    // =====================================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (activeDrawer != PlayerDrawerType.NONE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                closeDrawer()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            when {
                binding.autoNextOverlay.visibility == View.VISIBLE -> cancelAutoNextCountdown()
                isLocked -> {
                    isLocked = false
                    binding.btnUnlockScreen.visibility = View.GONE
                    binding.hudContainer.visibility = View.VISIBLE
                    wakeHud()
                    if (isTvProfile) binding.btnPlayPause.requestFocus()
                }
                else -> finish()
            }
            return true
        }

        if (isLocked) return true

        val wasHudVisible = showHud
        wakeHud()

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_J -> {
                seekByStep(-seekStep)
                return true
            }
            KeyEvent.KEYCODE_L -> {
                seekByStep(seekStep)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val focused = currentFocus
                if (!wasHudVisible || focused == null || focused == binding.seekBar) {
                    seekByStep(-seekStep)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val focused = currentFocus
                if (!wasHudVisible || focused == null || focused == binding.seekBar) {
                    seekByStep(seekStep)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!wasHudVisible || currentFocus == null) {
                    if (isTvProfile) binding.btnPlayPause.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!wasHudVisible || currentFocus == null) {
                    togglePlayPause()
                    if (isTvProfile) binding.btnPlayPause.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_S -> {
                performSkipIntro()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_N -> {
                if (currentEpIdx < totalEpisodesCount() - 1) switchToEpisode(currentEpIdx + 1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_P -> {
                if (safeEpIdx() > 0) switchToEpisode(safeEpIdx() - 1)
                return true
            }
            KeyEvent.KEYCODE_M -> {
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val next = if (cur > 0) 0 else max / 2
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                showVerticalGestureHud(false, ((next.toFloat() / max) * 100).toInt())
                return true
            }
            KeyEvent.KEYCODE_F -> {
                binding.btnFullscreen.performClick()
                return true
            }
        }

        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            val digit = keyCode - KeyEvent.KEYCODE_0
            val dur = exoPlayer?.duration ?: 0L
            if (dur > 0L) {
                exoPlayer?.seekTo((dur * (digit * 0.1)).toLong())
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    // =====================================================
    // Auto-Next 5s, HUD Timers & PiP
    // =====================================================
    private fun startAutoNextCountdown() {
        autoNextJob?.cancel()
        val nextName = episodesList().getOrNull(currentEpIdx + 1)?.name ?: "Tập ${currentEpIdx + 2}"
        binding.hudContainer.visibility = View.GONE
        binding.autoNextOverlay.visibility = View.VISIBLE
        if (isTvProfile) binding.btnPlayNextNow.post { binding.btnPlayNextNow.requestFocus() }

        autoNextJob = lifecycleScope.launch {
            for (sec in 5 downTo 1) {
                binding.txtAutoNextCountdown.text = "$nextName sẽ phát sau ${sec}s"
                delay(1000L)
            }
            binding.autoNextOverlay.visibility = View.GONE
            if (currentEpIdx < totalEpisodesCount() - 1) {
                switchToEpisode(currentEpIdx + 1)
            }
        }
    }

    private fun cancelAutoNextCountdown() {
        autoNextJob?.cancel()
        autoNextJob = null
        binding.autoNextOverlay.visibility = View.GONE
        if (!isLocked) wakeHud()
    }

    private fun wakeHud() {
        if (isLocked) return
        showHud = true
        binding.hudContainer.visibility = View.VISIBLE
        hudHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (exoPlayer?.isPlaying == true && activeDrawer == PlayerDrawerType.NONE) {
                hideHud()
            }
        }
        hudHideRunnable = r
        mainHandler.postDelayed(r, if (isTvProfile) 4500L else 3500L)
    }

    private fun hideHud() {
        showHud = false
        binding.hudContainer.visibility = View.GONE
    }

    private val unlockHideHandler = Handler(Looper.getMainLooper())
    private val unlockHideRunnable = Runnable {
        if (isLocked) {
            binding.btnUnlockScreen.animate().cancel()
            val reduced = DeviceProfileResolver.shouldUseReducedMotion(this, deviceProfile, settings.reducedMotion)
            val dur = if (reduced) 0L else 300L
            if (dur == 0L) {
                binding.btnUnlockScreen.visibility = View.GONE
            } else {
                binding.btnUnlockScreen.animate()
                    .alpha(0f)
                    .setDuration(dur)
                    .withEndAction {
                        if (isLocked) binding.btnUnlockScreen.visibility = View.GONE
                    }
                    .start()
            }
        }
    }

    private fun resetUnlockHideTimer() {
        unlockHideHandler.removeCallbacks(unlockHideRunnable)
        binding.btnUnlockScreen.animate().cancel()
        binding.btnUnlockScreen.alpha = 1f
        binding.btnUnlockScreen.visibility = View.VISIBLE
        unlockHideHandler.postDelayed(unlockHideRunnable, 3000L)
    }

    private fun cancelUnlockHideTimer() {
        unlockHideHandler.removeCallbacks(unlockHideRunnable)
    }

    private fun applySleepTimer(minutes: Int) {
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepTimerRunnable = null
        sleepTimerMinutes = minutes
        if (minutes <= 0) {
            sleepTimerEndAtMs = 0L
            showToast("Đã tắt hẹn giờ tắt trình phát")
            return
        }
        val delayMs = minutes * 60_000L
        sleepTimerEndAtMs = System.currentTimeMillis() + delayMs
        val r = Runnable {
            sleepTimerMinutes = 0
            sleepTimerEndAtMs = 0L
            exoPlayer?.pause()
            persistWatchProgressNow()
            showToast("Đã hết giờ hẹn — tự tạm dừng phim")
        }
        sleepTimerRunnable = r
        mainHandler.postDelayed(r, delayMs)
        showToast("Sẽ tự tạm dừng phát sau $minutes phút")
    }

    private fun applyBrightness(value: Float) {
        brightness = value.coerceIn(0.15f, 1.0f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
        binding.brightnessDimmer.alpha = ((1f - brightness) * 0.82f).coerceIn(0f, 0.72f)
    }

    private fun showVerticalGestureHud(isBrightness: Boolean, pct: Int) {
        val lp = binding.verticalGestureHud.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (isBrightness) {
            android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        } else {
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        binding.verticalGestureHud.layoutParams = lp
        binding.verticalGestureIcon.text = when {
            isBrightness -> "☀"
            pct == 0 -> "🔇"
            pct < 50 -> "🔉"
            else -> "🔊"
        }
        val clampedPct = pct.coerceIn(0, 100)
        val totalPx = (96f * resources.displayMetrics.density).toInt()
        binding.verticalGestureFill.layoutParams = binding.verticalGestureFill.layoutParams.apply {
            height = ((clampedPct / 100f) * totalPx).toInt().coerceAtLeast(0)
        }
        binding.verticalGestureFill.setBackgroundColor(
            Color.parseColor(if (isBrightness) "#FBBF24" else "#06B6D4")
        )
        binding.verticalGesturePct.text = "$clampedPct%"
        binding.verticalGestureLabel.text = if (isBrightness) "Độ sáng" else "Âm lượng"
        binding.verticalGestureHud.visibility = View.VISIBLE
        scheduleGestureOverlayHide()
    }

    private fun showDoubleTapRipple(left: Boolean) {
        val target = if (left) binding.rippleLeft else binding.rippleRight
        target.text = if (left) "⏪ -${seekStep}s" else "+${seekStep}s ⏩"
        target.visibility = View.VISIBLE
        mainHandler.postDelayed({ target.visibility = View.GONE }, 550L)
    }

    private fun scheduleGestureOverlayHide() {
        gestureFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            binding.verticalGestureHud.visibility = View.GONE
            binding.seekPreviewPill.visibility = View.GONE
        }
        gestureFadeRunnable = r
        mainHandler.postDelayed(r, 850L)
    }

    private fun loadStoryboardVtt(vttUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val vttText = runCatching {
                com.hoathinh3d.donghua.core.HttpClientProvider.getText(vttUrl, timeoutMs = 5000L)
            }.getOrNull().orEmpty()
            val cues = StoryboardParser.parseVtt(vttText, vttUrl)
            if (cues.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    storyboardCues = cues
                }
            }
        }
    }

    private fun showSeekThumbPreview(targetMs: Long, deltaMs: Long, autoHide: Boolean) {
        seekThumbHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val card = binding.seekThumbCard
        binding.txtSeekThumbTime.text = PlayerUtils.formatPlayerTimeMs(targetMs)
        binding.txtSeekThumbDelta.text = PlayerUtils.formatSeekDelta(deltaMs)
        binding.txtSeekThumbDelta.setTextColor(
            when {
                deltaMs > 0L -> Color.parseColor("#2CE59B")
                deltaMs < 0L -> Color.parseColor("#F87171")
                else -> Color.parseColor("#CBD5E1")
            }
        )
        val hasCue = bindStoryboardFrameTo(binding.imgSeekThumb, targetMs, showFallbackPoster = true)
        binding.txtSeekThumbMode.text = if (hasCue) "STORYBOARD" else "TẬP ${currentEpisodeNum()}"
        if (card.visibility != View.VISIBLE) {
            card.visibility = View.VISIBLE
        }
        card.post {
            val sb = binding.seekBar
            val barW = sb.width
            val cardW = card.width.takeIf { it > 0 } ?: (164f * resources.displayMetrics.density).toInt()
            val parentW = binding.bottomBar.width - binding.bottomBar.paddingLeft - binding.bottomBar.paddingRight
            val dur = exoPlayer?.duration?.takeIf { it > 0L } ?: 1L
            val ratio = (targetMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            val thumbCenterX = sb.left + (barW * ratio)
            val maxTx = (parentW - cardW).coerceAtLeast(0).toFloat()
            card.translationX = (thumbCenterX - (cardW / 2f)).coerceIn(0f, maxTx)
        }
        if (autoHide) {
            val r = Runnable { hideSeekThumbPreview() }
            seekThumbHideRunnable = r
            mainHandler.postDelayed(r, 900L)
        }
    }

    private fun hideSeekThumbPreview() {
        seekThumbHideRunnable?.let { mainHandler.removeCallbacks(it) }
        binding.seekThumbCard.visibility = View.GONE
    }

    private fun bindStoryboardFrameTo(
        imageView: android.widget.ImageView,
        targetMs: Long,
        showFallbackPoster: Boolean
    ): Boolean {
        val cue = StoryboardParser.findCueAt(storyboardCues, targetMs)
        if (cue != null) {
            imageView.visibility = View.VISIBLE
            val sheet = storyboardSprites.get(cue.imageUrl)
            if (sheet != null) {
                if (cue.hasCrop && sheet.width > 0 && sheet.height > 0) {
                    val cx = cue.x.coerceIn(0, (sheet.width - 1).coerceAtLeast(0))
                    val cy = cue.y.coerceIn(0, (sheet.height - 1).coerceAtLeast(0))
                    val cw = cue.w.coerceIn(1, (sheet.width - cx).coerceAtLeast(1))
                    val ch = cue.h.coerceIn(1, (sheet.height - cy).coerceAtLeast(1))
                    runCatching {
                        imageView.setImageBitmap(android.graphics.Bitmap.createBitmap(sheet, cx, cy, cw, ch))
                    }
                } else {
                    imageView.setImageBitmap(sheet)
                }
                return true
            }
            if (storyboardRequestedUrls.add(cue.imageUrl)) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder()
                        .url(cue.imageUrl)
                        .header("User-Agent", com.hoathinh3d.donghua.core.HttpClientProvider.DEFAULT_UA)
                        .build()
                    val bmp = runCatching {
                        com.hoathinh3d.donghua.core.HttpClientProvider.okHttpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            val bytes = resp.body?.bytes() ?: return@use null
                            val opts = android.graphics.BitmapFactory.Options().apply {
                                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                            }
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        }
                    }.getOrNull()
                    if (bmp != null) {
                        storyboardSprites.put(cue.imageUrl, bmp)
                    }
                }
            }
            if (!showFallbackPoster) return true
        } else if (!showFallbackPoster) {
            imageView.visibility = View.GONE
            return false
        }

        val thumbUrl = movie.thumb?.takeIf { it.isNotBlank() } ?: movie.poster
        if (thumbUrl.isNotBlank()) {
            imageView.visibility = View.VISIBLE
            if (lastSeekPosterLoaded != thumbUrl) {
                lastSeekPosterLoaded = thumbUrl
                com.bumptech.glide.Glide.with(this)
                    .load(thumbUrl)
                    .override(160, 90)
                    .centerCrop()
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC)
                    .into(imageView)
            }
        } else {
            imageView.visibility = View.GONE
        }
        return false
    }

    private fun enterPipSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.hudContainer.visibility = View.GONE
            binding.playerDrawer.visibility = View.GONE
        } else {
            binding.hudContainer.visibility = View.VISIBLE
            wakeHud()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (exoPlayer?.isPlaying == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipSafe()
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(progressTicker)
        persistWatchProgressNow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        persistWatchProgressNow()
        cancelUnlockHideTimer()
        sleepTimerRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
        storyboardSprites.evictAll()
        seekCoalescer?.release()
        audioDrc.release()
        loadEpisodeJob?.cancel()
        autoNextJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
