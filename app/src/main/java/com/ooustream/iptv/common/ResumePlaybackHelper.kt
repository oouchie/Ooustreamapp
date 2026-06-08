package com.ooustream.iptv.common

import android.content.Context
import com.ooustream.iptv.data.local.entity.WatchProgressEntity

/**
 * Resumes silently when watch progress exists (Netflix-style) — no blocking modal. A brief toast tells
 * the user where they resumed; "start over" lives on the in-player Restart control instead. If no
 * meaningful progress, calls [onPlay] with forceBeginning=false immediately.
 */
object ResumePlaybackHelper {

    fun showIfNeeded(
        context: Context,
        progress: WatchProgressEntity?,
        onPlay: (forceBeginning: Boolean) -> Unit
    ) {
        if (progress == null || progress.completed || progress.progressPercent <= 0.05f) {
            onPlay(false)
            return
        }

        // Silent resume — no dialog. Let the user know where they picked up; the player's Restart
        // button handles starting over.
        val resumeTime = formatTime(progress.position)
        android.widget.Toast.makeText(
            context, "Resuming from $resumeTime", android.widget.Toast.LENGTH_SHORT
        ).show()
        onPlay(false)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
