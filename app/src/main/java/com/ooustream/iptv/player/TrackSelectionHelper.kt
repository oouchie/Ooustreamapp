package com.ooustream.iptv.player

import android.content.Context
import android.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object TrackSelectionHelper {

    data class TrackInfo(
        val name: String,
        val groupIndex: Int,
        val trackIndex: Int,
        val isSelected: Boolean
    )

    /**
     * Shows a dialog to select audio track.
     */
    fun showAudioTrackSelector(context: Context, player: Player) {
        val tracks = getTracksOfType(player, C.TRACK_TYPE_AUDIO)
        if (tracks.isEmpty()) return

        showTrackDialog(context, "Audio Track", tracks) { selected ->
            applyTrackSelection(player, selected)
        }
    }

    /**
     * Shows a dialog to select subtitle track.
     */
    fun showSubtitleTrackSelector(context: Context, player: Player) {
        val tracks = getTracksOfType(player, C.TRACK_TYPE_TEXT)
        // Add "Off" option
        val tracksWithOff = listOf(
            TrackInfo("Off", -1, -1, tracks.none { it.isSelected })
        ) + tracks

        showTrackDialog(context, "Subtitles", tracksWithOff) { selected ->
            if (selected.groupIndex == -1) {
                // Disable subtitles
                disableTrackType(player, C.TRACK_TYPE_TEXT)
            } else {
                // Re-enable text tracks if they were disabled, then apply selection
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()
                applyTrackSelection(player, selected)
            }
        }
    }

    private fun getTracksOfType(player: Player, trackType: Int): List<TrackInfo> {
        val result = mutableListOf<TrackInfo>()
        val currentTracks = player.currentTracks

        for ((groupIndex, group) in currentTracks.groups.withIndex()) {
            if (group.type != trackType) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val name = buildTrackName(format, trackType, result.size + 1)
                val isSelected = group.isTrackSelected(trackIndex)
                result.add(TrackInfo(name, groupIndex, trackIndex, isSelected))
            }
        }
        return result
    }

    private fun buildTrackName(format: Format, trackType: Int, index: Int): String {
        val label = format.label
        val language = format.language
        return when {
            !label.isNullOrBlank() -> label
            !language.isNullOrBlank() -> {
                val locale = Locale(language)
                locale.displayLanguage
            }
            else -> {
                val typeLabel = if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitle"
                "$typeLabel Track $index"
            }
        }
    }

    private fun showTrackDialog(
        context: Context,
        title: String,
        tracks: List<TrackInfo>,
        onSelected: (TrackInfo) -> Unit
    ) {
        val names = tracks.map {
            if (it.isSelected) "\u2713 ${it.name}" else it.name
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(names) { _, which ->
                onSelected(tracks[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTrackSelection(player: Player, track: TrackInfo) {
        val group = player.currentTracks.groups[track.groupIndex]
        val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .build()
    }

    private fun disableTrackType(player: Player, trackType: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackType, true)
            .build()
    }
}
