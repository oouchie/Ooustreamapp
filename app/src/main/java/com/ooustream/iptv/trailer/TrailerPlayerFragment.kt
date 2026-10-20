package com.ooustream.iptv.trailer

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Full-screen in-app trailer. The provider only gives a YouTube id, so this is YouTube's own IFrame
 * player (android-youtube-player) with its controls hidden and the remote driving it: OK / play-pause
 * toggles, LEFT / RIGHT / FF / RW skip 10s, BACK leaves. The WebView never takes focus — a D-pad
 * inside YouTube's web UI is unusable from a remote.
 *
 * When YouTube refuses the embed (the studio disabled embedding, etc.) the screen says so and OK falls
 * back to opening the YouTube app, which is what every trailer button did before.
 */
@AndroidEntryPoint
class TrailerPlayerFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var diagnostics: StreamDiagnosticLogger

    private var ytView: YouTubePlayerView? = null
    private var player: YouTubePlayer? = null
    private var titleView: TextView? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var time: TextView? = null

    private var playing = false
    private var positionSec = 0f
    private var durationSec = 0f
    private var failed = false
    private var openedAt = 0L

    private val videoId get() = requireArguments().getString(ARG_VIDEO_ID).orEmpty()
    private val title get() = requireArguments().getString(ARG_TITLE).orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            // Block the WebView from ever taking D-pad focus; keys reach us via KeyEventHandler.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isFocusable = true
        }
        val yt = YouTubePlayerView(ctx).apply {
            enableAutomaticInitialization = false
            isFocusable = false
        }
        root.addView(yt, FrameLayout.LayoutParams(MATCH, MATCH))

        titleView = TextView(ctx).apply {
            text = getString(R.string.trailer_title, title)
            setTextColor(Color.WHITE)
            textSize = 20f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        root.addView(titleView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            setMargins((48 * dp).toInt(), (32 * dp).toInt(), (48 * dp).toInt(), 0)
        })

        status = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setText(R.string.trailer_loading)
        }
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.sd_gold)) // cyan accent (name is historical)
            visibility = View.INVISIBLE
        }
        root.addView(progress, FrameLayout.LayoutParams(MATCH, (4 * dp).toInt(), Gravity.BOTTOM).apply {
            setMargins((48 * dp).toInt(), 0, (48 * dp).toInt(), (40 * dp).toInt())
        })
        time = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            visibility = View.INVISIBLE
        }
        root.addView(time, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (48 * dp).toInt(), (52 * dp).toInt())
        })

        ytView = yt
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val yt = ytView ?: return
        viewLifecycleOwner.lifecycle.addObserver(yt)
        openedAt = System.currentTimeMillis()
        log("TRAILER_OPEN", "id=$videoId")

        // YouTube now rejects embeds that don't identify their app (error 153 / missing referer).
        // The library's 13.x does exactly this; we're on 12.x, so set it ourselves.
        val options = IFramePlayerOptions.Builder()
            .origin("https://${requireContext().packageName}")
            .controls(0)
            .rel(0)
            .ivLoadPolicy(3)
            .ccLoadPolicy(0)
            .build()
        yt.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                youTubePlayer.loadVideo(videoId, 0f)
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                playing = state == PlayerConstants.PlayerState.PLAYING
                when (state) {
                    PlayerConstants.PlayerState.PLAYING -> {
                        if (positionSec == 0f) log("TRAILER_PLAYING", "afterMs=${System.currentTimeMillis() - openedAt}")
                        status?.visibility = View.GONE
                        // YouTube draws its own title bar while playing; ours would sit on top of it.
                        titleView?.visibility = View.GONE
                        progress?.visibility = View.VISIBLE
                        time?.visibility = View.VISIBLE
                    }
                    PlayerConstants.PlayerState.PAUSED -> showStatus(getString(R.string.trailer_paused))
                    PlayerConstants.PlayerState.BUFFERING -> showStatus(getString(R.string.trailer_loading))
                    PlayerConstants.PlayerState.ENDED -> {
                        log("TRAILER_ENDED")
                        leave()
                    }
                    else -> {}
                }
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                failed = true
                log("TRAILER_ERROR", "id=$videoId error=$error")
                progress?.visibility = View.INVISIBLE
                time?.visibility = View.INVISIBLE
                showStatus(getString(R.string.trailer_cant_play))
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                positionSec = second
                renderProgress()
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                durationSec = duration
                renderProgress()
            }
        }, true, options)
    }

    override fun onKeyEvent(keyCode: Int): Boolean {
        if (failed) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    openInYouTubeApp(requireContext(), videoId)
                    leave()
                    true
                }
                else -> false
            }
        }
        val p = player ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (playing) p.pause() else p.play(); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(SEEK_SEC); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-SEEK_SEC); true }
            // UP/DOWN have nowhere to go; swallow so focus can't wander out of the screen.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
    }

    private fun seekBy(deltaSec: Float) {
        val target = (positionSec + deltaSec).coerceIn(0f, if (durationSec > 0f) durationSec - 1f else Float.MAX_VALUE)
        player?.seekTo(target)
        positionSec = target
        renderProgress()
    }

    private fun renderProgress() {
        if (durationSec <= 0f) return
        progress?.progress = ((positionSec / durationSec) * 1000).toInt()
        time?.text = "${fmt(positionSec)} / ${fmt(durationSec)}"
    }

    private fun showStatus(text: String) {
        status?.text = text
        status?.visibility = View.VISIBLE
        titleView?.visibility = View.VISIBLE
    }

    private fun leave() {
        if (isAdded && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack()
    }

    private fun log(event: String, details: String = "") {
        Log.w(TAG, "$event $details")
        runCatching { diagnostics.logAppEvent(event, details) }
    }

    override fun onDestroyView() {
        log("TRAILER_CLOSE", "watchedSec=${positionSec.toInt()} of ${durationSec.toInt()} failed=$failed")
        ytView?.release()
        ytView = null
        player = null
        titleView = null
        status = null
        progress = null
        time = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "OOUSTREAM_TRAILER"
        private const val ARG_VIDEO_ID = "video_id"
        private const val ARG_TITLE = "title"
        private const val SEEK_SEC = 10f
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

        private fun fmt(sec: Float): String {
            val s = sec.toInt().coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }

        /**
         * The provider's `youtube_trailer` is usually a bare 11-char id, sometimes a full URL
         * (watch?v=, youtu.be/, embed/). Null when no id can be found.
         */
        fun parseVideoId(raw: String?): String? {
            val t = raw?.trim().orEmpty()
            if (t.isEmpty()) return null
            if (ID_PATTERN.matches(t)) return t
            val uri = runCatching { Uri.parse(t) }.getOrNull() ?: return null
            uri.getQueryParameter("v")?.takeIf { ID_PATTERN.matches(it) }?.let { return it }
            return uri.lastPathSegment?.takeIf { ID_PATTERN.matches(it) }
        }

        /**
         * Open the trailer in the app. Falls back to the YouTube app (the pre-2026-10 behaviour)
         * when no id can be parsed or [context] isn't hosted by an activity we can navigate in.
         */
        fun open(context: Context, rawTrailer: String?, title: String) {
            val id = parseVideoId(rawTrailer)
            val activity = context.findActivity()
            if (id == null || activity == null || activity.supportFragmentManager.isStateSaved) {
                rawTrailer?.trim()?.takeIf { it.isNotEmpty() }?.let { openInYouTubeApp(context, it) }
                return
            }
            val fragment = TrailerPlayerFragment().apply {
                arguments = bundleOf(ARG_VIDEO_ID to id, ARG_TITLE to title)
            }
            activity.supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, fragment)
                .addToBackStack(null)
                .commit()
        }

        private fun openInYouTubeApp(context: Context, idOrUrl: String) {
            val url = if (idOrUrl.startsWith("http")) idOrUrl else "https://www.youtube.com/watch?v=$idOrUrl"
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        private tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
            is FragmentActivity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}
