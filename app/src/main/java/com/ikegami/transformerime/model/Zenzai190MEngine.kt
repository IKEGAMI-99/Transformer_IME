package com.ikegami.transformerime.model

import android.content.Context
import com.ikegami.transformerime.conversion.CandidateGenerator
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * v0.9 single-model Zenzai runtime.
 *
 * We keep only zenz-v3.2-small (~95.1M) and spend the saved memory/package size on more decoding
 * branches. One strong model thinking more times was more useful here than two near-identical models.
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
        val p = ZenzaiNative.nativeLoadModel(0, primary.absolutePath)
        primaryReady = p > 80_000_000L
        fallbackReady = false
        totalParameters = p.coerceAtLeast(0)
        return primaryReady
    }

    /**
     * Produces three visible AI-assisted choices while placing the raw reading immediately after AI1.
     * Result order is therefore: AI1 / raw reading / AI2 / AI3 / normal Mozc alternatives.
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
        val neural = primary.items
            .map { sanitizeConversion(it.text, reading.length) }
            .filter { it.isNotBlank() }
            .distinct()
        val margin = primary.items.firstOrNull()?.margin ?: 0f

        val aiSlots = fillAiSlots(neural, drafts, AI_CONVERSION_COUNT)
        if (aiSlots.isEmpty()) {
            val fallback = (listOf(reading) + drafts).filter { it.isNotBlank() }.distinct()
            return RankResult(fallback, primary.latencyMs, false, "", margin)
        }

        val visible = buildList {
            add(aiSlots[0])
            add(reading)
            aiSlots.drop(1).forEach(::add)
            drafts.forEach(::add)
            neural.forEach(::add)
        }.filter { it.isNotBlank() }.distinct()

        return RankResult(
            candidates = visible,
            latencyMs = primary.latencyMs,
            usedFallback = false,
            generated = neural.firstOrNull().orEmpty(),
            confidenceMargin = margin
        )
    }

    /**
     * Predict many possible next readings from the same model, expand each through Mozc, then
     * interleave surfaces so one ambiguous reading does not monopolize the row.
     */
    fun predictNext(leftContext: String, fallbackPool: List<String>): NextResult {
        if (!primaryReady || leftContext.isBlank()) return NextResult(fallbackPool, 0, false, "")
        val prompt = ZenzaiPrompt.inputPrediction(leftContext)
        val primary = generateCandidates(0, prompt, NEXT_MAX_TOKENS, NEXT_BRANCHES)
        val readings = primary.items
            .map { sanitizePredictedReading(it.text) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_NEXT_READINGS)

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
        private const val STRONG_MARGIN = 1.35f
        private const val AI_CONVERSION_COUNT = 3
        private const val CONVERSION_BRANCHES = 10
        private const val NEXT_BRANCHES = 12
        private const val MAX_NEXT_READINGS = 10
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

        internal fun fillAiSlots(
            neural: List<String>,
            drafts: List<String>,
            count: Int = AI_CONVERSION_COUNT
        ): List<String> {
            if (count <= 0) return emptyList()
            val result = LinkedHashSet<String>()
            neural.filter { it.isNotBlank() }.forEach { if (result.size < count) result += it }
            if (result.size >= count) return result.take(count)

            val reference = neural.firstOrNull().orEmpty()
            val indexedDrafts = drafts
                .filter { it.isNotBlank() && it !in result }
                .distinct()
                .mapIndexed { index, draft -> index to draft }
            val ordered = if (reference.isBlank()) indexedDrafts else indexedDrafts.sortedWith(
                compareByDescending<Pair<Int, String>> { commonPrefixLength(it.second, reference) }
                    .thenBy { it.first }
            )
            ordered.forEach { (_, draft) -> if (result.size < count) result += draft }
            return result.take(count)
        }

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
    external fun nativeParameterCount(index: Int): Long
    external fun nativeFree()
}
