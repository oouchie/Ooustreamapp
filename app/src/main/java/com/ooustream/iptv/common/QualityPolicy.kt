package com.ooustream.iptv.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

enum class QualityTier {
    HIGH,    // >10 Mbps: full-res images, preview player, full buffer
    MEDIUM,  // 2-10 Mbps: 400px images, preview enabled, reduced buffer
    LOW      // <2 Mbps: 200px images, no preview, minimal buffer, warn before VOD
}

@Singleton
class QualityPolicy @Inject constructor(
    private val networkMonitor: NetworkMonitor
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    val tier: StateFlow<QualityTier> = networkMonitor.state
        .map { state ->
            when {
                !state.isConnected -> QualityTier.LOW
                state.estimatedBandwidthKbps > 10_000 -> QualityTier.HIGH
                state.estimatedBandwidthKbps > 2_000 -> QualityTier.MEDIUM
                else -> QualityTier.MEDIUM // Default to MEDIUM when bandwidth unknown (0)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, QualityTier.MEDIUM)

    val imageSize: Int
        get() = when (tier.value) {
            QualityTier.HIGH -> 0     // 0 = original size
            QualityTier.MEDIUM -> 400
            QualityTier.LOW -> 200
        }

    val isPreviewEnabled: Boolean
        get() = tier.value != QualityTier.LOW

    val shouldWarnBeforeVod: Boolean
        get() = tier.value == QualityTier.LOW
}
