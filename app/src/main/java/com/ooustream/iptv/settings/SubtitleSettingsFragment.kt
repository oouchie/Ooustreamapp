package com.ooustream.iptv.settings

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.media3.ui.CaptionStyleCompat
import com.ooustream.iptv.player.SubtitlePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SubtitleSettingsFragment : GuidedStepSupportFragment() {

    @Inject lateinit var subtitlePreferences: SubtitlePreferences

    companion object {
        private const val ACTION_AUTO_ENABLE = 1L
        private const val ACTION_LANGUAGE = 2L
        private const val ACTION_TEXT_SIZE = 3L
        private const val ACTION_TEXT_COLOR = 4L
        private const val ACTION_BACKGROUND = 5L
        private const val ACTION_EDGE_STYLE = 6L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Subtitle Settings",
            "Customize subtitle appearance and behavior",
            "",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        // Auto-Enable Subtitles
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_AUTO_ENABLE)
                .title("Auto-Enable Subtitles")
                .description(if (subtitlePreferences.subtitlesEnabled) "On" else "Off")
                .build()
        )

        // Preferred Language
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_LANGUAGE)
                .title("Preferred Language")
                .description(languageDisplayName(subtitlePreferences.preferredLanguage))
                .build()
        )

        // Text Size
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_TEXT_SIZE)
                .title("Text Size")
                .description(textSizeLabel(subtitlePreferences.textSize))
                .build()
        )

        // Text Color
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_TEXT_COLOR)
                .title("Text Color")
                .description(textColorLabel(subtitlePreferences.textColor))
                .build()
        )

        // Background
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_BACKGROUND)
                .title("Background")
                .description(backgroundLabel(subtitlePreferences.backgroundColor))
                .build()
        )

        // Edge Style
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_EDGE_STYLE)
                .title("Edge Style")
                .description(edgeStyleLabel(subtitlePreferences.edgeType))
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_AUTO_ENABLE -> cycleAutoEnable(action)
            ACTION_LANGUAGE -> cycleLanguage(action)
            ACTION_TEXT_SIZE -> cycleTextSize(action)
            ACTION_TEXT_COLOR -> cycleTextColor(action)
            ACTION_BACKGROUND -> cycleBackground(action)
            ACTION_EDGE_STYLE -> cycleEdgeStyle(action)
        }
    }

    private fun cycleAutoEnable(action: GuidedAction) {
        val newValue = !subtitlePreferences.subtitlesEnabled
        subtitlePreferences.subtitlesEnabled = newValue
        action.description = if (newValue) "On" else "Off"
        notifyActionChanged(findActionPositionById(ACTION_AUTO_ENABLE))
    }

    private fun cycleLanguage(action: GuidedAction) {
        val languages = listOf("en", "es", "fr", "de", "pt", "it", "ru", "ar", "zh", "ja", "ko", "hi")
        val currentIdx = languages.indexOf(subtitlePreferences.preferredLanguage)
        val nextIdx = (currentIdx + 1) % languages.size
        subtitlePreferences.preferredLanguage = languages[nextIdx]
        action.description = languageDisplayName(languages[nextIdx])
        notifyActionChanged(findActionPositionById(ACTION_LANGUAGE))
    }

    private fun cycleTextSize(action: GuidedAction) {
        val sizes = listOf(-1, 0, 1, 2) // Small, Medium, Large, XL
        val currentIdx = sizes.indexOf(subtitlePreferences.textSize)
        val nextIdx = (currentIdx + 1) % sizes.size
        subtitlePreferences.textSize = sizes[nextIdx]
        action.description = textSizeLabel(sizes[nextIdx])
        notifyActionChanged(findActionPositionById(ACTION_TEXT_SIZE))
    }

    private fun cycleTextColor(action: GuidedAction) {
        val colors = listOf(Color.WHITE, Color.YELLOW, Color.GREEN, Color.CYAN)
        val currentIdx = colors.indexOf(subtitlePreferences.textColor)
        val nextIdx = (currentIdx + 1) % colors.size
        subtitlePreferences.textColor = colors[nextIdx]
        action.description = textColorLabel(colors[nextIdx])
        notifyActionChanged(findActionPositionById(ACTION_TEXT_COLOR))
    }

    private fun cycleBackground(action: GuidedAction) {
        val backgrounds = listOf(0xC0000000.toInt(), 0xFF000000.toInt(), Color.TRANSPARENT)
        val currentIdx = backgrounds.indexOf(subtitlePreferences.backgroundColor)
        val nextIdx = (currentIdx + 1) % backgrounds.size
        subtitlePreferences.backgroundColor = backgrounds[nextIdx]
        action.description = backgroundLabel(backgrounds[nextIdx])
        notifyActionChanged(findActionPositionById(ACTION_BACKGROUND))
    }

    private fun cycleEdgeStyle(action: GuidedAction) {
        val styles = listOf(
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            CaptionStyleCompat.EDGE_TYPE_NONE
        )
        val currentIdx = styles.indexOf(subtitlePreferences.edgeType)
        val nextIdx = (currentIdx + 1) % styles.size
        subtitlePreferences.edgeType = styles[nextIdx]
        action.description = edgeStyleLabel(styles[nextIdx])
        notifyActionChanged(findActionPositionById(ACTION_EDGE_STYLE))
    }

    // Display name helpers

    private fun languageDisplayName(code: String): String = when (code) {
        "en" -> "English"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        "pt" -> "Portuguese"
        "it" -> "Italian"
        "ru" -> "Russian"
        "ar" -> "Arabic"
        "zh" -> "Chinese"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "hi" -> "Hindi"
        else -> code.uppercase()
    }

    private fun textSizeLabel(size: Int): String = when (size) {
        -1 -> "Small"
        0 -> "Medium"
        1 -> "Large"
        2 -> "Extra Large"
        else -> "Medium"
    }

    private fun textColorLabel(color: Int): String = when (color) {
        Color.WHITE -> "White"
        Color.YELLOW -> "Yellow"
        Color.GREEN -> "Green"
        Color.CYAN -> "Cyan"
        else -> "White"
    }

    private fun backgroundLabel(color: Int): String = when (color) {
        0xC0000000.toInt() -> "Semi-Transparent"
        0xFF000000.toInt() -> "Solid Black"
        Color.TRANSPARENT -> "None"
        else -> "Semi-Transparent"
    }

    private fun edgeStyleLabel(type: Int): String = when (type) {
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> "Drop Shadow"
        CaptionStyleCompat.EDGE_TYPE_OUTLINE -> "Outline"
        CaptionStyleCompat.EDGE_TYPE_NONE -> "None"
        else -> "Drop Shadow"
    }
}
