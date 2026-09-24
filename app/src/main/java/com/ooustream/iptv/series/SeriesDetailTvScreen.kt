package com.ooustream.iptv.series

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.LifecycleCoroutineScope
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.R
import com.ooustream.iptv.common.PosterUrlRewriter
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Episode
import com.ooustream.iptv.data.model.SeriesInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * View binding + D-pad behaviour for fragment_series_detail_tv. The fragment owns the ViewModel and
 * the playback launch; this class only renders state and reports intent.
 *
 * Focus model: the primary action takes focus on arrival. Seasons (left) select on focus after a
 * short settle so scrolling past a season doesn't reload the list for each one; OK on a season
 * jumps into its episodes. RIGHT from anywhere on the left lands in the episode list.
 */
class SeriesDetailTvScreen(
    root: View,
    private val scope: LifecycleCoroutineScope,
    private val seriesName: () -> String,
    private val onPlay: (episode: Episode, fromStart: Boolean) -> Unit,
    private val onSelectSeason: (String) -> Unit
) {
    private val backdrop: ImageView = root.findViewById(R.id.sd_backdrop)
    private val title: TextView = root.findViewById(R.id.sd_title)
    private val year: TextView = root.findViewById(R.id.sd_year)
    private val genre: TextView = root.findViewById(R.id.sd_genre)
    private val rating: TextView = root.findViewById(R.id.sd_rating)
    private val primary: LinearLayout = root.findViewById(R.id.sd_primary)
    private val primaryLabel: TextView = root.findViewById(R.id.sd_primary_label)
    private val primarySub: TextView = root.findViewById(R.id.sd_primary_sub)
    private val startOver: TextView = root.findViewById(R.id.sd_start_over)
    private val seasons: VerticalGridView = root.findViewById(R.id.sd_seasons)
    private val seasonsSpacer: View = root.findViewById(R.id.sd_seasons_spacer)
    private val plot: TextView = root.findViewById(R.id.sd_plot)
    private val seasonHeading: TextView = root.findViewById(R.id.sd_season_heading)
    private val episodes: VerticalGridView = root.findViewById(R.id.sd_episodes)
    private val loading: ProgressBar = root.findViewById(R.id.sd_loading)

    private var target: ResumeTarget? = null
    private var tabs: List<SeasonTab> = emptyList()
    private var initialFocusDone = false
    private var seasonSettleJob: Job? = null

    private val episodeAdapter = EpisodeRowTvAdapter(
        seriesName = seriesName,
        onEpisodeClicked = { onPlay(it, false) }
    )
    private val seasonAdapter = SeasonRowTvAdapter(
        onSeasonFocused = { tab ->
            seasonSettleJob?.cancel()
            seasonSettleJob = scope.launch {
                delay(SEASON_SETTLE_MS)
                onSelectSeason(tab.key)
            }
        },
        onSeasonClicked = { tab ->
            seasonSettleJob?.cancel()
            onSelectSeason(tab.key)
            episodes.post { if (episodeAdapter.itemCount > 0) episodes.requestFocus() }
        }
    )

    init {
        episodes.adapter = episodeAdapter
        episodes.itemAnimator = null
        episodes.setAnimateChildLayout(false)
        // Focused row sits a third of the way down once scrolling starts; BOTH_EDGE keeps the first
        // row pinned to the top instead of pushing it down to that line on arrival.
        episodes.windowAlignment = VerticalGridView.WINDOW_ALIGN_BOTH_EDGE
        episodes.windowAlignmentOffsetPercent = 30f
        seasons.adapter = seasonAdapter
        seasons.itemAnimator = null
        // Short list: keep Season 1 pinned at the top instead of centring the focused season.
        seasons.windowAlignment = VerticalGridView.WINDOW_ALIGN_BOTH_EDGE

        // LEFT out of the episode list returns to the season being browsed (geometrically the Play
        // button is often nearer, which lost the viewer's place); single-season shows fall through
        // to normal focus search, which lands on the primary action.
        episodes.setOnKeyInterceptListener { e ->
            if (e.keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT || seasons.visibility != View.VISIBLE) {
                return@setOnKeyInterceptListener false
            }
            if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                val idx = seasonAdapter.currentList.indexOfFirst { it.key == seasonAdapter.selectedKey }
                if (idx >= 0) {
                    seasons.selectedPosition = idx
                    seasons.requestFocus()
                }
            }
            true
        }

        primary.setOnClickListener { target?.let { onPlay(it.episode, false) } }
        startOver.setOnClickListener { target?.let { onPlay(it.episode, true) } }
        val lift = View.OnFocusChangeListener { v, has ->
            val s = if (has) 1.04f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(150).start()
        }
        primary.pivotX = 0f
        startOver.pivotX = 0f
        primary.onFocusChangeListener = lift
        startOver.onFocusChangeListener = lift
    }

    fun setLoading(isLoading: Boolean) {
        loading.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    fun bindSeries(info: SeriesInfo) {
        val d = info.info
        // "(Un)Well (2020)" → "(Un)Well": the year is already in the meta row.
        title.text = (d?.name?.takeIf { it.isNotBlank() } ?: seriesName())
            .replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "")
        val backdropUrl = d?.backdropPath?.firstOrNull() ?: d?.cover
        if (!backdropUrl.isNullOrBlank()) {
            backdrop.load(PosterUrlRewriter.rewriteBackdrop(backdropUrl)) {
                crossfade(true)
                memoryCachePolicy(CachePolicy.ENABLED)
            }
        }
        year.show(d?.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) })
        genre.show(d?.genre?.split(",", "/")?.firstOrNull()?.trim())
        rating.show(d?.rating5based?.takeIf { it > 0 }?.let { "★ %.1f".format(it) })
        plot.show(d?.plot)
    }

    fun bindSeasons(list: List<SeasonTab>) {
        tabs = list
        val many = list.size > 1
        seasons.visibility = if (many) View.VISIBLE else View.GONE
        seasonsSpacer.visibility = if (many) View.GONE else View.VISIBLE
        seasonAdapter.submitList(list)
    }

    fun bindSelectedSeason(key: String) {
        seasonAdapter.selectedKey = key
        updateHeading()
    }

    fun bindEpisodes(list: List<Episode>) {
        episodeAdapter.submitList(list) {
            // Synchronously in-bounds, never via post{} — see project_vod_grid_position_minus1.
            if (list.isNotEmpty()) episodes.selectedPosition = 0
        }
        updateHeading(list.size)
    }

    fun bindProgress(map: Map<String, WatchProgressEntity>) {
        episodeAdapter.watchProgressMap = map
    }

    fun bindEpisodeNames(names: Map<String, String>) {
        episodeAdapter.episodeNames = names
        target?.let { renderPrimarySub(it) }
    }

    fun bindResumeTarget(t: ResumeTarget?) {
        target = t
        if (t == null) {
            primary.visibility = View.INVISIBLE
            startOver.visibility = View.GONE
            return
        }
        val ctx = primary.context
        val se = seasonNumberOf(t.episode)?.let { "S$it E${t.episode.episodeNum}" } ?: "E${t.episode.episodeNum}"
        primaryLabel.text = when (t.kind) {
            ResumeTarget.Kind.RESUME -> ctx.getString(R.string.sd_resume, se)
            ResumeTarget.Kind.REWATCH -> ctx.getString(R.string.sd_watch_again, se)
            else -> ctx.getString(R.string.sd_play, se)
        }
        renderPrimarySub(t)
        primary.visibility = View.VISIBLE
        startOver.visibility = if (t.kind == ResumeTarget.Kind.RESUME) View.VISIBLE else View.GONE
        if (!initialFocusDone) {
            initialFocusDone = true
            primary.requestFocus()
        }
    }

    private fun renderPrimarySub(t: ResumeTarget) {
        primarySub.text = when (t.kind) {
            ResumeTarget.Kind.RESUME -> primarySub.context.getString(R.string.sd_min_left, t.minutesLeft)
            else -> episodeAdapter.episodeNames[t.episode.id]
                ?: com.ooustream.iptv.common.MediaTitleFormatter
                    .episodeOwnTitle(seriesName(), t.episode.title)
                    .ifBlank { EpisodeRowTvAdapter.formatRuntime(t.episode).orEmpty() }
        }
        primarySub.visibility = if (primarySub.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun updateHeading(count: Int = episodeAdapter.itemCount) {
        val name = tabs.firstOrNull { it.key == seasonAdapter.selectedKey }?.displayName ?: return
        seasonHeading.text = if (count == 1) seasonHeading.context.getString(R.string.sd_season_heading_one, name)
        else seasonHeading.context.getString(R.string.sd_season_heading, name, count)
    }

    /** The episode's season number; for listings without one, the key of a single-season show. */
    private fun seasonNumberOf(ep: Episode): String? =
        ep.season?.takeIf { it > 0 }?.toString()
            ?: tabs.singleOrNull()?.key?.takeIf { k -> k.toIntOrNull()?.let { it in 1..99 } == true }

    private fun TextView.show(value: String?) {
        visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
        text = value
    }

    private companion object {
        const val SEASON_SETTLE_MS = 250L
    }
}
