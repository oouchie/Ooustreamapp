package com.ooustream.iptv.common.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.ooustream.iptv.R

/** Colors and type shared by the Compose for TV screens (trial, Kotlin 2 branch). */
object TvTheme {
    val Bg = Color(0xFF0A0A0A)
    val Accent = Color(0xFF00D4FF)
    val Muted = Color(0xFFC9CED5)
    val Dim = Color(0xFF8A929C)
    val CardBg = Color(0xFF1A1A1A)
    val Grotesk = FontFamily(Font(R.font.space_grotesk_bold, FontWeight.Bold))
}

private data class RailItem(val section: String, val icon: Int, val label: String)

private val RAIL = listOf(
    RailItem("search", R.drawable.ic_search, "Search"),
    RailItem("home", R.drawable.ic_home, "Home"),
    RailItem("vod", R.drawable.ic_movies, "Movies"),
    RailItem("series", R.drawable.ic_series, "Series"),
    RailItem("live", R.drawable.ic_live_tv, "Live TV"),
    RailItem("favorites", R.drawable.ic_favorites, "Favorites"),
    RailItem("settings", R.drawable.ic_settings, "Settings")
)

/**
 * Design B's left rail. Always on screen, so LEFT from the first card reaches it. [active] is the
 * MainActivity.navigateFromSidebar id of the screen showing it ("vod", "series", ...).
 */
@Composable
fun NavRail(active: String, onNavigate: (String) -> Unit) {
    Column(
        Modifier.width(64.dp).fillMaxHeight().background(Color(0xFF060606)).padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (item in RAIL) {
            val isActive = item.section == active
            Surface(
                onClick = { if (!isActive) onNavigate(item.section) },
                modifier = Modifier.size(44.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = TvTheme.Accent.copy(alpha = 0.18f)
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, TvTheme.Accent), shape = RoundedCornerShape(10.dp))
                )
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(item.icon),
                        contentDescription = item.label,
                        colorFilter = ColorFilter.tint(if (isActive) TvTheme.Accent else Color(0xFFB8BEC7)),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
