package com.ooustream.iptv.common

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

object DeviceUtils {

    fun isTV(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isPhone(context: Context): Boolean {
        if (isTV(context)) return false
        return context.resources.configuration.smallestScreenWidthDp < 600
    }

    fun isTablet(context: Context): Boolean {
        if (isTV(context)) return false
        return context.resources.configuration.smallestScreenWidthDp >= 600
    }
}
