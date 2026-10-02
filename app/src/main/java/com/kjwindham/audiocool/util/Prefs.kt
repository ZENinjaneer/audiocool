package com.kjwindham.audiocool.util

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How far before a note's timestamp playback starts when the note is tapped. */
    var leadInSeconds: Int
        get() = sp.getInt("lead_in_seconds", 3)
        set(value) = sp.edit { putInt("lead_in_seconds", value) }

    /** Show sessions on the main screen as a grid of thumbnails rather than a list. */
    var galleryView: Boolean
        get() = sp.getBoolean("gallery_view", false)
        set(value) = sp.edit { putBoolean("gallery_view", value) }

    /** "Not now" was chosen on the main screen's offer to download the speech model. */
    var speechModelOfferDismissed: Boolean
        get() = sp.getBoolean("speech_model_offer_dismissed", false)
        set(value) = sp.edit { putBoolean("speech_model_offer_dismissed", value) }

    /** Keep a notification with a Record button, for the lock screen, while nothing is recording. */
    var quickRecord: Boolean
        get() = sp.getBoolean("quick_record", false)
        set(value) = sp.edit { putBoolean("quick_record", value) }

    /** Record with a plugged-in mic when there is one, instead of the phone's own. */
    var recordWithExternalMic: Boolean
        get() = sp.getBoolean("record_with_external_mic", false)
        set(value) = sp.edit { putBoolean("record_with_external_mic", value) }

    var askedNotificationPermission: Boolean
        get() = sp.getBoolean("asked_notification_permission", false)
        set(value) = sp.edit { putBoolean("asked_notification_permission", value) }

    /** Transcribe each new recording when it stops (only once the speech model is downloaded). */
    var autoTranscribe: Boolean
        get() = sp.getBoolean("auto_transcribe", true)
        set(value) = sp.edit { putBoolean("auto_transcribe", value) }

    /** Recordings waiting to be transcribed, as "sessionId:recId,...". */
    var transcriptionQueue: String
        get() = sp.getString("transcription_queue", "").orEmpty()
        set(value) = sp.edit { putString("transcription_queue", value) }

    /** The recording being transcribed live ("sessionId:recId"), cleared when that finishes. */
    var liveRecording: String
        get() = sp.getString("live_recording", "").orEmpty()
        set(value) = sp.edit { putString("live_recording", value) }

    /** The paired AudioCool Desktop as "url\ntoken\nname", or empty. */
    var desktopPairing: String
        get() = sp.getString("desktop_pairing", "").orEmpty()
        set(value) = sp.edit { putString("desktop_pairing", value) }

    /** Sessions whose desktop transcripts haven't come back yet, comma-separated. */
    var desktopAwaiting: String
        get() = sp.getString("desktop_awaiting", "").orEmpty()
        set(value) = sp.edit { putString("desktop_awaiting", value) }

    /** The folder (a Storage Access Framework tree URI) that backups go to, if set. */
    var backupFolder: String?
        get() = sp.getString("backup_folder", null)
        set(value) = sp.edit { putString("backup_folder", value) }

    var lastBackupAt: Long
        get() = sp.getLong("last_backup_at", 0L)
        set(value) = sp.edit { putLong("last_backup_at", value) }

    /** What each session looked like when it was last backed up, so unchanged ones are skipped. */
    var backupFingerprints: String
        get() = sp.getString("backup_fingerprints", "").orEmpty()
        set(value) = sp.edit { putString("backup_fingerprints", value) }
}
