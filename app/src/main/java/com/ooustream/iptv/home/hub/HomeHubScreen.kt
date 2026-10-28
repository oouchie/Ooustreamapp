package com.ooustream.iptv.home.hub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.ooustream.iptv.R
import com.ooustream.iptv.catalog.CatalogBrowse
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.home.FeaturedItem
import com.ooustream.iptv.home.ForYouChannel
import com.ooustream.iptv.recommendation.BecauseYouWatchedRow
import com.ooustream.iptv.recommendation.RecommendedItem
import com.ooustream.iptv.home.HomeViewModel
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private val Bg = Color(0xFF0A0A0A)
private val Accent = Color(0xFF00D4FF)
private val Muted = Color(0xFFC9CED5)
private val Dim = Color(0xFF8A929C)
private val Panel = Color(0xFF121212)
private const val HERO_WAIT_MS = 4_000L
private const val PANEL = "panel"
private val Grotesk = FontFamily(Font(R.font.space_grotesk_bold, FontWeight.Bold))

/** Focus keys, so Back from a detail page or the player lands on the item that was opened. */
object HubFocus {
    const val DEFAULT = "play"
    fun cw(i: Int) = "cw:$i"
    fun tile(id: String) = "tile:$id"
    fun row(row: String, i: Int) = "row:$row:$i"
}

/** What a card does when clicked. The fragment owns navigation; these mirror HomeFragment's rows. */
sealed interface HubAction {
    data class Resume(val item: WatchProgressEntity) : HubAction
    data class OpenMovie(val id: Int, val name: String, val cover: String?, val ext: String?) : HubAction
    data class PlayMovie(val id: Int, val name: String, val icon: String?, val ext: String?) : HubAction
    /** [newest]: land on the newest episode instead of the Play button (Pick Up & New). */
    data class OpenSeries(val id: Int, val name: String, val clearNew: Boolean = false, val newest: Boolean = false) : HubAction
    data class PlayLive(val channel: ForYouChannel) : HubAction
}

/** Hold-OK "Remove from list" on a Continue Watching or Pick Up & New card. */
sealed interface HubRemoval {
    data class DismissProgress(val item: WatchProgressEntity) : HubRemoval
    data class ClearNew(val seriesId: Int) : HubRemoval
    data class DismissWatched(val streamId: String) : HubRemoval
}

private data class HubCard(
    val key: String,
    val title: String,
    val image: String?,
    val action: HubAction,
    val progress: Float? = null,
    val badge: String? = null,
    val subtitle: String? = null,
    val removal: HubRemoval? = null,
    /** Season/episode line drawn along the bottom of a poster card ("S2 E4"). */
    val caption: String? = null
)

private fun seasonEpisode(season: Int?, episode: Int?): String? =
    if (season != null && season > 0 && episode != null && episode > 0) "S$season E$episode" else null

private data class HubRow(val key: String, val title: String, val cards: List<HubCard>, val live: Boolean = false)

private data class Tile(val id: String, val label: String, val sub: String, val icon: Int, val color: Color)

// No counts on the tiles: the channel/title totals aren't loaded on Home, and a made-up number is
// worse than none.
private val TILES = listOf(
    Tile("live", "Live TV", "Channels", R.drawable.ic_live_tv, Color(0xFF123A4A)),
    Tile("guide", "TV Guide", "What’s on now", R.drawable.ic_guide, Color(0xFF1C2F44)),
    Tile("vod", "Movies", "Browse movies", R.drawable.ic_movies, Color(0xFF2B2440)),
    Tile("series", "Series", "Browse shows", R.drawable.ic_series, Color(0xFF3A2A1C)),
    Tile("catchup", "Catch Up", "Replay past shows", R.drawable.ic_catch_up, Color(0xFF1D3A2E)),
    Tile("favorites", "Favorites", "Your list", R.drawable.ic_favorites, Color(0xFF3A1C24))
)

@Composable
fun HomeHubScreen(
    viewModel: HomeViewModel,
    username: String?,
    /** Device passed UserPlanManager.isDeviceCapable(); the MultiView button is hidden otherwise. */
    multiViewAvailable: Boolean,
    initialFocus: String,
    onFocusSaved: (String) -> Unit,
    onPlayFeatured: (FeaturedItem) -> Unit,
    onFeaturedInfo: (FeaturedItem) -> Unit,
    onTile: (String) -> Unit,
    onAction: (HubAction) -> Unit,
    onRemove: (title: String, removal: HubRemoval, confirmed: () -> Unit) -> Unit
) {
    val featured by viewModel.featuredContent.collectAsState()
    // The history flows are cold (Room): null until their first emission, so a focus target in
    // them waits instead of falling back.
    val continueWatching by viewModel.continueWatching.collectAsState(initial = null)
    val newEpisodes by viewModel.newEpisodes.collectAsState(initial = null)
    val watchItAgain by viewModel.watchItAgain.collectAsState(initial = null)
    val top10 by viewModel.top10Content.collectAsState()
    val forYou by viewModel.forYouContent.collectAsState()
    val becauseYouWatched by viewModel.becauseYouWatchedRows.collectAsState()
    val liveNow by viewModel.forYouLiveNow.collectAsState()
    val trending by viewModel.trendingContent.collectAsState()
    val trendingSeries by viewModel.trendingSeries.collectAsState()
    val genreRows by viewModel.genreRows.collectAsState()

    val hero = featured.firstOrNull()
    val pickUp = continueWatching.orEmpty().take(3)
    val rows = remember(continueWatching, newEpisodes, watchItAgain, top10, forYou, becauseYouWatched, liveNow, trending, trendingSeries, genreRows) {
        buildRows(
            continueWatching.orEmpty(), newEpisodes.orEmpty(), watchItAgain.orEmpty(), top10, forYou,
            becauseYouWatched, liveNow, trending, trendingSeries, genreRows
        )
    }
    val historyLoaded = continueWatching != null && newEpisodes != null && watchItAgain != null

    // Restore once, as soon as the item it points at exists. Fall back to Play (or the first tile
    // before the featured title has loaded) when that item is gone — e.g. a finished show left CW.
    val requester = remember { FocusRequester() }
    var restored by remember { mutableStateOf(false) }
    val available = remember(rows, pickUp, hero) {
        buildSet {
            pickUp.indices.forEach { add(HubFocus.cw(it)) }
            rows.forEach { r -> r.cards.forEach { add(it.key) } }
            TILES.forEach { add(HubFocus.tile(it.id)) }
            add("search"); add("settings")
            if (multiViewAvailable) add("multiview")
            if (hero != null) { add("play"); add("info") }
        }
    }
    // The featured title arrives a moment after the screen; give it a few seconds before
    // settling for a tile, so a cold start lands on Play rather than mid-page.
    var heroTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(HERO_WAIT_MS); heroTimedOut = true }
    val target: String? = when {
        initialFocus in available -> initialFocus
        !historyLoaded -> null
        hero != null -> HubFocus.DEFAULT
        !heroTimedOut -> null
        else -> HubFocus.tile("live")
    }
    val scroll = rememberScrollState()
    LaunchedEffect(target) {
        if (restored || target == null) return@LaunchedEffect
        awaitFrame()
        if (runCatching { requester.requestFocus() }.isSuccess) {
            restored = true
            // Focusing Play brings it into view, which can push the greeting off the top.
            // That scroll is animated, so wait for it to settle before going back up.
            if (target == HubFocus.DEFAULT) {
                delay(350)
                scroll.animateScrollTo(0)
            }
        }
    }

    // After "Remove from list" the focused card disappears; move focus to whatever now sits in
    // its place (or the card before it) once the row has actually lost that card.
    var removed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var rowSizeAtRemoval by remember { mutableStateOf(0) }
    var removedPanelId by remember { mutableStateOf<String?>(null) }
    val refocusKey: String? = removed?.let { (row, i) ->
        val keys = if (row == PANEL) {
            // The panel always shows up to 3, so its size only drops once CW has fewer than 3.
            if (continueWatching.orEmpty().any { it.streamId == removedPanelId }) null
            else pickUp.indices.map { HubFocus.cw(it) }
        } else {
            rows.firstOrNull { it.key == row }?.cards?.map { it.key }.orEmpty()
                .takeIf { it.size != rowSizeAtRemoval } // the database hasn't caught up yet
        }
        when {
            keys == null -> null
            keys.isEmpty() -> HubFocus.DEFAULT
            else -> keys[minOf(i, keys.lastIndex)]
        }
    }
    val refocusRequester = remember { FocusRequester() }
    LaunchedEffect(refocusKey) {
        if (refocusKey == null) return@LaunchedEffect
        awaitFrame()
        runCatching { refocusRequester.requestFocus() }
        removed = null
    }

    fun Modifier.hub(key: String): Modifier =
        (if (key == target) focusRequester(requester) else this)
            .then(if (key == refocusKey) Modifier.focusRequester(refocusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocusSaved(key) }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Header(
                username, Modifier.hub("search"), Modifier.hub("settings"),
                Modifier.hub("multiview").takeIf { multiViewAvailable }, onTile
            )

            Row(Modifier.fillMaxWidth().height(220.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FeaturedCard(
                    hero,
                    Modifier.weight(1f).fillMaxHeight(),
                    playModifier = Modifier.hub("play"),
                    infoModifier = Modifier.hub("info"),
                    onPlay = { hero?.let(onPlayFeatured) },
                    onInfo = { hero?.let(onFeaturedInfo) }
                )
                if (pickUp.isNotEmpty()) {
                    PickUpPanel(
                        pickUp, { i -> Modifier.hub(HubFocus.cw(i)) },
                        onLongClick = { i, item ->
                            onRemove(item.name, HubRemoval.DismissProgress(item)) {
                                removedPanelId = item.streamId
                                removed = PANEL to i
                            }
                        }
                    ) { onAction(HubAction.Resume(it)) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (tile in TILES) {
                    TileCard(tile, Modifier.weight(1f).hub(HubFocus.tile(tile.id))) { onTile(tile.id) }
                }
            }
        }

        for (row in rows) {
            key(row.key) {
                Text(row.title, fontFamily = Grotesk, fontSize = 17.sp, color = Color.White, modifier = Modifier.padding(start = 36.dp, top = 4.dp))
                val start = row.cards.indexOfFirst { it.key == initialFocus }.coerceAtLeast(0)
                val rowState = rememberLazyListState(initialFirstVisibleItemIndex = start)
                LazyRow(
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // Edge to edge with the gutter inside the row: a focused card grows ~8%, and
                    // a row bounded at the gutter clipped its left/top border.
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 8.dp)
                ) {
                    items(row.cards, key = { it.key }) { card ->
                        if (row.live) {
                            LiveCard(card, Modifier.hub(card.key)) { onAction(card.action) }
                        } else {
                            PosterCard(
                                card, Modifier.hub(card.key),
                                onLongClick = card.removal?.let { r ->
                                    {
                                        onRemove(card.title, r) {
                                            rowSizeAtRemoval = row.cards.size
                                            removed = row.key to row.cards.indexOf(card)
                                        }
                                    }
                                }
                            ) { onAction(card.action) }
                        }
                    }
                }
            }
        }
    }
}

/** Same rows and dedupe as the Leanback HomeFragment; the For You rows sit right under Continue Watching. */
private fun buildRows(
    continueWatching: List<WatchProgressEntity>,
    newEpisodes: List<SeriesTrackingEntity>,
    watchItAgain: List<WatchProgressEntity>,
    top10: List<VodStream>,
    forYou: List<RecommendedItem>,
    becauseYouWatched: List<BecauseYouWatchedRow>,
    liveNow: List<ForYouChannel>,
    trending: List<VodStream>,
    trendingSeries: List<Series>,
    genreRows: List<HomeViewModel.GenreRow>
): List<HubRow> {
    val rows = mutableListOf<HubRow>()

    rows += HubRow("cw", "Continue Watching", continueWatching.mapIndexed { i, item ->
        HubCard(
            HubFocus.row("cw", i), item.name, item.icon, HubAction.Resume(item),
            progress = item.progressPercent.takeIf { !isUpNext(item) },
            badge = if (isUpNext(item)) "UP NEXT" else null,
            removal = HubRemoval.DismissProgress(item),
            caption = seasonEpisode(item.seasonNum, item.episodeNum)
        )
    })

    rows += HubRow("live", "For You — Live Now", liveNow.mapIndexed { i, ch ->
        HubCard(HubFocus.row("live", i), ch.channelName, ch.channelIcon, HubAction.PlayLive(ch),
            subtitle = ch.nowPlaying?.title)
    }, live = true)

    // The grouped "Because You Watched" rows replace the flat For You row when there are any.
    if (becauseYouWatched.isNotEmpty()) {
        becauseYouWatched.forEachIndexed { r, byw ->
            rows += HubRow("byw$r", "Because You Watched ${byw.seedTitle}",
                byw.recommendations.mapIndexed { i, rec -> recommendationCard("byw$r", i, rec) })
        }
    } else {
        rows += HubRow("foryou", "For You", forYou.mapIndexed { i, rec -> recommendationCard("foryou", i, rec) })
    }

    rows += HubRow("top10", "Top 10 Today", top10.mapIndexed { i, m ->
        HubCard(HubFocus.row("top10", i), m.name, m.streamIcon,
            HubAction.OpenMovie(m.streamId, m.name, m.streamIcon, m.containerExtension), badge = "${i + 1}")
    })

    // New episodes lead the rail; a watch-again series already shown as new isn't repeated.
    val newIds = newEpisodes.map { it.seriesId }.toSet()
    val again = watchItAgain.filterNot { it.type == "series" && it.seriesId != null && it.seriesId in newIds }
    rows += HubRow(
        "pickup", "Pick Up & New",
        newEpisodes.mapIndexed { i, s ->
            HubCard(
                HubFocus.row("pickup", i), s.seriesTitle, s.posterUrl,
                HubAction.OpenSeries(s.seriesId, s.seriesTitle, clearNew = true, newest = true),
                badge = if (s.newEpisodeCount > 0) "NEW · ${s.newEpisodeCount}" else "NEW",
                removal = HubRemoval.ClearNew(s.seriesId),
                caption = seasonEpisode(s.lastKnownSeason, s.lastKnownEpisode)?.let { "New $it" }
            )
        } + again.mapIndexed { j, item ->
            val action = if (item.type == "series" && item.seriesId != null) {
                HubAction.OpenSeries(item.seriesId, item.name, newest = true)
            } else {
                HubAction.Resume(item)
            }
            HubCard(
                HubFocus.row("pickup", newEpisodes.size + j), item.name, item.icon, action, badge = "✓",
                removal = HubRemoval.DismissWatched(item.streamId),
                caption = seasonEpisode(item.seasonNum, item.episodeNum)
            )
        }
    )

    rows += HubRow("trending", "Trending Now", trending.mapIndexed { i, m ->
        HubCard(HubFocus.row("trending", i), m.name, m.streamIcon,
            HubAction.OpenMovie(m.streamId, m.name, m.streamIcon, m.containerExtension))
    })

    rows += HubRow("tseries", "Trending Series", trendingSeries.mapIndexed { i, s ->
        HubCard(HubFocus.row("tseries", i), s.name, s.cover, HubAction.OpenSeries(s.seriesId, s.name))
    })

    genreRows.forEach { g ->
        val k = "genre:${g.categoryId}"
        rows += HubRow(k, g.genreName, g.items.mapIndexed { i, m ->
            HubCard(HubFocus.row(k, i), m.name, m.streamIcon,
                HubAction.OpenMovie(m.streamId, m.name, m.streamIcon, m.containerExtension))
        })
    }
    return rows.filter { it.cards.isNotEmpty() }
}

/** HomeFragment.navigateToRecommendation: series → detail, movie → play, channel → play. */
private fun recommendationCard(row: String, i: Int, rec: RecommendedItem): HubCard {
    val action = when (rec.type) {
        "series" -> HubAction.OpenSeries(rec.streamId, rec.name)
        "live" -> HubAction.PlayLive(ForYouChannel(rec.streamId, rec.name, rec.icon, null, null, null))
        else -> HubAction.PlayMovie(rec.streamId, rec.name, rec.icon, rec.containerExtension)
    }
    return HubCard(HubFocus.row(row, i), rec.name, rec.icon, action)
}

@Composable
private fun Header(
    username: String?,
    searchModifier: Modifier,
    settingsModifier: Modifier,
    /** Null when the device can't run MultiView. */
    multiViewModifier: Modifier?,
    onTile: (String) -> Unit
) {
    val clock by produceState(initialValue = now()) {
        while (true) {
            delay(30_000)
            value = now()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val greeting = greeting()
        Text(
            if (username.isNullOrBlank()) greeting else "$greeting, $username",
            fontFamily = Grotesk, fontSize = 22.sp, color = Color.White
        )
        Spacer(Modifier.weight(1f))
        IconButton(R.drawable.ic_search, "Search", searchModifier) { onTile("search") }
        multiViewModifier?.let { LabeledButton(R.drawable.ic_multiview_grid, "MultiView", it) { onTile("multiview") } }
        IconButton(R.drawable.ic_settings, "Settings", settingsModifier) { onTile("settings") }
        Text(clock, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun now(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

// Same buckets as HomeFragment.setupGreeting().
private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..20 -> "Good evening"
    else -> "Good night"
}

@Composable
private fun IconButton(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(38.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Accent.copy(alpha = 0.18f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Accent), shape = RoundedCornerShape(10.dp))
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** [IconButton] with its label beside the icon, for an entry that the bare icon doesn't explain. */
@Composable
private fun LabeledButton(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Accent.copy(alpha = 0.18f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Accent), shape = RoundedCornerShape(10.dp))
        )
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(18.dp)
            )
            Text(label, fontSize = 14.sp, color = Color.White)
        }
    }
}

@Composable
private fun FeaturedCard(
    hero: FeaturedItem?,
    modifier: Modifier,
    playModifier: Modifier,
    infoModifier: Modifier,
    onPlay: () -> Unit,
    onInfo: () -> Unit
) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A2129))) {
        if (hero == null) {
            Text("Loading…", fontSize = 14.sp, color = Dim, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        hero.backdropUrl?.let {
            AsyncImage(
                model = it, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Bg.copy(alpha = 0.92f), 0.55f to Bg.copy(alpha = 0.4f), 0.8f to Color.Transparent)
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 18.dp).width(330.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // The hero is the newest additions (HomeViewModel.loadFeaturedContent sorts by `added`).
            Text("NEW THIS WEEK", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Accent, letterSpacing = 1.4.sp)
            Text(
                if (hero.year.isNotBlank()) CatalogBrowse.withoutTrailingYear(hero.title) else hero.title,
                fontFamily = Grotesk, fontSize = 28.sp, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 30.sp
            )
            val meta = listOf(hero.year, genreLabel(hero.genre), hero.rating.takeIf { it.isNotBlank() }?.let { "★ $it" })
                .filter { !it.isNullOrBlank() }
                .joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                HeroButton("▶  Play", primary = true, playModifier, onPlay)
                HeroButton("More Info", primary = false, infoModifier, onInfo)
            }
        }
    }
}

/** Before get_vod_info lands, `genre` holds the category id (a number) — don't show that. */
private fun genreLabel(genre: String): String? =
    genre.split(',').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }

@Composable
private fun HeroButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
            contentColor = if (primary) Bg else Color.White,
            focusedContainerColor = if (primary) Accent else Color.White,
            focusedContentColor = Bg
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PickUpPanel(
    items: List<WatchProgressEntity>,
    keyFor: (Int) -> Modifier,
    onLongClick: (Int, WatchProgressEntity) -> Unit,
    onResume: (WatchProgressEntity) -> Unit
) {
    Column(
        Modifier.width(270.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Pick up where you left off", fontFamily = Grotesk, fontSize = 15.sp, color = Color.White,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        items.forEachIndexed { i, item ->
            Surface(
                onClick = { onResume(item) },
                onLongClick = { onLongClick(i, item) },
                modifier = keyFor(i).fillMaxWidth().height(54.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.White.copy(alpha = 0.08f)
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, Accent), shape = RoundedCornerShape(8.dp))
                )
            ) {
                Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Saved art is poster-shaped (movie poster / show cover), so the thumb is too.
                    Box(Modifier.size(width = 31.dp, height = 46.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2A))) {
                        item.icon?.takeIf { it.isNotBlank() }?.let {
                            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(resumeMeta(item), fontSize = 11.sp, color = Muted, maxLines = 1)
                        if (!isUpNext(item)) {
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF2A2A2A))) {
                                Box(Modifier.fillMaxWidth(item.progressPercent.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The "Up Next" placeholder row has position 0 — it's the next episode, not real progress. */
private fun isUpNext(item: WatchProgressEntity) = item.position <= 0L

private fun resumeMeta(item: WatchProgressEntity): String {
    // Series names already carry "S8 E13" (MediaTitleFormatter) — only add it when they don't.
    val episode = if (item.seasonNum != null && item.episodeNum != null) "S${item.seasonNum} E${item.episodeNum}" else null
    val showEpisode = episode?.takeIf { !item.name.contains(it) }
    val tail = if (isUpNext(item)) "Up next" else {
        val left = ((item.duration - item.position) / 60_000).coerceAtLeast(1)
        if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
    }
    return listOfNotNull(showEpisode, tail).joinToString(" · ")
}

@Composable
private fun TileCard(tile: Tile, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(70.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(containerColor = tile.color, focusedContainerColor = tile.color),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RoundedCornerShape(12.dp))
        )
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            Image(
                painter = painterResource(tile.icon), contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.7f)),
                modifier = Modifier.size(16.dp).align(Alignment.TopStart)
            )
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(tile.label, fontFamily = Grotesk, fontSize = 15.sp, color = Color.White, maxLines = 1)
                Text(tile.sub, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PosterCard(card: HubCard, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(width = 96.dp, height = 144.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RoundedCornerShape(8.dp))
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1A1A1A), focusedContainerColor = Color(0xFF1A1A1A))
    ) {
        Box(Modifier.fillMaxSize()) {
            // Title underneath shows through when there is no poster or it fails to load.
            Text(card.title, fontSize = 11.sp, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
            card.image?.takeIf { it.isNotBlank() }?.let {
                AsyncImage(model = it, contentDescription = card.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            card.badge?.let {
                Text(
                    it, fontFamily = Grotesk, fontSize = 11.sp, color = Bg,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Accent)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            card.caption?.let {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = if (card.progress != null) 8.dp else 5.dp)
                ) {
                    Text(it, fontFamily = Grotesk, fontSize = 12.sp, color = Color.White, maxLines = 1)
                }
            }
            card.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                }
            }
        }
    }
}

@Composable
private fun LiveCard(card: HubCard, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 170.dp, height = 96.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RoundedCornerShape(10.dp))
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF16222B), focusedContainerColor = Color(0xFF1C2C38))
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.06f))) {
                    card.image?.takeIf { it.isNotBlank() }?.let {
                        AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(card.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("LIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFE53935)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(card.subtitle ?: "", fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
