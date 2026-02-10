package com.ooustream.iptv.account

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.R
import com.ooustream.iptv.common.BaseFragment
import com.ooustream.iptv.data.model.AuthResponse
import com.ooustream.iptv.data.model.UserInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class AccountDashboardFragment : BaseFragment() {

    private val viewModel: AccountViewModel by viewModels()

    // Views
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var contentArea: LinearLayout
    private lateinit var statusBadge: TextView
    private lateinit var usernameText: TextView
    private lateinit var serverText: TextView
    private lateinit var createdText: TextView
    private lateinit var trialBadge: TextView
    private lateinit var daysRemaining: TextView
    private lateinit var daysLabel: TextView
    private lateinit var expiryDate: TextView
    private lateinit var connectionGauge: ConnectionGaugeView
    private lateinit var outputFormats: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_account_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        observeState()
        viewModel.loadAccountInfo()
    }

    private fun bindViews(view: View) {
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        contentArea = view.findViewById(R.id.content_area)
        statusBadge = view.findViewById(R.id.status_badge)
        usernameText = view.findViewById(R.id.username_text)
        serverText = view.findViewById(R.id.server_text)
        createdText = view.findViewById(R.id.created_text)
        trialBadge = view.findViewById(R.id.trial_badge)
        daysRemaining = view.findViewById(R.id.days_remaining)
        daysLabel = view.findViewById(R.id.days_label)
        expiryDate = view.findViewById(R.id.expiry_date)
        connectionGauge = view.findViewById(R.id.connection_gauge)
        outputFormats = view.findViewById(R.id.output_formats)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.accountInfo.collect { info ->
                        if (info != null) {
                            populateDashboard(info)
                            contentArea.visibility = View.VISIBLE
                        }
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        showError(message)
                    }
                }
            }
        }
    }

    private fun populateDashboard(response: AuthResponse) {
        val userInfo = response.userInfo
        val serverInfo = response.serverInfo

        // --- Status Badge ---
        populateStatusBadge(userInfo)

        // --- Username ---
        usernameText.text = userInfo.username

        // --- Server ---
        val serverUrl = serverInfo.url ?: "Unknown"
        val port = serverInfo.port ?: ""
        serverText.text = if (port.isNotEmpty()) "$serverUrl:$port" else serverUrl

        // --- Member Since ---
        val createdEpoch = userInfo.createdAt?.toLongOrNull()
        if (createdEpoch != null) {
            createdText.text = formatEpochDate(createdEpoch)
        } else {
            createdText.text = "Unknown"
        }

        // --- Trial badge ---
        val isTrial = userInfo.isTrial == "1"
        trialBadge.visibility = if (isTrial) View.VISIBLE else View.GONE

        // --- Days Remaining ---
        populateDaysRemaining(userInfo)

        // --- Connection Gauge ---
        val activeCons = userInfo.activeCons?.toIntOrNull() ?: 0
        val maxCons = userInfo.maxConnections?.toIntOrNull() ?: 1
        connectionGauge.setConnections(activeCons, maxCons)

        // --- Output Formats ---
        val formats = userInfo.allowedOutputFormats
        if (!formats.isNullOrEmpty()) {
            outputFormats.text = "Formats: ${formats.joinToString(", ") { it.uppercase(Locale.US) }}"
            outputFormats.visibility = View.VISIBLE
        } else {
            outputFormats.visibility = View.GONE
        }
    }

    private fun populateStatusBadge(userInfo: UserInfo) {
        val status = userInfo.status
        val isTrial = userInfo.isTrial == "1"

        val (badgeText, badgeColor) = when {
            status.equals("Active", ignoreCase = true) && isTrial -> "Trial" to COLOR_YELLOW
            status.equals("Active", ignoreCase = true) -> "Active" to COLOR_GREEN
            status.equals("Expired", ignoreCase = true) -> "Expired" to COLOR_RED
            status.equals("Disabled", ignoreCase = true) -> "Disabled" to COLOR_RED
            status.equals("Banned", ignoreCase = true) -> "Banned" to COLOR_RED
            else -> status to COLOR_YELLOW
        }

        statusBadge.text = badgeText

        // Update the badge background color dynamically
        val drawable = statusBadge.background
        if (drawable is GradientDrawable) {
            drawable.setColor(badgeColor)
        } else {
            // Fallback: create a new rounded drawable
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(badgeColor)
            }
            statusBadge.background = bg
        }
    }

    private fun populateDaysRemaining(userInfo: UserInfo) {
        val expEpochSeconds = userInfo.expDate?.toLongOrNull()

        if (expEpochSeconds == null) {
            // Unlimited / no expiry
            daysRemaining.text = "\u221E"  // infinity symbol
            daysRemaining.setTextColor(COLOR_GREEN)
            daysLabel.text = "unlimited"
            expiryDate.visibility = View.GONE
            return
        }

        val nowSeconds = System.currentTimeMillis() / 1000
        val diffSeconds = expEpochSeconds - nowSeconds
        val days = (diffSeconds / 86400).toInt()

        if (days < 0) {
            // Already expired
            daysRemaining.text = "0"
            daysRemaining.setTextColor(COLOR_RED)
            daysLabel.text = "expired"
            expiryDate.text = "Expired on ${formatEpochDate(expEpochSeconds)}"
            expiryDate.setTextColor(COLOR_RED)
            expiryDate.visibility = View.VISIBLE
            return
        }

        daysRemaining.text = days.toString()

        // Color code based on remaining days
        val daysColor = when {
            days > 30 -> COLOR_GREEN
            days > 7 -> COLOR_YELLOW
            else -> COLOR_RED
        }
        daysRemaining.setTextColor(daysColor)

        daysLabel.text = if (days == 1) "day remaining" else "days remaining"

        expiryDate.text = "Expires ${formatEpochDate(expEpochSeconds)}"
        expiryDate.visibility = View.VISIBLE
    }

    private fun formatEpochDate(epochSeconds: Long): String {
        return try {
            val date = Date(epochSeconds * 1000)
            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
            sdf.format(date)
        } catch (e: Exception) {
            "Unknown"
        }
    }

    companion object {
        private const val COLOR_GREEN = 0xFF22C55E.toInt()
        private const val COLOR_YELLOW = 0xFFF59E0B.toInt()
        private const val COLOR_RED = 0xFFEF4444.toInt()
    }
}
