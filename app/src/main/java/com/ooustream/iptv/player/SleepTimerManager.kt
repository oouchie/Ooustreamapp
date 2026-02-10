package com.ooustream.iptv.player

import android.os.CountDownTimer
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.media3.exoplayer.ExoPlayer
import com.ooustream.iptv.R

/**
 * Manages a sleep timer that pauses playback after a user-selected duration.
 *
 * In the final [fadeStartMinutes] minutes the volume is gradually reduced to
 * zero so the transition feels natural. Once the timer expires, playback is
 * paused and the original volume is restored for the next session.
 */
class SleepTimerManager(private val activity: FragmentActivity) {

    private var timer: CountDownTimer? = null
    private var player: ExoPlayer? = null
    private var totalMinutes: Int = 0
    private var fadeStartMinutes: Int = 4 // Start fading 4 minutes before end
    private var originalVolume: Float = 1f

    /** Whether a sleep timer is currently running. */
    val isActive: Boolean get() = timer != null

    /** Available timer durations in minutes. 0 means "off". */
    val options = listOf(0, 15, 30, 45, 60)

    /** Bind an [ExoPlayer] instance whose volume/playback we control. */
    fun setPlayer(exoPlayer: ExoPlayer) {
        player = exoPlayer
        originalVolume = exoPlayer.volume
    }

    /**
     * Start (or restart) the sleep timer for [minutes] minutes.
     * Passing 0 cancels any active timer.
     */
    fun setTimer(minutes: Int) {
        cancel()
        if (minutes == 0) {
            Toast.makeText(activity, activity.getString(R.string.sleep_timer_off), Toast.LENGTH_SHORT).show()
            return
        }

        totalMinutes = minutes
        Toast.makeText(activity, activity.getString(R.string.sleep_timer_set, minutes), Toast.LENGTH_SHORT).show()

        timer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val fadeStartMs = fadeStartMinutes * 60 * 1000L

                // Gradually reduce volume in the last few minutes
                if (millisUntilFinished < fadeStartMs) {
                    val fadeProgress = 1f - (millisUntilFinished.toFloat() / fadeStartMs)
                    val volume = originalVolume * (1f - fadeProgress)
                    player?.volume = volume.coerceAtLeast(0f)
                }
            }

            override fun onFinish() {
                player?.pause()
                player?.volume = originalVolume
                timer = null
                Toast.makeText(activity, activity.getString(R.string.sleep_timer_off), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Cancel the active timer and restore the original volume. */
    fun cancel() {
        timer?.cancel()
        timer = null
        player?.volume = originalVolume
    }

    /** Show an AlertDialog letting the user pick a sleep timer duration. */
    fun showTimerDialog() {
        val labels = options.map {
            if (it == 0) "Off" else "$it minutes"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.sleep_timer))
            .setItems(labels) { _, which ->
                setTimer(options[which])
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }
}
