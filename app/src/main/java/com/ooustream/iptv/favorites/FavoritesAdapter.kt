package com.ooustream.iptv.favorites

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FocusBracketDrawable
import com.ooustream.iptv.common.GoldGlowFocusDrawable
import com.ooustream.iptv.common.ChannelDisplayHelper
import com.ooustream.iptv.common.ProgressiveImageLoader

class FavoritesAdapter(
    private val onClick: (FavoriteDisplayItem) -> Unit,
    private val onLongClick: (FavoriteDisplayItem) -> Unit,
    private val onRemove: (FavoriteDisplayItem) -> Unit
) : ListAdapter<FavoriteDisplayItem, RecyclerView.ViewHolder>(FavoriteDiffCallback()) {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    var viewMode: ViewMode = ViewMode.GRID
        set(value) {
            if (field != value) {
                field = value
                isFirstLoad = true
                notifyDataSetChanged()
            }
        }

    var isEditMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var isFirstLoad = true
    private val focusHandler = Handler(Looper.getMainLooper())

    override fun getItemViewType(position: Int): Int {
        return if (viewMode == ViewMode.GRID) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val view = inflater.inflate(R.layout.item_favorite_card, parent, false)
            // Pre-create focus drawables (ChannelPresenter pattern)
            if (DeviceUtils.isTV(parent.context)) {
                view.setTag(R.id.focus_glow_drawable, GoldGlowFocusDrawable())
                view.setTag(R.id.focus_bracket_drawable, FocusBracketDrawable())
            }
            GridViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_favorite_list, parent, false)
            if (DeviceUtils.isTV(parent.context)) {
                view.setTag(R.id.focus_glow_drawable, GoldGlowFocusDrawable())
                view.setTag(R.id.focus_bracket_drawable, FocusBracketDrawable())
            }
            ListViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is GridViewHolder -> holder.bind(item)
            is ListViewHolder -> holder.bind(item)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (isFirstLoad) {
            val pos = holder.bindingAdapterPosition
            val delay = (pos * 50L).coerceAtMost(500L)
            holder.itemView.alpha = 0f
            holder.itemView.animate()
                .alpha(1f)
                .setDuration(300)
                .setStartDelay(delay)
                .start()
        }
    }

    fun markFirstLoadComplete() {
        isFirstLoad = false
    }

    // --- Grid ViewHolder ---

    inner class GridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.fav_image)
        private val name: TextView = itemView.findViewById(R.id.fav_name)
        private val liveBadge: LinearLayout = itemView.findViewById(R.id.fav_live_badge)
        private val categoryBadge: TextView = itemView.findViewById(R.id.fav_category_badge)
        private val removeBtn: ImageView = itemView.findViewById(R.id.fav_remove_btn)
        private val epgTitle: TextView = itemView.findViewById(R.id.fav_epg_title)
        private val scoreRow: LinearLayout = itemView.findViewById(R.id.fav_score_row)
        private val scoreText: TextView = itemView.findViewById(R.id.fav_score_text)
        private val scoreDetail: TextView = itemView.findViewById(R.id.fav_score_detail)
        private val nextProgram: TextView = itemView.findViewById(R.id.fav_next_program)
        private val progress: ProgressBar = itemView.findViewById(R.id.fav_progress)

        fun bind(item: FavoriteDisplayItem) {
            name.text = item.name

            // Image
            ProgressiveImageLoader.loadThumbnail(image, item.icon, "fav_${item.id}", 14f)

            // LIVE badge
            liveBadge.visibility = if (item.isLive) View.VISIBLE else View.GONE

            // Category badge
            if (item.categoryGroup != ChannelCategoryMapper.GROUP_ALL &&
                item.categoryGroup != ChannelCategoryMapper.GROUP_LIVE_TV &&
                item.categoryGroup != ChannelCategoryMapper.GROUP_MOVIES &&
                item.categoryGroup != ChannelCategoryMapper.GROUP_SERIES) {
                categoryBadge.text = item.categoryGroup
                categoryBadge.visibility = View.VISIBLE
            } else {
                categoryBadge.visibility = View.GONE
            }

            // EPG title
            if (!item.epgTitle.isNullOrBlank()) {
                epgTitle.text = item.epgTitle
                epgTitle.visibility = View.VISIBLE
            } else {
                epgTitle.visibility = View.GONE
            }

            // Score
            if (!item.score.isNullOrBlank()) {
                scoreRow.visibility = View.VISIBLE
                scoreText.text = item.score
                if (!item.scoreDetail.isNullOrBlank()) {
                    scoreDetail.text = item.scoreDetail
                    scoreDetail.visibility = View.VISIBLE
                } else {
                    scoreDetail.visibility = View.GONE
                }
            } else {
                scoreRow.visibility = View.GONE
            }

            // Next program
            if (!item.epgNextTitle.isNullOrBlank()) {
                nextProgram.text = item.epgNextTitle
                nextProgram.visibility = View.VISIBLE
            } else {
                nextProgram.visibility = View.GONE
            }

            // Progress bar (EPG for live, watch for VOD/series)
            val progressValue = if (item.isLive) item.epgProgress else item.watchProgress
            if (progressValue > 0f) {
                progress.progress = (progressValue * 1000).toInt()
                progress.visibility = View.VISIBLE
            } else {
                progress.visibility = View.GONE
            }

            // Edit mode
            removeBtn.visibility = if (isEditMode) View.VISIBLE else View.GONE
            removeBtn.setOnClickListener { onRemove(item) }

            // Click listeners
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener { onLongClick(item); true }

            // Focus handling
            setupFocusHandling(itemView, item)
        }
    }

    // --- List ViewHolder ---

    inner class ListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logo: ImageView = itemView.findViewById(R.id.fav_logo)
        private val logoInitials: TextView = itemView.findViewById(R.id.fav_logo_initials)
        private val name: TextView = itemView.findViewById(R.id.fav_name)
        private val category: TextView = itemView.findViewById(R.id.fav_category)
        private val epgTitle: TextView = itemView.findViewById(R.id.fav_epg_title)
        private val score: TextView = itemView.findViewById(R.id.fav_score)
        private val progress: ProgressBar = itemView.findViewById(R.id.fav_progress)
        private val liveBadge: TextView = itemView.findViewById(R.id.fav_live_badge)
        private val removeBtn: ImageView = itemView.findViewById(R.id.fav_remove_btn)

        fun bind(item: FavoriteDisplayItem) {
            name.text = item.name
            category.text = item.categoryName ?: item.categoryGroup

            // Image — use ChannelDisplayHelper for live channels (matches Live TV screen)
            if (item.isLive) {
                ChannelDisplayHelper.loadLogo(logo, logoInitials, item.icon, item.name)
            } else {
                logoInitials.visibility = View.GONE
                ProgressiveImageLoader.loadThumbnail(logo, item.icon, "fav_${item.id}", 6f)
            }

            // EPG
            epgTitle.text = item.epgTitle ?: ""

            // Score
            if (!item.score.isNullOrBlank()) {
                score.text = item.score
                score.visibility = View.VISIBLE
            } else {
                score.visibility = View.GONE
            }

            // Progress
            val progressValue = if (item.isLive) item.epgProgress else item.watchProgress
            if (progressValue > 0f) {
                progress.progress = (progressValue * 1000).toInt()
                progress.visibility = View.VISIBLE
            } else {
                progress.visibility = View.GONE
            }

            // LIVE badge
            liveBadge.visibility = if (item.isLive) View.VISIBLE else View.GONE

            // Edit mode
            removeBtn.visibility = if (isEditMode) View.VISIBLE else View.GONE
            removeBtn.setOnClickListener { onRemove(item) }

            // Click listeners
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener { onLongClick(item); true }

            // Focus handling
            setupFocusHandling(itemView, item)
        }
    }

    // --- Focus ---

    private fun setupFocusHandling(view: View, item: FavoriteDisplayItem) {
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()
            }
            // Debounced focus effects (60ms)
            focusHandler.removeCallbacksAndMessages(v)
            if (hasFocus) {
                focusHandler.postDelayed({
                    v.background = v.context.getDrawable(R.drawable.bg_favorite_card_focused)
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                    // Overlay drawables from tags
                    val glow = v.getTag(R.id.focus_glow_drawable) as? GoldGlowFocusDrawable
                    val brackets = v.getTag(R.id.focus_bracket_drawable) as? FocusBracketDrawable
                    v.overlay.clear()
                    glow?.let {
                        it.setBounds(0, 0, v.width, v.height)
                        v.overlay.add(it)
                    }
                    brackets?.let {
                        it.setBounds(0, 0, v.width, v.height)
                        v.overlay.add(it)
                    }
                    // Load full-res image on sustained focus
                    if (viewMode == ViewMode.GRID) {
                        val image = v.findViewById<ImageView>(R.id.fav_image)
                        if (image != null) {
                            ProgressiveImageLoader.loadFullRes(image, item.icon, 14f)
                        }
                    }
                }, 60)
            } else {
                focusHandler.postDelayed({
                    v.background = v.context.getDrawable(R.drawable.bg_favorite_card)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    v.overlay.clear()
                }, 60)
            }
        }
    }

    // --- DiffUtil ---

    class FavoriteDiffCallback : DiffUtil.ItemCallback<FavoriteDisplayItem>() {
        override fun areItemsTheSame(old: FavoriteDisplayItem, new: FavoriteDisplayItem): Boolean {
            return old.id == new.id
        }

        override fun areContentsTheSame(old: FavoriteDisplayItem, new: FavoriteDisplayItem): Boolean {
            return old == new
        }
    }
}
