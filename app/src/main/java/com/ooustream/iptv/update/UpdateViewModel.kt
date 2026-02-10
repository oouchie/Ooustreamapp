package com.ooustream.iptv.update

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateService: UpdateService
) : BaseViewModel() {

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data class UpdateAvailable(val manifest: UpdateManifest) : UpdateState()
        data class Downloading(val progress: Float) : UpdateState()
        data class DownloadComplete(val file: File) : UpdateState()
        data object UpToDate : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            _isLoading.value = true

            updateService.checkForUpdate()
                .onSuccess { manifest ->
                    if (manifest != null) {
                        _updateState.value = UpdateState.UpdateAvailable(manifest)
                    } else {
                        _updateState.value = UpdateState.UpToDate
                    }
                }
                .onFailure { throwable ->
                    val message = throwable.message ?: "Unknown error checking for updates"
                    _updateState.value = UpdateState.Error(message)
                    _error.emit(message)
                }

            _isLoading.value = false
        }
    }

    fun downloadUpdate(url: String) {
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0f)
            _isLoading.value = true

            updateService.downloadApk(url) { progress ->
                _updateState.value = UpdateState.Downloading(progress)
            }
                .onSuccess { file ->
                    _updateState.value = UpdateState.DownloadComplete(file)
                    _toastEvent.emit("Download complete")
                }
                .onFailure { throwable ->
                    val message = throwable.message ?: "Unknown error downloading update"
                    _updateState.value = UpdateState.Error(message)
                    _error.emit(message)
                }

            _isLoading.value = false
        }
    }

    fun installUpdate(file: File) {
        updateService.installApk(file)
    }
}
