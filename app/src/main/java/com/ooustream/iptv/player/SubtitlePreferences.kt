package com.ooustream.iptv.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat

class SubtitlePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ooustream_subtitle_prefs", Context.MODE_PRIVATE)

    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var preferredLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** -1=Small, 0=Medium, 1=Large, 2=Extra Large */
    var textSize: Int
        get() = prefs.getInt(KEY_TEXT_SIZE, 1)  // Default Large for TV
        set(value) = prefs.edit().putInt(KEY_TEXT_SIZE, value).apply()

    var textColor: Int
        get() = prefs.getInt(KEY_TEXT_COLOR, Color.WHITE)
        set(value) = prefs.edit().putInt(KEY_TEXT_COLOR, value).apply()

    var backgroundColor: Int
        get() = prefs.getInt(KEY_BG_COLOR, Color.TRANSPARENT)  // Netflix: no background box
        set(value) = prefs.edit().putInt(KEY_BG_COLOR, value).apply()

    var edgeType: Int
        get() = prefs.getInt(KEY_EDGE_TYPE, CaptionStyleCompat.EDGE_TYPE_OUTLINE)  // Netflix: outline
        set(value) = prefs.edit().putInt(KEY_EDGE_TYPE, value).apply()

    /** Fractional text size for ExoPlayer SubtitleView. */
    val textSizeFraction: Float
        get() = when (textSize) {
            -1 -> 0.050f  // Small
            0 -> 0.060f   // Medium
            1 -> 0.070f   // Large (TV default — Netflix-like)
            2 -> 0.085f   // Extra Large
            else -> 0.070f
        }

    fun buildCaptionStyle(): CaptionStyleCompat {
        return CaptionStyleCompat(
            textColor,
            backgroundColor,
            Color.TRANSPARENT,    // window color
            edgeType,
            0xE0000000.toInt(),   // edge color — strong black outline for readability
            Typeface.create("sans-serif-medium", Typeface.BOLD)  // Netflix-style clean bold sans-serif
        )
    }

    companion object {
        private const val KEY_ENABLED = "subtitle_enabled"
        private const val KEY_LANGUAGE = "subtitle_preferred_language"
        private const val KEY_TEXT_SIZE = "subtitle_text_size"
        private const val KEY_TEXT_COLOR = "subtitle_text_color"
        private const val KEY_BG_COLOR = "subtitle_bg_color"
        private const val KEY_EDGE_TYPE = "subtitle_edge_type"
    }
}
