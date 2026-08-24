package com.ikegami.transformerime.model

import android.content.Context
import com.ikegami.transformerime.conversion.CandidateGenerator
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ln
import kotlin.math.max

/**
 * Zenzai v3.2 runtime.
 *
 * v0.11 keeps the ten-way neural expansion, but no longer trusts a free-generated string as the
 * winner by default. Mozc / Personal-RAG drafts and neural expansions are pooled, then the native
 * model scores each surface as P(surface | left context, reading). The final order fuses that
 * conditional likelihood with the already-personalized draft order.
 */
class Zenzai190MEngine(private val context: Context) {
    data class Generation(
        val text: String,
        val latencyMs: Long,
        val margin: Float,
        val modelIndex: Int
    )

    private data class GenerationBatch(
        val items: List<Generation>,
        val latencyMs: Long
    )

    private data class ScoreBatch(
        val scores: List<Float>,
        val latencyMs: Long
    )

    data class RankResult(
        val candidates: List<String>,
        val latencyMs: Long,
        val usedFallback: Boolean,
        val generated: String,
        val confidenceMargin: Float
    )

    data class NextResult(
        val candidates: List<String>,
        val latencyMs: Long,
        val usedFallback: Boolean,
        val predictedReading: String
    )

    @Volatile var primaryReady: Boolean = false
        private set
    @Volatile var fallbackReady: Boolean = false
        private set
    @Volatile var totalParameters: Long = 0L
        private set

    fun initialize(): Boolean {
        if (!ZenzaiNative.ensureLoaded()) return false
        val primary = materializeAsset(PRIMARY_ASSET)
        val p = ZenzaiNative.nativeLoadModel(0, primary.absolutePath)
        primaryReady = p > 80_000_000L
        fallbackReady = false
        totalParameters = p.coerceAtLeast(0)
        return primaryReady
    }

    /**
     * Ten-way expansion supplies diverse neural hypotheses, but a constrained scorer decides the
     * final winner from a pool dominated by Mozc/RAG drafts. This greatly reduces hallucinated
     * first candidates while preserving the model's ability to surface a novel spelling.
     */
    fun rerankConversion(
        leftContext: String,
        reading: String,
        drafts: List<String>
    ): RankResult {
        if (!primaryReady || reading.isBlank()) return RankResult(drafts, 0, false, "", 0f)

        val cleanDrafts = drafts.filter { it.isNotBlank() }.distinct()
        if (cleanDrafts.isEmpty()) return RankResult(listOf(reading), 0, false, "", 0f)

        val prompt = ZenzaiPrompt.conversion(leftContext, reading)
        val maxTokens = (reading.length * 2 + 8).coerceIn(8, 48)
        val expansion = generateCandidates(0, prompt, maxTokens, INFERENCE_TRIALS)
        val neural = expansion.items
            .map { sanitizeConversion(it.text, reading.length) }
            .filter { it.isNotBlank() }
            .distinct()

        val pool = buildList {
            cleanDrafts.take(MAX_DRAFTS_FOR_SCORING).forEach(::add)
            neural.take(MAX_NOVEL_NEURAL).forEach { if (it !in this) add(it) }
        }.take(MAX_SCORED_CANDIDATES)

        val scored = scoreCandidates(0, prompt, pool)
        val ranked = if (scored.scores.size == pool.size && scored.scores.any { it.isFinite() }) {
            fuseConditionalScores(cleanDrafts, neural, pool, scored.scores)
        } else {
            constrainedFallback(cleanDrafts, neural, expansion.items.firstOrNull()?.margin ?: 0f)
        }

        val topScores = ranked.take(2).mapNotNull { candidate ->
            val index = pool.indexOf(candidate)
            scored.scores.getOrNull(index)?.takeIf { it.isFinite() }
        }
        val confidence = if (topScores.size >= 2) topScores[0] - topScores[1]
        else expansion.items.firstOrNull()?.margin ?: 0f

        return RankResult(
            candidates = (ranked + cleanDrafts + neural).filter { it.isNotBlank() }.distinct(),
            latencyMs = expansion.latencyMs + scored.latencyMs,
            usedFallback = scored.scores.isEmpty(),
            generated = neural.firstOrNull().orEmpty(),
            confidenceMargin = confidence
        )
    }

    /**
     * Next-input prediction still uses ten independent first-token branches because its neural
     * outputs are readings rather than surface candidates. Each reading is expanded through Mozc.
     */
    fun predictNext(leftContext: String, fallbackPool: List<String>): NextResult {
        if (!primaryReady || leftContext.isBlank()) return NextResult(fallbackPool, 0, false, "")
        val prompt = ZenzaiPrompt.inputPrediction(leftContext)
        val primary = generateCandidates(0, prompt, NEXT_MAX_TOKENS, INFERENCE_TRIALS)
        val readings = primary.items
            .map { sanitizePredictedReading(it.text) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(INFERENCE_TRIALS)

        val expanded = readings.map { reading ->
            CandidateGenerator.candidates(reading, limit = 8)
                .filter { it.isNotBlank() }
                .distinct()
        }

        val neuralCandidates = LinkedHashSet<String>()
        for (rank in 0 until 5) {
            expanded.forEach { list -> list.getOrNull(rank)?.let(neuralCandidates::add) }
        }
        val merged = (neuralCandidates + fallbackPool)
            .filter { it.isNotBlank() }
            .distinct()
            .take(16)

        return NextResult(
            candidates = merged,
            latencyMs = primary.latencyMs,
            usedFallback = false,
            predictedReading = readings.firstOrNull().orEmpty()
        )
    }

    fun close() {
        if (ZenzaiNative.isLoaded) ZenzaiNative.nativeFree()
        primaryReady = false
        fallbackReady = false
    }

    private fun generateCandidates(index: Int, prompt: String, maxTokens: Int, branches: Int): GenerationBatch {
        val raw = ZenzaiNative.nativeGenerateCandidates(index, prompt, maxTokens, branches)
        if (raw.isEmpty()) return GenerationBatch(emptyList(), 0L)
        val latency = raw[0].toLongOrNull() ?: 0L
        val items = buildList {
            var pos = 1
            while (pos + 1 < raw.size) {
                val text = raw[pos]
                val margin = raw[pos + 1].toFloatOrNull() ?: 0f
                if (text.isNotBlank()) add(Generation(text, latency, margin, index))
                pos += 2
            }
        }
        return GenerationBatch(items, latency)
    }

    private fun scoreCandidates(index: Int, prompt: String, candidates: List<String>): ScoreBatch {
        if (candidates.isEmpty()) return ScoreBatch(emptyList(), 0L)
        val raw = ZenzaiNative.nativeScoreCandidates(
            index,
            prompt,
            candidates.toTypedArray(),
            MAX_SCORED_CANDIDATES
        )
        if (raw.isEmpty()) return ScoreBatch(emptyList(), 0L)
        val latency = raw[0].toLongOrNull() ?: 0L
        val scores = raw.drop(1).take(candidates.size).map { it.toFloatOrNull() ?: Float.NEGATIVE_INFINITY }
        return ScoreBatch(scores, latency)
    }

    private fun materializeAsset(name: String): File {
        val dir = File(context.filesDir, "zenzai-models").apply { mkdirs() }
        val target = File(dir, name)
        if (target.exists() && target.length() > 60_000_000L) return target
        val tmp = File(dir, "$name.tmp")
        context.assets.open(name).use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Could not materialize $name" }
        return target
    }

    companion object {
        const val PRIMARY_ASSET = "zenz-v3.2-small-Q5_K_M.gguf"
        const val INFERENCE_TRIALS = 10
        const val MAX_SCORED_CANDIDATES = 20
        private const val MAX_DRAFTS_FOR_SCORING = 16
        private const val MAX_NOVEL_NEURAL = 4
        private const val STRONG_MARGIN = 1.80f
        private const val NEXT_MAX_TOKENS = 20

        internal fun sanitizeConversion(raw: String, readingLength: Int): String {
            val clean = raw
                .takeWhile { it !in '\uE000'..'\uF8FF' && it != '\n' && it != '\r' }
                .trim()
            val maxLength = max(8, readingLength * 3 + 8)
            return if (clean.length in 1..maxLength) clean else ""
        }

        internal fun sanitizePredictedReading(raw: String): String {
            val clipped = raw.takeWhile {
                it !in '\uE000'..'\uF8FF' && it !in setOf('、', '。', '！', '？', '\n', '\r')
            }.trim().take(28)
            return clipped.map { ch ->
                if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch
            }.joinToString("")
                .filter { it in 'ぁ'..'ゖ' || it == 'ー' }
        }

        /**
         * Fuses native conditional log-likelihood with the already-personalized Mozc/RAG order.
         * The prior is deliberately small: it breaks close neural ties but cannot rescue a clearly
         * implausible dictionary candidate. Novel neural strings pay a penalty unless the language
         * model scores them materially better than a constrained draft.
         */
        internal fun fuseConditionalScores(
            drafts: List<String>,
            neural: List<String>,
            pool: List<String>,
            conditionalScores: List<Float>
        ): List<String> {
            if (pool.isEmpty()) return drafts.distinct()
            val draftIndex = drafts.withIndex().associate { it.value to it.index }
            val neuralSet = neural.toHashSet()
            val anyFinite = conditionalScores.any { it.isFinite() }
            if (!anyFinite) return (drafts + neural).filter { it.isNotBlank() }.distinct()

            return pool.withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<String>> { indexed ->
                        val candidate = indexed.value
                        val neuralScore = conditionalScores.getOrNull(indexed.index)
                            ?.takeIf { it.isFinite() } ?: -1_000f
                        val pos = draftIndex[candidate]
                        val prior = if (pos != null) {
                            (-0.10 * ln(1.0 + pos.toDouble())).toFloat()
                        } else {
                            -0.34f
                        }
                        val expansionBonus = if (candidate in neuralSet) 0.06f else 0f
                        neuralScore + prior + expansionBonus
                    }.thenBy { indexed -> draftIndex[indexed.value] ?: Int.MAX_VALUE }
                )
                .map { it.value }
                .filter { it.isNotBlank() }
                .distinct()
        }

        private fun constrainedFallback(drafts: List<String>, neural: List<String>, margin: Float): List<String> {
            val primary = neural.firstOrNull()
            if (primary.isNullOrBlank()) return drafts.distinct()
            if (primary in drafts) return (listOf(primary) + drafts + neural).distinct()
            return if (margin >= STRONG_MARGIN) {
                (listOf(primary) + drafts + neural).filter { it.isNotBlank() }.distinct()
            } else {
                (drafts + neural).filter { it.isNotBlank() }.distinct()
            }
        }

        /** Retained for regression tests of the older speculative helper. */
        internal fun mergeSpeculativeDrafts(
            drafts: List<String>,
            primary: String,
            fallback: String,
            primaryMargin: Float
        ): List<String> {
            if (primary.isBlank()) return drafts.distinct()
            val base = drafts.distinct()
            if (primary == base.firstOrNull() || primary in base) return (listOf(primary) + base).distinct()
            if (fallback.isNotBlank() && fallback == primary) return (listOf(primary) + base).distinct()
            if (fallback.isNotBlank() && fallback in base) return (listOf(fallback, primary) + base).distinct()
            val constrainedDraft = base.maxByOrNull { commonPrefixLength(it, primary) }
            val prefixLength = constrainedDraft?.let { commonPrefixLength(it, primary) } ?: 0
            return when {
                primaryMargin >= STRONG_MARGIN -> (listOf(primary) + base + fallback).filter { it.isNotBlank() }.distinct()
                prefixLength >= 2 -> (listOf(constrainedDraft!!, primary) + base + fallback).filter { it.isNotBlank() }.distinct()
                else -> (base.take(1) + primary + fallback + base.drop(1)).filter { it.isNotBlank() }.distinct()
            }
        }

        private fun commonPrefixLength(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }
    }
}

internal object ZenzaiPrompt {
    private const val INPUT_TAG = '\uEE00'
    private const val OUTPUT_TAG = '\uEE01'
    private const val CONTEXT_TAG = '\uEE02'
    private const val CONTEXT_CHARS = 128

    fun conversion(leftContext: String, reading: String): String {
        val context = leftContext.takeLast(CONTEXT_CHARS)
        val kata = reading.map { ch -> if (ch in 'ぁ'..'ゖ') (ch.code + 0x60).toChar() else ch }.joinToString("")
        return buildString {
            if (context.isNotEmpty()) append(CONTEXT_TAG).append(context)
            append(INPUT_TAG).append(kata).append(OUTPUT_TAG)
        }
    }

    fun inputPrediction(leftContext: String): String = buildString {
        val context = leftContext.takeLast(CONTEXT_CHARS)
        if (context.isNotEmpty()) append(CONTEXT_TAG).append(context)
        append(INPUT_TAG)
    }
}

internal object ZenzaiNative {
    @Volatile var isLoaded: Boolean = false
        private set

    fun ensureLoaded(): Boolean {
        if (isLoaded) return true
        return synchronized(this) {
            if (isLoaded) return@synchronized true
            runCatching {
                System.loadLibrary("zenzjni")
                nativeInit()
                isLoaded = true
            }.isSuccess
        }
    }

    external fun nativeInit()
    external fun nativeLoadModel(index: Int, path: String): Long
    external fun nativeGenerate(index: Int, prompt: String, maxTokens: Int): Array<String>
    external fun nativeGenerateCandidates(index: Int, prompt: String, maxTokens: Int, branches: Int): Array<String>
    external fun nativeScoreCandidates(index: Int, prompt: String, candidates: Array<String>, limit: Int): Array<String>
    external fun nativeParameterCount(index: Int): Long
    external fun nativeFree()
}
