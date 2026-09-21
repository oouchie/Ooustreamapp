package com.ooustream.iptv.livetv

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentCacheRepository
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.EpgCacheRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import com.ooustream.iptv.data.repository.RecentChannelsRepository
import com.ooustream.iptv.parental.ContentFilterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val epgCacheRepository: EpgCacheRepository,
    private val contentCacheRepository: ContentCacheRepository,
    private val contentFilterManager: ContentFilterManager,
    private val recentChannelsRepository: RecentChannelsRepository
) : BaseViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _channels = MutableStateFlow<List<LiveStream>>(emptyList())
    val channels: StateFlow<List<LiveStream>> = _channels.asStateFlow()

    /**
     * EPG state wrapper keyed by streamId so StateFlow dedup treats
     * different-channel-but-both-empty emissions as distinct. Without this
     * wrapper, `emptyList() == emptyList()` silently swallows the emission
     * on empty-EPG providers and the SmartEpgFiller fallback never fires.
     */
    data class EpgState(val streamId: Int, val programs: List<EpgProgram>)

    private val _epgState = MutableStateFlow(EpgState(-1, emptyList()))
    val epgState: StateFlow<EpgState> = _epgState.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<String?>(FAVORITES_ID)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    /**
     * Non-null ⇒ the channel pane is legitimately empty, and this is the reason to show.
     *
     * This is a separate signal on purpose. The skeleton→real adapter swap in the fragment is
     * gated on `channels.isNotEmpty()`, and a category that resolves to zero channels sets
     * emptyList() on a StateFlow already holding emptyList() — StateFlow dedups, the collector
     * never runs, and the shimmer rows would stay on screen forever (reading to a customer as
     * "Live TV never loads"). A null→message transition always emits distinctly.
     */
    private val _emptyState = MutableStateFlow<String?>(null)
    val emptyState: StateFlow<String?> = _emptyState.asStateFlow()

    val favorites = favoriteRepository.getAllFavorites()

    init {
        selectCategory(FAVORITES_ID)
    }

    /** Full preview state — saved before fullscreen, consumed on return to restart preview */
    var lastPreviewedChannel: LiveStream? = null
    var lastPreviewedUrl: String? = null
    var lastPreviewedIndex: Int = -1

    /** Saved positions for focus restoration on back navigation */
    var savedChannelPosition: Int = -1
    var savedCategoryPosition: Int = -1

    // Single-flight guards: re-entry (onViewCreated after back-stack) or rapid category
    // switching must not leave overlapping collectors writing _categories/_channels.
    private var loadCategoriesJob: Job? = null
    private var selectCategoryJob: Job? = null

    fun loadCategories() {
        loadCategoriesJob?.cancel()
        loadCategoriesJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                contentCacheRepository.getCategories("live").collect { categories ->
                    _categories.value = contentFilterManager.filterCategories("live", categories)
                    // Both fallbacks must pick from the FILTERED list. Using the raw collector
                    // parameter could auto-drop an empty-favorites user into a category parental
                    // controls has hidden — whose streams are then all filtered out, leaving an
                    // empty channel pane on first open.
                    if (_selectedCategoryId.value == FAVORITES_ID) {
                        val favCount = favoriteRepository.getFavoritesListByType("live").size
                        if (favCount == 0) {
                            _categories.value.firstOrNull()?.let { selectCategory(it.categoryId) }
                        }
                    } else if (_selectedCategoryId.value == null) {
                        _categories.value.firstOrNull()?.let { selectCategory(it.categoryId) }
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load categories")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectCategory(categoryId: String) {
        _selectedCategoryId.value = categoryId
        _emptyState.value = null
        selectCategoryJob?.cancel()
        selectCategoryJob = viewModelScope.launch {
            try {
                when (categoryId) {
                    FAVORITES_ID -> {
                        val favs = favoriteRepository.getFavoritesListByType("live")
                        _channels.value = favs.map { fav ->
                            LiveStream(
                                num = null,
                                name = fav.name,
                                streamType = "live",
                                streamId = fav.streamId,
                                streamIcon = fav.icon,
                                epgChannelId = null,
                                added = null,
                                categoryId = fav.categoryId,
                                customSid = null,
                                tvArchive = 0,
                                directSource = null,
                                tvArchiveDuration = null
                            )
                        }
                        _emptyState.value = if (_channels.value.isEmpty()) {
                            "No favourite channels yet\nPress and hold OK on a channel to add one"
                        } else null
                    }
                    RECENT_ID -> {
                        // A Flow, not a one-shot read. WatchSessionLogger's insert is
                        // fire-and-forget on a detached IO scope, dispatched from the PLAYER's
                        // onPause — i.e. while this screen is coming back — and onResume
                        // re-queries nothing. A suspend read loses that race and the channel the
                        // user just watched would be missing, which is the one thing this rail
                        // exists to show. Room's invalidation tracker re-emits whenever the
                        // insert lands. The selectCategoryJob?.cancel() above already tears this
                        // collector down on the next category selection; this call never returns
                        // — the collector IS the branch.
                        recentChannelsRepository.observeRecentLiveChannels().collect { list ->
                            _channels.value = list
                            _emptyState.value = if (list.isEmpty()) {
                                "Nothing watched yet\nChannels you watch for 30 seconds or more show up here"
                            } else null
                        }
                    }
                    else -> {
                        val streams = contentRepository.getLiveStreams(categoryId)
                        _channels.value =
                            contentFilterManager.filterContent("live", streams) { it.categoryId }
                        _emptyState.value = if (_channels.value.isEmpty()) {
                            "No channels in this category"
                        } else null
                    }
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load channels")
            }
        }
    }

    companion object {
        const val FAVORITES_ID = "__favorites__"

        /** Same literal MultiView's channel picker already ships — do not add a second spelling. */
        const val RECENT_ID = "__recent__"
    }

    fun loadEpg(streamId: Int) {
        viewModelScope.launch {
            val programs = try {
                epgCacheRepository.getEpg(streamId)
            } catch (e: Exception) {
                emptyList()
            }
            _epgState.value = EpgState(streamId, programs)
        }
    }

    fun toggleFavorite(channel: LiveStream) {
        viewModelScope.launch {
            val id = "live_${channel.streamId}"
            if (favoriteRepository.isFavorite(id)) {
                favoriteRepository.removeFavorite(id)
                _toastEvent.emit("Removed from Favorites")
            } else {
                favoriteRepository.addFavorite(
                    FavoriteEntity(
                        id = id,
                        streamId = channel.streamId,
                        type = "live",
                        name = channel.name,
                        icon = channel.streamIcon,
                        categoryId = channel.categoryId,
                        extra = null
                    )
                )
                _toastEvent.emit("Added to Favorites")
            }
        }
    }

    fun buildStreamUrl(streamId: Int): String = contentRepository.buildLiveStreamUrl(streamId)
}
