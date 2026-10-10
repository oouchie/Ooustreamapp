package com.ooustream.iptv.catalog

import com.ooustream.iptv.common.CategoryItem
import com.ooustream.iptv.data.model.Category

/**
 * Builds the Movies / Series sidebar rows: the fixed pseudo-rows on top, then a "Browse by" group
 * block (genre / decade), then the services with the long tail folded under "More services".
 *
 * Shared by `VodFragment` and `SeriesFragment` so the two sidebars cannot drift.
 */
object BrowseSidebar {

    const val GROUP_CHEVRON_CLOSED = "▸"   // ▸
    const val GROUP_CHEVRON_OPEN = "▾"     // ▾
    private const val DECADE_EMOJI = "📅"   // 📅
    private const val GENRE_EMOJI = "🎭"    // 🎭
    private const val MORE_EMOJI = "☰"           // ☰

    /**
     * @param fixedTop   pseudo-rows already filtered against the search text by the caller.
     * @param apiCats    the parental-filtered provider categories, in provider order.
     * @param index      null until the bulk list has loaded → plain list, as before.
     * @param expanded   group ids the user has opened.
     * @param selectedId currently selected category (its group is always shown open).
     * @param search     lower-cased header-search text; non-empty flattens the list.
     */
    fun build(
        fixedTop: List<CategoryItem>,
        apiCats: List<Category>,
        index: CatalogBrowse.Index?,
        expanded: Set<String>,
        selectedId: String?,
        search: String,
        section: String
    ): List<CategoryItem> {
        val counts = index?.perCategory ?: emptyMap()
        fun apiItem(c: Category, indent: Boolean = false) = CategoryItem(
            id = c.categoryId,
            name = CatalogBrowse.displayName(c.categoryName, section),
            count = counts[c.categoryId] ?: 0,
            indent = indent
        )

        if (search.isNotEmpty()) {
            // Flat: every leaf whose label matches. No headers or groups while typing.
            val out = ArrayList<CategoryItem>(fixedTop)
            index?.genres?.filter { it.label.lowercase().contains(search) }
                ?.forEach { out += CategoryItem(it.id, it.label, it.count) }
            index?.decades?.filter { it.label.lowercase().contains(search) }
                ?.forEach { out += CategoryItem(it.id, it.label, it.count, emojiOverride = DECADE_EMOJI) }
            apiCats.filter { CatalogBrowse.displayName(it.categoryName, section).lowercase().contains(search) }
                .forEach { out += apiItem(it) }
            return out
        }

        val out = ArrayList<CategoryItem>(fixedTop)
        if (index == null) {
            apiCats.forEach { out += apiItem(it) }
            return out
        }

        val open = expanded + listOfNotNull(CatalogBrowse.groupOf(selectedId))

        if (index.hasBrowse) {
            out += CategoryItem("__hdr_browse__", "Browse by", kind = CategoryItem.Kind.HEADER)
            if (index.genres.isNotEmpty()) {
                val isOpen = CatalogBrowse.GROUP_GENRE in open
                out += group(CatalogBrowse.GROUP_GENRE, "By genre", index.genres.size, isOpen, GENRE_EMOJI)
                if (isOpen) index.genres.forEach { out += CategoryItem(it.id, it.label, it.count, indent = true) }
            }
            if (index.decades.isNotEmpty()) {
                val isOpen = CatalogBrowse.GROUP_DECADE in open
                out += group(CatalogBrowse.GROUP_DECADE, "By decade", index.decades.size, isOpen, DECADE_EMOJI)
                if (isOpen) index.decades.forEach {
                    out += CategoryItem(it.id, it.label, it.count, indent = true, emojiOverride = DECADE_EMOJI)
                }
            }
        }

        out += CategoryItem("__hdr_services__", "Services", kind = CategoryItem.Kind.HEADER)
        val major = ArrayList<Category>()
        val minor = ArrayList<Category>()
        var unlabeled: Category? = null
        for (c in apiCats) {
            when {
                CatalogBrowse.isUnlabeled(c.categoryName) -> unlabeled = c
                (counts[c.categoryId] ?: 0) >= CatalogBrowse.MAJOR_SERVICE_MIN -> major += c
                else -> minor += c
            }
        }
        major.forEach { out += apiItem(it) }
        // "All Other …" is a catch-all, not a service — it reads better at the end of the list.
        unlabeled?.let { out += apiItem(it) }
        if (minor.isNotEmpty()) {
            // A selected minor service keeps its group open so the highlighted row stays visible.
            val isOpen = CatalogBrowse.GROUP_MORE in open || minor.any { it.categoryId == selectedId }
            out += group(CatalogBrowse.GROUP_MORE, "More services", minor.size, isOpen, MORE_EMOJI)
            if (isOpen) minor.forEach { out += apiItem(it, indent = true) }
        }
        return out
    }

    private fun group(id: String, label: String, count: Int, open: Boolean, emoji: String) = CategoryItem(
        id = id,
        name = label,
        count = count,
        kind = CategoryItem.Kind.GROUP,
        emojiOverride = if (open) GROUP_CHEVRON_OPEN else GROUP_CHEVRON_CLOSED
    )
}
