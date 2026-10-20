package com.ooustream.iptv.livetv.bleed

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

@Composable
fun LiveFullBleedScreen(
    viewModel: LiveTvViewModel,
    tuneFlow: StateFlow<TuneState>,
    initialFocus: Int?,
    inferNow: (LiveStream, String?) -> String,
    onChannelFocused: (LiveStream?) -> Unit,
    onTune: (LiveStream) -> Unit,
    onOpen: (LiveStream, List<LiveStream>) -> Unit,
    onFavorite: (LiveStream) -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val selectedId by viewModel.selectedCategoryId.collectAsState()
    val emptyState by viewModel.emptyState.collectAsState()
    val epg by viewModel.epgState.collectAsState()
    val favorites by viewModel.favorites.collectAsState(initial = emptyList())
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

    // First focus: the channel we came back to, else the top of the list.
    val listState = rememberLazyListState()
    val channelRequester = remember { FocusRequester() }
    val chipRequester = remember { FocusRequester() }
    var restored by remember { mutableStateOf(false) }
    val targetIdx = channels.indexOfFirst { it.streamId == initialFocus }.coerceAtLeast(0)
    LaunchedEffect(channels) {
        if (restored || channels.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((targetIdx - 3).coerceAtLeast(0))
        awaitFrame()
        repeat(10) {
            if (runCatching { channelRequester.requestFocus() }.isSuccess) {
                restored = true
                return@LaunchedEffect
            }
            delay(50)
        }
    }
    LaunchedEffect(emptyState) {
        // Nothing in the list to land on: put the cursor on the category chips instead.
        if (!restored && emptyState != null && channels.isEmpty()) {
            awaitFrame()
            if (runCatching { chipRequester.requestFocus() }.isSuccess) restored = true
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
                    onClick = { viewModel.selectCategory(chip.id) }
                )
            }
        }

        Box(Modifier.padding(start = 30.dp, top = 70.dp, bottom = 120.dp).width(330.dp).fillMaxHeight()) {
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
                        modifier = (if (i == targetIdx && !restored) Modifier.focusRequester(channelRequester) else Modifier)
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
            Text("OK Full screen · Hold OK Favorite", fontSize = 12.sp, color = Muted)
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
