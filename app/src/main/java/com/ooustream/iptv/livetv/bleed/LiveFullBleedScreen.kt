package com.ooustream.iptv.livetv.bleed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.ooustream.iptv.common.compose.TvTheme.Accent
import com.ooustream.iptv.common.compose.TvTheme.Dim
import com.ooustream.iptv.common.compose.TvTheme.Grotesk
import com.ooustream.iptv.common.compose.TvTheme.Muted
import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.epg.ChannelNameParser
import com.ooustream.iptv.livetv.LiveTvViewModel
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

/** How long the cursor rests on a channel before it starts playing behind the screen. */
private const val TUNE_DWELL_MS = 700L

private val Shade = Color(0xFF080808)

private data class Chip(val id: String, val label: String)

/** Where the cursor goes when a channel list arrives. */
private enum class Landing {
    /** The channel we came back to, else the top of the list. */
    Restore,
    /** The top of the category a LEFT/RIGHT step moved to. */
    Step
}

@Composable
fun LiveFullBleedScreen(
    viewModel: LiveTvViewModel,
    tuneFlow: StateFlow<TuneState>,
    initialFocus: Int?,
    inferNow: (LiveStream, String?) -> String,
    onChannelFocused: (LiveStream?) -> Unit,
    /** True while no category to the left of the selected one has channels. */
    onFirstCategory: (Boolean) -> Unit,
    onTune: (LiveStream) -> Unit,
    onOpen: (LiveStream, List<LiveStream>) -> Unit,
    onFavorite: (LiveStream) -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val selectedId by viewModel.selectedCategoryId.collectAsState()
    val shownId by viewModel.shownCategoryId.collectAsState()
    val emptyState by viewModel.emptyState.collectAsState()
    val epg by viewModel.epgState.collectAsState()
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
    val hasRecent by viewModel.hasRecent.collectAsState()
    val tune by tuneFlow.collectAsState()

    val chips = remember(categories) {
        listOf(Chip(LiveTvViewModel.FAVORITES_ID, "Favorites"), Chip(LiveTvViewModel.RECENT_ID, "Recent")) +
            categories.map { Chip(it.categoryId, it.categoryName) }
    }
    val categoryName = categories.firstOrNull { it.categoryId == selectedId }?.categoryName
    val favoriteIds = remember(favorites) { favorites.map { it.streamId }.toSet() }

    var focused by remember { mutableStateOf<LiveStream?>(null) }
    // What the info panel describes: the focused channel, or the last one while focus is on the chips.
    var shown by remember { mutableStateOf<LiveStream?>(null) }

    LaunchedEffect(focused?.streamId) {
        val ch = focused ?: return@LaunchedEffect
        delay(TUNE_DWELL_MS)
        onTune(ch)
    }

    // LEFT/RIGHT on a channel steps to the previous/next category (v5.2.4). Categories with no
    // channels are skipped: Favorites and Recent are known up front, the rest once they've loaded
    // empty.
    val emptyIds = remember { mutableStateSetOf<String>() }
    fun hasNoChannels(id: String) = when (id) {
        LiveTvViewModel.FAVORITES_ID -> favorites.none { it.type == "live" }
        LiveTvViewModel.RECENT_ID -> !hasRecent
        else -> id in emptyIds
    }
    fun neighbour(from: Int, dir: Int): Int? {
        var i = from + dir
        while (i in chips.indices) {
            if (!hasNoChannels(chips[i].id)) return i
            i += dir
        }
        return null
    }
    val selectedIdx = chips.indexOfFirst { it.id == selectedId }
    // Nothing to the left has channels: LEFT stays put here, and LEFT-LEFT opens the menu.
    val firstCategory = selectedIdx < 0 || neighbour(selectedIdx, -1) == null
    SideEffect { onFirstCategory(firstCategory) }

    val listState = rememberLazyListState()
    val chipState = rememberLazyListState()
    val rowRequester = remember { FocusRequester() }
    val chipRequester = remember { FocusRequester() }
    // Holds the cursor on the list itself while a step loads the next category (see step()).
    val parkRequester = remember { FocusRequester() }
    // Null once the cursor has landed. A step remembers its direction (to skip on past an empty
    // category) and the category it started from (to go back to if nothing that way has channels).
    var landing by remember { mutableStateOf<Landing?>(Landing.Restore) }
    var stepDir by remember { mutableIntStateOf(0) }
    var stepOrigin by remember { mutableStateOf<String?>(null) }
    val landingIdx = when (landing) {
        Landing.Restore -> channels.indexOfFirst { it.streamId == initialFocus }.coerceAtLeast(0)
        Landing.Step -> 0
        null -> -1
    }

    fun step(dir: Int): Boolean {
        val from = chips.indexOfFirst { it.id == viewModel.selectedCategoryId.value }
        if (from < 0) return false
        // At either end: RIGHT does nothing, and LEFT goes on to MainActivity's double-LEFT menu.
        val to = neighbour(from, dir) ?: return dir > 0
        if (landing != Landing.Step) stepOrigin = chips[from].id
        stepDir = dir
        landing = Landing.Step
        // Park the cursor on the list until the new rows arrive. Left on a row, it would go with the
        // old list, and Compose would put it on the first chip: the chip row jumped back to the
        // start, and a held key moved along the chips instead of stepping.
        parkRequester.requestFocus()
        // The old list stays up until the new one arrives; a further press steps on from the newly
        // selected category. The background channel changes only after the cursor rests on the new
        // list.
        viewModel.selectCategory(chips[to].id)
        return true
    }

    LaunchedEffect(channels, emptyState, shownId, selectedId, landing) {
        when (landing ?: return@LaunchedEffect) {
            Landing.Restore -> {
                if (channels.isEmpty()) {
                    // Nothing in the list to land on: put the cursor on the category chips instead.
                    if (emptyState != null) {
                        awaitFrame()
                        if (chipRequester.requestFocus(FocusDirection.Enter)) landing = null
                    }
                    return@LaunchedEffect
                }
                val idx = channels.indexOfFirst { it.streamId == initialFocus }.coerceAtLeast(0)
                listState.scrollToItem((idx - 3).coerceAtLeast(0))
            }
            Landing.Step -> {
                val sel = selectedId ?: return@LaunchedEffect
                // Still the previous category's list on screen.
                if (shownId != sel) return@LaunchedEffect
                if (channels.isEmpty()) {
                    // Landed on an empty category: keep going the same way, else go back.
                    if (sel != LiveTvViewModel.FAVORITES_ID && sel != LiveTvViewModel.RECENT_ID) emptyIds.add(sel)
                    val from = chips.indexOfFirst { it.id == sel }
                    val next = if (from >= 0 && stepDir != 0) neighbour(from, stepDir) else null
                    val origin = stepOrigin
                    when {
                        next != null -> viewModel.selectCategory(chips[next].id)
                        origin != null && origin != sel -> {
                            stepDir = 0
                            viewModel.selectCategory(origin)
                        }
                        else -> {
                            // Focus first: clearing `landing` restarts this effect.
                            awaitFrame()
                            chipRequester.requestFocus(FocusDirection.Enter)
                            landing = null
                        }
                    }
                    return@LaunchedEffect
                }
                listState.scrollToItem(0)
            }
        }
        // The row to land on is composed a frame or two after the list arrives.
        repeat(30) {
            if (rowRequester.requestFocus(FocusDirection.Enter)) {
                landing = null
                return@LaunchedEffect
            }
            awaitFrame()
        }
        landing = null // give up rather than pull the cursor over later
    }

    // Keep the selected category's chip on screen: there are about 200 of them.
    LaunchedEffect(selectedIdx) {
        if (selectedIdx < 0) return@LaunchedEffect
        val info = chipState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selectedIdx }
        if (item == null || item.offset < info.viewportStartOffset ||
            item.offset + item.size > info.viewportEndOffset
        ) {
            chipState.animateScrollToItem((selectedIdx - 1).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Scrims over the video: dark behind the list on the left, and under the info line.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Shade.copy(alpha = 0.94f), 0.36f to Shade.copy(alpha = 0.82f), 0.62f to Color.Transparent
                )
            )
        )
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(150.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Shade.copy(alpha = 0.92f))))
        )

        LazyRow(
            state = chipState,
            modifier = Modifier.padding(top = 22.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(chips, key = { _, c -> c.id }) { _, chip ->
                val selected = chip.id == selectedId
                CategoryChip(
                    label = chip.label,
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(chipRequester) else Modifier,
                    onClick = {
                        // Picked by hand: the cursor stays on the chips, as before.
                        landing = null
                        viewModel.selectCategory(chip.id)
                    }
                )
            }
        }

        Box(
            Modifier.padding(start = 30.dp, top = 70.dp, bottom = 120.dp).width(330.dp).fillMaxHeight()
                // Reached while a channel row has focus, or the list itself during a step.
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> step(-1)
                        Key.DirectionRight -> step(1)
                        else -> false
                    }
                }
                // Focusable only while a step is loading the next category: it parks the cursor.
                .focusRequester(parkRequester)
                .focusProperties { canFocus = landing == Landing.Step }
                .focusable()
        ) {
            if (channels.isEmpty()) {
                Text(
                    emptyState ?: "Loading channels…", fontSize = 14.sp, color = Muted,
                    modifier = Modifier.padding(12.dp)
                )
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(channels, key = { _, ch -> ch.streamId }) { i, ch ->
                    val now = if (epg.streamId == ch.streamId) currentProgram(epg.programs)?.title else null
                    ChannelRow(
                        channel = ch,
                        nowTitle = now,
                        guess = remember(ch.streamId, categoryName) { inferNow(ch, categoryName) },
                        favorite = ch.streamId in favoriteIds,
                        modifier = (if (i == landingIdx) Modifier.focusRequester(rowRequester) else Modifier)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    focused = ch
                                    shown = ch
                                    onChannelFocused(ch)
                                } else if (focused?.streamId == ch.streamId) {
                                    focused = null
                                    onChannelFocused(null)
                                }
                            },
                        onClick = { onOpen(ch, channels) },
                        onLongClick = { onFavorite(ch) }
                    )
                }
            }
        }

        shown?.let { ch ->
            InfoLine(
                channel = ch,
                programs = if (epg.streamId == ch.streamId) epg.programs else null,
                guess = inferNow(ch, categoryName),
                tune = tune,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 30.dp, end = 30.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Accent else Color.White.copy(alpha = 0.2f)), shape = RoundedCornerShape(20.dp)),
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(20.dp))
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Accent else Color.White.copy(alpha = 0.1f),
            contentColor = if (selected) Color(0xFF0A0A0A) else Color.White,
            focusedContainerColor = if (selected) Accent else Color.White.copy(alpha = 0.25f),
            focusedContentColor = if (selected) Color(0xFF0A0A0A) else Color.White
        )
    ) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ChannelRow(
    channel: LiveStream,
    nowTitle: String?,
    guess: String,
    favorite: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val name = remember(channel.name) { ChannelNameParser.parseForDisplay(channel.name).name.ifBlank { channel.name } }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RoundedCornerShape(10.dp))
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.12f)
        )
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                channel.num?.toString() ?: "", fontSize = 11.sp, color = Dim, textAlign = TextAlign.End,
                modifier = Modifier.width(26.dp)
            )
            Box(
                Modifier.size(width = 40.dp, height = 27.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(name.take(3).uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
                channel.streamIcon?.takeIf { it.isNotBlank() }?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)).padding(2.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (nowTitle != null) {
                    Text(nowTitle, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    // A rule-based guess from the channel name, styled as one (italic, dim).
                    Text(guess, fontSize = 11.sp, color = Dim, fontStyle = FontStyle.Italic,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (favorite) Text("★", fontSize = 13.sp, color = Accent)
        }
    }
}

@Composable
private fun InfoLine(channel: LiveStream, programs: List<EpgProgram>?, guess: String, tune: TuneState, modifier: Modifier) {
    val name = remember(channel.name) { ChannelNameParser.parseForDisplay(channel.name).name.ifBlank { channel.name } }
    val current = programs?.let { currentProgram(it) }
    val next = programs?.let { p -> current?.let { c -> nextProgram(p, c) } }
    val start = current?.startTimestamp?.toLongOrNull()
    val stop = current?.stopTimestamp?.toLongOrNull()

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFE53935)).padding(horizontal = 5.dp, vertical = 2.dp))
                val meta = listOfNotNull(
                    channel.num?.toString(), name,
                    if (start != null && stop != null) "${clock(start)} – ${clock(stop)}" else null
                ).joinToString(" · ")
                Text(meta, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (tune) {
                    TuneState.Tuning -> Text("Tuning…", fontSize = 12.sp, color = Accent)
                    TuneState.Failed -> Text("This channel isn't playing right now", fontSize = 12.sp, color = Color(0xFFFF8A80))
                    else -> {}
                }
            }
            if (current?.title != null) {
                Text(current.title, fontFamily = Grotesk, fontSize = 26.sp, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(guess, fontFamily = Grotesk, fontSize = 26.sp, color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (start != null && stop != null && stop > start) {
                val now = System.currentTimeMillis() / 1000
                val p = ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
                Box(Modifier.width(390.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Accent))
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            next?.let { n ->
                val at = n.startTimestamp?.toLongOrNull()?.let { clock(it) }
                Text(listOfNotNull("Next", at).joinToString(" ") + " · " + (n.title ?: ""),
                    fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(300.dp), textAlign = TextAlign.End)
            }
            Text("◀ ▶ Category · OK Full screen · Hold OK Favorite", fontSize = 12.sp, color = Muted)
        }
    }
}

private fun currentProgram(programs: List<EpgProgram>): EpgProgram? {
    val now = System.currentTimeMillis() / 1000
    return programs.firstOrNull { p ->
        val s = p.startTimestamp?.toLongOrNull()
        val e = p.stopTimestamp?.toLongOrNull()
        s != null && e != null && now >= s && now < e
    }
}

private fun nextProgram(programs: List<EpgProgram>, current: EpgProgram): EpgProgram? {
    val end = current.stopTimestamp?.toLongOrNull() ?: return null
    return programs
        .filter { (it.startTimestamp?.toLongOrNull() ?: 0) >= end }
        .minByOrNull { it.startTimestamp?.toLongOrNull() ?: Long.MAX_VALUE }
}

private fun clock(epochSeconds: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000))
