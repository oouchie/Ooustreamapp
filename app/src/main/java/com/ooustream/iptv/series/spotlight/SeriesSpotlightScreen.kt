package com.ooustream.iptv.series.spotlight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.ooustream.iptv.common.compose.NavRail
import com.ooustream.iptv.common.compose.PinFocusedRow
import com.ooustream.iptv.common.compose.PinnedRowList
import com.ooustream.iptv.common.compose.WithRowScroll
import com.ooustream.iptv.common.compose.TvTheme.Accent
import com.ooustream.iptv.common.compose.TvTheme.Bg
import com.ooustream.iptv.common.compose.TvTheme.CardBg
import com.ooustream.iptv.common.compose.TvTheme.Dim
import com.ooustream.iptv.common.compose.TvTheme.Grotesk
import com.ooustream.iptv.common.compose.TvTheme.Muted
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.vod.spotlight.SpotlightFocus
import kotlinx.coroutines.android.awaitFrame

@Composable
fun SeriesSpotlightScreen(
    viewModel: SeriesSpotlightViewModel,
    initialFocus: SpotlightFocus,
    onFocusSaved: (SpotlightFocus) -> Unit,
    onOpen: (SpotlightShow, newest: Boolean) -> Unit,
    onResume: (WatchProgressEntity) -> Unit,
    onBrowse: (categoryId: String?) -> Unit,
    onNavigate: (section: String) -> Unit
) {
    val rows by viewModel.rows.collectAsState()
    val hero by viewModel.hero.collectAsState()
    val states by viewModel.states.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    Row(Modifier.fillMaxSize().background(Bg)) {
        NavRail(active = "series", onNavigate = onNavigate)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Backdrop(hero)
            Column(Modifier.fillMaxSize()) {
                HeroText(
                    show = hero,
                    state = hero?.let { states[it.id] },
                    onOpen = onOpen,
                    onResume = onResume,
                    onAll = { onBrowse(null) }
                )
                when {
                    rows.isNotEmpty() -> Rows(rows, states, initialFocus, viewModel::focus, onFocusSaved, onOpen, onBrowse)
                    loading -> Message("Loading series…")
                    error != null -> Message(error ?: "")
                    else -> Message("No series to show")
                }
            }
        }
    }
}

@Composable
private fun Backdrop(show: SpotlightShow?) {
    val image = show?.backdrop ?: show?.cover
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.62f)) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.68f).fillMaxHeight()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                // Opaque until the picture starts (32% in), so its left edge never shows.
                Brush.horizontalGradient(0f to Bg, 0.33f to Bg, 0.48f to Bg.copy(alpha = 0.8f), 0.75f to Color.Transparent)
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg)))
    }
}

private fun episodeLabel(season: Int?, episode: Int?): String? =
    if (season != null && season > 0 && episode != null && episode > 0) "S$season E$episode" else null

private fun minutesLeft(item: WatchProgressEntity): String? {
    if (item.duration <= 0 || item.position <= 0) return null
    val m = ((item.duration - item.position) / 60_000).coerceAtLeast(1)
    return "${m}m left"
}

@Composable
private fun HeroText(
    show: SpotlightShow?,
    state: ShowState?,
    onOpen: (SpotlightShow, Boolean) -> Unit,
    onResume: (WatchProgressEntity) -> Unit,
    onAll: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().height(262.dp).padding(start = 40.dp, top = 30.dp, end = 360.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (show == null) return@Column
        val kicker = when (state) {
            is ShowState.InProgress -> "YOU'RE WATCHING"
            is ShowState.NewEpisodes -> "NEW EPISODES ADDED"
            null -> null
        }
        kicker?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Accent, letterSpacing = 1.2.sp) }
        Text(
            show.name, fontFamily = Grotesk, fontSize = 34.sp, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            show.year?.let { Text("$it", fontSize = 14.sp, color = Muted) }
            show.genre?.let { Text(it, fontSize = 14.sp, color = Muted) }
            show.rating?.let { Text("★ %.1f".format(it), fontSize = 14.sp, color = Accent) }
        }
        if (state is ShowState.InProgress) {
            val item = state.item
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                episodeLabel(item.seasonNum, item.episodeNum)?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                if (item.position > 0) {
                    Box(Modifier.width(200.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(item.progressPercent.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                    }
                }
                Text(minutesLeft(item) ?: "Up next", fontSize = 12.sp, color = Muted)
            }
        }
        Text(
            show.plot ?: "",
            fontSize = 14.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp, modifier = Modifier.height(42.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 2.dp)) {
            when (state) {
                is ShowState.InProgress -> {
                    val ep = episodeLabel(state.item.seasonNum, state.item.episodeNum)
                    HeroButton(listOfNotNull("▶  Resume", ep).joinToString(" "), primary = true) { onResume(state.item) }
                    HeroButton("Episodes", primary = false) { onOpen(show, false) }
                }
                is ShowState.NewEpisodes -> {
                    val ep = episodeLabel(state.tracking.lastKnownSeason, state.tracking.lastKnownEpisode)
                    HeroButton(listOfNotNull("New", ep).joinToString(" ") + "  ›", primary = true) { onOpen(show, true) }
                    HeroButton("Episodes", primary = false) { onOpen(show, false) }
                }
                null -> HeroButton("Episodes", primary = true) { onOpen(show, false) }
            }
            HeroButton("All Series", primary = false, onClick = onAll)
        }
    }
}

@Composable
private fun HeroButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
            contentColor = if (primary) Bg else Color.White,
            focusedContainerColor = if (primary) Accent else Color.White,
            focusedContentColor = Bg
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The small line under a card's title. */
private fun cardMeta(show: SpotlightShow, state: ShowState?): String = when (state) {
    is ShowState.InProgress -> listOfNotNull(
        episodeLabel(state.item.seasonNum, state.item.episodeNum),
        minutesLeft(state.item) ?: "Up next"
    ).joinToString(" · ")
    is ShowState.NewEpisodes -> listOfNotNull("NEW", episodeLabel(state.tracking.lastKnownSeason, state.tracking.lastKnownEpisode))
        .joinToString(" · ")
    null -> listOfNotNull(show.year?.toString(), show.rating?.let { "★ %.1f".format(it) }).joinToString(" · ")
}

@Composable
private fun Rows(
    rows: List<ShowRow>,
    states: Map<Int, ShowState>,
    initialFocus: SpotlightFocus,
    onCardFocused: (SpotlightShow) -> Unit,
    onFocusSaved: (SpotlightFocus) -> Unit,
    onOpen: (SpotlightShow, Boolean) -> Unit,
    onBrowse: (String?) -> Unit
) {
    val start = initialFocus.copy(row = initialFocus.row.coerceIn(0, rows.lastIndex))
    val columnState = rememberLazyListState(initialFirstVisibleItemIndex = start.row)
    val requester = remember { FocusRequester() }
    var focusedRow by remember { mutableIntStateOf(-1) }
    PinFocusedRow(columnState, focusedRow)
    // Keep Watching arrives after the catalogue rows; rows keyed by key keep their scroll state.
    val rowStates = remember { HashMap<String, LazyListState>() }
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (focusPlaced) return@LaunchedEffect
        awaitFrame()
        if (runCatching { requester.requestFocus() }.isSuccess) focusPlaced = true
    }
    PinnedRowList { rowSpec ->
        LazyColumn(
            state = columnState,
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(rows, key = { _, r -> r.key }) { rowIndex, row ->
                val keep = row.key == SeriesSpotlightViewModel.KEEP_WATCHING
                val state = rowStates.getOrPut(row.key) {
                    LazyListState(firstVisibleItemIndex = if (rowIndex == start.row) start.item.coerceIn(0, row.items.lastIndex) else 0)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        row.title, fontFamily = Grotesk, fontSize = 18.sp,
                        color = if (keep) Accent else Color.White,
                        modifier = Modifier.padding(start = 48.dp)
                    )
                    WithRowScroll(rowSpec) {
                        LazyRow(
                            state = state,
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(row.items, key = { _, s -> s.id }) { itemIndex, show ->
                                val isStart = !focusPlaced && rowIndex == start.row &&
                                    itemIndex == start.item.coerceIn(0, row.items.lastIndex)
                                val showState = if (keep) states[show.id] else null
                                ShowCard(
                                    show = show,
                                    meta = cardMeta(show, showState),
                                    progress = (showState as? ShowState.InProgress)?.item?.takeIf { it.position > 0 }?.progressPercent,
                                    modifier = if (isStart) Modifier.focusRequester(requester) else Modifier,
                                    onFocused = {
                                        onCardFocused(show)
                                        // Keep the focused row's title pinned right under the hero.
                                        focusedRow = rowIndex
                                        onFocusSaved(SpotlightFocus(rowIndex, itemIndex))
                                    },
                                    onClick = { onOpen(show, showState is ShowState.NewEpisodes) }
                                )
                            }
                            row.browseId?.let { id ->
                                item(key = "see_all") { SeeAllCard(onClick = { onBrowse(id) }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowCard(
    show: SpotlightShow,
    meta: String,
    progress: Float?,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(width = 174.dp, height = 98.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RoundedCornerShape(10.dp))
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = CardBg, focusedContainerColor = CardBg)
    ) {
        Box(Modifier.fillMaxSize()) {
            (show.backdrop ?: show.cover)?.let {
                AsyncImage(
                    model = it, contentDescription = show.name, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f))
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = if (progress != null) 9.dp else 7.dp)) {
                Text(show.name, fontFamily = Grotesk, fontSize = 13.sp, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) Text(meta, fontSize = 10.sp, color = Muted, maxLines = 1)
            }
            progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                }
            }
        }
    }
}

/** End of every catalogue row: the row only shows 20, this opens the full grid on the same category. */
@Composable
private fun SeeAllCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 120.dp, height = 98.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), shape = RoundedCornerShape(10.dp)),
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RoundedCornerShape(10.dp))
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF141414), focusedContainerColor = Color(0xFF1C1C1C))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("See all  ›", fontFamily = Grotesk, fontSize = 15.sp, color = Color.White)
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp)) {
        Text(text, fontSize = 16.sp, color = Dim)
    }
}
