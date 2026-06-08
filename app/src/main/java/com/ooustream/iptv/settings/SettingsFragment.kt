package com.ooustream.iptv.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.BuildConfig
import com.ooustream.iptv.MainActivity
import com.ooustream.iptv.R
import com.ooustream.iptv.common.AudioLogger
import com.ooustream.iptv.common.PhoneGuidedStepFragment
import com.ooustream.iptv.common.CrashLogger
import com.ooustream.iptv.account.AccountDashboardFragment
import com.ooustream.iptv.backup.BackupFragment
import com.ooustream.iptv.common.SendDebugLogManager
import com.ooustream.iptv.parental.ContentFilterManager
import com.ooustream.iptv.parental.ParentalControlManager
import com.ooustream.iptv.parental.ParentalPinFragment
import com.ooustream.iptv.speedtest.SpeedTestFragment
import javax.inject.Inject
import com.ooustream.iptv.update.UpdateFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : PhoneGuidedStepFragment() {

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
        private const val ACTION_AUDIO_DECODER = 11L
        private const val ACTION_CLEAR_HISTORY = 12L
        private const val ACTION_SUBTITLE_SETTINGS = 13L
        private const val ACTION_SEND_DEBUG_LOG = 14L
        private const val ACTION_ALLOW_SELF_SIGNED_CERTS = 15L
    }

    @Inject lateinit var parentalControlManager: ParentalControlManager
    @Inject lateinit var contentFilterManager: ContentFilterManager
    @Inject lateinit var sendDebugLogManager: SendDebugLogManager

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
        val parentalDesc = if (parentalControlManager.isEnabled.value) {
            "ON — Manage PIN and content restrictions"
        } else {
            "OFF — Set up PIN and content restrictions"
        }
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_PARENTAL)
                .title("Parental Controls")
                .description(parentalDesc)
                .build()
        )

        // Subtitle Settings
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SUBTITLE_SETTINGS)
                .title("Subtitle Settings")
                .description("Customize captions and subtitle appearance")
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

        // Send Debug Log
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SEND_DEBUG_LOG)
                .title("Send Debug Log")
                .description("Send diagnostic report to Ooustream support")
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

        // Clear Watch History
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CLEAR_HISTORY)
                .title("Clear Watch History")
                .description("Remove all watch progress and series tracking data")
                .build()
        )

        // Advanced: Allow Self-Signed Certificates
        val selfSignedOn = com.ooustream.iptv.settings.NetworkSettings
            .allowSelfSignedCerts(requireContext())
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ALLOW_SELF_SIGNED_CERTS)
                .title("Allow Self-Signed Certificates")
                .description(
                    if (selfSignedOn) "ON — weakens TLS (restart required)"
                    else "OFF — standard TLS verification"
                )
                .build()
        )

        // v3.7.0: "Reset Playback Recovery" action removed — libVLC and its crash
        // guard are both gone.

        // Audio Decoder info (non-actionable)
        val ffmpegAvailable = AudioLogger.isFfmpegAvailable
        val decoderDesc = if (ffmpegAvailable)
            "FFmpeg (AC3, DTS, EAC3, AAC, FLAC)"
        else
            "Hardware Only"
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_AUDIO_DECODER)
                .title("Audio Decoder")
                .description(decoderDesc)
                .focusable(false)
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
            ACTION_SUBTITLE_SETTINGS -> navigateToFragment(SubtitleSettingsFragment())
            ACTION_BACKUP -> navigateToFragment(BackupFragment())
            ACTION_SPEED_TEST -> navigateToFragment(SpeedTestFragment())
            ACTION_UPDATE -> navigateToFragment(UpdateFragment())
            ACTION_REFRESH_PLAYLIST -> refreshPlaylist()
            ACTION_SEND_DEBUG_LOG -> showSendDebugLogConfirmation()
            ACTION_CRASH_LOG -> showCrashLog()
            ACTION_CLEAR_CACHE -> showClearCacheConfirmation()
            ACTION_CLEAR_HISTORY -> showClearHistoryConfirmation()
            ACTION_ALLOW_SELF_SIGNED_CERTS -> showSelfSignedCertsConfirmation()
            ACTION_LOGOUT -> showLogoutConfirmation()
        }
    }

    private fun showSelfSignedCertsConfirmation() {
        val currentlyOn = com.ooustream.iptv.settings.NetworkSettings
            .allowSelfSignedCerts(requireContext())
        val targetState = !currentlyOn
        val title = if (targetState) "Allow Self-Signed Certificates?" else "Disable Self-Signed Certificates?"
        val message = if (targetState) {
            "This weakens TLS verification so Ooustream will connect to IPTV " +
                "servers with self-signed or invalid SSL certificates.\n\n" +
                "Only turn this on if your provider uses a self-signed cert and " +
                "streams fail to load. App restart required."
        } else {
            "Restore standard TLS certificate verification. App restart required."
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (targetState) "Allow" else "Disable") { _, _ ->
                com.ooustream.iptv.settings.NetworkSettings
                    .setAllowSelfSignedCerts(requireContext(), targetState)
                android.widget.Toast.makeText(
                    requireContext(),
                    "Setting saved. Restart the app to apply.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                // Re-render the action so the ON/OFF description updates on return.
                val newDesc = if (targetState) "ON — weakens TLS (restart required)"
                    else "OFF — standard TLS verification"
                val idx = findActionPositionById(ACTION_ALLOW_SELF_SIGNED_CERTS)
                if (idx >= 0) {
                    actions[idx].description = newDesc
                    notifyActionChanged(idx)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // endregion

    // region State Observation

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeEvents()
    }

    override fun onResume() {
        super.onResume()
        // Refresh parental controls description when returning from settings
        updateParentalDescription()
    }

    private fun updateParentalDescription() {
        val pos = findActionPositionById(ACTION_PARENTAL)
        if (pos >= 0) {
            val desc = if (parentalControlManager.isEnabled.value) {
                "ON — Manage PIN and content restrictions"
            } else {
                "OFF — Set up PIN and content restrictions"
            }
            findActionById(ACTION_PARENTAL)?.description = desc
            notifyActionChanged(pos)
        }
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
        }
        val scrollView = ScrollView(requireContext()).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isScrollbarFadingEnabled = false
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

    // region Debug Log

    private fun showSendDebugLogConfirmation() {
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Briefly describe your issue (optional)"
            maxLines = 3
            setTextColor(0xFFCCCCCC.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(32, 16, 32, 16)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Send Debug Log?")
            .setMessage(
                "This will send a diagnostic report to Ooustream support.\n\n" +
                "The report includes:\n" +
                "  \u2022 Device info (model, Android version)\n" +
                "  \u2022 App version\n" +
                "  \u2022 Stream playback events (last 30 min)\n" +
                "  \u2022 Network connection info\n\n" +
                "No personal data or passwords are included."
            )
            .setView(editText)
            .setPositiveButton("Send Report") { _, _ ->
                val description = editText.text?.toString() ?: ""
                sendDebugLogManager.sendDebugLog(requireActivity(), description)
                Toast.makeText(requireContext(), "Preparing debug log...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // endregion

    // region Confirmation Dialogs

    private fun showClearHistoryConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Watch History?")
            .setMessage("This will remove all watch progress, continue watching items, and series tracking data. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                viewModel.clearWatchHistory()
                Toast.makeText(requireContext(), "Watch history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
