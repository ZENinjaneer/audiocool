package com.kjwindham.audiocool.util

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How far before a note's timestamp playback starts when the note is tapped. */
    var leadInSeconds: Int
        get() = sp.getInt("lead_in_seconds", 3)
        set(value) = sp.edit { putInt("lead_in_seconds", value) }

    /** How much of what was said a session's timeline shows: a [com.kjwindham.audiocool.data.TimelineMode] name. */
    var timelineMode: String
        get() = sp.getString("timeline_mode", "EVERYTHING").orEmpty()
        set(value) = sp.edit { putString("timeline_mode", value) }

    /** The folder the main screen shows; empty for all sessions. */
    var shownFolder: String
        get() = sp.getString("shown_folder", "").orEmpty()
        set(value) = sp.edit { putString("shown_folder", value) }

    /** File each new session in the folder that fits once its summary is ready (the summary model decides). */
    var autoFile: Boolean
        get() = sp.getBoolean("auto_file", false)
        set(value) = sp.edit { putBoolean("auto_file", value) }

    /** When [autoFile] was turned on: older sessions are left for suggestions. */
    var autoFileSince: Long
        get() = sp.getLong("auto_file_since", 0)
        set(value) = sp.edit { putLong("auto_file_since", value) }

    /** Sessions the model has already placed (or found no folder for), so they aren't asked about again. */
    var autoFileDone: Set<String>
        get() = sp.getStringSet("auto_file_done", emptySet()).orEmpty()
        set(value) = sp.edit { putStringSet("auto_file_done", value) }

    /** Folders the model suggested and that haven't been reviewed yet, as JSON. */
    var folderSuggestions: String
        get() = sp.getString("folder_suggestions", "").orEmpty()
        set(value) = sp.edit { putString("folder_suggestions", value) }

    /** What the last suggestions were made from, so the same sessions aren't looked at twice. */
    var suggestBasis: String
        get() = sp.getString("suggest_basis", "").orEmpty()
        set(value) = sp.edit { putString("suggest_basis", value) }

    /** How many sessions were in no folder when suggestions were last turned down. */
    var suggestDismissedAt: Int
        get() = sp.getInt("suggest_dismissed_at", 0)
        set(value) = sp.edit { putInt("suggest_dismissed_at", value) }

    /** File a session recorded during a calendar event in a folder named after it. */
    var calendarFolders: Boolean
        get() = sp.getBoolean("calendar_folders", false)
        set(value) = sp.edit { putBoolean("calendar_folders", value) }

    /** The app crashed and the log hasn't been shared since: offer to. */
    var unreportedCrash: Boolean
        get() = sp.getBoolean("unreported_crash", false)
        set(value) = sp.edit(commit = true) { putBoolean("unreported_crash", value) }

    /** Summarize sessions on the phone once the summary model is downloaded. */
    var summaries: Boolean
        get() = sp.getBoolean("summaries", true)
        set(value) = sp.edit { putBoolean("summaries", value) }

    /** The summary model on this phone's GPU: "on" when asked for, "failed" once it's crashed there; else off. */
    var summaryGpu: String
        get() = sp.getString("summary_gpu", "").orEmpty()
        set(value) = sp.edit(commit = true) { putString("summary_gpu", value) }

    /** Speculative decoding has crashed the summary model on this phone; go without it. */
    var summaryNoSpeculative: Boolean
        get() = sp.getBoolean("summary_no_speculative", false)
        set(value) = sp.edit(commit = true) { putBoolean("summary_no_speculative", value) }

    /** "Not now" was chosen on the offer to set up summaries. */
    var summaryOfferDismissed: Boolean
        get() = sp.getBoolean("summary_offer_dismissed", false)
        set(value) = sp.edit { putBoolean("summary_offer_dismissed", value) }

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
