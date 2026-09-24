package com.ooustream.iptv.vod

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.VodInfo
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
                val list = contentRepository.getVodStreams(categoryId)
                // Parental controls apply here too — a blocked category must never leak in
                // through a side door. (Same category as the movie, but the filter is the
                // single source of truth, and it also covers the temporary-unlock window.)
                val allowed = contentFilterManager.filterContent("vod", list) { it.categoryId }
                _similar.value = allowed.asSequence()
                    .filter { it.streamId != vodId }
                    .map { it to (it.rating5based ?: it.rating?.toDoubleOrNull()?.div(2) ?: 0.0) }
                    .sortedByDescending { it.second }
                    .map { it.first }
                    .take(20)
                    .toList()
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
}
