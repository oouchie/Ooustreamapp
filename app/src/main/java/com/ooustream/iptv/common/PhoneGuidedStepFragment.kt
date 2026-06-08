package com.ooustream.iptv.common

import androidx.leanback.app.GuidedStepSupportFragment
import com.ooustream.iptv.R

/**
 * Base for all GuidedStepSupportFragment screens (Settings, Subtitle, Update, Backup, the two
 * Confirm screens, and the Parental PIN entry).
 *
 * Leanback lays GuidedStep out as a two-pane composition — a guidance pane (fixed weight 1) on
 * the left and the actions list on the right (weight `guidedActionContentWidthWeight`, default
 * 0.714). That gives the actions — i.e. the actual menu — only ~42% of the width, which crams
 * the whole menu into less than half of a portrait phone. There is no portrait layout variant in
 * Leanback 1.0.0, so phones get the side-by-side layout.
 *
 * On phone we return a theme that widens the actions pane to ~97%; on TV we return -1 so Leanback
 * resolves its default theme exactly as before (TV is unchanged).
 */
abstract class PhoneGuidedStepFragment : GuidedStepSupportFragment() {

    override fun onProvideTheme(): Int {
        val ctx = context ?: return -1
        return if (DeviceUtils.isTV(ctx)) -1 else R.style.Theme_Ooustream_GuidedStep_Phone
    }
}
