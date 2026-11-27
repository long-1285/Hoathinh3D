package com.hoathinh3d.donghua.ui.schedule

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.core.ThemePalette
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.model.ResolvedDeviceProfile
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.data.source.YanHh3dNowScraper
import com.hoathinh3d.donghua.data.source.YanHh3dScraper
import com.hoathinh3d.donghua.databinding.FragmentScheduleBinding
import com.hoathinh3d.donghua.databinding.ItemScheduleMovieBinding
import com.hoathinh3d.donghua.ui.AppHost
import com.hoathinh3d.donghua.ui.ChromeInsetsListener
import com.hoathinh3d.donghua.ui.RefreshableTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.Calendar

class ScheduleFragment : Fragment(), RefreshableTab, ChromeInsetsListener {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val host: AppHost? get() = activity as? AppHost

    private val todayName: String by lazy {
        when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Thứ 2"
            Calendar.TUESDAY -> "Thứ 3"
            Calendar.WEDNESDAY -> "Thứ 4"
            Calendar.THURSDAY -> "Thứ 5"
            Calendar.FRIDAY -> "Thứ 6"
            Calendar.SATURDAY -> "Thứ 7"
            else -> "Chủ Nhật"
        }
    }

    private var activeDay: String = "Thứ 2"
    private var yanDayMovies: List<DonghuaMovie> = emptyList()
    private var fetchJob: Job? = null
    private var lastDayTabsKey: String? = null
    private var currentCols = 1
    private var restoreDayTabFocus = false
    private lateinit var adapter: ScheduleMovieAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeDay = todayName
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val h = host ?: return
        adapter = ScheduleMovieAdapter { h.openMovieDetail(it) }
        currentCols = if (h.getState().resolvedDevice == ResolvedDeviceProfile.MOBILE) 1 else 2
        binding.rvSchedule.layoutManager = GridLayoutManager(requireContext(), currentCols)
        binding.rvSchedule.adapter = adapter
        if (DeviceProfileResolver.shouldUseReducedMotion(requireContext(), h.getState().resolvedDevice, h.getState().settings.reducedMotion)) {
            binding.rvSchedule.itemAnimator = null
        }
        binding.rvSchedule.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                host?.onMainContentScroll(dy, recyclerView.computeVerticalScrollOffset())
            }
        })
        binding.swipeRefreshSchedule.setOnRefreshListener {
            loadScheduleForDay(activeDay)
        }
        refreshUi()
        loadScheduleForDay(activeDay)
    }

    private fun loadScheduleForDay(day: String) {
        val showtimeId = YanHh3dScraper.DAY_TO_SHOWTIME_ID[day] ?: return
        fetchJob?.cancel()
        _binding?.txtLoadingSchedule?.visibility = View.VISIBLE
        _binding?.txtLoadingSchedule?.text = "⏳ Đang đồng bộ lịch chiếu $day từ YanHH3D Now & VIP..."
        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            val nowTask = async { runCatching { YanHh3dNowScraper.fetchScheduleByDay(day) }.getOrDefault(emptyList()) }
            val yanTask = async { runCatching { YanHh3dScraper.fetchMovies(showtimeId = showtimeId, perPage = 30).movies }.getOrDefault(emptyList()) }
            yanDayMovies = MovieRepository.mergeMovieLists(listOf(nowTask.await(), yanTask.await()))
            _binding?.txtLoadingSchedule?.visibility = View.GONE
            _binding?.swipeRefreshSchedule?.isRefreshing = false
            refreshUi()
        }
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
        b.scheduleRoot.setPadding(hPad, topPx, hPad, 0)
        b.rvSchedule.setPadding(0, 0, 0, bottomPx)
    }

    override fun refreshUi() {
        val b = _binding ?: return
        val h = host ?: return
        val p = h.getPalette()
        val st = h.getState()
        val isTv = st.resolvedDevice == ResolvedDeviceProfile.TV
        val desiredCols = if (st.resolvedDevice == ResolvedDeviceProfile.MOBILE) 1 else 2
        if (desiredCols != currentCols) {
            currentCols = desiredCols
            (b.rvSchedule.layoutManager as? GridLayoutManager)?.spanCount = desiredCols
        }
        val hPad = ThemeManager.dp(
            requireContext(),
            when (st.resolvedDevice) {
                ResolvedDeviceProfile.TV -> 22f
                ResolvedDeviceProfile.TABLET -> 18f
                ResolvedDeviceProfile.MOBILE -> 14f
            }
        )
        val (chromeTop, chromeBot) = h.getChromeInsets()
        b.scheduleRoot.setPadding(hPad, chromeTop, hPad, 0)
        b.rvSchedule.setPadding(0, 0, 0, chromeBot)

        b.swipeRefreshSchedule.isEnabled = !isTv
        b.swipeRefreshSchedule.setColorSchemeColors(p.accentFrom, p.accentTo)
        b.swipeRefreshSchedule.setProgressBackgroundColorSchemeColor(p.bgSecondary)
        if (fetchJob?.isActive != true && b.swipeRefreshSchedule.isRefreshing) {
            b.swipeRefreshSchedule.isRefreshing = false
        }

        b.scheduleHeaderPanel.background = ThemeManager.glassPanelDrawable(requireContext(), p, 22f)
        b.txtScheduleTitle.setTextColor(p.textMain)
        b.txtScheduleSub.setTextColor(p.textMuted)
        b.txtScheduleToday.text = "🕒 Hôm nay: $todayName"
        b.txtScheduleToday.background = ThemeManager.pillDrawable(requireContext(), p, false, 16f)
        b.txtScheduleToday.setTextColor(p.accentFrom)
        b.txtLoadingSchedule.setTextColor(p.accentFrom)

        val dayTabsKey = "${activeDay}_${p.preset.wireValue}"
        if (dayTabsKey != lastDayTabsKey) {
            lastDayTabsKey = dayTabsKey
            val shouldRestore = restoreDayTabFocus
            restoreDayTabFocus = false
            b.dayTabsContainer.removeAllViews()
            for (day in InitialData.SCHEDULE_DAYS) {
                val isActive = day == activeDay
                val isToday = day == todayName
                val btn = Button(requireContext()).apply {
                    text = if (isToday) "$day •" else day
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
                    background = ThemeManager.pillDrawable(context, p, active = isActive, radiusDp = 16f)
                    setTextColor(if (isActive) Color.WHITE else p.textMain)
                    isFocusable = true
                    setOnClickListener {
                        restoreDayTabFocus = isTv || hasFocus()
                        activeDay = day
                        loadScheduleForDay(day)
                        refreshUi()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = ThemeManager.dp(requireContext(), 8f) }
                b.dayTabsContainer.addView(btn, lp)
                if (isActive && shouldRestore) {
                    btn.post { btn.requestFocus() }
                }
            }
        }

        val list = MovieRepository.mergeCatalogPages(
            yanDayMovies,
            st.allMovies.filter { it.scheduleDay == activeDay }
        )

        if (list.isEmpty()) {
            b.txtEmptySchedule.visibility = View.VISIBLE
            b.txtEmptySchedule.text = "📅 Chưa có lịch phát sóng cho $activeDay"
            b.txtEmptySchedule.setTextColor(p.textMuted)
            b.rvSchedule.visibility = View.GONE
        } else {
            b.txtEmptySchedule.visibility = View.GONE
            b.rvSchedule.visibility = View.VISIBLE
            adapter.palette = p
            adapter.isTvMode = isTv
            adapter.submitList(list)
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        fetchJob?.cancel()
        lastDayTabsKey = null
        _binding = null
        super.onDestroyView()
    }
}

class ScheduleMovieAdapter(
    private val onClick: (DonghuaMovie) -> Unit
) : ListAdapter<DonghuaMovie, ScheduleMovieAdapter.VH>(DIFF) {
    var palette: ThemePalette = ThemeManager.palette("pearl")
    var isTvMode: Boolean = false

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DonghuaMovie>() {
            override fun areItemsTheSame(a: DonghuaMovie, b: DonghuaMovie): Boolean = a.id == b.id
            override fun areContentsTheSame(a: DonghuaMovie, b: DonghuaMovie): Boolean = a == b
        }
    }

    inner class VH(val b: ItemScheduleMovieBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemScheduleMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = getItem(position)
        val b = holder.b
        b.scheduleCardRoot.background =
            ThemeManager.glassPanelDrawable(b.root.context, palette, 18f, isCard = true)
        ThemeManager.applyRoundedOutline(b.imgSchedPoster, 12f)
        b.txtSchedTime.text = "🕒 ${m.scheduleTime}"
        b.txtSchedTime.setTextColor(palette.accentFrom)
        b.txtSchedTime.background = ThemeManager.pillDrawable(b.root.context, palette, false, 8f)
        b.txtSchedStudio.text = m.studio
        b.txtSchedStudio.setTextColor(palette.textMuted)
        b.txtSchedTitle.text = m.title
        b.txtSchedTitle.setTextColor(palette.textMain)
        b.txtSchedOrig.text = m.originalTitle
        b.txtSchedOrig.setTextColor(palette.textMuted)
        b.txtSchedEp.text = "Tập ${m.latestEp}"
        b.txtSchedEp.setTextColor(palette.accentFrom)
        b.txtSchedLatestLabel.setTextColor(palette.textMuted)
        val isLow = com.hoathinh3d.donghua.core.DeviceProfileResolver.isLowRamDevice(b.root.context)
        val fmt = if (isLow) com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565 else com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888
        val optSched = MovieRepository.getOptimizedPosterUrl(m.poster, 240)
        if (optSched.isBlank() && MovieRepository.isDeadOrBlankPoster(m.poster)) {
            Glide.with(b.imgSchedPoster).clear(b.imgSchedPoster)
            b.imgSchedPoster.setImageResource(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
        } else {
            Glide.with(b.imgSchedPoster)
                .load(optSched.ifBlank { m.poster })
                .placeholder(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
                .override(200, 280)
                .format(fmt)
                .error(
                    Glide.with(b.imgSchedPoster)
                        .load(m.poster)
                        .override(200, 280)
                        .format(fmt)
                        .error(com.hoathinh3d.donghua.R.drawable.bg_poster_placeholder)
                )
                .into(b.imgSchedPoster)
        }
        b.scheduleCardRoot.setOnClickListener { onClick(m) }
        ThemeManager.attachTvCardFocusEffect(b.scheduleCardRoot, enableScale = isTvMode)
    }
}
