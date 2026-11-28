package com.hoathinh3d.donghua.ui.history

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile
import com.hoathinh3d.donghua.data.model.WatchProgress
import com.hoathinh3d.donghua.databinding.FragmentHistoryBinding
import com.hoathinh3d.donghua.player.PlayerUtils
import com.hoathinh3d.donghua.ui.AppHost
import com.hoathinh3d.donghua.ui.ChromeInsetsListener
import com.hoathinh3d.donghua.ui.RefreshableTab
import com.hoathinh3d.donghua.ui.common.MovieCardAdapter
import com.hoathinh3d.donghua.ui.common.MovieCardUiMeta

class HistoryFragment : Fragment(), RefreshableTab, ChromeInsetsListener {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val host: AppHost? get() = activity as? AppHost

    private lateinit var historyAdapter: MovieCardAdapter
    private var currentSpan = 3
    private var lastRenderSig: String? = null
    private var progressByMovieId: Map<String, WatchProgress> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val h = host ?: return
        historyAdapter = MovieCardAdapter(
            fixedWidthDp = null,
            onSelect = { movie ->
                val prog = progressByMovieId[movie.id]
                if (prog != null) {
                    resumeMovieProgress(h, movie, prog)
                } else {
                    h.openMovieDetail(movie)
                }
            },
            onToggleFavorite = { movie -> h.toggleFavorite(movie.id) },
            onLongSelect = { movie -> showHistoryItemActions(h, movie) }
        )
        currentSpan = DeviceProfileResolver.gridSpanCount(h.getState().resolvedDevice)
        binding.rvHistory.layoutManager = GridLayoutManager(requireContext(), currentSpan)
        binding.rvHistory.adapter = historyAdapter
        if (DeviceProfileResolver.shouldUseReducedMotion(requireContext(), h.getState().resolvedDevice, h.getState().settings.reducedMotion)) {
            binding.rvHistory.itemAnimator = null
        }

        binding.historyScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            host?.onMainContentScroll(scrollY - oldScrollY, scrollY)
        }
        binding.btnClearAllHistory.setOnClickListener { h.clearHistory() }
        refreshUi()
    }

    private fun resumeMovieProgress(h: AppHost, resolved: DonghuaMovie, prog: WatchProgress) {
        val matched = h.resolveMovieForProgress(prog)
        val matchedSrvIdx = matched.serverGroups
            ?.indexOfFirst { it.serverName.equals(prog.server, ignoreCase = true) }
            ?.takeIf { it >= 0 } ?: -1
        h.quickPlayMovie(if (matched.title.isNotBlank()) matched else resolved, prog.episode, matchedSrvIdx, prog.currentTime)
    }

    private fun showHistoryItemActions(h: AppHost, movie: DonghuaMovie) {
        val prog = progressByMovieId[movie.id]
        val isFav = h.getState().favorites.contains(movie.id)
        val options = arrayOf(
            if (prog != null) "▶ Tiếp tục Tập ${prog.episode}" else "▶ Xem ngay",
            "ℹ Chi tiết & Danh sách tập",
            if (isFav) "♡ Bỏ khỏi Yêu thích" else "♥ Thêm vào Yêu thích",
            "🗑 Xóa khỏi Lịch sử"
        )
        AlertDialog.Builder(requireContext())
            .setTitle(movie.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (prog != null) resumeMovieProgress(h, movie, prog) else h.openMovieDetail(movie)
                    1 -> h.openMovieDetail(if (prog != null) h.resolveMovieForProgress(prog) else movie)
                    2 -> h.toggleFavorite(movie.id)
                    3 -> h.removeHistoryItem(movie.id)
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
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
        b.historyScroll.setPadding(hPad, topPx, hPad, bottomPx)
    }

    override fun refreshUi() {
        val b = _binding ?: return
        val h = host ?: return
        val st = h.getState()
        val p = h.getPalette()
        val isTv = st.resolvedDevice == ResolvedDeviceProfile.TV
        val hPad = ThemeManager.dp(
            requireContext(),
            when (st.resolvedDevice) {
                ResolvedDeviceProfile.TV -> 22f
                ResolvedDeviceProfile.TABLET -> 18f
                ResolvedDeviceProfile.MOBILE -> 14f
            }
        )
        val (chromeTop, chromeBot) = h.getChromeInsets()
        b.historyScroll.setPadding(hPad, chromeTop, hPad, chromeBot)

        val desiredSpan = DeviceProfileResolver.gridSpanCount(st.resolvedDevice)
        if (desiredSpan != currentSpan) {
            currentSpan = desiredSpan
            (b.rvHistory.layoutManager as? GridLayoutManager)?.spanCount = desiredSpan
        }

        val sortedProg = st.watchHistory.values.sortedByDescending { it.updatedAt }
        progressByMovieId = sortedProg.associateBy { it.donghuaId }
        val offlineCountByMovie = st.offlineEpisodes.values.groupingBy { it.donghuaId }.eachCount()

        val historyMovies = sortedProg.map { prog ->
            val base = h.resolveMovieForProgress(prog)
            base.copy(
                latestEp = maxOf(base.latestEp, prog.episode),
                totalEp = maxOf(base.totalEp, prog.episode),
                originalTitle = "${PlayerUtils.formatRelativeDayLabel(prog.updatedAt)} • ${PlayerUtils.formatContinueMeta(prog.episode, prog.currentTime, prog.progressPct)}"
            )
        }

        b.historyHeaderPanel.background = ThemeManager.glassPanelDrawable(requireContext(), p, 22f)
        b.txtHistoryTitle.setTextColor(p.textMain)
        b.txtHistorySub.text = "Đã lưu ${historyMovies.size} bộ phim bạn từng thưởng thức"
        b.txtHistorySub.setTextColor(p.textMuted)
        b.btnClearAllHistory.visibility = if (historyMovies.isNotEmpty()) View.VISIBLE else View.GONE
        b.btnClearAllHistory.backgroundTintList = null
        b.btnClearAllHistory.background = ThemeManager.tintedFocusDrawable(
            requireContext(),
            android.graphics.Color.parseColor("#33F43F5E"),
            12f
        )

        if (historyMovies.isEmpty()) {
            b.txtEmptyHistory.visibility = View.VISIBLE
            b.txtEmptyHistory.setTextColor(p.textMuted)
            b.rvHistory.visibility = View.GONE
        } else {
            b.txtEmptyHistory.visibility = View.GONE
            b.rvHistory.visibility = View.VISIBLE
            historyAdapter.palette = p
            historyAdapter.isTvMode = isTv
            historyAdapter.reducedMotion = DeviceProfileResolver.shouldUseReducedMotion(
                requireContext(),
                st.resolvedDevice,
                st.settings.reducedMotion
            )
            historyAdapter.metaProvider = { m, _ ->
                val prog = progressByMovieId[m.id]
                MovieCardUiMeta(
                    isFavorite = st.favorites.contains(m.id),
                    progressPct = prog?.progressPct,
                    offlineCount = offlineCountByMovie[m.id]
                )
            }
            historyAdapter.submitList(historyMovies)
            val sig = buildString {
                append(p.preset.wireValue).append('|')
                append(p.isDark).append('|')
                append(p.glassOpacityPct).append('|')
                append(isTv).append('|')
                append(historyAdapter.reducedMotion).append('|')
                append(st.favorites.joinToString(",")).append('|')
                append(sortedProg.joinToString(",") { "${it.donghuaId}:${it.episode}:${it.progressPct}" })
            }
            if (sig != lastRenderSig) {
                lastRenderSig = sig
                historyAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroyView() {
        lastRenderSig = null
        _binding = null
        super.onDestroyView()
    }
}
