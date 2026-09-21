package com.ooustream.iptv.parental

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks, per section, whether parental blocks still need re-matching onto a new provider's
 * category ids.
 *
 * Blocks are stored as `(section, categoryId)`. When the app is pointed at a different panel
 * those ids are meaningless: a block either matches nothing (adult content becomes visible) or,
 * worse, matches a category the user never blocked. The entity also carries `categoryName`,
 * which is stable across panels, so the fix is to re-match by name — see
 * `ContentFilterManager.filterCategories`, which gets the live catalogue handed to it anyway.
 *
 * Armed by `ProviderMigration`; cleared per section as each one is re-matched. While a section is
 * armed, `ContentFilterManager` additionally blocks adult categories by NAME, so a child is never
 * exposed during the gap between the switch and the first category load.
 *
 * Persisted rather than held in memory: the re-match needs the live catalogue, and an install
 * that is killed (or never regains network) before that arrives must stay protected across
 * restarts.
 */
@Singleton
class ParentalRemapState @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun armAllSections() {
        prefs.edit().apply {
            SECTIONS.forEach { putBoolean(key(it), true) }
        }.apply()
    }

    fun isPending(section: String): Boolean = prefs.getBoolean(key(section), false)

    fun clear(section: String) {
        prefs.edit().putBoolean(key(section), false).apply()
    }

    private fun key(section: String) = "remap_pending_$section"

    companion object {
        val SECTIONS = listOf("live", "vod", "series")
        private const val PREFS = "ooustream_parental_remap"
    }
}
