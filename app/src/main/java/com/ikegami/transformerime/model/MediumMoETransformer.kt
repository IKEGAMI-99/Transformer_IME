package com.ikegami.transformerime.model

import android.content.Context

/** Compatibility adapter around the v0.9 single-model Zenzai runtime. */
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

    fun rerank(contextText: String, reading: String, candidates: List<String>): RankResult {
        if (candidates.isEmpty()) return RankResult(candidates, 0)
        val active = engine ?: return RankResult(candidates, 0)
        return if (reading.isNotEmpty()) {
            val result = active.rerankConversion(contextText, reading, candidates)
            lastInferenceMs = result.latencyMs
            RankResult(result.candidates, result.latencyMs)
        } else {
            val result = active.predictNext(contextText, candidates)
            lastInferenceMs = result.latencyMs
            RankResult(result.candidates, result.latencyMs)
        }
    }

    fun close() {
        engine?.close()
    }

    companion object {
        const val DIM = 0
        const val HEADS = 0
        const val FF_DIM = 0
        const val LAYERS = 0
        const val EXPERTS = 0
        const val VOCAB_BUCKETS = 0
        const val CONTEXT_LENGTH = 256
        private const val EXPECTED_TOTAL = 80_000_000L

        fun load(context: Context): MediumMoETransformer {
            val engine = Zenzai190MEngine(context)
            val ready = runCatching { engine.initialize() }.getOrDefault(false)
            val params = engine.totalParameters
            return if (ready && params >= EXPECTED_TOTAL) {
                MediumMoETransformer(
                    engine = engine,
                    corpusTrained = true,
                    sourceLabel = "Zenzai zenz-v3.2 · 95.1M · Q5_K_M · 10/12-way decoding",
                    parameterCount = params.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            } else {
                runCatching { engine.close() }
                create()
            }
        }

        fun create(): MediumMoETransformer = MediumMoETransformer(
            engine = null,
            corpusTrained = false,
            sourceLabel = "classical/Tiny fallback",
            parameterCount = 0
        )
    }
}
