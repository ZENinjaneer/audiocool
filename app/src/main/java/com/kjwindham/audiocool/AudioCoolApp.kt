package com.kjwindham.audiocool

import android.app.Application
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.backup.BackupController
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.transcribe.TranscriptionController

class AudioCoolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionRepository.init(this)
        RecorderController.init(this)
        TranscriptionController.init(this)
        BackupController.init(this)
        DesktopSync.init(this)
    }
}
