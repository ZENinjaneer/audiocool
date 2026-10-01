package com.kjwindham.audiocool

import android.app.Application
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.SessionRepository

class AudioCoolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionRepository.init(this)
        RecorderController.init(this)
    }
}
