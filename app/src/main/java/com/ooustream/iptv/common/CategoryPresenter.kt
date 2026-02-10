package com.ooustream.iptv.common

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R
import com.ooustream.iptv.data.model.Category

data class CategoryItem(
    val id: String,
    val name: String,
    val count: Int = 0,
    val iconRes: Int? = null,
    val accentColor: Int = Color.WHITE,
    val isSpecial: Boolean = false
)

class CategoryPresenter(
    private val selectedId: () -> String?
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cat = item as CategoryItem
        val root = viewHolder.view as LinearLayout
        val icon = root.findViewById<ImageView>(R.id.category_icon)
        val name = root.findViewById<TextView>(R.id.category_name)
        val count = root.findViewById<TextView>(R.id.category_count)

        name.text = cat.name
        count.text = if (cat.count > 0) cat.count.toString() else ""

        if (cat.iconRes != null) {
            icon.setImageResource(cat.iconRes)
            icon.setColorFilter(cat.accentColor)
            icon.visibility = android.view.View.VISIBLE
        } else {
            icon.visibility = android.view.View.GONE
        }

        val isSelected = selectedId() == cat.id
        if (isSelected) {
            root.setBackgroundColor(Color.parseColor("#263244"))
            name.setTextColor(Color.parseColor("#FFC107"))
        } else {
            root.setBackgroundColor(Color.parseColor("#1F2937"))
            name.setTextColor(Color.WHITE)
        }

        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundColor(Color.parseColor("#263244"))
            } else if (!isSelected) {
                v.setBackgroundColor(Color.parseColor("#1F2937"))
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as LinearLayout
        root.setOnFocusChangeListener(null)
    }
}
