package com.ooustream.iptv.backup

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupService: BackupService
) : BaseViewModel() {

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    fun exportBackup() {
        viewModelScope.launch {
            _backupState.value = BackupState.Exporting
            _isLoading.value = true

            backupService.exportBackup()
                .onSuccess { file ->
                    _backupState.value = BackupState.ExportSuccess(file.absolutePath)
                    _toastEvent.emit("Backup saved to ${file.name}")
                }
                .onFailure { error ->
                    _backupState.value = BackupState.Error(error.message ?: "Export failed")
                    _error.emit(error.message ?: "Export failed")
                }

            _isLoading.value = false
        }
    }

    fun importBackup(json: String) {
        viewModelScope.launch {
            _backupState.value = BackupState.Importing
            _isLoading.value = true

            backupService.importBackup(json)
                .onSuccess { count ->
                    _backupState.value = BackupState.ImportSuccess(count)
                    _toastEvent.emit("Restored $count items")
                }
                .onFailure { error ->
                    _backupState.value = BackupState.Error(error.message ?: "Import failed")
                    _error.emit(error.message ?: "Import failed")
                }

            _isLoading.value = false
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            _isLoading.value = true

            runCatching {
                backupService.clearAllData()
            }.onSuccess {
                _backupState.value = BackupState.Idle
                _toastEvent.emit("All data cleared")
            }.onFailure { error ->
                _backupState.value = BackupState.Error(error.message ?: "Clear failed")
                _error.emit(error.message ?: "Failed to clear data")
            }

            _isLoading.value = false
        }
    }

    fun resetState() {
        _backupState.value = BackupState.Idle
    }
}

sealed class BackupState {
    data object Idle : BackupState()
    data object Exporting : BackupState()
    data class ExportSuccess(val filePath: String) : BackupState()
    data object Importing : BackupState()
    data class ImportSuccess(val itemCount: Int) : BackupState()
    data class Error(val message: String) : BackupState()
}
