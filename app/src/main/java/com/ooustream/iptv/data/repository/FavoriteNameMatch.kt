package com.ooustream.iptv.data.repository

import java.text.Normalizer

/**
 * Name matching for [FavoriteRemapper]. No Android or Room types, so it runs on the plain JVM.
 *
 * A favorite whose id left the catalog is re-pointed at a catalog title with the same name only
 * when exactly ONE title has that name. A wrong match is worse than none: an unmatched favorite
 * stays where it is and the user can remove it. The key keeps the quality prefix ("4K: Parasite"
 * and "Parasite" are different titles), so a 4K favorite never moves to the HD copy.
 */
object FavoriteNameMatch {

    data class Title(
        val id: Int,
        // Nullable on purpose: Gson leaves a JSON null in a field Kotlin declares non-null.
        val name: String?,
        val icon: String?,
        val categoryId: String?,
        val extension: String?
    )

    /** A saved favorite whose id is no longer in the catalog. */
    data class Dead(val id: String, val name: String?)

    sealed interface Step { val oldId: String }
    /** Save the favorite under [newId] (pointing at [title]), then delete [oldId]. */
    data class Move(override val oldId: String, val newId: String, val title: Title) : Step
    /** [newId] is already a favorite: only delete [oldId]. */
    data class Drop(override val oldId: String, val newId: String) : Step

    /**
     * What to do with each dead favorite of [type] ("vod" / "series"). Favorites with no unique
     * match get no step and stay as they are. Two dead favorites that land on the same title give
     * one Move and one Drop.
     */
    fun plan(type: String, dead: List<Dead>, savedIds: Set<String>, catalog: Sequence<Title>): List<Step> {
        val matches = match(dead.map { it.name }, catalog)
        val saved = HashSet(savedIds)
        return dead.mapNotNull { fav ->
            val title = matches[key(fav.name)] ?: return@mapNotNull null
            val newId = "${type}_${title.id}"
            if (saved.add(newId)) Move(fav.id, newId, title) else Drop(fav.id, newId)
        }
    }

    /** Case, accents, punctuation, spacing and "&"/"and" differences don't block a match. */
    fun key(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()
            .replace("&", "and")
            .filter { it.isLetterOrDigit() }
    }

    /**
     * The catalog title for each dead favorite's name [key], when exactly one title carries it.
     * Keys with no title, or with titles under two different ids, are left out.
     */
    fun match(deadNames: Collection<String?>, catalog: Sequence<Title>): Map<String, Title> {
        val wanted = deadNames.mapTo(HashSet()) { key(it) }.apply { remove("") }
        if (wanted.isEmpty()) return emptyMap()
        val found = HashMap<String, Title>()
        val ambiguous = HashSet<String>()
        for (title in catalog) {
            val k = key(title.name)
            if (k !in wanted || k in ambiguous) continue
            val earlier = found[k]
            if (earlier == null) {
                found[k] = title
            } else if (earlier.id != title.id) {
                found.remove(k)
                ambiguous.add(k)
            }
        }
        return found
    }

    private val MARKS = Regex("\\p{M}+")
}
