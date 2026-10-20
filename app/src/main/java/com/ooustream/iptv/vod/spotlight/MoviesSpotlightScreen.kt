package com.ooustream.iptv.vod.spotlight

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.ooustream.iptv.common.compose.NavRail
import com.ooustream.iptv.common.compose.PinFocusedRow
import com.ooustream.iptv.common.compose.PinnedRowList
import com.ooustream.iptv.common.compose.WithRowScroll
import kotlinx.coroutines.android.awaitFrame

private val Bg = Color(0xFF0A0A0A)
private val Accent = Color(0xFF00D4FF)
private val Muted = Color(0xFFC9CED5)
private val Dim = Color(0xFF8A929C)
private val CardBg = Color(0xFF1A1A1A)
private val Grotesk = FontFamily(Font(R.font.space_grotesk_bold, FontWeight.Bold))

/** Where focus was when the screen was left, so Back from a detail page lands on the same card. */
data class SpotlightFocus(val row: Int = 0, val item: Int = 0)

@Composable
fun MoviesSpotlightScreen(
    viewModel: MoviesSpotlightViewModel,
    initialFocus: SpotlightFocus,
    onFocusSaved: (SpotlightFocus) -> Unit,
    onOpen: (SpotlightMovie) -> Unit,
    onPlay: (SpotlightMovie) -> Unit,
    onBrowse: (categoryId: String?) -> Unit,
    onNavigate: (section: String) -> Unit
) {
    val rows by viewModel.rows.collectAsState()
    val hero by viewModel.hero.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    Row(Modifier.fillMaxSize().background(Bg)) {
        NavRail(active = "vod", onNavigate = onNavigate)
        Box(Modifier.weight(1f).fillMaxHeight()) {
        Backdrop(hero)
        Column(Modifier.fillMaxSize()) {
            HeroText(
                hero,
                onPlay = { hero?.movie?.let(onPlay) },
                onInfo = { hero?.movie?.let(onOpen) },
                onAll = { onBrowse(null) }
            )
            when {
                rows.isNotEmpty() -> Rows(rows, initialFocus, viewModel::focus, onFocusSaved, onOpen, onBrowse)
                loading -> Message("Loading movies…")
                error != null -> Message(error ?: "")
                else -> Message("No movies to show")
            }
        }
        }
    }
}

@Composable
private fun Backdrop(hero: SpotlightHero?) {
    val image = hero?.backdrop ?: hero?.movie?.poster
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
                Brush.horizontalGradient(0f to Bg, 0.38f to Bg.copy(alpha = 0.88f), 0.72f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg)
            )
        )
    }
}

@Composable
private fun HeroText(hero: SpotlightHero?, onPlay: () -> Unit, onInfo: () -> Unit, onAll: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(250.dp).padding(start = 40.dp, top = 36.dp, end = 360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (hero == null) return@Column
        val m = hero.movie
        Text(
            m.name,
            fontFamily = Grotesk, fontSize = 34.sp, color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            m.year?.let { Text("$it", fontSize = 14.sp, color = Muted) }
            hero.duration?.let { Text(it, fontSize = 14.sp, color = Muted) }
            hero.genre?.let { Text(it, fontSize = 14.sp, color = Muted) }
            m.rating?.let { Text("★ %.1f".format(it), fontSize = 14.sp, color = Accent) }
        }
        Text(
            hero.plot ?: "",
            fontSize = 14.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp, modifier = Modifier.height(42.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            HeroButton("▶  Play", primary = true, onClick = onPlay)
            HeroButton("More Info", primary = false, onClick = onInfo)
            HeroButton("All Movies", primary = false, onClick = onAll)
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

@Composable
private fun Rows(
    rows: List<SpotlightRow>,
    initialFocus: SpotlightFocus,
    onCardFocused: (SpotlightMovie) -> Unit,
    onFocusSaved: (SpotlightFocus) -> Unit,
    onOpen: (SpotlightMovie) -> Unit,
    onBrowse: (String?) -> Unit
) {
    val start = initialFocus.copy(row = initialFocus.row.coerceIn(0, rows.lastIndex))
    val columnState = rememberLazyListState(initialFirstVisibleItemIndex = start.row)
    val requester = remember { FocusRequester() }
    var focusedRow by remember { mutableIntStateOf(-1) }
    PinFocusedRow(columnState, focusedRow)
    val rowStates = remember(rows) {
        rows.mapIndexed { i, r ->
            LazyListState(firstVisibleItemIndex = if (i == start.row) start.item.coerceIn(0, r.items.lastIndex) else 0)
        }
    }
    LaunchedEffect(rows) {
        awaitFrame()
        runCatching { requester.requestFocus() }
    }
    PinnedRowList { rowSpec ->
        LazyColumn(
            state = columnState,
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(rows, key = { _, r -> r.key }) { rowIndex, row ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        row.title, fontFamily = Grotesk, fontSize = 18.sp, color = Color.White,
                        modifier = Modifier.padding(start = 48.dp)
                    )
                    WithRowScroll(rowSpec) {
                        LazyRow(
                            state = rowStates[rowIndex],
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(row.items, key = { _, m -> m.id }) { itemIndex, movie ->
                                val isStart = rowIndex == start.row &&
                                    itemIndex == start.item.coerceIn(0, row.items.lastIndex)
                                PosterCard(
                                    movie = movie,
                                    modifier = if (isStart) Modifier.focusRequester(requester) else Modifier,
                                    onFocused = {
                                        onCardFocused(movie)
                                        // Keep the focused row's title pinned right under the hero.
                                        focusedRow = rowIndex
                                        onFocusSaved(SpotlightFocus(rowIndex, itemIndex))
                                    },
                                    onClick = { onOpen(movie) }
                                )
                            }
                            item(key = "see_all") {
                                SeeAllCard(onClick = { onBrowse(row.browseId) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    movie: SpotlightMovie,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(width = 110.dp, height = 165.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RoundedCornerShape(10.dp))
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = CardBg, focusedContainerColor = CardBg)
    ) {
        Box(Modifier.fillMaxSize()) {
            // Title underneath shows through when there is no poster or it fails to load.
            Text(
                movie.name, fontSize = 12.sp, color = Color.White, maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )
            if (movie.poster != null) {
                AsyncImage(
                    model = movie.poster,
                    contentDescription = movie.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** End of every row: the row only shows 20, this opens the full grid on the same category. */
@Composable
private fun SeeAllCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 110.dp, height = 165.dp),
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
