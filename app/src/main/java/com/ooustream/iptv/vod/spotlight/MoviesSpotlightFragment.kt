package com.ooustream.iptv.vod.spotlight

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.ooustream.iptv.MainActivity
import com.ooustream.iptv.R
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.ResumePlaybackHelper
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.vod.VodDetailFragment
import com.ooustream.iptv.vod.VodFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Movies, design B ("Spotlight + Rows"), drawn with Compose for TV. Trial screen on the
 * Kotlin 2 branch — the Leanback [com.ooustream.iptv.vod.VodFragment] is untouched.
 */
@AndroidEntryPoint
class MoviesSpotlightFragment : Fragment() {

    private val viewModel: MoviesSpotlightViewModel by viewModels()

    // The fragment instance survives on the back stack while its view is destroyed, so this
    // brings focus back to the card that was opened.
    private var lastFocus = SpotlightFocus()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MoviesSpotlightScreen(
                    viewModel = viewModel,
                    initialFocus = lastFocus,
                    onFocusSaved = { f ->
                        // Only while we're on screen: on the way out, focus moving inside the
                        // detaching ComposeView would overwrite the card that was opened.
                        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            lastFocus = f
                        }
                    },
                    onOpen = ::openDetail,
                    onPlay = ::play,
                    onBrowse = ::openFullList,
                    onNavigate = { section ->
                        val main = activity as? MainActivity ?: return@MoviesSpotlightScreen
                        if (section == "home") main.navigateToHome() else main.navigateFromSidebar(section)
                    }
                )
            }
        }

    /** The full Leanback grid + "Browse by" sidebar, optionally starting on one category. */
    private fun openFullList(categoryId: String?) {
        val fragment = if (categoryId == null) VodFragment() else VodFragment.newInstance(categoryId)
        parentFragmentManager.beginTransaction()
            .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
            .replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openDetail(movie: SpotlightMovie) {
        val fragment = VodDetailFragment.newInstance(movie.id, movie.name, movie.poster, movie.ext)
        parentFragmentManager.beginTransaction()
            .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.FORWARD) }
            .replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Same resume prompt and player entry as VodDetailFragment.playMovie(). */
    private fun play(movie: SpotlightMovie) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = viewModel.progressFor(movie)
            if (!isAdded) return@launch
            ResumePlaybackHelper.showIfNeeded(requireContext(), progress) { forceBeginning ->
                val fragment = OoustreamPlaybackFragment.newInstance(
                    streamUrl = viewModel.buildStreamUrl(movie),
                    contentType = ContentType.VOD,
                    streamId = movie.id.toString(),
                    streamName = movie.name,
                    streamIcon = movie.poster ?: "",
                    forceStartFromBeginning = forceBeginning
                )
                parentFragmentManager.beginTransaction()
                    .also { tx -> FragmentTransitions.apply(tx, TransitionDirection.PLAYER) }
                    .replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        }
    }
}
