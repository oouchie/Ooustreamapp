package com.ooustream.iptv.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stable RecyclerView adapter for category lists. Reuses views across data updates
 * instead of recreating the entire adapter, enabling smooth D-pad scrolling.
 *
 * Background state is handled by StateListDrawable (bg_category_states.xml) —
 * the framework manages focus/selected transitions with cached drawables,
 * so there are ZERO setBackgroundResource() calls during scrolling.
 */
class CategoryListAdapter(
    private val onCategorySelected: (CategoryItem) -> Unit
) : RecyclerView.Adapter<CategoryListAdapter.CategoryViewHolder>() {

    private var items: List<CategoryItem> = emptyList()
    private var selectedId: String? = null
    /** Map of special category IDs to their emoji color (e.g. Favorites -> red). */
    private var specialEmojiColors: Map<String, Int> = emptyMap()

    fun updateData(newItems: List<CategoryItem>, newSelectedId: String?, emojiColors: Map<String, Int> = emptyMap()) {
        val oldItems = items
        val oldSelectedId = selectedId
        items = newItems
        selectedId = newSelectedId
        specialEmojiColors = emojiColors
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(old: Int, new: Int) = oldItems[old].id == newItems[new].id
            override fun areContentsTheSame(old: Int, new: Int) =
                oldItems[old] == newItems[new] && (oldSelectedId == selectedId || (oldItems[old].id != oldSelectedId && newItems[new].id != selectedId))
        })
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        // Explicit focus target — bypasses geometric search which fails when scrolled
        view.nextFocusRightId = R.id.channels_list
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val cat = items[position]
        holder.bind(cat, selectedId == cat.id, specialEmojiColors[cat.id])
    }

    override fun getItemCount(): Int = items.size

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root = itemView as LinearLayout
        private val name: TextView = root.findViewById(R.id.category_name)
        private val count: TextView = root.findViewById(R.id.category_count)
        private val emoji: TextView = root.findViewById(R.id.category_emoji)
        private var focusJob: Job? = null

        fun bind(cat: CategoryItem, isSelected: Boolean, emojiColor: Int?) {
            name.text = cat.name
            count.text = if (cat.count > 0) cat.count.toString() else ""
            emoji.text = CategoryEmoji.get(cat.name)
            emoji.setTextColor(emojiColor ?: 0xFFFFFFFF.toInt())

            // Phone: strip focusability so a finger-tap selects on the FIRST tap. The XML
            // focusableInTouchMode is for D-pad on TV; on a touchscreen it makes the first tap
            // only move focus (the "have to double tap" complaint).
            if (!DeviceUtils.isTV(root.context)) {
                root.isFocusable = false
                root.isFocusableInTouchMode = false
            }

            // Drive the StateListDrawable: selected state handled by framework
            root.isSelected = isSelected
            name.setTextColor(if (isSelected) 0xFFFFC107.toInt() else 0xFF9CA3AF.toInt())

            root.setOnFocusChangeListener { v, hasFocus ->
                focusJob?.cancel()

                if (hasFocus) {
                    // Background handled by StateListDrawable — instant, no inflation
                    name.setTextColor(0xFFFFC107.toInt())

                    // Debounce scale animation — skip during rapid scrolling
                    focusJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(60)
                        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).start()
                    }
                } else {
                    // Background handled by StateListDrawable — instant, no inflation
                    name.setTextColor(if (isSelected) 0xFFFFC107.toInt() else 0xFF9CA3AF.toInt())
                    v.animate().cancel()
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            }

            itemView.setOnClickListener {
                onCategorySelected(cat)
            }
        }
    }
}
