package com.ooustream.iptv.settings

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.BuildConfig
import com.ooustream.iptv.MainActivity
import com.ooustream.iptv.R
import com.ooustream.iptv.common.CrashLogger
import com.ooustream.iptv.account.AccountDashboardFragment
import com.ooustream.iptv.backup.BackupFragment
import com.ooustream.iptv.parental.ParentalPinFragment
import com.ooustream.iptv.speedtest.SpeedTestFragment
import com.ooustream.iptv.update.UpdateFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val ACTION_ACCOUNT = 1L
        private const val ACTION_PARENTAL = 2L
        private const val ACTION_BACKUP = 3L
        private const val ACTION_SPEED_TEST = 4L
        private const val ACTION_UPDATE = 5L
        private const val ACTION_CLEAR_CACHE = 6L
        private const val ACTION_ABOUT = 7L
        private const val ACTION_LOGOUT = 8L
        private const val ACTION_REFRESH_PLAYLIST = 9L
        private const val ACTION_CRASH_LOG = 10L
    }

    private val viewModel: SettingsViewModel by viewModels()

    // region Guidance

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            "Settings",
            "Manage your Ooustream account and preferences",
            "",
            null
        )
    }

    // endregion

    // region Actions

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        // Account Dashboard
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ACCOUNT)
                .title("Account")
                .description("${viewModel.getUsername()} \u2022 View subscription details")
                .build()
        )

        // Parental Controls
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_PARENTAL)
                .title("Parental Controls")
                .description("Manage PIN and content restrictions")
                .build()
        )

        // Backup & Restore
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_BACKUP)
                .title("Backup & Restore")
                .description("Export or import your data")
                .build()
        )

        // Speed Test
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SPEED_TEST)
                .title("Speed Test")
                .description("Test your connection speed")
                .build()
        )

        // Check for Updates
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_UPDATE)
                .title("Check for Updates")
                .description("Current version: ${BuildConfig.VERSION_NAME}")
                .build()
        )

        // Update Playlist
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_REFRESH_PLAYLIST)
                .title("Update Playlist")
                .description("Refresh channels and content from server")
                .build()
        )

        // Clear Cache
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CLEAR_CACHE)
                .title("Clear Cache")
                .description("Clear all cached data")
                .build()
        )

        // Crash Logs
        val crashDesc = if (CrashLogger.hasCrashLog(requireContext()))
            "View recent crash reports for troubleshooting"
        else
            "No crashes recorded"
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CRASH_LOG)
                .title("Crash Logs")
                .description(crashDesc)
                .build()
        )

        // About (non-actionable)
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ABOUT)
                .title("About")
                .description("Ooustream v${BuildConfig.VERSION_NAME}")
                .focusable(false)
                .build()
        )

        // Logout
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_LOGOUT)
                .title("Logout")
                .description("Sign out of your account")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_ACCOUNT -> navigateToFragment(AccountDashboardFragment())
            ACTION_PARENTAL -> navigateToFragment(ParentalPinFragment())
            ACTION_BACKUP -> navigateToFragment(BackupFragment())
            ACTION_SPEED_TEST -> navigateToFragment(SpeedTestFragment())
            ACTION_UPDATE -> navigateToFragment(UpdateFragment())
            ACTION_REFRESH_PLAYLIST -> refreshPlaylist()
            ACTION_CRASH_LOG -> showCrashLog()
            ACTION_CLEAR_CACHE -> showClearCacheConfirmation()
            ACTION_LOGOUT -> showLogoutConfirmation()
        }
    }

    // endregion

    // region State Observation

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeEvents()
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.event.collect { event ->
                        when (event) {
                            is SettingsViewModel.SettingsEvent.LoggedOut -> {
                                (activity as? MainActivity)?.navigateToLogin()
                            }
                            is SettingsViewModel.SettingsEvent.CacheCleared -> {
                                Toast.makeText(requireContext(), "Cache cleared", Toast.LENGTH_SHORT).show()
                            }
                            is SettingsViewModel.SettingsEvent.PlaylistRefreshed -> {
                                resetPlaylistAction()
                                Toast.makeText(requireContext(), "Playlist updated", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        resetPlaylistAction()
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // endregion

    // region Navigation

    private fun navigateToFragment(fragment: androidx.fragment.app.Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // endregion

    // region Playlist Refresh

    private fun refreshPlaylist() {
        val pos = findActionPositionById(ACTION_REFRESH_PLAYLIST)
        if (pos >= 0) {
            findActionById(ACTION_REFRESH_PLAYLIST)?.description = "Refreshing\u2026"
            notifyActionChanged(pos)
        }
        viewModel.refreshPlaylist()
    }

    private fun resetPlaylistAction() {
        val pos = findActionPositionById(ACTION_REFRESH_PLAYLIST)
        if (pos >= 0) {
            findActionById(ACTION_REFRESH_PLAYLIST)?.description = "Refresh channels and content from server"
            notifyActionChanged(pos)
        }
    }

    // endregion

    // region Crash Log

    private fun showCrashLog() {
        val crashText = CrashLogger.getLastCrash(requireContext()) ?: "No crash data available."
        val textView = TextView(requireContext()).apply {
            text = crashText
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(requireContext()).apply {
            addView(textView)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Crash Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear Logs") { _, _ ->
                CrashLogger.clearCrashLog(requireContext())
                Toast.makeText(requireContext(), "Crash logs cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // endregion

    // region Confirmation Dialogs

    private fun showClearCacheConfirmation() {
        add(
            parentFragmentManager,
            SettingsConfirmFragment.newInstance(
                title = "Clear Cache?",
                message = "This will clear all cached content data. Your favorites, watch history, and EPG cache will be removed. Continue?",
                confirmLabel = "Clear",
                onConfirm = { viewModel.clearCache() }
            )
        )
    }

    private fun showLogoutConfirmation() {
        add(
            parentFragmentManager,
            SettingsConfirmFragment.newInstance(
                title = "Logout?",
                message = "Are you sure you want to sign out of your account?",
                confirmLabel = "Logout",
                onConfirm = { viewModel.logout() }
            )
        )
    }

    // endregion
}
