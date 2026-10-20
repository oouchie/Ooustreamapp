package com.ooustream.iptv.series.spotlight

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import com.ooustream.iptv.MainActivity
import com.ooustream.iptv.R
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.series.SeriesDetailFragment
import com.ooustream.iptv.series.SeriesFragment
import com.ooustream.iptv.vod.spotlight.SpotlightFocus
import dagger.hilt.android.AndroidEntryPoint

/**
 * Series, design B ("Spotlight + Rows", the "series 1" pick), drawn with Compose for TV. Trial
 * screen on the Kotlin 2 branch — the Leanback [SeriesFragment] is untouched and is where
 * "All Series" / "See all" lead.
 */
@AndroidEntryPoint
class SeriesSpotlightFragment : Fragment() {

    private val viewModel: SeriesSpotlightViewModel by viewModels()

    // Survives on the back stack while the view is destroyed: Back lands on the card that was opened.
    private var lastFocus = SpotlightFocus()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SeriesSpotlightScreen(
                    viewModel = viewModel,
                    initialFocus = lastFocus,
                    onFocusSaved = { f ->
                        // Only while on screen: focus moving inside the detaching view on the way
                        // out would overwrite the card that was opened.
                        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            lastFocus = f
                        }
                    },
                    onOpen = { show, newest ->
                        push(SeriesDetailFragment.newInstance(show.id, show.name, focusNewest = newest), TransitionDirection.FORWARD)
                    },
                    onResume = ::resume,
                    onBrowse = { id ->
                        push(if (id == null) SeriesFragment() else SeriesFragment.newInstance(id), TransitionDirection.FORWARD)
                    },
                    onNavigate = { section ->
                        val main = activity as? MainActivity ?: return@SeriesSpotlightScreen
                        if (section == "home") main.navigateToHome() else main.navigateFromSidebar(section)
                    }
                )
            }
        }

    /** Same player entry as Home's Continue Watching. */
    private fun resume(item: WatchProgressEntity) {
        val url = viewModel.resumeUrl(item) ?: return
        push(
            OoustreamPlaybackFragment.newInstance(
                streamUrl = url,
                contentType = ContentType.SERIES,
                streamId = item.streamId,
                streamName = item.name,
                streamIcon = item.icon ?: "",
                seriesId = item.seriesId ?: 0,
                seasonNum = item.seasonNum ?: 0,
                episodeNum = item.episodeNum ?: 0
            ),
            TransitionDirection.PLAYER
        )
    }

    private fun push(fragment: Fragment, direction: TransitionDirection) {
        parentFragmentManager.beginTransaction()
            .also { tx -> FragmentTransitions.apply(tx, direction) }
            .replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}
