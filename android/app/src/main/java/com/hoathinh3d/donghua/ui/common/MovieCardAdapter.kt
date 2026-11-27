package com.hoathinh3d.donghua.ui.common

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.hoathinh3d.donghua.R
import com.hoathinh3d.donghua.core.ThemeManager
import com.hoathinh3d.donghua.core.ThemePalette
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.GenreItem
import com.hoathinh3d.donghua.data.model.WatchProgress
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.databinding.ItemContinueCardBinding
import com.hoathinh3d.donghua.databinding.ItemGenreChipBinding
import com.hoathinh3d.donghua.databinding.ItemMovieCardBinding

data class MovieCardUiMeta(
    val isFavorite: Boolean = false,
    val progressPct: Int? = null,
    val rankBadge: Int? = null,
    val newEpBadge: Int? = null,
    val offlineCount: Int? = null
)

class MovieCardAdapter(
    private val fixedWidthDp: Int? = null,
    private val onSelect: (DonghuaMovie) -> Unit,
    private val onToggleFavorite: (DonghuaMovie) -> Unit
) : ListAdapter<DonghuaMovie, MovieCardAdapter.VH>(DIFF) {

    var palette: ThemePalette = ThemeManager.palette("pearl")
    var isTvMode: Boolean = false
    var reducedMotion: Boolean = false
    var metaProvider: (DonghuaMovie, Int) -> MovieCardUiMeta = { _, _ -> MovieCardUiMeta() }

    private var lastBadgeKey: String = ""
    private var cachedQualityBg: GradientDrawable? = null
    private var cachedEpBg: GradientDrawable? = null
    private var cachedRatingBg: GradientDrawable? = null
    private var cachedCardKey: String = ""
    private var cachedCardBgColor: Int = 0
    private var cachedCardStrokeColor: Int = 0

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DonghuaMovie>() {
            override fun areItemsTheSame(a: DonghuaMovie, b: DonghuaMovie): Boolean = a.id == b.id
            override fun areContentsTheSame(a: DonghuaMovie, b: DonghuaMovie): Boolean = a == b
        }
    }

    inner class VH(val b: ItemMovieCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMovieCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (fixedWidthDp != null) {
            val wPx = ThemeManager.dp(parent.context, fixedWidthDp.toFloat())
            b.cardRoot.layoutParams = RecyclerView.LayoutParams(wPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = ThemeManager.dp(parent.context, 10f)
            }
        }
        ThemeManager.applyRoundedOutline(b.posterFrame, 15f)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val movie = getItem(position)
        val meta = metaProvider(movie, position)
        val b = holder.b
        val ctx = b.root.context

        val cardKey = "${palette.preset.wireValue}_${palette.isDark}_${palette.glassOpacityPct}_$isTvMode"
        if (cardKey != cachedCardKey || b.cardRoot.background == null) {
            cachedCardKey = cardKey
            cachedCardBgColor = palette.glassCard
            cachedCardStrokeColor = palette.glassSubtleBorder
            b.cardRoot.background = ThemeManager.glassPanelDrawable(ctx, palette, radiusDp = 20f, isCard = true)
        } else if (b.cardRoot.getTag(R.id.cardRoot) != cardKey) {
            b.cardRoot.background = ThemeManager.glassPanelDrawable(ctx, palette, radiusDp = 20f, isCard = true)
        }
        b.cardRoot.setTag(R.id.cardRoot, cardKey)

        b.txtMovieTitle.setTextColor(palette.textMain)
        b.txtMovieOrig.setTextColor(palette.textMuted)
        b.txtMovieViews.setTextColor(palette.textMuted)
        b.txtMovieStudio.setTextColor(palette.accentFrom)

        val optUrl = MovieRepository.getOptimizedPosterUrl(movie.poster, if (reducedMotion) 280 else 400)
        val targetW = if (reducedMotion) 260 else 380
        val targetH = if (reducedMotion) 368 else 540
        val decodeFmt = if (reducedMotion) {
            com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565
        } else {
            com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888
        }
        val req = Glide.with(b.imgPoster)
            .load(optUrl)
            .override(targetW, targetH)
            .format(decodeFmt)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .error(
                Glide.with(b.imgPoster)
                    .load(movie.poster)
                    .override(targetW, targetH)
                    .format(decodeFmt)
            )
        if (reducedMotion) {
            req.dontAnimate().into(b.imgPoster)
        } else {
            req.into(b.imgPoster)
        }

        if (lastBadgeKey != "v1" || cachedQualityBg == null) {
            lastBadgeKey = "v1"
            cachedQualityBg = GradientDrawable().apply {
                cornerRadius = ThemeManager.dp(ctx, 7f).toFloat()
                setColor(Color.parseColor("#B3080D18"))
                setStroke(1, Color.argb(65, 255, 255, 255))
            }
            cachedEpBg = GradientDrawable().apply {
                cornerRadius = ThemeManager.dp(ctx, 7f).toFloat()
                setColor(Color.parseColor("#CC312E81"))
                setStroke(1, Color.argb(70, 255, 255, 255))
            }
            cachedRatingBg = GradientDrawable().apply {
                cornerRadius = ThemeManager.dp(ctx, 7f).toFloat()
                setColor(Color.parseColor("#B3080D18"))
                setStroke(1, Color.argb(60, 255, 255, 255))
            }
        }

        b.txtMovieTitle.text = movie.title
        b.txtMovieOrig.text = movie.originalTitle
        b.txtMovieViews.text = "👁 ${movie.views}"
        b.txtMovieStudio.text = movie.studio
        b.txtQualityBadge.text = movie.quality
        b.txtQualityBadge.background = cachedQualityBg?.constantState?.newDrawable()?.mutate() ?: cachedQualityBg
        b.txtEpBadge.text = "Tập ${movie.latestEp}/${movie.totalEp}"
        b.txtEpBadge.background = cachedEpBg?.constantState?.newDrawable()?.mutate() ?: cachedEpBg
        b.txtRatingBadge.text = "★ ${movie.rating}"
        b.txtRatingBadge.background = cachedRatingBg?.constantState?.newDrawable()?.mutate() ?: cachedRatingBg

        if (meta.rankBadge != null) {
            b.txtRankBadge.visibility = View.VISIBLE
            b.txtRankBadge.text = "#${meta.rankBadge}"
            val rankColor = when (meta.rankBadge) {
                1 -> Color.parseColor("#F59E0B")
                2 -> Color.parseColor("#64748B")
                3 -> Color.parseColor("#B45309")
                else -> Color.parseColor("#0F172A")
            }
            b.txtRankBadge.background = GradientDrawable().apply {
                cornerRadius = ThemeManager.dp(ctx, 8f).toFloat()
                setColor(rankColor)
            }
        } else {
            b.txtRankBadge.visibility = View.GONE
        }

        if (meta.newEpBadge != null && meta.newEpBadge > 0) {
            b.txtNewEpBadge.visibility = View.VISIBLE
            b.txtNewEpBadge.text = "+${meta.newEpBadge} tập mới"
        } else {
            b.txtNewEpBadge.visibility = View.GONE
        }

        if (meta.offlineCount != null && meta.offlineCount > 0) {
            b.txtOfflineBadge.visibility = View.VISIBLE
            b.txtOfflineBadge.text = "⬇ ${meta.offlineCount} offline"
        } else {
            b.txtOfflineBadge.visibility = View.GONE
        }

        b.btnFavHeart.setBackgroundColor(Color.TRANSPARENT)
        b.btnFavHeart.setImageResource(
            if (meta.isFavorite) R.drawable.ic_fav_heart_filled else R.drawable.ic_fav_heart_outline
        )

        if (meta.progressPct != null && meta.progressPct > 0) {
            b.progressWatchBar.visibility = View.VISIBLE
            b.progressWatchBar.progress = meta.progressPct.coerceIn(0, 100)
        } else {
            b.progressWatchBar.visibility = View.GONE
        }

        b.cardRoot.setOnClickListener { onSelect(movie) }
        b.cardRoot.setOnLongClickListener {
            onToggleFavorite(movie)
            true
        }
        b.btnFavHeart.isFocusable = !isTvMode
        b.btnFavHeart.isFocusableInTouchMode = false
        b.btnFavHeart.setOnClickListener { onToggleFavorite(movie) }
        ThemeManager.attachTvCardFocusEffect(b.cardRoot, enableScale = isTvMode && !reducedMotion)
    }
}

class ContinueWatchingAdapter(
    private val onQuickPlay: (WatchProgress) -> Unit
) : ListAdapter<WatchProgress, ContinueWatchingAdapter.VH>(DIFF) {

    var palette: ThemePalette = ThemeManager.palette("pearl")
    var isTvMode: Boolean = false
    var reducedMotion: Boolean = false

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WatchProgress>() {
            override fun areItemsTheSame(a: WatchProgress, b: WatchProgress): Boolean =
                a.donghuaId == b.donghuaId
            override fun areContentsTheSame(a: WatchProgress, b: WatchProgress): Boolean = a == b
        }
    }

    inner class VH(val b: ItemContinueCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemContinueCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val prog = getItem(position)
        val b = holder.b
        b.continueCardRoot.background =
            ThemeManager.glassPanelDrawable(b.root.context, palette, radiusDp = 16f, isCard = true)
        ThemeManager.applyRoundedOutline(b.continuePosterFrame, 12f)
        b.txtContinueTitle.text = prog.title
        b.txtContinueTitle.setTextColor(palette.textMain)
        b.txtContinueSub.text = com.hoathinh3d.donghua.player.PlayerUtils.formatContinueMeta(
            prog.episode,
            prog.currentTime,
            prog.progressPct
        )
        b.txtContinueSub.setTextColor(palette.accentFrom)
        b.progressContinue.progress = prog.progressPct.coerceIn(0, 100)
        val decodeFmt = if (reducedMotion) {
            com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565
        } else {
            com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888
        }
        val req = Glide.with(b.imgContinuePoster)
            .load(MovieRepository.getOptimizedPosterUrl(prog.poster, 300))
            .override(260, 146)
            .format(decodeFmt)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        if (reducedMotion) req.dontAnimate().into(b.imgContinuePoster) else req.into(b.imgContinuePoster)
        b.continueCardRoot.setOnClickListener { onQuickPlay(prog) }
        ThemeManager.attachTvCardFocusEffect(b.continueCardRoot, enableScale = isTvMode && !reducedMotion)
    }
}

class GenreChipAdapter(
    private val onSelectGenre: (GenreItem) -> Unit
) : ListAdapter<GenreItem, GenreChipAdapter.VH>(DIFF) {

    var palette: ThemePalette = ThemeManager.palette("pearl")
    var selectedId: String = "Tất cả"

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GenreItem>() {
            override fun areItemsTheSame(a: GenreItem, b: GenreItem): Boolean = a.id == b.id
            override fun areContentsTheSame(a: GenreItem, b: GenreItem): Boolean = a == b
        }
    }

    inner class VH(val b: ItemGenreChipBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGenreChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = getItem(position)
        val active = g.id == selectedId
        val tv = holder.b.chipGenre
        tv.text = g.label
        tv.background = ThemeManager.pillDrawable(tv.context, palette, active = active, radiusDp = 20f)
        tv.setTextColor(if (active) Color.WHITE else palette.textMain)
        tv.setOnClickListener { onSelectGenre(g) }
    }
}
