package com.ooustream.iptv.splash

import android.animation.ObjectAnimator
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class IntroSplashFragment : Fragment(), KeyEventHandler {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val hasSkipped = AtomicBoolean(false)

    var onFinished: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        playerView = PlayerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false
        }
        return playerView!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(requireContext()).apply {
            setEnableDecoderFallback(true)
        }
        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(renderersFactory)
            .build().apply {
            val uri = RawResourceDataSource.buildRawResourceUri(R.raw.intro)
            setMediaItem(MediaItem.fromUri(uri))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) skip()
                }
                override fun onPlayerError(error: PlaybackException) {
                    skip() // Never block app on video error
                }
            })
            prepare()
            play()
        }
        playerView?.player = player

        // Max 5 seconds for intro video — skip if it takes too long
        viewLifecycleOwner.lifecycleScope.launch {
            delay(5000)
            skip()
        }
    }

    private fun skip() {
        if (!hasSkipped.compareAndSet(false, true)) return
        val view = playerView ?: run { onFinished?.invoke(); return }

        ObjectAnimator.ofFloat(view, "alpha", 1f, 0f).apply {
            duration = 400
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    try { player?.release() } catch (_: Exception) {}
                    player = null
                    // Post to avoid calling fragment transactions inside animation callbacks,
                    // and check lifecycle to prevent IllegalStateException after onSaveInstanceState
                    view.post {
                        if (isAdded && activity?.isFinishing == false) {
                            onFinished?.invoke()
                        }
                    }
                }
            })
            start()
        }
    }

    override fun onKeyEvent(keyCode: Int): Boolean {
        if (!hasSkipped.get()) {
            skip()
            return true
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { player?.release() } catch (_: Exception) {}
        player = null
        playerView = null
    }
}
