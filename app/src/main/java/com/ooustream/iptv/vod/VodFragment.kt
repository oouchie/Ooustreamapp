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
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.PosterItem
import com.ooustream.iptv.common.PosterPresenter
import com.ooustream.iptv.common.PosterSkeletonPresenter
import com.ooustream.iptv.common.TransitionDirection
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VodFragment : Fragment(), KeyEventHandler {

    private val viewModel: VodViewModel by viewModels()
    private var searchFilter = ""
    private var filteredMovies: List<com.ooustream.iptv.data.model.VodStream> = emptyList()

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

        navHints.text = "OK: Play Movie \u2022 Long-press: Favorite \u2022 Back: Home"

        categoriesList.layoutManager = LinearLayoutManager(requireContext())

        // Poster grid: 5 columns
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
                        viewModel.toggleFavorite(filteredMovies[pos])
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
                updateMovieList(posterAdapter)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Observe categories
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collect { updateCategoryList(categoriesList) }
            }
        }

        // Observe movies
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.movies.collect { movies ->
                    updateMovieList(posterAdapter)
                    // Swap from skeleton to real adapter on first data arrival
                    if (!skeletonSwapped && movies.isNotEmpty()) {
                        posterGrid.adapter = posterBridgeAdapter
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

    private fun updateMovieList(posterAdapter: ArrayObjectAdapter) {
        val movies = viewModel.movies.value
        filteredMovies = if (searchFilter.isEmpty()) {
            movies
        } else {
            movies.filter { it.name.lowercase().contains(searchFilter) }
        }
        posterAdapter.clear()
        filteredMovies.forEach { movie ->
            posterAdapter.add(PosterItem(
                id = movie.streamId,
                title = movie.name,
                imageUrl = movie.streamIcon,
                rating = movie.rating,
                extension = movie.containerExtension,
                type = "vod"
            ))
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
                    VodViewModel.FAVORITES_ID -> emoji.setTextColor(0xFFEF4444.toInt())
                    VodViewModel.NEW_RELEASES_ID -> emoji.setTextColor(0xFF3B82F6.toInt())
                    VodViewModel.RECENTLY_ADDED_ID -> emoji.setTextColor(0xFF10B981.toInt())
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
