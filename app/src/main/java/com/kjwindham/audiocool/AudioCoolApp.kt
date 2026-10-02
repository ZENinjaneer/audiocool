package com.kjwindham.audiocool

import android.app.Application
import com.kjwindham.audiocool.audio.QuickRecord
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.backup.BackupController
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.ocr.SlideText
import com.kjwindham.audiocool.summarize.SummaryController
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.AppLog

class AudioCoolApp : Application() {
    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrDefault(packageName)
        }

    override fun onCreate() {
        super.onCreate()
        // The summary model's own process (SummaryEngineService) needs none of the app's machinery.
        if (processName().endsWith(":summaries")) return
        // First, so a crash in anything after it is recorded.
        AppLog.init(this)
        SessionRepository.init(this)
        RecorderController.init(this)
        QuickRecord.init(this)
        TranscriptionController.init(this)
        BackupController.init(this)
        DesktopSync.init(this)
        SlideText.init(this)
        SummaryController.init(this)
    }
}
