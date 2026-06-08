package com.ooustream.iptv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.auth.LoginFragment
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.NetworkMonitor
import com.ooustream.iptv.common.QuickSidebar
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.CredentialStore
import com.ooustream.iptv.data.UserPlanManager
import com.ooustream.iptv.deeplink.DeepLinkRouter
import com.ooustream.iptv.deeplink.DeepLinkTarget
import com.ooustream.iptv.favorites.FavoritesFragment
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.home.HomeFragment
import com.ooustream.iptv.livetv.LiveTvFragment
import com.ooustream.iptv.multiview.MultiViewFragment
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import com.ooustream.iptv.search.SearchFragment
import com.ooustream.iptv.series.SeriesFragment
import com.ooustream.iptv.settings.SettingsFragment
import com.ooustream.iptv.splash.IntroSplashFragment
import com.ooustream.iptv.vod.VodFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

interface KeyEventHandler {
    fun onKeyEvent(keyCode: Int): Boolean
    /** Override for full KeyEvent access (ACTION_DOWN + ACTION_UP). Return true to consume. */
    fun onFullKeyEvent(event: KeyEvent): Boolean = false
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var credentialStore: CredentialStore
    @Inject lateinit var contentRepository: ContentRepository
    @Inject lateinit var userPlanManager: UserPlanManager

    private var sidebar: QuickSidebar? = null
    private var bottomNav: BottomNavigationView? = null
    private var pendingDeepLink: DeepLinkTarget? = null
    private var lastLeftEdgeTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mobile gets Material theme; TV keeps Leanback theme from manifest
        if (!DeviceUtils.isTV(this)) {
            setTheme(R.style.Theme_Ooustream_Mobile)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Phone: resize the window when the soft keyboard appears so scrollable forms (Login,
        // search inputs) can pan their focused field above the IME. TV has no soft keyboard.
        if (!DeviceUtils.isTV(this)) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        setupSidebar()
        setupBottomNavigation()
        handleDeepLink(intent)

        if (savedInstanceState == null) {
            // Start with intro splash
            val splash = IntroSplashFragment()
            splash.onFinished = {
                // Skip login if already authenticated
                if (credentialStore.load() != null) {
                    // Refresh plan state (Pro/Basic) from server
                    lifecycleScope.launch { userPlanManager.refreshPlan() }
                    navigateToHome()
                } else {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.main_container, LoginFragment())
                        .commitAllowingStateLoss()
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, splash)
                .commit()
        }

    }

    private fun setupSidebar() {
        sidebar = QuickSidebar(this).also { sb ->
            // Add sidebar to the dedicated overlay container above the fragment container
            val sidebarContainer = findViewById<FrameLayout>(R.id.sidebar_container)
            sidebarContainer.addView(
                sb,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            sb.onItemSelected = { itemId ->
                navigateFromSidebar(itemId)
            }
        }

        // Observe network state for connection status indicator + user toast
        var wasConnected = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.state.collect { networkState ->
                    sidebar?.updateConnectionStatus(
                        isConnected = networkState.isConnected,
                        isWifi = networkState.isWifi
                    )
                    // Toast on connectivity change (skip player — it has its own handling)
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.main_container)
                    val isPlayerActive = currentFragment is OoustreamPlaybackFragment
                    if (!networkState.isConnected && wasConnected && !isPlayerActive) {
                        Toast.makeText(this@MainActivity, R.string.error_network, Toast.LENGTH_LONG).show()
                    } else if (networkState.isConnected && !wasConnected && !isPlayerActive) {
                        Toast.makeText(this@MainActivity, "Connection restored", Toast.LENGTH_SHORT).show()
                    }
                    wasConnected = networkState.isConnected
                }
            }
        }
    }

    private fun setupBottomNavigation() {
        // bottomNav is null on TV (layout-television/activity_main.xml has no BottomNavigationView)
        bottomNav = findViewById(R.id.bottom_navigation) ?: return

        bottomNav?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { navigateFromSidebar("home"); true }
                R.id.nav_live -> { navigateFromSidebar("live"); true }
                R.id.nav_movies -> { navigateFromSidebar("vod"); true }
                R.id.nav_series -> { navigateFromSidebar("series"); true }
                R.id.nav_search -> { navigateFromSidebar("search"); true }
                else -> false
            }
        }

        // Sync bottom nav highlight when fragments change
        supportFragmentManager.addOnBackStackChangedListener { syncBottomNav() }
    }

    /** Update bottom nav selected item to match the currently visible fragment. */
    private fun syncBottomNav() {
        val nav = bottomNav ?: return
        val fragment = supportFragmentManager.findFragmentById(R.id.main_container)
        val itemId = when (fragment) {
            is HomeFragment -> R.id.nav_home
            is LiveTvFragment -> R.id.nav_live
            is VodFragment -> R.id.nav_movies
            is SeriesFragment -> R.id.nav_series
            is SearchFragment -> R.id.nav_search
            else -> null
        }

        // Hide bottom nav during player / multiview, show otherwise
        val hideNav = fragment is OoustreamPlaybackFragment || fragment is MultiViewFragment
                || fragment is IntroSplashFragment || fragment is LoginFragment
        nav.visibility = if (hideNav) View.GONE else View.VISIBLE

        // Reclaim the reserved nav strip when the bar is hidden — otherwise main_container keeps
        // its 56dp bottom margin and a dead black bar sits under the fullscreen player / MultiView
        // / Login. Mirror the margin to the bar's visibility.
        findViewById<View>(R.id.main_container)?.let { container ->
            (container.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val target = if (hideNav) 0
                    else resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
                if (lp.bottomMargin != target) {
                    lp.bottomMargin = target
                    container.layoutParams = lp
                }
            }
        }

        // Silently update the selected item (don't re-trigger listener)
        if (itemId != null && nav.selectedItemId != itemId) {
            nav.setOnItemSelectedListener(null)
            nav.selectedItemId = itemId
            nav.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> { navigateFromSidebar("home"); true }
                    R.id.nav_live -> { navigateFromSidebar("live"); true }
                    R.id.nav_movies -> { navigateFromSidebar("vod"); true }
                    R.id.nav_series -> { navigateFromSidebar("series"); true }
                    R.id.nav_search -> { navigateFromSidebar("search"); true }
                    else -> false
                }
            }
        }
    }

    /**
     * Determines if the quick sidebar is allowed to open for the current fragment.
     * Sidebar is TV-only — mobile uses bottom navigation.
     */
    private fun isSidebarAllowedForCurrentFragment(): Boolean {
        if (!DeviceUtils.isTV(this)) return false
        val fragment = supportFragmentManager.findFragmentById(R.id.main_container)
        return when (fragment) {
            is HomeFragment,
            is VodFragment,
            is SeriesFragment,
            is FavoritesFragment,
            is SearchFragment,
            is SettingsFragment -> true
            else -> false
        }
    }

    private fun navigateFromSidebar(itemId: String) {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.main_container)

        // Don't navigate to the same section
        val isSameSection = when (itemId) {
            "home" -> currentFragment is HomeFragment
            "live" -> currentFragment is LiveTvFragment
            "vod" -> currentFragment is VodFragment
            "series" -> currentFragment is SeriesFragment
            "favorites" -> currentFragment is FavoritesFragment
            "search" -> currentFragment is SearchFragment
            "settings" -> currentFragment is SettingsFragment
            else -> false
        }
        if (isSameSection) return

        when (itemId) {
            "home" -> navigateToHome()
            else -> {
                val fragment = when (itemId) {
                    "live" -> LiveTvFragment()
                    "vod" -> VodFragment()
                    "series" -> SeriesFragment()
                    "favorites" -> FavoritesFragment()
                    "search" -> SearchFragment()
                    "settings" -> SettingsFragment()
                    else -> return
                }
                // Pop back to home first so we don't pile up sidebar navigations
                supportFragmentManager.popBackStackImmediate(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                val tx = supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                tx.replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val fragment = supportFragmentManager.findFragmentById(R.id.main_container)

        // Never let Back exit during intro splash
        if (fragment is IntroSplashFragment) return

        // Exit confirmation on Home screen
        if (fragment is HomeFragment && supportFragmentManager.backStackEntryCount == 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.exit_title)
                .setMessage(R.string.exit_message)
                .setPositiveButton(R.string.exit) { _, _ -> finish() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Forward full key events (ACTION_DOWN + ACTION_UP) for long-press detection
        val fragment = supportFragmentManager.findFragmentById(R.id.main_container)
        if (fragment is KeyEventHandler) {
            if (fragment.onFullKeyEvent(event)) return true
        }

        // Guard against Android framework focus crash during fragment transitions.
        // ViewGroup.offsetRectBetweenParentAndChild throws IllegalArgumentException
        // when a detached view is still referenced by the focus navigation system.
        try {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // If sidebar is open, let it handle the key event first
                if (sidebar?.isOpen == true) {
                    if (sidebar?.handleKeyEvent(event.keyCode) == true) return true
                    // While sidebar is open, DPAD_UP/DOWN navigation within
                    // the sidebar is handled by the Android focus system
                    return super.dispatchKeyEvent(event)
                }

                // Route to fragment's key event handler
                if (fragment is KeyEventHandler) {
                    if (fragment.onKeyEvent(event.keyCode)) return true
                }

                // For DPAD_LEFT on allowed fragments: double-tap at the left edge opens sidebar.
                // Normal left navigation is never blocked.
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isSidebarAllowedForCurrentFragment()) {
                    val focused = currentFocus
                    val atLeftEdge = focused != null && focused.focusSearch(View.FOCUS_LEFT) == null
                    if (atLeftEdge) {
                        val now = System.currentTimeMillis()
                        if (now - lastLeftEdgeTime < 500) {
                            lastLeftEdgeTime = 0
                            sidebar?.show()
                            return true
                        }
                        lastLeftEdgeTime = now
                    } else {
                        lastLeftEdgeTime = 0
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        } catch (_: IllegalArgumentException) {
            // Swallow — view hierarchy is mid-transition, focus target detached
            return true
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    private fun handleDeepLink(intent: Intent) {
        val target = DeepLinkRouter.parse(intent.data) ?: return

        // If user is not authenticated yet, save for after login
        val hasCredentials = credentialStore.load() != null
        if (!hasCredentials) {
            pendingDeepLink = target
            return
        }

        navigateToDeepLink(target)
    }

    private fun navigateToDeepLink(target: DeepLinkTarget) {
        when (target) {
            is DeepLinkTarget.Home -> navigateToHome()
            is DeepLinkTarget.LiveTv -> {
                supportFragmentManager.popBackStackImmediate(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                val tx = supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                tx.replace(R.id.main_container, LiveTvFragment())
                    .addToBackStack(null)
                    .commit()
            }
            is DeepLinkTarget.Favorites -> {
                supportFragmentManager.popBackStackImmediate(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                val tx = supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                tx.replace(R.id.main_container, FavoritesFragment())
                    .addToBackStack(null)
                    .commit()
            }
            is DeepLinkTarget.Live -> {
                val streamId = target.streamId.toIntOrNull() ?: return
                val streamUrl = contentRepository.buildLiveStreamUrl(streamId)
                val fragment = OoustreamPlaybackFragment.newInstance(
                    streamUrl = streamUrl,
                    contentType = ContentType.LIVE,
                    streamId = target.streamId,
                    streamName = "Channel ${target.streamId}"
                )
                val tx = supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                tx.replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
            is DeepLinkTarget.Vod -> {
                val streamId = target.streamId.toIntOrNull() ?: return
                val streamUrl = contentRepository.buildVodStreamUrl(streamId, "mp4")
                val fragment = OoustreamPlaybackFragment.newInstance(
                    streamUrl = streamUrl,
                    contentType = ContentType.VOD,
                    streamId = target.streamId,
                    streamName = "Movie ${target.streamId}"
                )
                val tx = supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.PLAYER)
                tx.replace(R.id.main_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
            is DeepLinkTarget.Series -> {
                supportFragmentManager.popBackStackImmediate(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                val tx = supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                tx.replace(R.id.main_container, SeriesFragment())
                    .addToBackStack(null)
                    .commit()
            }
            is DeepLinkTarget.Search -> {
                supportFragmentManager.popBackStackImmediate(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                val tx = supportFragmentManager.beginTransaction()
                FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
                tx.replace(R.id.main_container, SearchFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    fun navigateToHome() {
        // If there's a pending deep link from before auth, navigate there instead
        val pending = pendingDeepLink
        pendingDeepLink = null
        if (pending != null) {
            navigateToDeepLink(pending)
            return
        }

        // Clear back stack so Home is the root.
        // Skip the pop entirely if state is already saved (splash animation finishing while
        // app backgrounded) — commitAllowingStateLoss below handles the replace safely.
        if (!supportFragmentManager.isStateSaved) {
            try {
                supportFragmentManager.popBackStackImmediate(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            } catch (_: IllegalStateException) {
                // Race condition: state saved between check and call — proceed anyway
            }
        }
        val tx = supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.HOME)
        tx.replace(R.id.main_container, HomeFragment())
            .commitAllowingStateLoss()
        syncBottomNav()
    }

    fun navigateToMultiView(seedChannel: LiveStream? = null, streamUrl: String? = null) {
        val tx = supportFragmentManager.beginTransaction()
        FragmentTransitions.apply(tx, TransitionDirection.FORWARD)
        tx.replace(R.id.main_container, MultiViewFragment.newInstance(seedChannel, streamUrl))
            .addToBackStack(null)
            .commit()
    }

    fun navigateToLogin() {
        // Clear back stack so Login is the root
        supportFragmentManager.popBackStackImmediate(null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, LoginFragment())
            .commit()
        // Login commits don't hit the back-stack listener, so hide the nav chrome deterministically
        // (logout arrives here from Home where the bar is visible).
        bottomNav?.visibility = View.GONE
        findViewById<View>(R.id.main_container)?.let { container ->
            (container.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.bottomMargin != 0) {
                    lp.bottomMargin = 0
                    container.layoutParams = lp
                }
            }
        }
    }
}
