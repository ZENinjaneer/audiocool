package com.kjwindham.audiocool.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.kjwindham.audiocool.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a record of what the app did, for finding out why something went wrong on a phone that
 * isn't here: a report of each crash (the full stack), and the app's log as it runs, written to
 * files by logcat itself (which also catches crashes in native code). An app can only read its
 * own lines, so nothing of other apps is in it. [share] sends it all as one text file.
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val KEEP_CRASHES = 10

    // The application context, which lives as long as the process, so holding it isn't a leak.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private lateinit var dir: File

    fun init(context: Context) {
        app = context.applicationContext
        dir = File(app.filesDir, "logs").apply { mkdirs() }
        installCrashHandler()
        startLogcat()
        Log.i(TAG, "AudioCool ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started on ${device()}")
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(dir, "crash-${stamp()}.txt").writeText(header() + "Thread: ${thread.name}\n\n${e.stackTraceToString()}")
                crashes().drop(KEEP_CRASHES).forEach { it.delete() }
                Prefs(app).unreportedCrash = true
            }
            // Then on to Android's own handling, which logs it too and closes the app.
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Has logcat write this app's lines to rotating files as they come (2 MB at most). It starts with
     * what's still in the log from before, such as the lines leading up to a crash. Android ends it with
     * the app.
     */
    private fun startLogcat() {
        try {
            ProcessBuilder(
                "logcat", "-v", "threadtime", "-b", "main,system,crash",
                "-f", File(dir, "logcat.txt").path, "-r", "512", "-n", "3",
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't keep a log file", e)
        }
    }

    private fun crashes(): List<File> =
        dir.listFiles { f -> f.name.startsWith("crash-") }.orEmpty().sortedByDescending { it.name }

    /** Opens the share sheet with everything in one text file: the phone, the crash reports, and the log. */
    fun share(context: Context) {
        Prefs(context).unreportedCrash = false
        val out = File(File(context.cacheDir, "share").apply { mkdirs() }, "audiocool-log-${stamp()}.txt")
        out.bufferedWriter().use { w ->
            w.write(header())
            val crashes = crashes()
            w.write("\n===== Crashes (${crashes.size}, newest first) =====\n")
            crashes.forEach { w.write("\n--- ${it.name}\n${it.readText()}\n") }
            // Oldest rotation first, so the log reads in order.
            val logs = dir.listFiles { f -> f.name.startsWith("logcat.txt") }.orEmpty()
                .sortedByDescending { it.name.substringAfter("logcat.txt.", "0").toIntOrNull() ?: 0 }
            w.write("\n===== Log, as kept =====\n")
            logs.forEach { w.write(it.readText()) }
            // logcat writes its file a block at a time; what's still in Android's log has the latest lines,
            // such as the last ones before a crash.
            w.write("\n===== Log, latest =====\n")
            w.write(currentLog())
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AudioCool ${BuildConfig.VERSION_NAME} log")
            clipData = ClipData.newRawUri("AudioCool log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share the app's log"))
    }

    /** What's in Android's log for this app right now. */
    private fun currentLog(): String = runCatching {
        ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()
    }.getOrElse { "(couldn't read the log: $it)\n" }

    /** True when the app crashed since the log was last shared, to offer sending it. */
    fun crashedLastTime(context: Context): Boolean = Prefs(context).unreportedCrash

    fun dismissCrash(context: Context) {
        Prefs(context).unreportedCrash = false
    }

    private fun header(): String {
        val memory = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return buildString {
            appendLine("AudioCool ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("When: ${Date()}")
            appendLine("Phone: ${device()}")
            appendLine("Memory: ${memory.availMem / 1_000_000} MB free of ${memory.totalMem / 1_000_000} MB${if (memory.lowMemory) " (low)" else ""}")
            appendLine("Storage: ${app.filesDir.usableSpace / 1_000_000} MB free")
        }
    }

    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}"

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
