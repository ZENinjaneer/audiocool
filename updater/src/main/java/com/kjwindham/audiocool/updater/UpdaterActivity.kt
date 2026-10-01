package com.kjwindham.audiocool.updater

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Installs the latest AudioCool from its GitHub releases. If Android refuses, it shows why: the
 * installer's own error, the installed and downloaded versions and signing keys, and free space.
 * (Phones often just say "package appears to be invalid", which hides the reason.)
 */
@SuppressLint("SetTextI18n") // a one-screen tool in English
class UpdaterActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var button: Button
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        button = Button(this).apply {
            text = "Install the latest AudioCool"
            setOnClickListener { start() }
        }
        log = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                addView(
                    LinearLayout(this@UpdaterActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad, pad, pad)
                        addView(button)
                        addView(log)
                    },
                )
            },
        )
        describePhone()
        onResult(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onResult(intent)
    }

    private fun say(line: String) {
        main.post { log.append(line + "\n") }
    }

    private fun describePhone() {
        say("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        say("Build: ${Build.DISPLAY}")
        say("Chips: ${Build.SUPPORTED_ABIS.joinToString()}")
        say("Free space: ${StatFs(filesDir.path).availableBytes / 1_000_000} MB")
        val installed = runCatching { packageManager.getPackageInfo(TARGET, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
        if (installed == null) {
            say("AudioCool: not installed")
        } else {
            say("AudioCool installed: ${installed.versionName} (code ${installed.longVersionCode})")
            say("  signed by: ${signers(installed)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { packageManager.getInstallSourceInfo(TARGET) }.getOrNull()?.let {
                    say("  installed by: ${it.installingPackageName} (via ${it.initiatingPackageName})")
                }
            }
        }
        say("")
    }

    private fun start() {
        if (!packageManager.canRequestPackageInstalls()) {
            say("First let this app install apps: turn on \"Allow from this source\", come back, and tap the button again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        button.isEnabled = false
        thread {
            try {
                val apk = download()
                if (describeDownload(apk)) install(apk) else main.post { button.isEnabled = true }
            } catch (e: Exception) {
                say("Failed: ${e.javaClass.simpleName}: ${e.message}")
                main.post { button.isEnabled = true }
            }
        }
    }

    private fun download(): File {
        say("Downloading the latest AudioCool…")
        val file = File(cacheDir, "AudioCool.apk")
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - reported >= 4_000_000) {
                            reported = done
                            say("  ${done / 1_000_000} of ${total / 1_000_000} MB")
                        }
                    }
                }
            }
            say("Downloaded ${file.length()} bytes" + if (total > 0 && total != file.length()) " but expected $total!" else "")
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** Describes the downloaded app; false if Android can't read it as one. */
    private fun describeDownload(apk: File): Boolean {
        say("  SHA-256: ${sha256(apk.readBytes())}")
        val info = packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info == null) {
            say("Android can't read the download as an app.")
            return false
        }
        say("Downloaded: ${info.versionName} (code ${info.longVersionCode})")
        say("  signed by: ${signers(info)}")
        return true
    }

    private fun install(apk: File) {
        say("Installing…")
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(TARGET)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // Android reports back to this screen: first to ask you to confirm, then with the outcome.
            val result = Intent().setClassName(this, RESULT_ALIAS)
            val callback = PendingIntent.getActivity(this, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(callback.intentSender)
        }
    }

    @SuppressLint("UnsafeIntentLaunch") // only results through the unexported alias are acted on
    private fun onResult(intent: Intent?) {
        // Only trust results that came through the alias, which other apps can't start.
        if (intent?.component?.className != RESULT_ALIAS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm) else say("Android didn't ask to confirm (status $status).")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                say("Installed! You can open AudioCool now.")
                button.isEnabled = true
            }
            else -> {
                say("Android refused to install it.")
                say("  status: $status")
                say("  reason: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                say("  code: ${intent.getIntExtra(EXTRA_LEGACY_STATUS, 0)}")
                intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)?.let { say("  conflicts with: $it") }
                say("Please send a screenshot of this screen.")
                button.isEnabled = true
            }
        }
    }

    private fun signers(info: PackageInfo): String {
        val signing = info.signingInfo ?: return "unknown"
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certs.joinToString { sha256(it.toByteArray()).take(16) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TARGET = "com.kjwindham.audiocool"
        const val LATEST = "https://github.com/ZENinjaneer/audiocool/releases/latest/download/AudioCool.apk"
        const val RESULT_ALIAS = "com.kjwindham.audiocool.updater.InstallResult"

        // PackageInstaller's numeric failure code (INSTALL_FAILED_...), not in the public API.
        const val EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"
    }
}
