package com.ooustream.iptv.parental

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.BlockedCategoryEntity
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.repository.ContentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryToggleItem(
    val categoryId: String,
    val categoryName: String,
    val isBlocked: Boolean
)

@HiltViewModel
class ParentalSettingsViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val contentFilterManager: ContentFilterManager,
    private val parentalControlManager: ParentalControlManager
) : BaseViewModel() {

    private val _activeSection = MutableStateFlow("live")
    val activeSection: StateFlow<String> = _activeSection.asStateFlow()

    private val _categoryItems = MutableStateFlow<List<CategoryToggleItem>>(emptyList())
    val categoryItems: StateFlow<List<CategoryToggleItem>> = _categoryItems.asStateFlow()

    private val _blockedCounts = MutableStateFlow(Triple(0, 0, 0)) // live, vod, series
    val blockedCounts: StateFlow<Triple<Int, Int, Int>> = _blockedCounts.asStateFlow()

    // Cache raw categories per section to avoid re-fetching
    private val categoryCache = mutableMapOf<String, List<Category>>()

    init {
        loadSection("live")
        loadBlockedCounts()
    }

    fun selectSection(section: String) {
        _activeSection.value = section
        loadSection(section)
    }

    private fun loadSection(section: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val categories = categoryCache.getOrPut(section) {
                    when (section) {
                        "live" -> contentRepository.getLiveCategories()
                        "vod" -> contentRepository.getVodCategories()
                        "series" -> contentRepository.getSeriesCategories()
                        else -> emptyList()
                    }
                }

                val blocked = contentFilterManager.getBlockedBySection(section)
                val blockedIds = blocked.map { it.categoryId }.toSet()

                // Sort: blocked at top, then alphabetical within each group
                val items = categories.map { cat ->
                    CategoryToggleItem(
                        categoryId = cat.categoryId,
                        categoryName = cat.categoryName,
                        isBlocked = cat.categoryId in blockedIds
                    )
                }.sortedWith(
                    compareByDescending<CategoryToggleItem> { it.isBlocked }
                        .thenBy { it.categoryName.lowercase() }
                )

                _categoryItems.value = items
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load categories")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleCategory(item: CategoryToggleItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val section = _activeSection.value
            if (item.isBlocked) {
                contentFilterManager.unblockCategory(section, item.categoryId)
            } else {
                contentFilterManager.blockCategory(section, item.categoryId, item.categoryName)
            }
            // Reload to reflect change
            loadSection(section)
            loadBlockedCounts()
        }
    }

    fun blockAllAdult() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sections = listOf("live", "vod", "series")
                val toBlock = mutableListOf<BlockedCategoryEntity>()

                for (section in sections) {
                    val categories = categoryCache.getOrPut(section) {
                        when (section) {
                            "live" -> contentRepository.getLiveCategories()
                            "vod" -> contentRepository.getVodCategories()
                            "series" -> contentRepository.getSeriesCategories()
                            else -> emptyList()
                        }
                    }
                    val adultCats = AdultCategoryDetector.findAdultCategories(categories)
                    toBlock.addAll(adultCats.map { cat ->
                        BlockedCategoryEntity(
                            section = section,
                            categoryId = cat.categoryId,
                            categoryName = cat.categoryName
                        )
                    })
                }

                if (toBlock.isNotEmpty()) {
                    contentFilterManager.blockAll(toBlock)
                    _toastEvent.emit("${toBlock.size} adult categories blocked")
                } else {
                    _toastEvent.emit("No adult categories found")
                }

                loadSection(_activeSection.value)
                loadBlockedCounts()
            } catch (e: Exception) {
                _error.emit("Failed to block adult categories")
            }
        }
    }

    fun allowAllInSection() {
        viewModelScope.launch(Dispatchers.IO) {
            val section = _activeSection.value
            contentFilterManager.unblockAllInSection(section)
            _toastEvent.emit("All categories allowed in ${sectionLabel(section)}")
            loadSection(section)
            loadBlockedCounts()
        }
    }

    /**
     * Auto-block adult categories on first PIN setup.
     * Called from ParentalPinFragment after first PIN is created.
     */
    suspend fun autoBlockAdultOnSetup() {
        try {
            val sections = listOf("live", "vod", "series")
            val toBlock = mutableListOf<BlockedCategoryEntity>()

            for (section in sections) {
                val categories = when (section) {
                    "live" -> contentRepository.getLiveCategories()
                    "vod" -> contentRepository.getVodCategories()
                    "series" -> contentRepository.getSeriesCategories()
                    else -> emptyList()
                }
                val adultCats = AdultCategoryDetector.findAdultCategories(categories)
                toBlock.addAll(adultCats.map { cat ->
                    BlockedCategoryEntity(
                        section = section,
                        categoryId = cat.categoryId,
                        categoryName = cat.categoryName
                    )
                })
            }

            if (toBlock.isNotEmpty()) {
                contentFilterManager.blockAll(toBlock)
            }
        } catch (_: Exception) {
            // Non-critical: auto-block is best-effort
        }
    }

    private fun loadBlockedCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val live = contentFilterManager.getBlockedBySection("live").size
                val vod = contentFilterManager.getBlockedBySection("vod").size
                val series = contentFilterManager.getBlockedBySection("series").size
                _blockedCounts.value = Triple(live, vod, series)
            } catch (_: Exception) { }
        }
    }

    private fun sectionLabel(section: String): String = when (section) {
        "live" -> "Live TV"
        "vod" -> "Movies"
        "series" -> "Series"
        else -> section
    }
}
