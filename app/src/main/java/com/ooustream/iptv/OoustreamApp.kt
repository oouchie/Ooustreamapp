package com.ooustream.iptv

import android.app.Application
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.ProgressiveImageLoader
import com.ooustream.iptv.common.QualityPolicy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OoustreamApp : Application() {

    @Inject lateinit var dpadSoundManager: DpadSoundManager
    @Inject lateinit var qualityPolicy: QualityPolicy

    override fun onCreate() {
        super.onCreate()
        dpadSoundManager.initialize()
        DpadSoundManager.setInstance(dpadSoundManager)
        ProgressiveImageLoader.setQualityPolicy(qualityPolicy)
    }
}
