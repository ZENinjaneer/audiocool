package com.kjwindham.audiocool.summarize

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig

/** Answers a prompt with a language model; [GemmaSummarizer] on the phone, a stand-in in tests. */
interface Summarizer : AutoCloseable {
    /** Where it runs, for showing: "GPU", or "CPU (4 threads)". */
    val where: String

    /** Reading and writing speed of the last reply, tokens per second, when known. */
    val lastSpeed: Pair<Double, Double>?

    /** The reply to [prompt], at most [maxTokens] long. Blocks: call it on a worker thread. */
    fun reply(prompt: String, maxTokens: Int): String

    /** What each of [texts] means, as the meaning model ([modelPath]) puts it: vectors of length 1. Blocks. */
    fun embed(modelPath: String, texts: List<String>): List<FloatArray> = throw UnsupportedOperationException("No meaning model here")
}

/** Gemma 4 E2B, through LiteRT-LM. */
class GemmaSummarizer private constructor(private val engine: Engine, override val where: String) : Summarizer {
    override var lastSpeed: Pair<Double, Double>? = null
        private set

    @OptIn(ExperimentalApi::class)
    override fun reply(prompt: String, maxTokens: Int): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(SummaryPrompts.SYSTEM),
            // Low temperature: a summary should say what's there, the same way each time.
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3, seed = 1),
            maxOutputToken = maxTokens,
        )
        return engine.createConversation(config).use { conversation ->
            val message = conversation.sendMessage(prompt)
            runCatching { conversation.getBenchmarkInfo() }.getOrNull()?.let {
                lastSpeed = it.lastPrefillTokensPerSecond to it.lastDecodeTokensPerSecond
            }
            message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    override fun close() = engine.close()

    companion object {
        private const val TAG = "GemmaSummarizer"

        /**
         * Opens the model on the GPU when [gpu] and it works there (some phones' GPU drivers garble
         * it, so it's checked with a one-word question), else on the CPU with [threads] threads.
         */
        @OptIn(ExperimentalApi::class)
        fun open(context: Context, gpu: Boolean, threads: Int, speculative: Boolean): GemmaSummarizer {
            ExperimentalFlags.enableBenchmark = true
            val model = SummaryModel.file(context).path
            // The engine's cache of the model (about 0.8 GB) makes it start twice as fast; skip it when space is short.
            val cache = context.cacheDir.takeIf { it.usableSpace > 3_000_000_000L }?.path
            if (gpu) {
                try {
                    val summarizer = GemmaSummarizer(start(model, Backend.GPU(), cache, speculative), "GPU")
                    if (summarizer.reply("Reply with just the word: ready", 8).contains("ready", ignoreCase = true)) return summarizer
                    Log.w(TAG, "The GPU gave a wrong answer; using the CPU")
                    summarizer.close()
                } catch (e: Exception) {
                    Log.w(TAG, "The GPU couldn't run the model; using the CPU", e)
                }
            }
            return GemmaSummarizer(start(model, Backend.CPU(threadCount = threads), cache, speculative), "CPU ($threads threads)")
        }

        @OptIn(ExperimentalApi::class)
        private fun start(model: String, backend: Backend, cache: String?, speculative: Boolean): Engine {
            // Speculative decoding writes summaries faster; if this build or phone can't do it, go without.
            for (spec in if (speculative) listOf(true, false) else listOf(false)) {
                ExperimentalFlags.enableSpeculativeDecoding = spec
                val engine = Engine(EngineConfig(modelPath = model, backend = backend, maxNumTokens = 4096, cacheDir = cache))
                try {
                    engine.initialize()
                    return engine
                } catch (e: Exception) {
                    engine.close()
                    if (!spec) throw e
                    Log.w(TAG, "Couldn't start with speculative decoding", e)
                }
            }
            error("unreachable")
        }
    }
}
