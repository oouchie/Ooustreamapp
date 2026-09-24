package com.ooustream.iptv.catchup

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.CatchUpProgramme
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.parental.ContentFilterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Catch-up (5.0): channels the provider archives (`tv_archive=1`) and their past programmes.
 * Parental filtering applies to the channel list exactly as on Live TV.
 */
@HiltViewModel
class CatchUpViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val contentFilterManager: ContentFilterManager
) : BaseViewModel() {

    data class ChannelRow(val stream: LiveStream, val categoryName: String)

    sealed interface ProgrammesState {
        data object Idle : ProgrammesState
        data object Loading : ProgrammesState
        data class Loaded(val channelId: Int, val programmes: List<CatchUpProgramme>) : ProgrammesState
        data object Failed : ProgrammesState
    }

    private val _channels = MutableStateFlow<List<ChannelRow>?>(null)
    /** null = still loading; empty = the provider archives nothing for this account. */
    val channels: StateFlow<List<ChannelRow>?> = _channels.asStateFlow()

    private val _programmes = MutableStateFlow<ProgrammesState>(ProgrammesState.Idle)
    val programmes: StateFlow<ProgrammesState> = _programmes.asStateFlow()

    /** Longest archive window the provider advertises, in days (0 = unknown). */
    var archiveDays = 0
        private set

    private var programmesJob: Job? = null
    private var shownChannelId = -1

    fun load() {
        if (_channels.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val categories = runCatching { contentRepository.getLiveCategories() }.getOrDefault(emptyList())
                    .associate { it.categoryId to it.categoryName }
                val all = contentRepository.getLiveStreams()
                val archived = all.filter { it.tvArchive == 1 }
                val allowed = contentFilterManager.filterContent("live", archived) { it.categoryId }
                archiveDays = allowed.maxOfOrNull { it.tvArchiveDuration ?: 0 } ?: 0
                _channels.value = allowed
                    .map { ChannelRow(it, categories[it.categoryId].orEmpty()) }
                    .sortedWith(compareBy({ it.categoryName.lowercase() }, { it.stream.name.lowercase() }))
            } catch (e: Exception) {
                _channels.value = emptyList()
                _error.emit(e.message ?: "Couldn't load catch-up channels")
            }
        }
    }

    /** Debounced: scrolling past channels doesn't fire a request for each one. */
    fun showChannel(channelId: Int, debounceMs: Long = 300L) {
        if (channelId == shownChannelId && _programmes.value is ProgrammesState.Loaded) return
        shownChannelId = channelId
        programmesJob?.cancel()
        programmesJob = viewModelScope.launch(Dispatchers.IO) {
            delay(debounceMs)
            _programmes.value = ProgrammesState.Loading
            _programmes.value = try {
                ProgrammesState.Loaded(channelId, contentRepository.getCatchUpProgrammes(channelId))
            } catch (_: Exception) {
                ProgrammesState.Failed
            }
        }
    }

    fun buildUrl(channelId: Int, programme: CatchUpProgramme): String =
        contentRepository.buildCatchUpUrl(channelId, programme)
}
