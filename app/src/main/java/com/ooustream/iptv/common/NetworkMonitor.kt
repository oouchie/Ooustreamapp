package com.ooustream.iptv.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkState(
    val isConnected: Boolean = false,
    val isWifi: Boolean = false,
    val estimatedBandwidthKbps: Int = 0
)

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Diagnostic logger — set after Hilt injection to avoid circular dependency */
    var diagnosticLogger: StreamDiagnosticLogger? = null

    // Throttle NETWORK/CAPABILITIES logging to reduce diagnostic log noise
    private var lastCapabilitiesLogTime = 0L
    private var lastLoggedDownMbps = -1
    private var lastLoggedIsWifi: Boolean? = null

    companion object {
        private const val CAPABILITIES_LOG_INTERVAL_MS = 60_000L  // 60 seconds
        private const val BANDWIDTH_CHANGE_THRESHOLD = 0.20f       // 20% change
    }

    private val _state = MutableStateFlow(getCurrentState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateState()
            val type = getNetworkType(network)
            diagnosticLogger?.logNetworkChange(type, connected = true, wifiSignal = getWifiSignalDbm())
        }

        override fun onLost(network: Network) {
            _state.value = NetworkState(isConnected = false)
            diagnosticLogger?.logNetworkChange("DISCONNECTED", connected = false, wifiSignal = null)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val bandwidth = capabilities.linkDownstreamBandwidthKbps
            _state.value = NetworkState(
                isConnected = true,
                isWifi = isWifi,
                estimatedBandwidthKbps = bandwidth
            )
            val downMbps = capabilities.linkDownstreamBandwidthKbps / 1000
            val upMbps = capabilities.linkUpstreamBandwidthKbps / 1000

            // Throttle logging: only log if time elapsed OR significant change
            val now = android.os.SystemClock.elapsedRealtime()
            val timeElapsed = now - lastCapabilitiesLogTime >= CAPABILITIES_LOG_INTERVAL_MS
            val wifiChanged = lastLoggedIsWifi != null && isWifi != lastLoggedIsWifi
            val bandwidthChanged = lastLoggedDownMbps > 0 &&
                kotlin.math.abs(downMbps - lastLoggedDownMbps).toFloat() / lastLoggedDownMbps > BANDWIDTH_CHANGE_THRESHOLD

            if (timeElapsed || wifiChanged || bandwidthChanged || lastCapabilitiesLogTime == 0L) {
                lastCapabilitiesLogTime = now
                lastLoggedDownMbps = downMbps
                lastLoggedIsWifi = isWifi
                diagnosticLogger?.logNetworkCapabilities(downMbps, upMbps, isWifi)
            }
        }
    }

    init {
        registerCallback()
    }

    private fun registerCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    private fun updateState() {
        _state.value = getCurrentState()
    }

    @Suppress("DEPRECATION")
    fun getWifiSignalDbm(): Int? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val rssi = wifiManager.connectionInfo.rssi
            if (rssi != -127) rssi else null
        } catch (_: Exception) { null }
    }

    fun getNetworkType(network: Network? = null): String {
        val net = network ?: connectivityManager.activeNetwork ?: return "NONE"
        val caps = connectivityManager.getNetworkCapabilities(net) ?: return "UNKNOWN"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
    }

    @Suppress("DEPRECATION")
    private fun getCurrentState(): NetworkState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return NetworkState()
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkState()
            return NetworkState(
                isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                estimatedBandwidthKbps = caps.linkDownstreamBandwidthKbps
            )
        } else {
            val info = connectivityManager.activeNetworkInfo
            return NetworkState(
                isConnected = info?.isConnected == true,
                isWifi = info?.type == ConnectivityManager.TYPE_WIFI,
                estimatedBandwidthKbps = 0
            )
        }
    }
}
