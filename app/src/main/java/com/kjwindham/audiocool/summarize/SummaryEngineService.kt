package com.kjwindham.audiocool.summarize

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the summary model in a process of its own (":summaries"). The engine is native code, and on
 * a phone it hasn't been tried on it may crash; this way only this process goes, and the app carries
 * on and can try again more carefully. [RemoteSummarizer] talks to it through a plain Binder.
 */
class SummaryEngineService : Service() {
    private var engine: GemmaSummarizer? = null
    private var config: Triple<Boolean, Int, Boolean>? = null

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != REPLY) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val wanted = Triple(data.readInt() == 1, data.readInt(), data.readInt() == 1)
            val prompt = data.readString().orEmpty()
            val maxTokens = data.readInt()
            try {
                val (text, model) = synchronized(this@SummaryEngineService) {
                    val model = engine?.takeIf { config == wanted } ?: run {
                        engine?.close()
                        GemmaSummarizer.open(this@SummaryEngineService, gpu = wanted.first, threads = wanted.second, speculative = wanted.third).also {
                            engine = it
                            config = wanted
                        }
                    }
                    model.reply(prompt, maxTokens) to model
                }
                reply?.writeNoException()
                reply?.writeString(text)
                reply?.writeString(model.where)
                reply?.writeDouble(model.lastSpeed?.first ?: -1.0)
                reply?.writeDouble(model.lastSpeed?.second ?: -1.0)
            } catch (e: Exception) {
                reply?.writeException(IllegalStateException(e.message ?: e.javaClass.simpleName))
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    // The app's done with it: give the model's memory back at once, rather than when Android gets round to it.
    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(this) {
            engine?.close()
            engine = null
        }
        stopSelf()
        Process.killProcess(Process.myPid())
        return false
    }

    companion object {
        const val DESCRIPTOR = "com.kjwindham.audiocool.summarize.SummaryEngine"
        const val REPLY = IBinder.FIRST_CALL_TRANSACTION
    }
}

/** The model in [SummaryEngineService]'s process. A crash there shows up here as a DeadObjectException. */
class RemoteSummarizer private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val binder: IBinder,
    private val gpu: Boolean,
    private val threads: Int,
    private val speculative: Boolean,
) : Summarizer {
    override var where: String = if (gpu) "GPU" else "CPU ($threads threads)"
        private set
    override var lastSpeed: Pair<Double, Double>? = null
        private set

    override fun reply(prompt: String, maxTokens: Int): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SummaryEngineService.DESCRIPTOR)
            data.writeInt(if (gpu) 1 else 0)
            data.writeInt(threads)
            data.writeInt(if (speculative) 1 else 0)
            data.writeString(prompt)
            data.writeInt(maxTokens)
            binder.transact(SummaryEngineService.REPLY, data, reply, 0)
            reply.readException()
            val text = reply.readString().orEmpty()
            where = reply.readString() ?: where
            val read = reply.readDouble()
            val write = reply.readDouble()
            if (read >= 0) lastSpeed = read to write
            return text
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun close() {
        runCatching { context.unbindService(connection) }
    }

    companion object {
        /** Starts the model's process and connects to it. Blocks: call it on a worker thread. */
        fun open(context: Context, gpu: Boolean, threads: Int, speculative: Boolean): RemoteSummarizer {
            val connected = CountDownLatch(1)
            var bound: IBinder? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    bound = service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            if (!context.bindService(Intent(context, SummaryEngineService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                error("Couldn't start the summary model's process")
            }
            if (!connected.await(30, TimeUnit.SECONDS) || bound == null) {
                runCatching { context.unbindService(connection) }
                error("The summary model's process didn't start")
            }
            return RemoteSummarizer(context, connection, bound!!, gpu, threads, speculative)
        }
    }
}
