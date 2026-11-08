package com.ooustream.iptv.vod

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.VodInfo
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VodDetailViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val contentFilterManager: com.ooustream.iptv.parental.ContentFilterManager
) : BaseViewModel() {

    private val _vodInfo = MutableStateFlow<VodInfo?>(null)
    val vodInfo: StateFlow<VodInfo?> = _vodInfo.asStateFlow()

    private val _watchProgress = MutableStateFlow<WatchProgressEntity?>(null)
    val watchProgress: StateFlow<WatchProgressEntity?> = _watchProgress.asStateFlow()

    /** "More like this": same category, best rated first, parental filter applied, self excluded. */
    private val _similar = MutableStateFlow<List<com.ooustream.iptv.data.model.VodStream>>(emptyList())
    val similar: StateFlow<List<com.ooustream.iptv.data.model.VodStream>> = _similar.asStateFlow()

    private var loadedVodId: Int = 0

    fun loadVodInfo(vodId: Int) {
        loadedVodId = vodId
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val info = contentRepository.getVodInfo(vodId)
                _vodInfo.value = info
                // Load watch progress alongside
                loadWatchProgress(vodId)
                loadSimilar(vodId, info.movieData?.categoryId)
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load movie info")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadSimilar(vodId: Int, categoryId: String?) {
        if (categoryId.isNullOrBlank()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                // Parental controls apply here too — a blocked category must never leak in
                // through a side door. Every title here shares the movie's category, so one
                // check covers the list.
                if (contentFilterManager.filterContent("vod", listOf(categoryId)) { it }.isEmpty()) {
                    _similar.value = emptyList()
                    return@launch
                }
                // Streamed and kept to the best SIMILAR_COUNT: a category can hold 125k titles
                // (bp-v2 adult categories, 2026-11-04), and holding the whole list here while the
                // category grid holds it too ran the heap out.
                val best = java.util.PriorityQueue<Pair<VodStream, Double>>(
                    SIMILAR_COUNT + 1, compareBy { it.second }
                )
                contentRepository.streamVodStreams(categoryId) { movie ->
                    if (movie.streamId == vodId) return@streamVodStreams
                    val score = movie.rating5based ?: movie.rating?.toDoubleOrNull()?.div(2) ?: 0.0
                    if (best.size < SIMILAR_COUNT) {
                        best.add(movie to score)
                    } else if (score > best.peek()!!.second) {
                        best.poll()
                        best.add(movie to score)
                    }
                }
                _similar.value = best.sortedByDescending { it.second }.map { it.first }
            } catch (_: Exception) {
                _similar.value = emptyList()
            }
        }
    }

    private suspend fun loadWatchProgress(vodId: Int) {
        try {
            _watchProgress.value = watchProgressRepository.getProgress(vodId.toString())
        } catch (_: Exception) { }
    }

    /** Called from Fragment onResume to refresh after returning from player */
    fun refreshWatchProgress() {
        if (loadedVodId == 0) return
        viewModelScope.launch { loadWatchProgress(loadedVodId) }
    }

    fun buildStreamUrl(streamId: Int, ext: String): String {
        return contentRepository.buildVodStreamUrl(streamId, ext)
    }

    private companion object {
        const val SIMILAR_COUNT = 20
    }
}
