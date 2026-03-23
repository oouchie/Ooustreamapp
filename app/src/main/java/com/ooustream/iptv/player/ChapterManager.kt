package com.ooustream.iptv.player

/**
 * Auto-generates evenly-spaced chapter markers for VOD/Series content.
 * Chapters are created based on total duration — no external data needed.
 *
 * Interval: 5 minutes for content > 20 min, 2 minutes for shorter content.
 */
class ChapterManager {

    data class Chapter(val index: Int, val startMs: Long, val label: String)

    private var chapters: List<Chapter> = emptyList()
    private var durationMs: Long = 0

    val chapterCount: Int get() = chapters.size

    /** Generate chapters once duration is known. Call when duration changes. */
    fun generate(durationMs: Long) {
        if (durationMs <= 0 || durationMs == this.durationMs) return
        this.durationMs = durationMs

        val intervalMs = if (durationMs > 20 * 60_000L) 5 * 60_000L else 2 * 60_000L
        val count = (durationMs / intervalMs).toInt().coerceAtLeast(1)

        chapters = (0..count).map { i ->
            val startMs = i * intervalMs
            Chapter(
                index = i,
                startMs = startMs.coerceAtMost(durationMs),
                label = "Chapter ${i + 1}"
            )
        }
    }

    /** Find the chapter index for the given playback position. */
    fun currentChapter(positionMs: Long): Int {
        if (chapters.isEmpty()) return 0
        for (i in chapters.indices.reversed()) {
            if (positionMs >= chapters[i].startMs) return i
        }
        return 0
    }

    /** Get the start position of the next chapter, or null if at the last chapter. */
    fun nextChapterMs(positionMs: Long): Long? {
        val current = currentChapter(positionMs)
        val next = current + 1
        return if (next < chapters.size) chapters[next].startMs else null
    }

    /** Get the start position of the previous chapter (or beginning of current if >5s in). */
    fun prevChapterMs(positionMs: Long): Long {
        val current = currentChapter(positionMs)
        if (current <= 0) return 0
        // If more than 5s into current chapter, go to start of current
        val currentStart = chapters[current].startMs
        return if (positionMs - currentStart > 5_000) currentStart
        else chapters[current - 1].startMs
    }

    /** Format current chapter for display: "Ch 3 / 12" */
    fun formatChapter(positionMs: Long): String {
        if (chapters.isEmpty()) return ""
        val current = currentChapter(positionMs) + 1
        return "Ch $current / ${chapters.size}"
    }
}
