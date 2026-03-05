package com.ooustream.iptv.player

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.ooustream.iptv.R
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.recommendation.RecommendedItem

/**
 * Full-screen overlay that shows "You Might Also Like" movie suggestions
 * when a VOD movie finishes playing. The user D-pads to pick a movie
 * or presses Back to exit.
 */
class WatchNextOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val grid: HorizontalGridView

    /** Called when the user selects a movie card. */
    var onMovieSelected: ((RecommendedItem) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_watch_next, this, true)
        grid = findViewById(R.id.watch_next_grid)
        visibility = GONE
    }

    /**
     * Populate the grid with [items] and fade in. First card receives focus.
     * If [items] is empty the overlay stays hidden.
     */
    fun show(items: List<RecommendedItem>) {
        if (items.isEmpty()) return

        grid.adapter = WatchNextAdapter(items) { item ->
            onMovieSelected?.invoke(item)
        }

        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()

        // Focus the first card after layout pass
        grid.post {
            grid.getChildAt(0)?.requestFocus()
        }
    }

    fun dismiss() {
        animate().alpha(0f).setDuration(300).withEndAction {
            visibility = GONE
        }.start()
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    // ── Adapter ──────────────────────────────────────────────

    private class WatchNextAdapter(
        private val items: List<RecommendedItem>,
        private val onClick: (RecommendedItem) -> Unit
    ) : RecyclerView.Adapter<WatchNextAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val poster: ImageView = view.findViewById(R.id.watch_next_poster)
            val name: TextView = view.findViewById(R.id.watch_next_name)
            val reason: TextView = view.findViewById(R.id.watch_next_reason)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_watch_next_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.reason.text = item.reason
            holder.poster.load(PosterUrlRewriter.rewrite(item.icon)) {
                crossfade(200)
                placeholder(R.drawable.bg_card_rounded)
                error(R.drawable.bg_card_rounded)
                transformations(RoundedCornersTransformation(12f))
            }
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
