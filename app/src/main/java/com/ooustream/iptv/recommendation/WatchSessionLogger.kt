package com.ooustream.iptv.recommendation

import com.ooustream.iptv.data.local.dao.ChannelWatchLogDao
import com.ooustream.iptv.data.local.entity.ChannelWatchLogEntity
import com.ooustream.iptv.data.model.LiveStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks Live TV viewing sessions and logs them to Room for recommendation scoring.
 * Only sessions >= 30 seconds are recorded to avoid accidental channel-surf noise.
 */
@Singleton
class WatchSessionLogger @Inject constructor(
    private val watchLogDao: ChannelWatchLogDao
) {

    private data class ActiveSession(
        val channelId: Int,
        val channelName: String,
        val categoryId: String?,
        val categoryName: String?,
        val channelIcon: String?,
        val startTime: Long
    )

    private var currentSession: ActiveSession? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Start tracking a new live channel session.
     * Ends any previous session first.
     */
    fun onChannelStarted(channel: LiveStream, categoryName: String?) {
        endCurrentSession()
        currentSession = ActiveSession(
            channelId = channel.streamId,
            channelName = channel.name,
            categoryId = channel.categoryId,
            categoryName = categoryName,
            channelIcon = channel.streamIcon,
            startTime = System.currentTimeMillis()
        )
    }

    /**
     * End the current session and persist if >= 30 seconds.
     */
    fun endCurrentSession() {
        val session = currentSession ?: return
        currentSession = null

        val durationMs = System.currentTimeMillis() - session.startTime
        val durationSec = durationMs / 1000
        if (durationSec < MIN_SESSION_SECONDS) return

        val calendar = Calendar.getInstance().apply { timeInMillis = session.startTime }
        val entity = ChannelWatchLogEntity(
            channelId = session.channelId,
            channelName = session.channelName,
            categoryId = session.categoryId,
            categoryName = session.categoryName,
            channelIcon = session.channelIcon,
            dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK),
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            durationSeconds = durationSec,
            timestamp = session.startTime
        )

        // Fire-and-forget insert with NonCancellable to survive scope cancellation
        scope.launch {
            withContext(NonCancellable) {
                watchLogDao.insert(entity)
            }
        }
    }

    /**
     * Called when player exits. Ends any active session.
     */
    fun onPlayerExit() {
        endCurrentSession()
    }

    companion object {
        private const val MIN_SESSION_SECONDS = 30L
    }
}
