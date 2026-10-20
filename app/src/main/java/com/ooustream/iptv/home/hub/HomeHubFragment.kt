package com.ooustream.iptv.home.hub

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
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.repository.CredentialStore
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.home.FeaturedItem
import com.ooustream.iptv.home.ForYouChannel
import com.ooustream.iptv.player.ChannelListHolder
import com.ooustream.iptv.series.SeriesDetailFragment
import com.ooustream.iptv.home.HomeViewModel
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.vod.VodDetailFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home, design 3 ("Hub with Section Tiles"), drawn with Compose for TV. Trial screen on the
 * Kotlin 2 branch. Same data as the Leanback [com.ooustream.iptv.home.HomeFragment] — it shares
 * [HomeViewModel], so adult/parental filtering and the history-art override come along unchanged.
 */
@AndroidEntryPoint
class HomeHubFragment : Fragment(), com.ooustream.iptv.common.compose.SidebarGate {

    private val viewModel: HomeViewModel by viewModels()

    @Inject lateinit var credentialStore: CredentialStore

    // Survives on the back stack while the view is destroyed: Back lands on what was opened.
    private var lastFocus = HubFocus.DEFAULT

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HomeHubScreen(
                    viewModel = viewModel,
                    username = loadUsername(),
                    initialFocus = lastFocus,
                    onFocusSaved = { key ->
                        // Only while on screen: focus moving inside the detaching view on the way
                        // out would overwrite the item that was opened.
                        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            lastFocus = key
                        }
                    },
                    onPlayFeatured = ::playFeatured,
                    onFeaturedInfo = { openMovie(it.streamId.toIntOrNull(), it.title, it.backdropUrl, it.containerExtension) },
                    onTile = ::openSection,
                    onAction = ::handle,
                    onRemove = ::confirmRemove
                )
            }
        }

    /** Double-LEFT opens the quick menu only from the hero's Play button. */
    override fun allowsSidebar(): Boolean = lastFocus == HubFocus.DEFAULT

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The view model outlives the view on the back stack; only load once.
        if (viewModel.featuredContent.value.isEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch { viewModel.loadFeaturedContent() }
        }
    }

    private fun loadUsername(): String? = try {
        credentialStore.load()?.username?.replaceFirstChar { it.uppercase() }
    } catch (_: Exception) { null }

    private fun openSection(section: String) {
        val main = activity as? MainActivity ?: return
        when (section) {
            "guide" -> main.navigateToEpgGuide(null, null)
            "catchup" -> push(com.ooustream.iptv.catchup.CatchUpFragment(), TransitionDirection.FORWARD)
            else -> main.navigateFromSidebar(section)
        }
    }

    private fun openMovie(id: Int?, name: String, cover: String?, ext: String?) {
        id ?: return
        push(VodDetailFragment.newInstance(id, name, cover, ext), TransitionDirection.FORWARD)
    }

    private fun playFeatured(item: FeaturedItem) {
        val id = item.streamId.toIntOrNull() ?: return
        playMovie(id, item.title, item.backdropUrl, item.containerExtension)
    }

    /** Hold-OK on a Pick Up & New card. android.app.AlertDialog: Leanback theme isn't AppCompat. */
    private fun confirmRemove(title: String, removal: HubRemoval, confirmed: () -> Unit) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(arrayOf("Remove from list", "Cancel")) { _, which ->
                if (which != 0) return@setItems
                confirmed()
                when (removal) {
                    is HubRemoval.ClearNew -> viewModel.clearNewEpisodes(removal.seriesId)
                    is HubRemoval.DismissWatched -> viewModel.dismissWatchAgain(removal.streamId)
                    is HubRemoval.DismissProgress -> viewModel.dismissContinueWatching(removal.item)
                }
            }
            .show()
    }

    private fun handle(action: HubAction) {
        when (action) {
            is HubAction.Resume -> resume(action.item)
            is HubAction.OpenMovie -> openMovie(action.id, action.name, action.cover, action.ext)
            is HubAction.PlayMovie -> playMovie(action.id, action.name, action.icon, action.ext)
            is HubAction.OpenSeries -> {
                // Clear the NEW badge at once so the card leaves Pick Up & New (as HomeFragment does).
                if (action.clearNew) viewModel.clearNewEpisodes(action.id)
                push(SeriesDetailFragment.newInstance(action.id, action.name, focusNewest = action.newest), TransitionDirection.FORWARD)
            }
            is HubAction.PlayLive -> playLive(action.channel)
        }
    }

    /** Same resume prompt and player entry as HomeFragment.playVodWithResumeCheck(). */
    private fun playMovie(id: Int, name: String, icon: String?, ext: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = try { viewModel.getWatchProgress(id.toString()) } catch (_: Exception) { null }
            if (!isAdded) return@launch
            ResumePlaybackHelper.showIfNeeded(requireContext(), progress) { forceBeginning ->
                if (!isAdded) return@showIfNeeded
                push(
                    OoustreamPlaybackFragment.newInstance(
                        streamUrl = viewModel.buildVodStreamUrl(id, ext?.takeIf { it.isNotBlank() } ?: "mp4"),
                        contentType = ContentType.VOD,
                        streamId = id.toString(),
                        streamName = name,
                        streamIcon = icon ?: "",
                        forceStartFromBeginning = forceBeginning
                    ),
                    TransitionDirection.PLAYER
                )
            }
        }
    }

    /** Same as HomeFragment.navigateToLiveChannel(): the Live Now row becomes the zap list. */
    private fun playLive(channel: ForYouChannel) {
        val all = viewModel.forYouLiveNow.value.ifEmpty { listOf(channel) }
        ChannelListHolder.channels = all.map { ch ->
            LiveStream(
                num = null, name = ch.channelName, streamType = "live",
                streamId = ch.channelId, streamIcon = ch.channelIcon,
                epgChannelId = null, added = null, categoryId = null,
                customSid = null, tvArchive = null, directSource = null,
                tvArchiveDuration = null
            )
        }
        ChannelListHolder.currentIndex = all.indexOfFirst { it.channelId == channel.channelId }.coerceAtLeast(0)
        push(
            OoustreamPlaybackFragment.newInstance(
                streamUrl = viewModel.buildLiveStreamUrl(channel.channelId),
                contentType = ContentType.LIVE,
                streamId = channel.channelId.toString(),
                streamName = channel.channelName
            ),
            TransitionDirection.PLAYER
        )
    }

    /** Same as HomeFragment.navigateToContinueWatching(). */
    private fun resume(item: WatchProgressEntity) {
        val url = viewModel.resumeStreamUrl(item) ?: return
        val type = when (item.type.lowercase()) {
            "live" -> ContentType.LIVE
            "series" -> ContentType.SERIES
            else -> ContentType.VOD
        }
        push(
            OoustreamPlaybackFragment.newInstance(
                streamUrl = url,
                contentType = type,
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
