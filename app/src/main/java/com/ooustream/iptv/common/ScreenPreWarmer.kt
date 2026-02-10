package com.ooustream.iptv.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced pre-warming helper for Leanback section cards.
 *
 * When the user hovers (focuses) on a section card for 500 ms the supplied
 * [preWarmAction] is executed on [Dispatchers.IO].  If the user moves focus
 * before the delay elapses the pending job is cancelled (debounce).  Each
 * section is only pre-warmed once per session.
 */
class ScreenPreWarmer(private val scope: CoroutineScope) {

    private var warmJob: Job? = null
    private val warmedSections = mutableSetOf<String>()

    /**
     * Call when a section card receives focus.
     *
     * @param sectionId stable identifier for the section (e.g. "live", "vod")
     * @param preWarmAction suspend lambda that populates caches for the section
     */
    fun onSectionFocused(sectionId: String, preWarmAction: suspend () -> Unit) {
        warmJob?.cancel()

        if (sectionId in warmedSections) return // Already pre-warmed

        warmJob = scope.launch(Dispatchers.IO) {
            delay(500)
            try {
                preWarmAction()
                warmedSections.add(sectionId)
            } catch (_: Exception) { }
        }
    }

    /** Call when no section card has focus (e.g. focus moved away). */
    fun onSectionBlurred() {
        warmJob?.cancel()
    }

    /** Clear all pre-warm state so sections can be warmed again. */
    fun reset() {
        warmJob?.cancel()
        warmedSections.clear()
    }
}
