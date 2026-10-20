package com.ooustream.iptv.common.compose

/**
 * A Compose screen is one Android View, so MainActivity's focusSearch(FOCUS_LEFT) edge test
 * can't see where focus is inside it. Screens that allow the double-LEFT quick menu say when.
 */
interface SidebarGate {
    fun allowsSidebar(): Boolean
}
