package com.ikegami.transformerime.model

import android.content.Context

/**
 * Compatibility adapter kept so the existing IME pipeline can switch from the old custom
 * JP21M reranker to the Zenzai-based ~190M cascade without blocking the UI.
 *
 * A non-empty reading means kana->kanji conversion. An empty reading is the existing
 * post-commit next-candidate path. Both are now handled by Zenzai190MEngine.
 */
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
        // Retained for settings/debug UI compatibility. Zenz itself is a GPT-2-family dense LM,
        // so these constants no longer describe the runtime architecture.
        const val DIM = 0
        const val HEADS = 0
        const val FF_DIM = 0
        const val LAYERS = 0
        const val EXPERTS = 0
        const val VOCAB_BUCKETS = 0
        const val CONTEXT_LENGTH = 256
        private const val EXPECTED_TOTAL = 180_000_000L

        fun load(context: Context): MediumMoETransformer {
            val engine = Zenzai190MEngine(context)
            val ready = runCatching { engine.initialize() }.getOrDefault(false)
            val params = engine.totalParameters
            return if (ready && params >= EXPECTED_TOTAL) {
                MediumMoETransformer(
                    engine = engine,
                    corpusTrained = true,
                    sourceLabel = "Zenzai zenz-v3.2 + v3.1 · Q5_K_M · llama.cpp",
                    parameterCount = params.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            } else {
                runCatching { engine.close() }
                create()
            }
        }

        /** Safe identity fallback if native/model initialization is unavailable. */
        fun create(): MediumMoETransformer = MediumMoETransformer(
            engine = null,
            corpusTrained = false,
            sourceLabel = "classical/Tiny fallback",
            parameterCount = 0
        )
    }
}
