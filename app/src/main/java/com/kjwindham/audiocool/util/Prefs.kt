package com.kjwindham.audiocool.util

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How far before a note's timestamp playback starts when the note is tapped. */
    var leadInSeconds: Int
        get() = sp.getInt("lead_in_seconds", 3)
        set(value) = sp.edit { putInt("lead_in_seconds", value) }

    var askedNotificationPermission: Boolean
        get() = sp.getBoolean("asked_notification_permission", false)
        set(value) = sp.edit { putBoolean("asked_notification_permission", value) }
}
