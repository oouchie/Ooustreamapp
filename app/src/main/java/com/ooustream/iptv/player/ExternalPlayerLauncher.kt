package com.ooustream.iptv.player

import android.content.Context
import android.content.Intent
import android.net.Uri

object ExternalPlayerLauncher {

    enum class Player(val displayName: String) {
        VLC("VLC"),
        MX_PLAYER("MX Player"),
        KODI("Kodi"),
        SYSTEM("System Default")
    }

    /**
     * Returns a list of external players that are installed on the device.
     * Always includes SYSTEM as the last option.
     */
    fun getAvailablePlayers(context: Context): List<Player> {
        val available = mutableListOf<Player>()
        val pm = context.packageManager

        // Check VLC
        if (isPackageInstalled(pm, "org.videolan.vlc")) {
            available.add(Player.VLC)
        }
        // Check MX Player (free and pro)
        if (isPackageInstalled(pm, "com.mxtech.videoplayer.ad") ||
            isPackageInstalled(pm, "com.mxtech.videoplayer.pro")) {
            available.add(Player.MX_PLAYER)
        }
        // Check Kodi
        if (isPackageInstalled(pm, "org.xbmc.kodi")) {
            available.add(Player.KODI)
        }

        available.add(Player.SYSTEM)
        return available
    }

    /**
     * Launches the specified external player with the given stream URL.
     * Returns true if the player was launched successfully.
     */
    fun launch(context: Context, player: Player, url: String, title: String? = null, positionMs: Long = 0L): Boolean {
        return try {
            val intent = when (player) {
                Player.VLC -> createVlcIntent(url, title, positionMs)
                Player.MX_PLAYER -> createMxPlayerIntent(url, title, positionMs)
                Player.KODI -> createKodiIntent(url)
                Player.SYSTEM -> createSystemIntent(url)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createVlcIntent(url: String, title: String?, positionMs: Long): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setPackage("org.videolan.vlc")
            setDataAndType(Uri.parse(url), "video/*")
            title?.let { putExtra("title", it) }
            if (positionMs > 0) putExtra("position", positionMs)
        }
    }

    private fun createMxPlayerIntent(url: String, title: String?, positionMs: Long): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            // Try free version first, MX will handle the intent
            setDataAndType(Uri.parse(url), "video/*")
            title?.let { putExtra("title", it) }
            if (positionMs > 0) putExtra("position", positionMs.toInt())
        }
    }

    private fun createKodiIntent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setPackage("org.xbmc.kodi")
            setDataAndType(Uri.parse(url), "video/*")
        }
    }

    private fun createSystemIntent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
        }
    }
}
