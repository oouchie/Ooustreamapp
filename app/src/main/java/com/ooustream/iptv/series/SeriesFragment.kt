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

import com.ooustream.iptv.common.DeviceUtils
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
import com.ooustream.iptv.common.CategoryListAdapter
import com.ooustream.iptv.common.ContentInfoHelper
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.common.PosterSkeletonPresenter
import com.ooustream.iptv.data.repository.ContentRepository
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SeriesFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var contentRepository: ContentRepository

    private val viewModel: SeriesViewModel by viewModels()
    private var contentInfoHelper: ContentInfoHelper? = null
    private var searchFilter = ""
    private var searchOpen = false
    private var filteredSeries: List<com.ooustream.iptv.data.model.Series> = emptyList()
    private var categoryAdapter: CategoryListAdapter? = null
    private var updatePending = false

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

        navHints.text = "OK: Series Detail \u2022 Long-press: More Info \u2022 Back: Home"

        // Mobile: keep the header visible — its search icon is the ONLY in-screen series filter
        // (the bottom nav's Search tab is a different global search). Hide just the decorative
        // branding. Touch passthrough on the Leanback grid (intercepts first tap for focus).
        if (!DeviceUtils.isTV(requireContext())) {
            navHints.visibility = View.GONE
            view.findViewById<View>(R.id.header_center_logo)?.visibility = View.GONE
            view.findViewById<View>(R.id.header_title)?.visibility = View.GONE
            posterGrid.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            posterGrid.isFocusableInTouchMode = false
        }

        // Content info overlay for long-press
        val infoHelper = ContentInfoHelper(this, contentRepository) { item ->
            val series = filteredSeries.find { it.seriesId == item.id } ?: return@ContentInfoHelper
            val fragment = SeriesDetailFragment.newInstance(series.seriesId, series.name)
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, fragment)
                .addToBackStack(null)
                .commit()
        }
        infoHelper.attach(view as ViewGroup)
        infoHelper.setOnFavorite { item ->
            val series = filteredSeries.find { it.seriesId == item.id }
            if (series != null) viewModel.toggleFavorite(series)
        }
        contentInfoHelper = infoHelper

        categoriesList.layoutManager = LinearLayoutManager(requireContext())
        categoriesList.setHasFixedSize(true)
        categoriesList.setItemViewCacheSize(20)
        categoryAdapter = CategoryListAdapter { cat ->
            viewModel.selectCategory(cat.id)
            updateCategoryList(categoriesList)
        }
        categoriesList.adapter = categoryAdapter

        // Force 4 columns on TV — a Fire TV's smallestWidth (~540dp) matches the
        // values-sw320dp bucket (2 cols), which outranks the -television qualifier.
        val posterColumns = if (DeviceUtils.isTV(requireContext())) 4
                            else resources.getInteger(R.integer.poster_columns)
        posterGrid.setNumColumns(posterColumns)
        posterGrid.setHorizontalSpacing(resources.getDimensionPixelSize(R.dimen.poster_grid_h_spacing))
        posterGrid.setVerticalSpacing(resources.getDimensionPixelSize(R.dimen.poster_grid_v_spacing))
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
                    val pos = viewHolder.bindingAdapterPosition
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
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos >= 0 && pos < filteredSeries.size) {
                        val s = filteredSeries[pos]
                        contentInfoHelper?.onLongPress?.invoke(PosterItem(
                            id = s.seriesId,
                            title = s.name,
                            imageUrl = s.cover,
                            rating = s.rating,
                            extension = null,
                            type = "series"
                        ))
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
                viewModel.categories.collect {
                    updateCategoryList(categoriesList)
                    if (viewModel.savedCategoryPosition >= 0) {
                        categoriesList.scrollToPosition(viewModel.savedCategoryPosition)
                        viewModel.savedCategoryPosition = -1
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.seriesList.collect { series ->
                    updateSeriesList(posterAdapter)
                    // Swap from skeleton to real adapter on first data arrival
                    if (!skeletonSwapped && series.isNotEmpty()) {
                        posterGrid.adapter = posterBridgeAdapter
                        skeletonSwapped = true
                        val restorePos = viewModel.savedGridPosition
                        posterGrid.post {
                            val maxPos = (posterAdapter.size() - 1).coerceAtLeast(0)
                            posterGrid.selectedPosition = (if (restorePos >= 0) restorePos else 0).coerceIn(0, maxPos)
                            viewModel.savedGridPosition = -1
                        }
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
        val grid = view?.findViewById<VerticalGridView>(R.id.series_grid) ?: return
        if (grid.isComputingLayout) {
            if (!updatePending) {
                updatePending = true
                grid.post {
                    updatePending = false
                    if (isAdded) updateSeriesListInternal(posterAdapter, grid)
                }
            }
            return
        }
        updateSeriesListInternal(posterAdapter, grid)
    }

    private fun updateSeriesListInternal(posterAdapter: ArrayObjectAdapter, grid: VerticalGridView) {
        val series = viewModel.seriesList.value
        filteredSeries = if (searchFilter.isEmpty()) {
            series
        } else {
            series.filter { it.name.lowercase().contains(searchFilter) }
        }
        val savedPos = grid.selectedPosition
        val newItems = filteredSeries.map { s ->
            PosterItem(
                id = s.seriesId,
                title = s.name,
                imageUrl = s.cover,
                rating = s.rating,
                extension = null,
                type = "series",
                tmdbId = s.tmdbId
            )
        }
        posterAdapter.setItems(newItems, null)
        // Leanback GridLayoutManager crash guard (IndexOutOfBoundsException: Invalid item position -1).
        // setItems(..., null) calls notifyDataSetChanged(), which schedules a DEFERRED layout pass.
        // On the empty -> populated transition (common on slow devices that emit a loading-empty list
        // mid category switch) leanback's mFocusPosition is left at NO_POSITION (-1); the deferred
        // layout then calls createItem(-1) -> IOOBE. Force a valid, in-bounds focus index SYNCHRONOUSLY
        // here so leanback always lays out from a real position. Must be synchronous (not grid.post{})
        // or the posted/parent layout can win the race and still observe -1.
        if (newItems.isNotEmpty()) {
            val target = (if (savedPos >= 0) savedPos else 0).coerceIn(0, newItems.size - 1)
            try { grid.selectedPosition = target } catch (_: Exception) { }
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

        val emojiColors = mapOf(
            SeriesViewModel.FAVORITES_ID to 0xFFEF4444.toInt(),
            SeriesViewModel.RECENTLY_ADDED_ID to 0xFF10B981.toInt()
        )
        categoryAdapter?.updateData(cats, viewModel.selectedCategoryId.value, emojiColors)
    }

    override fun onDestroyView() {
        // Save focus positions for restoration on back navigation
        view?.findViewById<VerticalGridView>(R.id.series_grid)?.let {
            viewModel.savedGridPosition = it.selectedPosition
        }
        view?.findViewById<RecyclerView>(R.id.series_categories_list)?.let {
            viewModel.savedCategoryPosition =
                (it.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: -1
        }
        contentInfoHelper?.cleanup()
        contentInfoHelper = null
        super.onDestroyView()
    }

    override fun onKeyEvent(keyCode: Int): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (contentInfoHelper?.dismiss() == true) return true
            if (searchOpen) {
                closeSearch()
                return true
            }
        }
        return false
    }

    private fun closeSearch() {
        searchOpen = false
        val v = view ?: return
        v.findViewById<EditText>(R.id.header_search_input)?.let {
            it.setText("")
            it.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.header_title)?.visibility = View.VISIBLE
        v.findViewById<android.widget.TextClock>(R.id.header_clock)?.visibility = View.VISIBLE
        v.findViewById<ImageView>(R.id.header_center_logo)?.visibility = View.VISIBLE
    }
}
