package com.ooustream.iptv.parental

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R

class CategoryToggleAdapter(
    private val onToggle: (CategoryToggleItem) -> Unit
) : ListAdapter<CategoryToggleItem, CategoryToggleAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_toggle, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val toggle: Switch = itemView.findViewById(R.id.toggle_switch)
        private val name: TextView = itemView.findViewById(R.id.category_name)
        private val badge: TextView = itemView.findViewById(R.id.blocked_badge)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onToggle(getItem(pos))
                }
            }
            // D-pad: OK/Enter toggles the category
            itemView.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onToggle(getItem(pos))
                    }
                    true
                } else false
            }
        }

        fun bind(item: CategoryToggleItem) {
            name.text = item.categoryName
            toggle.isChecked = !item.isBlocked

            if (item.isBlocked) {
                name.setTextColor(0x66FFFFFF)
                name.paintFlags = name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                badge.visibility = View.VISIBLE
                toggle.isChecked = false
            } else {
                name.setTextColor(0xFFFFFFFF.toInt())
                name.paintFlags = name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                badge.visibility = View.GONE
                toggle.isChecked = true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CategoryToggleItem>() {
            override fun areItemsTheSame(a: CategoryToggleItem, b: CategoryToggleItem) =
                a.categoryId == b.categoryId

            override fun areContentsTheSame(a: CategoryToggleItem, b: CategoryToggleItem) =
                a == b
        }
    }
}
