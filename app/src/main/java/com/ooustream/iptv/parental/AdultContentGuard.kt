package com.ooustream.iptv.parental

import android.content.Context
import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.data.repository.CatalogCache
import com.ooustream.iptv.data.repository.ContentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the provider files under an adult category, so Home can keep it off screen.
 *
 * This is NOT parental controls. It is always on, whether or not a PIN is set: adult titles
 * never surface on Home (hero, trending, Top 10, genre rows, For You, Continue Watching,
 * Pick Up & New, For You — Live Now). They stay reachable by browsing their category.
 *
 * Home's history rows carry no category id, so the guard needs the actual item ids. Movie ids
 * come from [CatalogCache.vodAdult] — the bulk movie list, read once and shared with Home —
 * series and live ids from fetching just the adult categories (both are small on this panel).
 *
 * Ids are held as sorted [IntArray]s and saved to a small binary file. Until v5.2.5 they were a
 * boxed Set plus a SharedPreferences StringSet: when the provider's adult movie categories grew
 * to 125k titles (2026-11-04) that settings file stayed in memory for good, was rewritten every
 * 15 minutes, and the heap ran out (ANRs on the Movies screens).
 *
 * [ids] is null until the saved copy loads or the first resolve finishes. Callers must treat null
 * as "unknown" and hold the row back (fail closed) — an adult poster flashing up for a second is
 * the failure we are preventing.
 */
@Singleton
class AdultContentGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentRepository: ContentRepository,
    private val catalogCache: CatalogCache,
    private val diagnosticLogger: StreamDiagnosticLogger
) {
    /** The three id arrays MUST be sorted ascending (they are binary-searched). */
    class AdultIds(
        val vodCategories: Set<String>,
        val seriesCategories: Set<String>,
        val liveCategories: Set<String>,
        private val vod: IntArray,
        private val series: IntArray,
        private val live: IntArray
    ) {
        fun isAdultCategory(section: String, categoryId: String?): Boolean = when (section) {
            "vod" -> categoryId in vodCategories
            "series" -> categoryId in seriesCategories
            "live" -> categoryId in liveCategories
            else -> false
        }

        /** [type] is "vod" / "series" / "live"; [id] is a stream id, or a series id for "series". */
        fun isAdultItem(type: String, id: Int?): Boolean {
            if (id == null) return false
            val ids = when (type) {
                "vod" -> vod
                "series" -> series
                "live" -> live
                else -> return false
            }
            return ids.binarySearch(id) >= 0
        }

        internal fun counts() = "vod=${vod.size} series=${series.size} live=${live.size}"

        internal fun writeTo(out: DataOutputStream) {
            out.writeInt(FILE_MAGIC)
            for (set in listOf(vodCategories, seriesCategories, liveCategories)) {
                out.writeInt(set.size)
                set.forEach { out.writeUTF(it) }
            }
            for (arr in listOf(vod, series, live)) {
                out.writeInt(arr.size)
                arr.forEach { out.writeInt(it) }
            }
        }

        internal companion object {
            fun readFrom(input: DataInputStream): AdultIds? {
                if (input.readInt() != FILE_MAGIC) return null
                val sets = List(3) { List(input.readInt()) { input.readUTF() }.toSet() }
                val arrays = List(3) { IntArray(input.readInt()) { input.readInt() }.also { it.sort() } }
                return AdultIds(sets[0], sets[1], sets[2], arrays[0], arrays[1], arrays[2])
            }
        }
    }

    private val mutex = Mutex()
    private var lastResolvedAt = 0L
    private val file = File(context.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ids = MutableStateFlow<AdultIds?>(null)
    val ids: StateFlow<AdultIds?> = _ids.asStateFlow()

    /** Loads the saved copy off the main thread; [ensure] waits for it before deciding to resolve. */
    private val loaded = scope.async {
        deleteOldPrefs()
        val saved = try {
            if (file.exists()) {
                DataInputStream(BufferedInputStream(file.inputStream())).use { AdultIds.readFrom(it) }
            } else null
        } catch (e: Exception) {
            null
        }
        if (saved != null && _ids.value == null) _ids.value = saved
    }

    /**
     * Returns current ids, resolving first if they are unknown or stale. Returns the previous
     * value (possibly null) if the resolve fails — never a partial set.
     */
    suspend fun ensure(): AdultIds? {
        loaded.await()
        val current = _ids.value
        if (current != null && System.currentTimeMillis() - lastResolvedAt < TTL_MS) return current
        return mutex.withLock {
            val again = _ids.value
            if (again != null && System.currentTimeMillis() - lastResolvedAt < TTL_MS) return@withLock again
            try {
                val resolved = resolve()
                lastResolvedAt = System.currentTimeMillis()
                _ids.value = resolved
                scope.launch { persist(resolved) }
                resolved
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                diagnosticLogger.logAppEvent("ADULT_GUARD", "resolve FAILED: ${e.javaClass.simpleName}")
                _ids.value
            }
        }
    }

    private suspend fun resolve(): AdultIds = coroutineScope {
        // Movies: the ids the shared catalogue set aside while reading the bulk list.
        val vodJob = async { catalogCache.vodAdult() }
        val seriesCatsJob = async { contentRepository.getSeriesCategories() }
        val liveCatsJob = async { contentRepository.getLiveCategories() }

        val seriesCats = AdultCategoryDetector.findAdultCategories(seriesCatsJob.await()).map { it.categoryId }.toSet()
        val liveCats = AdultCategoryDetector.findAdultCategories(liveCatsJob.await()).map { it.categoryId }.toSet()

        // Any failure throws out of here, so a half-resolved set is never published.
        val gate = Semaphore(4)
        val series = seriesCats.map { id -> async { gate.withPermit { contentRepository.getSeries(id).map { it.seriesId } } } }
        val live = liveCats.map { id -> async { gate.withPermit { contentRepository.getLiveStreams(id).map { it.streamId } } } }
        val vod = vodJob.await()

        val result = AdultIds(
            vodCategories = vod.categories,
            seriesCategories = seriesCats,
            liveCategories = liveCats,
            vod = vod.ids,
            series = series.awaitAll().flatten().toIntArray().also { it.sort() },
            live = live.awaitAll().flatten().toIntArray().also { it.sort() }
        )
        diagnosticLogger.logAppEvent(
            "ADULT_GUARD",
            "categories vod=${vod.categories.size} series=${seriesCats.size} live=${liveCats.size}, " +
                "items ${result.counts()}"
        )
        result
    }

    private suspend fun persist(ids: AdultIds) = withContext(Dispatchers.IO) {
        try {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { ids.writeTo(it) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: Exception) {
            // Next launch resolves from the network instead; nothing else depends on the file.
        }
    }

    /**
     * v5.0.3–v5.2.4 kept the ids in SharedPreferences (125k strings on 2026-11-04). Delete the
     * file without opening it — opening it would load every entry into memory.
     */
    private fun deleteOldPrefs() {
        try {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(dir, "$OLD_PREFS.xml").delete()
            File(dir, "$OLD_PREFS.xml.bak").delete()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val OLD_PREFS = "adult_content_guard"
        const val FILE_NAME = "adult_content_guard.bin"
        const val FILE_MAGIC = 0x4F414731 // "OAG1"
        const val TTL_MS = 15 * 60 * 1000L
    }
}
