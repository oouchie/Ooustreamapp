package com.ooustream.iptv.vod

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.R
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.VodInfo
import com.ooustream.iptv.data.model.VodStream

/**
 * View binding + D-pad behaviour for fragment_vod_detail_tv (5.0). The fragment owns the ViewModel
 * and playback launch; this class renders state and reports intent — same split as the series
 * screen's SeriesDetailTvScreen.
 */
class VodDetailTvScreen(
    root: View,
    private val fallbackTitle: String,
    private val onPlay: (fromStart: Boolean) -> Unit,
    private val onOpenMovie: (VodStream) -> Unit
) {
    private val backdrop: ImageView = root.findViewById(R.id.vd_backdrop)
    private val scroll: ScrollView = root.findViewById(R.id.vd_scroll)
    private val left: LinearLayout = root.findViewById(R.id.vd_left)
    private val title: TextView = root.findViewById(R.id.vd_title)
    private val year: TextView = root.findViewById(R.id.vd_year)
    private val runtime: TextView = root.findViewById(R.id.vd_runtime)
    private val genre: TextView = root.findViewById(R.id.vd_genre)
    private val rating: TextView = root.findViewById(R.id.vd_rating)
    private val plot: TextView = root.findViewById(R.id.vd_plot)
    private val primary: LinearLayout = root.findViewById(R.id.vd_primary)
    private val primaryLabel: TextView = root.findViewById(R.id.vd_primary_label)
    private val primarySub: TextView = root.findViewById(R.id.vd_primary_sub)
    private val startOver: TextView = root.findViewById(R.id.vd_start_over)
    private val trailer: TextView = root.findViewById(R.id.vd_trailer)
    private val cast: TextView = root.findViewById(R.id.vd_cast)
    private val moreHeading: TextView = root.findViewById(R.id.vd_more_heading)
    private val more: HorizontalGridView = root.findViewById(R.id.vd_more)
    private val loading: ProgressBar = root.findViewById(R.id.vd_loading)

    private var runtimeText: String? = null
    private var progress: WatchProgressEntity? = null
    private val moreAdapter = MoreLikeThisAdapter(onOpenMovie)

    init {
        title.text = fallbackTitle
        more.adapter = moreAdapter
        more.itemAnimator = null
        primary.setOnClickListener { onPlay(false) }
        startOver.setOnClickListener { onPlay(true) }
        val lift = View.OnFocusChangeListener { v, has ->
            val s = if (has) 1.04f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(150).start()
        }
        startOver.onFocusChangeListener = lift
        trailer.onFocusChangeListener = lift
        // Top section fills the screen minus a peek of the "More like this" heading.
        root.post {
            val peek = (72 * root.resources.displayMetrics.density).toInt()
            left.minimumHeight = (root.height - peek - scroll.paddingTop - 40.dp(root)).coerceAtLeast(0)
        }
        // Returning to the top section scrolls back up so the backdrop/title read again.
        primary.setOnFocusChangeListener { v, has ->
            lift.onFocusChange(v, has)
            if (has) scroll.smoothScrollTo(0, 0)
        }
        primary.requestFocus()
        // Coming BACK to this page, the ScrollView restores its old offset after onViewCreated
        // (view-state restore), leaving the title scrolled off with Play focused. post{} runs after
        // that restore.
        scroll.post { scroll.scrollTo(0, 0) }
    }

    fun setLoading(isLoading: Boolean) {
        loading.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    fun setPlaceholderArt(coverUrl: String?) {
        if (coverUrl.isNullOrBlank()) return
        backdrop.load(PosterUrlRewriter.rewriteBackdrop(coverUrl)) {
            crossfade(true); memoryCachePolicy(CachePolicy.ENABLED)
        }
    }

    fun bindInfo(info: VodInfo, coverUrl: String?) {
        val d = info.info
        title.text = (info.movieData?.name?.takeIf { it.isNotBlank() } ?: fallbackTitle)
            .replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "")
        val art = d?.backdropPath?.firstOrNull() ?: d?.movieImage ?: coverUrl
        if (!art.isNullOrBlank()) {
            backdrop.load(PosterUrlRewriter.rewriteBackdrop(art)) {
                crossfade(true); memoryCachePolicy(CachePolicy.ENABLED)
            }
        }
        year.show(d?.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) })
        runtimeText = formatRuntime(d?.durationSecs, d?.duration)
        runtime.show(runtimeText)
        genre.show(d?.genre?.split(",", "/")?.firstOrNull()?.trim())
        rating.show(d?.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) })
        plot.show(d?.plot)
        val castList = d?.cast?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.take(4)
        cast.show(castList?.takeIf { it.isNotEmpty() }?.let {
            cast.context.getString(R.string.vd_with, it.joinToString(", "))
        })
        val trailerId = d?.youtubeTrailer?.trim()
        trailer.visibility = if (trailerId.isNullOrBlank()) View.GONE else View.VISIBLE
        trailer.setOnClickListener {
            if (trailerId.isNullOrBlank()) return@setOnClickListener
            val url = if (trailerId.startsWith("http")) trailerId else "https://www.youtube.com/watch?v=$trailerId"
            runCatching { trailer.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        renderPrimary()
    }

    fun bindProgress(p: WatchProgressEntity?) {
        progress = p
        renderPrimary()
    }

    fun bindSimilar(list: List<VodStream>) {
        moreAdapter.submitList(list)
        val show = list.isNotEmpty()
        moreHeading.visibility = if (show) View.VISIBLE else View.GONE
        more.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun renderPrimary() {
        val ctx = primary.context
        val p = progress
        val resuming = p != null && !p.completed && p.position > 0L && p.progressPercent > 0.05f
        primaryLabel.text = ctx.getString(
            when {
                resuming -> R.string.vd_resume
                p?.completed == true -> R.string.vd_watch_again
                else -> R.string.vd_play
            }
        )
        primarySub.show(
            if (resuming) formatLeft(p!!.duration - p.position) else runtimeText
        )
        startOver.visibility = if (resuming) View.VISIBLE else View.GONE
    }

    private fun TextView.show(value: String?) {
        visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
        text = value
    }

    private fun Int.dp(v: View) = (this * v.resources.displayMetrics.density).toInt()

    companion object {
        fun formatRuntime(secs: Int?, raw: String?): String? {
            val s = secs?.takeIf { it > 0 }
                ?: raw?.split(":")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }
                    ?.let { (h, m, x) -> h * 3600 + m * 60 + x }?.takeIf { it > 0 }
                ?: return null
            val mins = (s + 30) / 60
            return if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
        }

        fun formatLeft(ms: Long): String? {
            val mins = (ms / 60_000L).toInt()
            if (mins <= 0) return null
            return if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else "${mins}m left"
        }
    }
}

private class MoreLikeThisAdapter(
    private val onOpen: (VodStream) -> Unit
) : ListAdapter<VodStream, MoreLikeThisAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_more_like_this, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        private val image: ImageView = v.findViewById(R.id.ml_image)
        private val title: TextView = v.findViewById(R.id.ml_title)

        init {
            v.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onOpen(getItem(it)) }
            }
            v.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.06f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(150).start()
            }
        }

        fun bind(item: VodStream) {
            title.text = item.name
            val url = item.streamIcon?.takeIf { it.isNotBlank() }
            if (url == null) {
                image.setImageDrawable(null)
            } else {
                image.load(PosterUrlRewriter.rewrite(url)) {
                    crossfade(true)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    // Broken artwork → show the title underneath instead of a blank tile.
                    listener(onError = { _, _ -> image.setImageDrawable(null) })
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<VodStream>() {
        override fun areItemsTheSame(o: VodStream, n: VodStream) = o.streamId == n.streamId
        override fun areContentsTheSame(o: VodStream, n: VodStream) = o == n
    }
}
