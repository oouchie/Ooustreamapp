package com.ooustream.iptv.parental

import com.ooustream.iptv.data.model.Category

/**
 * Detects categories likely containing adult content based on name patterns.
 * Used for "Block All Adult" and auto-blocking on first PIN setup.
 */
object AdultCategoryDetector {

    private val ADULT_PATTERNS = listOf(
        Regex("\\badult\\b", RegexOption.IGNORE_CASE),
        Regex("\\bxxx\\b", RegexOption.IGNORE_CASE),
        Regex("\\b18\\+", RegexOption.IGNORE_CASE),
        Regex("\\bporn", RegexOption.IGNORE_CASE),
        Regex("\\berotic", RegexOption.IGNORE_CASE),
        Regex("\\bx[- ]?rated\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnsfw\\b", RegexOption.IGNORE_CASE),
        Regex("\\blate\\s*night\\b", RegexOption.IGNORE_CASE),
        Regex("\\bafter\\s*dark\\b", RegexOption.IGNORE_CASE),
        Regex("\\badults\\s*only\\b", RegexOption.IGNORE_CASE),
        Regex("\\bfor\\s*adults\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmature\\b", RegexOption.IGNORE_CASE)
    )

    fun isAdultCategory(categoryName: String): Boolean {
        return ADULT_PATTERNS.any { it.containsMatchIn(categoryName) }
    }

    fun findAdultCategories(categories: List<Category>): List<Category> {
        return categories.filter { isAdultCategory(it.categoryName) }
    }
}
