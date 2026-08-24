package com.ikegami.transformerime.model

import android.content.Context
import com.ikegami.transformerime.conversion.CandidateGenerator
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Dual-model Zenzai cascade.
 *
 * Primary:  zenz-v3.2-small Q5_K_M (~95.1M params)
 * Fallback: zenz-v3.1-small Q5_K_M (~95.1M params)
 *
 * v0.8.1 keeps the packaged neural capacity at ~190.2M parameters, but uses top-first-token
 * branching inside the primary model to expose multiple genuinely neural conversion choices.
 * The fallback model remains a conditional second opinion instead of running on every keypress.
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
        val fallback = materializeAsset(FALLBACK_ASSET)
        val p = ZenzaiNative.nativeLoadModel(0, primary.absolutePath)
        primaryReady = p > 80_000_000L
        val f = ZenzaiNative.nativeLoadModel(1, fallback.absolutePath)
        fallbackReady = f > 80_000_000L
        totalParameters = p.coerceAtLeast(0) + f.coerceAtLeast(0)
        return primaryReady
    }

    /**
     * Kana -> kanji conversion with three neural hypotheses.
     *
     * The primary Zenzai model branches from its top first-token choices, so the first three
     * surfaced candidates are different neural continuations rather than one generation plus
     * two labels copied from the classical dictionary. Mozc candidates remain immediately after
     * them as a safety net and as useful alternatives for uncommon names and terminology.
     */
    fun rerankConversion(
        leftContext: String,
        reading: String,
        drafts: List<String>
    ): RankResult {
        if (!primaryReady || reading.isBlank()) return RankResult(drafts, 0, false, "", 0f)
        val prompt = ZenzaiPrompt.conversion(leftContext, reading)
        val maxTokens = (reading.length * 2 + 8).coerceIn(8, 48)
        val primary = generateCandidates(0, prompt, maxTokens, CONVERSION_BRANCHES)
        val primaryTexts = primary.items
            .map { sanitizeConversion(it.text, reading.length) }
            .filter { it.isNotBlank() }
            .distinct()
        val primaryMargin = primary.items.firstOrNull()?.margin ?: 0f

        val needsSecondOpinion = fallbackReady &&
            (primaryTexts.size < AI_CONVERSION_COUNT || primaryMargin < WEAK_MARGIN)
        val fallback = if (needsSecondOpinion) {
            generateCandidates(1, prompt, maxTokens, FALLBACK_BRANCHES)
        } else null
        val fallbackTexts = fallback?.items.orEmpty()
            .map { sanitizeConversion(it.text, reading.length) }
            .filter { it.isNotBlank() }
            .distinct()

        val neural = (primaryTexts + fallbackTexts)
            .filter { it.isNotBlank() }
            .distinct()
            .take(AI_CONVERSION_COUNT)

        if (neural.isEmpty()) {
            return RankResult(
                candidates = drafts,
                latencyMs = primary.latencyMs + (fallback?.latencyMs ?: 0L),
                usedFallback = fallback != null,
                generated = "",
                confidenceMargin = primaryMargin
            )
        }

        // Keep all three AI alternatives visible first. Classical/Mozc candidates follow and
        // duplicates disappear, so a neural hypothesis that Mozc also knows does not waste space.
        val ranked = (neural + drafts)
            .filter { it.isNotBlank() }
            .distinct()

        return RankResult(
            candidates = ranked,
            latencyMs = primary.latencyMs + (fallback?.latencyMs ?: 0L),
            usedFallback = fallback != null,
            generated = neural.first(),
            confidenceMargin = primaryMargin
        )
    }

    /**
     * Post-commit prediction.
     *
     * Zenzai predicts several possible *next kana readings* from the committed context. Each
     * reading is independently expanded through the normal Mozc converter. We interleave the
     * best surface from each reading before taking second choices, which prevents one ambiguous
     * reading from monopolising the candidate row.
     */
    fun predictNext(leftContext: String, fallbackPool: List<String>): NextResult {
        if (!primaryReady || leftContext.isBlank()) return NextResult(fallbackPool, 0, false, "")
        val prompt = ZenzaiPrompt.inputPrediction(leftContext)
        val primary = generateCandidates(0, prompt, NEXT_MAX_TOKENS, NEXT_PRIMARY_BRANCHES)
        val primaryReadings = primary.items
            .map { sanitizePredictedReading(it.text) }
            .filter { it.length >= 1 }
            .distinct()
        val primaryMargin = primary.items.firstOrNull()?.margin ?: 0f

        val shouldFallback = fallbackReady &&
            (primaryReadings.size < MIN_NEXT_READINGS || primaryMargin < WEAK_MARGIN)
        val fallback = if (shouldFallback) {
            generateCandidates(1, prompt, NEXT_MAX_TOKENS, NEXT_FALLBACK_BRANCHES)
        } else null
        val fallbackReadings = fallback?.items.orEmpty()
            .map { sanitizePredictedReading(it.text) }
            .filter { it.length >= 1 }
            .distinct()

        val readings = (primaryReadings + fallbackReadings)
            .distinct()
            .take(MAX_NEXT_READINGS)

        val expanded = readings.map { reading ->
            CandidateGenerator.candidates(reading, limit = 6)
                .filter { it.isNotBlank() }
                .distinct()
        }

        val neuralCandidates = LinkedHashSet<String>()
        // Diversity pass: first surface from each predicted reading.
        expanded.forEach { list -> list.firstOrNull()?.let(neuralCandidates::add) }
        // Then add alternative conversions without allowing one reading to dominate early slots.
        for (rank in 1 until 4) {
            expanded.forEach { list -> list.getOrNull(rank)?.let(neuralCandidates::add) }
        }

        val merged = (neuralCandidates + fallbackPool)
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)

        return NextResult(
            candidates = merged,
            latencyMs = primary.latencyMs + (fallback?.latencyMs ?: 0L),
            usedFallback = fallback != null,
            predictedReading = readings.firstOrNull().orEmpty()
        )
    }

    fun close() {
        if (ZenzaiNative.isLoaded) ZenzaiNative.nativeFree()
        primaryReady = false
        fallbackReady = false
    }

    private fun generate(index: Int, prompt: String, maxTokens: Int): Generation {
        val raw = ZenzaiNative.nativeGenerate(index, prompt, maxTokens)
        return Generation(
            text = raw.getOrNull(0).orEmpty(),
            latencyMs = raw.getOrNull(1)?.toLongOrNull() ?: 0L,
            margin = raw.getOrNull(2)?.toFloatOrNull() ?: 0f,
            modelIndex = index
        )
    }

    private fun generateCandidates(
        index: Int,
        prompt: String,
        maxTokens: Int,
        branches: Int
    ): GenerationBatch {
        val raw = ZenzaiNative.nativeGenerateCandidates(index, prompt, maxTokens, branches)
        if (raw.isEmpty()) return GenerationBatch(emptyList(), 0L)
        val latency = raw[0].toLongOrNull() ?: 0L
        val items = buildList {
            var pos = 1
            while (pos + 1 < raw.size) {
                val text = raw[pos]
                val margin = raw[pos + 1].toFloatOrNull() ?: 0f
                if (text.isNotBlank()) {
                    add(
                        Generation(
                            text = text,
                            latencyMs = latency,
                            margin = margin,
                            modelIndex = index
                        )
                    )
                }
                pos += 2
            }
        }
        return GenerationBatch(items, latency)
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
        const val FALLBACK_ASSET = "zenz-v3.1-small-Q5_K_M.gguf"
        private const val STRONG_MARGIN = 1.35f
        private const val WEAK_MARGIN = 0.65f
        private const val AI_CONVERSION_COUNT = 3
        private const val CONVERSION_BRANCHES = 3
        private const val FALLBACK_BRANCHES = 2
        private const val NEXT_PRIMARY_BRANCHES = 4
        private const val NEXT_FALLBACK_BRANCHES = 3
        private const val MIN_NEXT_READINGS = 3
        private const val MAX_NEXT_READINGS = 5
        private const val NEXT_MAX_TOKENS = 18

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
            }.trim().take(24)
            return clipped.map { ch ->
                if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch
            }.joinToString("")
                .filter { it in 'ぁ'..'ゖ' || it == 'ー' }
        }

        /**
         * Retained for unit tests and backwards-compatible speculative ranking behaviour.
         * v0.8.1's live conversion path uses three explicit neural hypotheses first.
         */
        internal fun mergeSpeculativeDrafts(
            drafts: List<String>,
            primary: String,
            fallback: String,
            primaryMargin: Float
        ): List<String> {
            if (primary.isBlank()) return drafts.distinct()
            val base = drafts.distinct()
            if (primary == base.firstOrNull() || primary in base) {
                return (listOf(primary) + base).distinct()
            }
            if (fallback.isNotBlank() && fallback == primary) {
                return (listOf(primary) + base).distinct()
            }
            if (fallback.isNotBlank() && fallback in base) {
                return (listOf(fallback, primary) + base).distinct()
            }

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
    private const val CONTEXT_CHARS = 96

    fun conversion(leftContext: String, reading: String): String {
        val context = leftContext.takeLast(CONTEXT_CHARS)
        val kata = reading.map { ch ->
            if (ch in 'ぁ'..'ゖ') (ch.code + 0x60).toChar() else ch
        }.joinToString("")
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
    external fun nativeParameterCount(index: Int): Long
    external fun nativeFree()
}
