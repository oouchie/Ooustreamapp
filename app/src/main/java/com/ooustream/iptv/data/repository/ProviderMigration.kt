package com.ooustream.iptv.data.repository

import android.content.Context
import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.data.local.dao.ChannelScoreDao
import com.ooustream.iptv.data.local.dao.ChannelWatchLogDao
import com.ooustream.iptv.data.local.dao.ContentCacheDao
import com.ooustream.iptv.data.local.dao.EpgCacheDao
import com.ooustream.iptv.data.local.dao.EpgPatternDao
import com.ooustream.iptv.data.local.dao.SearchIndexDao
import com.ooustream.iptv.data.local.dao.VodCastDao
import com.ooustream.iptv.parental.ContentFilterManager
import com.ooustream.iptv.parental.ParentalRemapState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time cleanup when the app is pointed at a different panel host.
 *
 * `CredentialStore.load()` already moves the saved login onto the new host, so playback keeps
 * working by itself. What it CANNOT fix is everything this app caches **keyed by the provider's
 * own numeric ids** — category ids, stream ids, series ids. A new panel is under no obligation to
 * reuse those numbers, so a stale row can silently resolve to *different* content rather than to
 * nothing (the loud, easy failure). Dropping the derived caches costs one refetch; keeping them
 * risks showing the wrong thing with no error.
 *
 * Deliberately NOT touched here:
 *  - `favorites`, `watch_progress`, `series_tracking` — real user data. `WatchHistoryPruner`
 *    already drops history rows whose ids are absent from the live catalog, which is smarter than
 *    a blanket wipe and runs on its own.
 *  - `poster_cache` — keyed by TMDB id, which is global and provider-independent.
 *  - `blocked_categories` — parental blocks are re-matched BY NAME against the new catalogue
 *    instead of being dropped; see [ParentalRemapState] and ContentFilterManager. Clearing them
 *    would silently unblock adult content, which is the one outcome that must never happen here.
 */
@Singleton
class ProviderMigration @Inject constructor(
    @ApplicationContext context: Context,
    private val credentialStore: CredentialStore,
    private val contentCacheDao: ContentCacheDao,
    private val epgCacheDao: EpgCacheDao,
    private val epgPatternDao: EpgPatternDao,
    private val searchIndexDao: SearchIndexDao,
    private val channelScoreDao: ChannelScoreDao,
    private val channelWatchLogDao: ChannelWatchLogDao,
    private val vodCastDao: VodCastDao,
    private val parentalRemapState: ParentalRemapState,
    private val contentFilterManager: ContentFilterManager,
    private val diagnosticLogger: StreamDiagnosticLogger
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Compares the host this build targets against the one last seen, and cleans up if it moved.
     *
     * Call once per process, off the main thread. Safe to call again — the marker makes it a
     * no-op after the first successful run.
     */
    suspend fun runIfNeeded() {
        val canonical = credentialStore.canonicalServerUrl
        val lastSeen = prefs.getString(KEY_LAST_HOST, null)
        if (lastSeen == canonical) return

        // A fresh install has nothing to migrate: no saved login, nothing cached. Record where we
        // are and stop, so a first-time user never pays for a cleanup that has no subject.
        val savedLogin = credentialStore.rawSavedServerUrl()
        if (lastSeen == null && savedLogin == null) {
            prefs.edit().putString(KEY_LAST_HOST, canonical).apply()
            return
        }

        // `lastSeen` is null on an install that upgraded from a build predating this marker.
        // Those are exactly the installs sitting on the retired host, so treat null as "moved".
        val from = lastSeen ?: savedLogin ?: "unknown"
        diagnosticLogger.logAppEvent("PROVIDER_MIGRATION", "from=$from, to=$canonical")

        contentCacheDao.deleteAllCategories()
        contentCacheDao.deleteAllStreams()
        epgCacheDao.clearAll()
        epgPatternDao.clearAll()
        searchIndexDao.clearAll()
        channelScoreDao.clearAll()
        channelWatchLogDao.clearAll()
        vodCastDao.clearAll()

        // Arm the by-name re-match of parental blocks. Until each section completes, adult
        // categories are additionally blocked by name, so there is no unprotected window.
        parentalRemapState.armAllSections()

        // Written LAST: if anything above throws, the marker stays stale and the whole cleanup is
        // retried on the next launch rather than being half-applied and forgotten.
        prefs.edit().putString(KEY_LAST_HOST, canonical).apply()
        diagnosticLogger.logAppEvent("PROVIDER_MIGRATION", "complete to=$canonical")

        remapParentalBlocksNow()
    }

    /**
     * Re-matches parental blocks immediately instead of waiting for the user to open a section.
     *
     * `filterCategories` does this lazily and covers the general case, but only once a section is
     * actually browsed. Doing all three up front collapses the window in which content rows could
     * still be filtered against un-remapped ids.
     *
     * Best effort by design: with no network (or no login yet) every section stays armed, the
     * name-based fallback keeps protecting them, and the lazy path finishes the job later.
     */
    private suspend fun remapParentalBlocksNow() {
        try {
            contentFilterManager.remapPendingSections()
        } catch (e: Exception) {
            diagnosticLogger.logAppEvent(
                "PARENTAL_REMAP", "deferred (${e.javaClass.simpleName})"
            )
        }
    }

    private companion object {
        const val PREFS = "ooustream_provider"
        const val KEY_LAST_HOST = "last_provider_host"
    }
}
