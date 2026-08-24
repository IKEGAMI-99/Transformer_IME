package com.ikegami.transformerime.model

import android.content.Context

/** Compatibility adapter around the single process-wide Zenzai runtime. */
class MediumMoETransformer private constructor(
    private val engine: Zenzai190MEngine?,
    val corpusTrained: Boolean,
    val sourceLabel: String,
    val parameterCount: Int
) {
    data class RankResult(
        val candidates: List<String>,
        val latencyMs: Long
    )

    @Volatile
    var lastInferenceMs: Long = 0
        private set

    /**
     * The native model is shared process-wide, while Android may briefly overlap old and new
     * InputMethodService instances during recreation. Serialize every llama.cpp call here so two
     * service-local executors can never enter the same native context concurrently.
     */
    fun rerank(contextText: String, reading: String, candidates: List<String>): RankResult {
        if (candidates.isEmpty()) return RankResult(candidates, 0)
        val active = engine ?: return RankResult(candidates, 0)
        return synchronized(nativeInferenceLock) {
            if (reading.isNotEmpty()) {
                val result = active.rerankConversion(contextText, reading, candidates)
                lastInferenceMs = result.latencyMs
                RankResult(result.candidates, result.latencyMs)
            } else {
                val result = active.predictNext(contextText, candidates)
                lastInferenceMs = result.latencyMs
                RankResult(result.candidates, result.latencyMs)
            }
        }
    }

    /**
     * The active Zenzai instance is process-scoped. An InputMethodService must not free it because
     * another service instance or an already-running native call may still own it. Android reclaims
     * the process and native memory together.
     */
    fun close() = Unit

    companion object {
        const val DIM = 0
        const val HEADS = 0
        const val FF_DIM = 0
        const val LAYERS = 0
        const val EXPERTS = 0
        const val VOCAB_BUCKETS = 0
        const val CONTEXT_LENGTH = 256
        private const val EXPECTED_TOTAL = 80_000_000L

        @Volatile private var sharedInstance: MediumMoETransformer? = null
        private val nativeInferenceLock = Any()

        @Synchronized
        fun load(context: Context): MediumMoETransformer {
            // A healthy native instance is permanent for the process. A fallback instance is not:
            // initialization may have failed transiently while assets were being materialized.
            sharedInstance?.takeIf { it.corpusTrained }?.let { return it }

            val engine = Zenzai190MEngine(context.applicationContext)
            val ready = runCatching { engine.initialize() }.getOrDefault(false)
            val params = engine.totalParameters
            val result = if (ready && params >= EXPECTED_TOTAL) {
                MediumMoETransformer(
                    engine = engine,
                    corpusTrained = true,
                    sourceLabel = "Zenzai zenz-v3.2 · 95.1M · Q5_K_M · 10-way decoding",
                    parameterCount = params.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            } else {
                runCatching { engine.close() }
                create()
            }
            sharedInstance = result
            return result
        }

        fun create(): MediumMoETransformer = MediumMoETransformer(
            engine = null,
            corpusTrained = false,
            sourceLabel = "classical/Tiny fallback",
            parameterCount = 0
        )
    }
}
