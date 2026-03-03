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
    private val watchProgressRepository: WatchProgressRepository
) : BaseViewModel() {

    private val _vodInfo = MutableStateFlow<VodInfo?>(null)
    val vodInfo: StateFlow<VodInfo?> = _vodInfo.asStateFlow()

    private val _watchProgress = MutableStateFlow<WatchProgressEntity?>(null)
    val watchProgress: StateFlow<WatchProgressEntity?> = _watchProgress.asStateFlow()

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
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load movie info")
            } finally {
                _isLoading.value = false
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
