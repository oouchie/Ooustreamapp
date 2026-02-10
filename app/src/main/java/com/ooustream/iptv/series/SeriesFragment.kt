package com.ooustream.iptv.series

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.CategoryEmoji
import com.ooustream.iptv.common.CategoryItem
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.common.PosterSkeletonPresenter
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SeriesFragment : Fragment(), KeyEventHandler {

    private val viewModel: SeriesViewModel by viewModels()
    private var searchFilter = ""
    private var filteredSeries: List<com.ooustream.iptv.data.model.Series> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_series, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set branded header title and color
        view.findViewById<TextView>(R.id.header_title)?.let {
            it.text = getString(R.string.series)
            it.setTextColor(resources.getColor(R.color.series_amber, null))
        }

        val categoriesList = view.findViewById<RecyclerView>(R.id.series_categories_list)
        val posterGrid = view.findViewById<VerticalGridView>(R.id.series_grid)
        val navHints = view.findViewById<TextView>(R.id.series_nav_hints)

        // Header search
        val headerTitle = view.findViewById<TextView>(R.id.header_title)
        val headerClock = view.findViewById<TextClock>(R.id.header_clock)
        val headerSearchIcon = view.findViewById<ImageView>(R.id.header_search_icon)
        val headerSearchInput = view.findViewById<EditText>(R.id.header_search_input)

        navHints.text = "OK: Series Detail \u2022 Long-press: Favorite \u2022 Back: Home"

        categoriesList.layoutManager = LinearLayoutManager(requireContext())

        posterGrid.setNumColumns(5)
        val posterAdapter = ArrayObjectAdapter(PosterPresenter())

        // Show shimmer skeletons while loading
        val skeletonAdapter = ArrayObjectAdapter(PosterSkeletonPresenter())
        repeat(10) { skeletonAdapter.add(Unit) }
        posterGrid.adapter = ItemBridgeAdapter(skeletonAdapter)

        // Build real poster bridge adapter with click listeners
        val posterBridgeAdapter = ItemBridgeAdapter(posterAdapter)
        posterBridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos < 0) return@setOnClickListener
                    if (pos >= filteredSeries.size) return@setOnClickListener
                    val series = filteredSeries[pos]
                    val fragment = SeriesDetailFragment.newInstance(series.seriesId, series.name)
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(R.id.main_container, fragment)
                        .addToBackStack(null)
                        .commit()
                }
                viewHolder.itemView.setOnLongClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < filteredSeries.size) {
                        viewModel.toggleFavorite(filteredSeries[pos])
                    }
                    true
                }
            }
        })
        var skeletonSwapped = false

        // Search icon focus highlight
        val centerLogo = view.findViewById<ImageView>(R.id.header_center_logo)
        headerSearchIcon.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200).start()
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setStroke(2, 0xFFFFC107.toInt())
                    setColor(0x33FFC107)
                }
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                v.background = null
            }
        }

        // Header search icon toggle
        var searchOpen = false
        headerSearchIcon.setOnClickListener {
            searchOpen = !searchOpen
            if (searchOpen) {
                headerTitle?.visibility = View.GONE
                headerClock?.visibility = View.GONE
                centerLogo?.visibility = View.GONE
                headerSearchInput.visibility = View.VISIBLE
                headerSearchInput.requestFocus()
            } else {
                headerSearchInput.setText("")
                headerSearchInput.visibility = View.GONE
                headerTitle?.visibility = View.VISIBLE
                headerClock?.visibility = View.VISIBLE
                centerLogo?.visibility = View.VISIBLE
            }
        }

        // Header search filter
        headerSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchFilter = s?.toString()?.lowercase() ?: ""
                updateCategoryList(categoriesList)
                updateSeriesList(posterAdapter)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collect { updateCategoryList(categoriesList) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.seriesList.collect { series ->
                    updateSeriesList(posterAdapter)
                    // Swap from skeleton to real adapter on first data arrival
                    if (!skeletonSwapped && series.isNotEmpty()) {
                        posterGrid.adapter = posterBridgeAdapter
                        posterGrid.selectedPosition = 0
                        skeletonSwapped = true
                    }
                }
            }
        }

        // Observe toast events (favorites toggle)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toastEvent.collect { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.loadCategories()
    }

    private fun updateSeriesList(posterAdapter: ArrayObjectAdapter) {
        val series = viewModel.seriesList.value
        filteredSeries = if (searchFilter.isEmpty()) {
            series
        } else {
            series.filter { it.name.lowercase().contains(searchFilter) }
        }
        posterAdapter.clear()
        filteredSeries.forEach { s ->
            posterAdapter.add(PosterItem(
                id = s.seriesId,
                title = s.name,
                imageUrl = s.cover,
                rating = s.rating,
                extension = null,
                type = "series"
            ))
        }
    }

    private fun updateCategoryList(recyclerView: RecyclerView) {
        val favoritesCat = CategoryItem(SeriesViewModel.FAVORITES_ID, "Favorites")
        val recentlyAddedCat = CategoryItem(SeriesViewModel.RECENTLY_ADDED_ID, "Recently Added")
        val apiCats = viewModel.categories.value
            .filter { searchFilter.isEmpty() || it.categoryName.lowercase().contains(searchFilter) }
            .map { CategoryItem(it.categoryId, it.categoryName) }

        val virtualCats = mutableListOf<CategoryItem>()
        if (searchFilter.isEmpty() || "favorites".contains(searchFilter)) virtualCats.add(favoritesCat)
        if (searchFilter.isEmpty() || "recently added".contains(searchFilter)) virtualCats.add(recentlyAddedCat)
        val cats = virtualCats + apiCats

        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val cat = cats[position]
                val root = holder.itemView as android.widget.LinearLayout
                val name = root.findViewById<android.widget.TextView>(R.id.category_name)
                val count = root.findViewById<android.widget.TextView>(R.id.category_count)
                val emoji = root.findViewById<android.widget.TextView>(R.id.category_emoji)
                name.text = cat.name
                count.text = if (cat.count > 0) cat.count.toString() else ""
                emoji.text = CategoryEmoji.get(cat.name)

                // Special category colored emoji
                when (cat.id) {
                    SeriesViewModel.FAVORITES_ID -> emoji.setTextColor(0xFFEF4444.toInt())
                    SeriesViewModel.RECENTLY_ADDED_ID -> emoji.setTextColor(0xFF10B981.toInt())
                    else -> emoji.setTextColor(0xFFFFFFFF.toInt())
                }

                val isSelected = viewModel.selectedCategoryId.value == cat.id
                if (isSelected) {
                    root.setBackgroundResource(R.drawable.bg_sidebar_item_active)
                    name.setTextColor(0xFFFFC107.toInt())
                } else {
                    root.setBackgroundResource(R.drawable.bg_category_aurora)
                    name.setTextColor(0xFF9CA3AF.toInt())
                }
                root.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.setBackgroundResource(R.drawable.bg_sidebar_item_focused_v2)
                        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).start()
                        name.setTextColor(0xFFFFC107.toInt())
                    } else {
                        if (isSelected) {
                            v.setBackgroundResource(R.drawable.bg_sidebar_item_active)
                            name.setTextColor(0xFFFFC107.toInt())
                        } else {
                            v.setBackgroundResource(R.drawable.bg_category_aurora)
                            name.setTextColor(0xFF9CA3AF.toInt())
                        }
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }
                }
                holder.itemView.setOnClickListener {
                    viewModel.selectCategory(cat.id)
                    notifyDataSetChanged()
                }
            }
            override fun getItemCount() = cats.size
        }
    }

    override fun onKeyEvent(keyCode: Int): Boolean = false
}
