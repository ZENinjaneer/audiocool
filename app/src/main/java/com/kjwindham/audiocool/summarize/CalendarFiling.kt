package com.kjwindham.audiocool.summarize

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.core.content.ContextCompat
import com.kjwindham.audiocool.data.FolderRepository
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.Prefs
import kotlin.concurrent.thread

/**
 * An option, off until turned on: a session recorded during an event on your calendar goes in a folder
 * named after the event, so a weekly lecture's recordings end up together. It only reads the calendar.
 */
object CalendarFiling {
    private const val TAG = "CalendarFiling"

    data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean)

    /** The event going on at [at]: of those not all day, the one that started last. */
    fun eventAt(events: List<Event>, at: Long): Event? =
        events.filter { !it.allDay && it.title.isNotBlank() && it.begin <= at && at < it.end }.maxByOrNull { it.begin }

    /** Files [sessionId], if it's in no folder, by the event going on now; when the option is on and allowed. */
    fun fileNow(context: Context, sessionId: String) {
        val app = context.applicationContext
        if (!Prefs(app).calendarFolders) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        if (SessionRepository.get(sessionId)?.folder != null) return
        val now = System.currentTimeMillis()
        thread(name = "calendar") {
            val event = try {
                eventAt(eventsAround(app, now), now)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the calendar", e)
                null
            } ?: return@thread
            Handler(Looper.getMainLooper()).post {
                // Unless it's been filed meanwhile.
                if (SessionRepository.get(sessionId)?.folder != null) return@post
                val folder = FolderRepository.create(event.title.trim().take(40))
                SessionRepository.moveToFolder(sessionId, folder)
                Organizer.filed(Organizer.Filed(sessionId, folder, byCalendar = true))
            }
        }
    }

    private fun eventsAround(context: Context, at: Long): List<Event> {
        val columns = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY)
        return Instances.query(context.contentResolver, columns, at - 60_000, at + 60_000)?.use { c ->
            buildList { while (c.moveToNext()) add(Event(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getInt(3) == 1)) }
        }.orEmpty()
    }
}
