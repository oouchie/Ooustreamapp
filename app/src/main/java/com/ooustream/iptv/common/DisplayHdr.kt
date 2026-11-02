package com.ooustream.iptv.common

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/**
 * The HDR formats Android reports for the connected display (v5.2.4). Logged in the debug export
 * and with DEVICE_TIER, so a Dolby Vision report says whether the TV takes Dolby Vision at all,
 * and [com.ooustream.iptv.player.DolbyVisionBaseLayer] asks the same question here.
 */
object DisplayHdr {

    /** The display's HDR types: empty when it reports none, null when it can't be asked. */
    fun types(context: Context): IntArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return null
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        } catch (_: Throwable) {
            null
        }
    }

    fun supportsDolbyVision(context: Context): Boolean =
        types(context)?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true

    /** "DV, HDR10, HLG", "none", or "unknown". */
    fun describe(context: Context): String {
        val types = types(context) ?: return "unknown"
        if (types.isEmpty()) return "none"
        return types.distinct().sorted().joinToString(", ") { name(it) }
    }

    private fun name(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DV"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "type$type"
    }
}
