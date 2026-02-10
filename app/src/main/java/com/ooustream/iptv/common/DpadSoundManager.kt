package com.ooustream.iptv.common

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.ooustream.iptv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DpadSoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var soundPool: SoundPool? = null
    private var moveId: Int = 0
    private var selectId: Int = 0
    private var boundaryId: Int = 0
    private var enabled: Boolean = true

    companion object {
        @Volatile
        private var instance: DpadSoundManager? = null

        fun getInstance(): DpadSoundManager? = instance

        internal fun setInstance(mgr: DpadSoundManager) {
            instance = mgr
        }
    }

    fun initialize() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()

        soundPool?.let { pool ->
            moveId = pool.load(context, R.raw.nav_move, 1)
            selectId = pool.load(context, R.raw.nav_select, 1)
            boundaryId = pool.load(context, R.raw.nav_boundary, 1)
        }
    }

    fun playMove() {
        if (enabled && moveId != 0) {
            soundPool?.play(moveId, 0.3f, 0.3f, 1, 0, 1.0f)
        }
    }

    fun playSelect() {
        if (enabled && selectId != 0) {
            soundPool?.play(selectId, 0.4f, 0.4f, 1, 0, 1.0f)
        }
    }

    fun playBoundary() {
        if (enabled && boundaryId != 0) {
            soundPool?.play(boundaryId, 0.2f, 0.2f, 1, 0, 1.0f)
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
}
