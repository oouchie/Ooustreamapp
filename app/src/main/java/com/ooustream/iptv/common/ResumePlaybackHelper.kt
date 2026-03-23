package com.ooustream.iptv.common

import android.content.Context
import com.ooustream.iptv.data.local.entity.WatchProgressEntity

/**
 * Shows a Resume / Play from Beginning dialog when watch progress exists.
 * If no progress, calls [onPlay] with forceBeginning=false immediately.
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

        val resumeTime = formatTime(progress.position)
        android.app.AlertDialog.Builder(context).apply {
            setTitle("Resume Playback")
            setMessage("You stopped at $resumeTime. Would you like to resume or start over?")
            setPositiveButton("Resume from $resumeTime") { _, _ -> onPlay(false) }
            setNegativeButton("Play from Beginning") { _, _ -> onPlay(true) }
            setCancelable(true)
        }.show()
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
