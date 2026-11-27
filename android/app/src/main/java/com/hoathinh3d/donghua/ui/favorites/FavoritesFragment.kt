package com.hoathinh3d.donghua.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.core.ThemePalette
import com.hoathinh3d.donghua.data.model.OfflineEpisodeRecord
import com.hoathinh3d.donghua.databinding.FragmentFavoritesBinding
import com.hoathinh3d.donghua.databinding.ItemOfflineEpisodeBinding
import com.hoathinh3d.donghua.ui.AppHost
import com.hoathinh3d.donghua.ui.ChromeInsetsListener
import com.hoathinh3d.donghua.ui.RefreshableTab
import com.hoathinh3d.donghua.ui.common.MovieCardAdapter
import com.hoathinh3d.donghua.ui.common.MovieCardUiMeta

class FavoritesFragment : Fragment(), RefreshableTab, ChromeInsetsListener {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!
    private val host: AppHost? get() = activity as? AppHost

    private lateinit var offlineAdapter: OfflineEpisodeAdapter
    private lateinit var favAdapter: MovieCardAdapter
    private var currentSpan = 2
    private var lastFavMetaSig: String? = null
    private var lastOfflineMetaSig: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val h = host ?: return

        offlineAdapter = OfflineEpisodeAdapter(
            onPlay = { h.playOfflineRecord(it) },
            onDelete = { h.removeOfflineRecord(it.key) }
        )
        binding.rvOfflineEpisodes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOfflineEpisodes.adapter = offlineAdapter

        favAdapter = MovieCardAdapter(
            fixedWidthDp = null,
            onSelect = { h.openMovieDetail(it) },
            onToggleFavorite = { h.toggleFavorite(it.id) }
        )
        currentSpan = DeviceProfileResolver.gridSpanCount(h.getState().resolvedDevice)
        binding.rvFavorites.layoutManager = GridLayoutManager(requireContext(), currentSpan)
        binding.rvFavorites.adapter = favAdapter

        binding.btnCheckFavUpdates.setOnClickListener { h.checkFavoritesNewEpisodes() }
        binding.favoritesScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            host?.onMainContentScroll(scrollY - oldScrollY, scrollY)
        }
        refreshUi()
    }

    override fun onChromeInsetsChanged(topPx: Int, bottomPx: Int) {
        val b = _binding ?: return
        val h = host ?: return
        val isTv = h.getState().resolvedDevice == com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile.TV
        val hPad = ThemeManager.dp(requireContext(), if (isTv) 22f else 14f)
        b.favoritesScroll.setPadding(hPad, topPx, hPad, bottomPx)
    }

    override fun refreshUi() {
        val b = _binding ?: return
        val h = host ?: return
        val st = h.getState()
        val p = h.getPalette()
        val isTv = st.resolvedDevice == com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile.TV
        val hPad = ThemeManager.dp(requireContext(), if (isTv) 22f else 14f)
        val (chromeTop, chromeBot) = h.getChromeInsets()
        b.favoritesScroll.setPadding(hPad, chromeTop, hPad, chromeBot)

        val favMovies = st.allMovies.filter { st.favorites.contains(it.id) }
        val offlineList = st.offlineEpisodes.values.sortedByDescending { it.savedAt }
        val offlineCountByMovie = offlineList.groupingBy { it.donghuaId }.eachCount()
        val totalNew = favMovies.sumOf { st.favoriteMetaMap[it.id]?.newEpCount ?: 0 }

        b.favHeaderPanel.background = ThemeManager.glassPanelDrawable(requireContext(), p, 22f)
        b.txtFavTitle.setTextColor(p.textMain)
        b.txtFavSub.text = "${favMovies.size} phim đang theo dõi • ${offlineList.size} tập đã lưu offline"
        b.txtFavSub.setTextColor(p.textMuted)
        b.txtFavNewBadge.visibility = if (totalNew > 0) View.VISIBLE else View.GONE
        b.txtFavNewBadge.text = "+$totalNew tập mới"

        b.btnCheckFavUpdates.isEnabled = !st.isCheckingFavUpdates && favMovies.isNotEmpty()
        b.btnCheckFavUpdates.text = if (st.isCheckingFavUpdates) "⏳ Đang kiểm tra..." else "🔔 Kiểm tra tập mới"
        b.btnCheckFavUpdates.background = ThemeManager.pillDrawable(requireContext(), p, false, 14f)
        b.btnCheckFavUpdates.setTextColor(p.accentFrom)

        if (offlineList.isNotEmpty()) {
            b.offlineSectionPanel.visibility = View.VISIBLE
            b.offlineSectionPanel.background = ThemeManager.glassPanelDrawable(requireContext(), p, 22f)
            b.txtOfflineSectionTitle.text = "⬇ Tập Phim Đã Lưu Offline (${offlineList.size})"
            b.txtOfflineSectionTitle.setTextColor(p.textMain)
            b.txtOfflineSectionSub.setTextColor(p.textMuted)
            offlineAdapter.palette = p
            offlineAdapter.submitList(offlineList)
            val offSig = "${p.preset.wireValue}_${p.isDark}_${p.glassOpacityPct}"
            if (offSig != lastOfflineMetaSig) {
                lastOfflineMetaSig = offSig
                offlineAdapter.notifyDataSetChanged()
            }
        } else {
            b.offlineSectionPanel.visibility = View.GONE
        }

        val desiredSpan = DeviceProfileResolver.gridSpanCount(st.resolvedDevice)
        if (desiredSpan != currentSpan) {
            currentSpan = desiredSpan
            (b.rvFavorites.layoutManager as? GridLayoutManager)?.spanCount = desiredSpan
        }

        if (favMovies.isEmpty()) {
            b.txtEmptyFavorites.visibility = View.VISIBLE
            b.txtEmptyFavorites.setTextColor(p.textMuted)
            b.rvFavorites.visibility = View.GONE
        } else {
            b.txtEmptyFavorites.visibility = View.GONE
            b.rvFavorites.visibility = View.VISIBLE
            favAdapter.palette = p
            favAdapter.isTvMode = isTv
            favAdapter.reducedMotion = DeviceProfileResolver.shouldUseReducedMotion(
                requireContext(),
                st.resolvedDevice,
                st.settings.reducedMotion
            )
            favAdapter.metaProvider = { m, _ ->
                MovieCardUiMeta(
                    isFavorite = true,
                    progressPct = st.watchHistory[m.id]?.progressPct,
                    newEpBadge = st.favoriteMetaMap[m.id]?.newEpCount,
                    offlineCount = offlineCountByMovie[m.id]
                )
            }
            favAdapter.submitList(favMovies)
            val favSig = buildString {
                append(p.preset.wireValue).append('|')
                append(p.isDark).append('|')
                append(p.glassOpacityPct).append('|')
                append(isTv).append('|')
                append(st.watchHistory.entries.joinToString(",") { "${it.key}:${it.value.progressPct}" }).append('|')
                append(st.favoriteMetaMap.entries.joinToString(",") { "${it.key}:${it.value.newEpCount}" }).append('|')
                append(offlineCountByMovie.entries.joinToString(",") { "${it.key}:${it.value}" })
            }
            if (favSig != lastFavMetaSig) {
                lastFavMetaSig = favSig
                favAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroyView() {
        lastFavMetaSig = null
        lastOfflineMetaSig = null
        _binding = null
        super.onDestroyView()
    }
}

class OfflineEpisodeAdapter(
    private val onPlay: (OfflineEpisodeRecord) -> Unit,
    private val onDelete: (OfflineEpisodeRecord) -> Unit
) : ListAdapter<OfflineEpisodeRecord, OfflineEpisodeAdapter.VH>(DIFF) {
    var palette: ThemePalette = ThemeManager.palette("pearl")

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<OfflineEpisodeRecord>() {
            override fun areItemsTheSame(a: OfflineEpisodeRecord, b: OfflineEpisodeRecord): Boolean = a.key == b.key
            override fun areContentsTheSame(a: OfflineEpisodeRecord, b: OfflineEpisodeRecord): Boolean = a == b
        }
    }

    inner class VH(val b: ItemOfflineEpisodeBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemOfflineEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = getItem(position)
        val b = holder.b
        b.offlineItemRoot.background =
            ThemeManager.glassPanelDrawable(b.root.context, palette, 18f, isCard = true)
        ThemeManager.applyRoundedOutline(b.imgOfflinePoster, 12f)
        b.txtOfflineTitle.text = rec.title
        b.txtOfflineTitle.setTextColor(palette.textMain)
        val mb = String.format(java.util.Locale.US, "%.1f MB", rec.approxBytes / (1024.0 * 1024.0))
        b.txtOfflineEpSize.text = "${rec.episodeName} • $mb"
        b.txtOfflineServer.text = rec.serverName
        b.txtOfflineServer.setTextColor(palette.textMuted)
        val isLow = DeviceProfileResolver.isLowRamDevice(b.root.context)
        val fmt = if (isLow) com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565 else com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888
        Glide.with(b.imgOfflinePoster)
            .load(rec.poster)
            .override(200, 280)
            .format(fmt)
            .into(b.imgOfflinePoster)
        b.btnPlayOffline.backgroundTintList = null
        b.btnPlayOffline.background = ThemeManager.accentGradientDrawable(b.root.context, palette, 10f)
        b.btnDeleteOffline.backgroundTintList = null
        b.btnDeleteOffline.background = ThemeManager.tintedFocusDrawable(
            b.root.context,
            android.graphics.Color.parseColor("#33F43F5E"),
            10f
        )
        b.btnPlayOffline.setOnClickListener { onPlay(rec) }
        b.btnDeleteOffline.setOnClickListener { onDelete(rec) }
    }
}
