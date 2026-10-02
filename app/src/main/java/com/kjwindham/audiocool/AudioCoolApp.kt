package com.kjwindham.audiocool

import android.app.Application
import com.kjwindham.audiocool.audio.QuickRecord
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.backup.BackupController
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.ocr.SlideText
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.AppLog

class AudioCoolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, so a crash in anything after it is recorded.
        AppLog.init(this)
        SessionRepository.init(this)
        RecorderController.init(this)
        QuickRecord.init(this)
        TranscriptionController.init(this)
        BackupController.init(this)
        DesktopSync.init(this)
        SlideText.init(this)
    }
}
