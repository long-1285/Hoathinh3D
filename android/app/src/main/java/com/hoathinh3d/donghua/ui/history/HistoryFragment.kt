package com.hoathinh3d.donghua.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.core.ThemePalette
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile
import com.hoathinh3d.donghua.data.model.WatchProgress
import com.hoathinh3d.donghua.databinding.FragmentHistoryBinding
import com.hoathinh3d.donghua.databinding.ItemHistoryMovieBinding
import com.hoathinh3d.donghua.ui.AppHost
import com.hoathinh3d.donghua.ui.ChromeInsetsListener
import com.hoathinh3d.donghua.ui.RefreshableTab

class HistoryFragment : Fragment(), RefreshableTab, ChromeInsetsListener {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val host: AppHost? get() = activity as? AppHost
    private lateinit var adapter: HistoryAdapter
    private var currentCols = 1
    private var lastRenderSig: String? = null

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
        adapter = HistoryAdapter(
            onClick = { prog ->
                val matched = h.resolveMovieForProgress(prog)
                val matchedSrvIdx = matched.serverGroups
                    ?.indexOfFirst { it.serverName.equals(prog.server, ignoreCase = true) }
                    ?.takeIf { it >= 0 } ?: -1
                h.quickPlayMovie(matched, prog.episode, matchedSrvIdx, prog.currentTime)
            },
            onRemove = { prog ->
                h.removeHistoryItem(prog.donghuaId)
            }
        )
        currentCols = when (h.getState().resolvedDevice) {
            ResolvedDeviceProfile.MOBILE -> 1
            ResolvedDeviceProfile.TABLET -> 2
            ResolvedDeviceProfile.TV -> 3
        }
        binding.rvHistory.layoutManager = GridLayoutManager(requireContext(), currentCols)
        binding.rvHistory.adapter = adapter
        binding.rvHistory.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                host?.onMainContentScroll(dy, recyclerView.computeVerticalScrollOffset())
            }
        })
        binding.btnClearAllHistory.setOnClickListener { h.clearHistory() }
        refreshUi()
    }

    override fun onChromeInsetsChanged(topPx: Int, bottomPx: Int) {
        val b = _binding ?: return
        val h = host ?: return
        val isTv = h.getState().resolvedDevice == ResolvedDeviceProfile.TV
        val hPad = ThemeManager.dp(requireContext(), if (isTv) 22f else 14f)
        b.historyRoot.setPadding(hPad, topPx, hPad, 0)
        b.rvHistory.setPadding(0, 0, 0, bottomPx)
    }

    override fun refreshUi() {
        val b = _binding ?: return
        val h = host ?: return
        val p = h.getPalette()
        val isTv = h.getState().resolvedDevice == ResolvedDeviceProfile.TV
        val hPad = ThemeManager.dp(requireContext(), if (isTv) 22f else 14f)
        val (chromeTop, chromeBot) = h.getChromeInsets()
        b.historyRoot.setPadding(hPad, chromeTop, hPad, 0)
        b.rvHistory.setPadding(0, 0, 0, chromeBot)
        val desiredCols = when (h.getState().resolvedDevice) {
            ResolvedDeviceProfile.MOBILE -> 1
            ResolvedDeviceProfile.TABLET -> 2
            ResolvedDeviceProfile.TV -> 3
        }
        if (desiredCols != currentCols) {
            currentCols = desiredCols
            (b.rvHistory.layoutManager as? GridLayoutManager)?.spanCount = desiredCols
        }
        val list = h.getState().watchHistory.values.sortedByDescending { it.updatedAt }

        b.historyHeaderPanel.background = ThemeManager.glassPanelDrawable(requireContext(), p, 22f)
        b.txtHistoryTitle.setTextColor(p.textMain)
        b.txtHistorySub.text = "Đã lưu ${list.size} bộ phim bạn từng thưởng thức"
        b.txtHistorySub.setTextColor(p.textMuted)
        b.btnClearAllHistory.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
        b.btnClearAllHistory.backgroundTintList = null
        b.btnClearAllHistory.background = ThemeManager.tintedFocusDrawable(
            requireContext(),
            android.graphics.Color.parseColor("#33F43F5E"),
            12f
        )

        if (list.isEmpty()) {
            b.txtEmptyHistory.visibility = View.VISIBLE
            b.txtEmptyHistory.setTextColor(p.textMuted)
            b.rvHistory.visibility = View.GONE
        } else {
            b.txtEmptyHistory.visibility = View.GONE
            b.rvHistory.visibility = View.VISIBLE
            adapter.palette = p
            adapter.isTvMode = isTv
            adapter.submitList(list)
            val sig = "${p.preset.wireValue}_${p.isDark}_${p.glassOpacityPct}_$isTv"
            if (sig != lastRenderSig) {
                lastRenderSig = sig
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroyView() {
        lastRenderSig = null
        _binding = null
        super.onDestroyView()
    }
}

class HistoryAdapter(
    private val onClick: (WatchProgress) -> Unit,
    private val onRemove: (WatchProgress) -> Unit
) : ListAdapter<WatchProgress, HistoryAdapter.VH>(DIFF) {
    var palette: ThemePalette = ThemeManager.palette("pearl")
    var isTvMode: Boolean = false

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WatchProgress>() {
            override fun areItemsTheSame(a: WatchProgress, b: WatchProgress): Boolean =
                a.donghuaId == b.donghuaId
            override fun areContentsTheSame(a: WatchProgress, b: WatchProgress): Boolean = a == b
        }
    }

    inner class VH(val b: ItemHistoryMovieBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHistoryMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.b
        b.historyCardRoot.background =
            ThemeManager.glassPanelDrawable(b.root.context, palette, 18f, isCard = true)
        ThemeManager.applyRoundedOutline(b.historyPosterFrame, 13f)
        b.txtHistTitle.text = item.title
        b.txtHistTitle.setTextColor(palette.textMain)
        val timeLabel = com.hoathinh3d.donghua.player.PlayerUtils.formatPlayerTime(item.currentTime.toLong())
        b.txtHistEp.text = if (item.currentTime >= 5.0) {
            "Đang xem Tập ${item.episode} • $timeLabel"
        } else {
            "Đang xem Tập ${item.episode}"
        }
        b.txtHistEp.setTextColor(palette.accentFrom)
        b.txtHistPct.text = "${item.progressPct}%"
        b.txtHistPct.setTextColor(palette.accentFrom)
        b.txtHistMeta.text = "${item.server} • ${item.audio}"
        b.txtHistMeta.setTextColor(palette.textMuted)
        b.progressHistBar.progress = item.progressPct.coerceIn(0, 100)
        val isLow = com.hoathinh3d.donghua.core.DeviceProfileResolver.isLowRamDevice(b.root.context)
        val fmt = if (isLow) com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565 else com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888
        Glide.with(b.imgHistPoster)
            .load(com.hoathinh3d.donghua.data.source.MovieRepository.getOptimizedPosterUrl(item.poster, 260))
            .override(220, 300)
            .format(fmt)
            .error(Glide.with(b.imgHistPoster).load(item.poster).override(220, 300).format(fmt))
            .into(b.imgHistPoster)
        b.btnRemoveHistoryItem.background = ThemeManager.tintedFocusDrawable(
            b.root.context,
            android.graphics.Color.argb(175, 15, 23, 42),
            14f,
            android.graphics.Color.parseColor("#F43F5E")
        )
        b.btnRemoveHistoryItem.setOnClickListener { onRemove(item) }
        b.historyCardRoot.setOnClickListener { onClick(item) }
        ThemeManager.attachTvCardFocusEffect(b.historyCardRoot, enableScale = isTvMode)
    }
}
