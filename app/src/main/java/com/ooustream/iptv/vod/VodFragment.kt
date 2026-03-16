package com.ooustream.iptv.vod

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
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.common.PosterSkeletonPresenter
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.repository.ContentRepository
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VodFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var contentRepository: ContentRepository

    private val viewModel: VodViewModel by viewModels()
    private var contentInfoHelper: ContentInfoHelper? = null
    private var searchFilter = ""
    private var searchOpen = false
    private var filteredMovies: List<com.ooustream.iptv.data.model.VodStream> = emptyList()
    private var categoryAdapter: CategoryListAdapter? = null
    private var updatePending = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vod, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set branded header title and color
        view.findViewById<TextView>(R.id.header_title)?.let {
            it.text = getString(R.string.movies)
            it.setTextColor(resources.getColor(R.color.movies_purple, null))
        }

        val categoriesList = view.findViewById<RecyclerView>(R.id.vod_categories_list)
        val posterGrid = view.findViewById<VerticalGridView>(R.id.vod_grid)
        val navHints = view.findViewById<TextView>(R.id.vod_nav_hints)

        // Header search
        val headerTitle = view.findViewById<TextView>(R.id.header_title)
        val headerClock = view.findViewById<TextClock>(R.id.header_clock)
        val headerSearchIcon = view.findViewById<ImageView>(R.id.header_search_icon)
        val headerSearchInput = view.findViewById<EditText>(R.id.header_search_input)

        navHints.text = "OK: Play Movie \u2022 Long-press: More Info \u2022 Back: Home"

        // Hide TV-only elements on mobile
        if (!DeviceUtils.isTV(requireContext())) {
            navHints.visibility = View.GONE
            view.findViewById<View>(R.id.frosted_header)?.visibility = View.GONE
        }

        // Content info overlay for long-press
        val infoHelper = ContentInfoHelper(this, contentRepository) { item ->
            val movie = filteredMovies.find { it.streamId == item.id } ?: return@ContentInfoHelper
            val fragment = VodDetailFragment.newInstance(
                vodId = movie.streamId,
                vodName = movie.name,
                coverUrl = movie.streamIcon,
                containerExtension = movie.containerExtension
            )
            requireActivity().supportFragmentManager.beginTransaction()
                .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
                .replace(R.id.main_container, fragment)
                .addToBackStack(null)
                .commit()
        }
        infoHelper.attach(view as ViewGroup)
        infoHelper.setOnFavorite { item ->
            val movie = filteredMovies.find { it.streamId == item.id }
            if (movie != null) viewModel.toggleFavorite(movie)
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

        // Poster grid: column count from resources (4 on TV, 3 on phone)
        posterGrid.setNumColumns(resources.getInteger(R.integer.poster_columns))
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
                    val pos = viewHolder.adapterPosition
                    if (pos < 0) return@setOnClickListener
                    if (pos >= filteredMovies.size) return@setOnClickListener
                    val movie = filteredMovies[pos]
                    val fragment = VodDetailFragment.newInstance(
                        vodId = movie.streamId,
                        vodName = movie.name,
                        coverUrl = movie.streamIcon,
                        containerExtension = movie.containerExtension
                    )
                    requireActivity().supportFragmentManager.beginTransaction()
                        .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
                        .replace(R.id.main_container, fragment)
                        .addToBackStack(null)
                        .commit()
                }
                viewHolder.itemView.setOnLongClickListener {
                    val pos = viewHolder.adapterPosition
                    if (pos >= 0 && pos < filteredMovies.size) {
                        val movie = filteredMovies[pos]
                        contentInfoHelper?.onLongPress?.invoke(PosterItem(
                            id = movie.streamId,
                            title = movie.name,
                            imageUrl = movie.streamIcon,
                            rating = movie.rating,
                            extension = movie.containerExtension,
                            type = "vod"
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
                updateMovieList(posterAdapter)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Observe categories
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

        // Observe movies
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.movies.collect { movies ->
                    // Load watch progress for the new movie list
                    viewModel.loadWatchProgress(movies.map { it.streamId })
                    updateMovieList(posterAdapter)
                    // Swap from skeleton to real adapter on first data arrival
                    if (!skeletonSwapped && movies.isNotEmpty()) {
                        posterGrid.adapter = posterBridgeAdapter
                        skeletonSwapped = true
                        if (viewModel.savedGridPosition >= 0) {
                            posterGrid.post {
                                posterGrid.selectedPosition = viewModel.savedGridPosition
                                viewModel.savedGridPosition = -1
                            }
                        }
                    }
                }
            }
        }

        // Observe watch progress changes → rebind posters with updated state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.watchProgressMap.collect {
                    if (filteredMovies.isNotEmpty()) {
                        updateMovieList(posterAdapter)
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

    private fun updateMovieList(posterAdapter: ArrayObjectAdapter) {
        val grid = view?.findViewById<VerticalGridView>(R.id.vod_grid) ?: return
        if (grid.isComputingLayout) {
            // Defer if grid is mid-layout to prevent IndexOutOfBoundsException
            if (!updatePending) {
                updatePending = true
                grid.post {
                    updatePending = false
                    if (isAdded) updateMovieListInternal(posterAdapter, grid)
                }
            }
            return
        }
        updateMovieListInternal(posterAdapter, grid)
    }

    private fun updateMovieListInternal(posterAdapter: ArrayObjectAdapter, grid: VerticalGridView) {
        val movies = viewModel.movies.value
        val progressMap = viewModel.watchProgressMap.value
        filteredMovies = if (searchFilter.isEmpty()) {
            movies
        } else {
            movies.filter { it.name.lowercase().contains(searchFilter) }
        }
        val savedPos = grid.selectedPosition
        val newItems = filteredMovies.map { movie ->
            val progress = progressMap[movie.streamId.toString()]
            PosterItem(
                id = movie.streamId,
                title = movie.name,
                imageUrl = movie.streamIcon,
                rating = movie.rating,
                extension = movie.containerExtension,
                type = "vod",
                watchCompleted = progress?.completed == true,
                watchProgress = progress?.progressPercent ?: 0f,
                tmdbId = movie.tmdbId
            )
        }
        // Use atomic notifyDataSetChanged (null DiffCallback) to prevent
        // VerticalGridView position -1 crash from granular diff notifications
        posterAdapter.setItems(newItems, null)
        if (newItems.isNotEmpty() && savedPos >= 0) {
            grid.selectedPosition = savedPos.coerceAtMost(newItems.size - 1)
        }
    }

    private fun updateCategoryList(recyclerView: RecyclerView) {
        val favoritesCat = CategoryItem(VodViewModel.FAVORITES_ID, "Favorites")
        val newReleasesCat = CategoryItem(VodViewModel.NEW_RELEASES_ID, "New Releases")
        val recentlyAddedCat = CategoryItem(VodViewModel.RECENTLY_ADDED_ID, "Recently Added")
        val apiCats = viewModel.categories.value
            .filter { searchFilter.isEmpty() || it.categoryName.lowercase().contains(searchFilter) }
            .map { CategoryItem(it.categoryId, it.categoryName) }

        val virtualCats = mutableListOf<CategoryItem>()
        if (searchFilter.isEmpty() || "favorites".contains(searchFilter)) virtualCats.add(favoritesCat)
        if (searchFilter.isEmpty() || "new releases".contains(searchFilter)) virtualCats.add(newReleasesCat)
        if (searchFilter.isEmpty() || "recently added".contains(searchFilter)) virtualCats.add(recentlyAddedCat)
        val cats = virtualCats + apiCats

        val emojiColors = mapOf(
            VodViewModel.FAVORITES_ID to 0xFFEF4444.toInt(),
            VodViewModel.NEW_RELEASES_ID to 0xFF3B82F6.toInt(),
            VodViewModel.RECENTLY_ADDED_ID to 0xFF10B981.toInt()
        )
        categoryAdapter?.updateData(cats, viewModel.selectedCategoryId.value, emojiColors)
    }

    override fun onResume() {
        super.onResume()
        // Refresh watch progress when returning from player
        val movieIds = filteredMovies.map { it.streamId }
        if (movieIds.isNotEmpty()) {
            viewModel.loadWatchProgress(movieIds)
        }
    }

    override fun onDestroyView() {
        // Save focus positions for restoration on back navigation
        view?.findViewById<VerticalGridView>(R.id.vod_grid)?.let {
            viewModel.savedGridPosition = it.selectedPosition
        }
        view?.findViewById<RecyclerView>(R.id.vod_categories_list)?.let {
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
