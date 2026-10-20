package com.ooustream.iptv.livetv.bleed

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ooustream.iptv.R
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.common.compose.SidebarGate
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.epg.SmartEpgFiller
import com.ooustream.iptv.livetv.LiveTvViewModel
import com.ooustream.iptv.player.ChannelListHolder
import com.ooustream.iptv.player.LivePreviewManager
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Live TV, "Full-Bleed" design, drawn with Compose for TV. Trial screen on the Kotlin 2 branch —
 * the Leanback [com.ooustream.iptv.livetv.LiveTvFragment] is untouched (`ooustream://oldlive`).
 *
 * The channel the cursor rests on plays behind the whole screen, with sound. OK opens it full
 * screen, hold OK adds/removes a favourite. One stream at a time: the background player is
 * released before the full player opens (1-connection accounts).
 */
@AndroidEntryPoint
class LiveFullBleedFragment : Fragment(), SidebarGate {

    private val viewModel: LiveTvViewModel by viewModels()

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var smartEpgFiller: SmartEpgFiller

    private var preview: LivePreviewManager? = null
    private var playerView: PlayerView? = null

    /** What the background player is doing, for the info panel. */
    private val tune = MutableStateFlow<TuneState>(TuneState.Idle)
    private var tunedId: Int? = null
    private var lastTuned: LiveStream? = null
    private var tuneStartedAt = 0L

    // Survive on the back stack while the view is destroyed.
    private var lastFocus: Int? = null
    private var listFocused = false
    private var viewFresh = false

    /** 1GB sticks: no background video (same gate as the old screen's auto-preview). */
    private val lowMemory by lazy {
        (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass <= 128
    }

    override fun allowsSidebar(): Boolean = listFocused

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = FrameLayout(ctx).apply { setBackgroundColor(0xFF0A0A0A.toInt()) }
        val pv = PlayerView(ctx).apply {
            useController = false
            controllerAutoShow = false
            isFocusable = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // full bleed: crop a sliver rather than show bars
            setShutterBackgroundColor(0xFF0A0A0A.toInt())
        }
        root.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playerView = pv
        viewFresh = true

        // Returning from the full player: land on the channel it ended on (zapping changes it).
        ChannelListHolder.lastPlayedStreamId.takeIf { it > 0 }?.let { lastFocus = it }
        ChannelListHolder.lastPlayedStreamId = -1
        ChannelListHolder.lastPlayedChannel = null
        ChannelListHolder.lastPlayedIndex = -1

        val compose = ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiveFullBleedScreen(
                    viewModel = viewModel,
                    tuneFlow = tune,
                    initialFocus = lastFocus,
                    inferNow = { ch, category -> smartEpgFiller.inferRuleBased(ch.name, category).title },
                    onChannelFocused = { ch ->
                        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            lastFocus = ch?.streamId
                        }
                        listFocused = ch != null
                    },
                    onTune = ::tuneTo,
                    onOpen = ::goFullscreen,
                    onFavorite = { viewModel.toggleFavorite(it) }
                )
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (viewModel.categories.value.isEmpty()) viewModel.loadCategories()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toastEvent.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun tuneTo(channel: LiveStream) {
        viewModel.loadEpg(channel.streamId)
        lastTuned = channel
        if (lowMemory || tunedId == channel.streamId) return
        val pv = playerView ?: return
        val mgr = preview ?: LivePreviewManager(requireContext(), okHttpClient).apply {
            setAudible()
            onError = {
                Log.w(TAG, "tune failed id=$tunedId after ${SystemClock.elapsedRealtime() - tuneStartedAt}ms")
                tune.value = TuneState.Failed
            }
            onFirstFrame = {
                Log.w(TAG, "first frame id=$tunedId after ${SystemClock.elapsedRealtime() - tuneStartedAt}ms")
                tune.value = TuneState.Playing
            }
            preview = this
        }
        tunedId = channel.streamId
        tuneStartedAt = SystemClock.elapsedRealtime()
        Log.w(TAG, "tune id=${channel.streamId} ${channel.name}")
        tune.value = TuneState.Tuning
        mgr.startPreview(pv, viewModel.buildStreamUrl(channel.streamId))
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopBackground() {
        preview?.release()
        preview = null
        tunedId = null
        tune.value = TuneState.Idle
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun goFullscreen(channel: LiveStream, list: List<LiveStream>) {
        stopBackground()
        lastFocus = channel.streamId
        ChannelListHolder.channels = list
        ChannelListHolder.currentIndex = list.indexOfFirst { it.streamId == channel.streamId }.coerceAtLeast(0)
        val fragment = OoustreamPlaybackFragment.newInstance(
            streamUrl = viewModel.buildStreamUrl(channel.streamId),
            contentType = ContentType.LIVE,
            streamId = channel.streamId.toString(),
            streamName = channel.name
        )
        parentFragmentManager.beginTransaction()
            .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.PLAYER) }
            .replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onResume() {
        super.onResume()
        // Home button and back: the view survived, so focus didn't move and nothing re-tunes.
        // A fresh view (back from the player) tunes itself when focus is restored.
        if (!viewFresh && preview == null) lastTuned?.let { tuneTo(it) }
        viewFresh = false
    }

    override fun onPause() {
        // Free the stream (and the account's connection slot) whenever we're not on screen.
        stopBackground()
        super.onPause()
    }

    override fun onDestroyView() {
        stopBackground()
        playerView = null
        super.onDestroyView()
    }
}

private const val TAG = "OOUSTREAM_LIVE"

sealed interface TuneState {
    data object Idle : TuneState
    data object Tuning : TuneState
    data object Playing : TuneState
    data object Failed : TuneState
}
