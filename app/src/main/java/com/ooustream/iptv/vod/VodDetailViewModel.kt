package com.ooustream.iptv.vod

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.model.VodInfo
import com.ooustream.iptv.data.repository.ContentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VodDetailViewModel @Inject constructor(
    private val contentRepository: ContentRepository
) : BaseViewModel() {

    private val _vodInfo = MutableStateFlow<VodInfo?>(null)
    val vodInfo: StateFlow<VodInfo?> = _vodInfo.asStateFlow()

    fun loadVodInfo(vodId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val info = contentRepository.getVodInfo(vodId)
                _vodInfo.value = info
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load movie info")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun buildStreamUrl(streamId: Int, ext: String): String {
        return contentRepository.buildVodStreamUrl(streamId, ext)
    }
}
