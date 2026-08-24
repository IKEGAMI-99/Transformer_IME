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
 * Total packaged neural capacity is ~190.2M parameters, but the fallback model is only
 * invoked when the primary result disagrees with the classical draft or is low-confidence.
 */
class Zenzai190MEngine(private val context: Context) {
    data class Generation(
        val text: String,
        val latencyMs: Long,
        val margin: Float,
        val modelIndex: Int
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

    fun rerankConversion(
        leftContext: String,
        reading: String,
        drafts: List<String>
    ): RankResult {
        if (!primaryReady || reading.isBlank()) return RankResult(drafts, 0, false, "", 0f)
        val prompt = ZenzaiPrompt.conversion(leftContext, reading)
        val maxTokens = (reading.length * 2 + 8).coerceIn(8, 48)
        val primary = generate(0, prompt, maxTokens)
        val primaryText = sanitizeConversion(primary.text, reading.length)
        if (primaryText.isBlank()) return RankResult(drafts, primary.latencyMs, false, "", primary.margin)

        val firstDraft = drafts.firstOrNull().orEmpty()
        val primaryKnown = primaryText in drafts
        val needsSecondOpinion = fallbackReady &&
            primaryText != firstDraft &&
            !primaryKnown &&
            primary.margin < STRONG_MARGIN

        val fallback = if (needsSecondOpinion) generate(1, prompt, maxTokens) else null
        val fallbackText = fallback?.let { sanitizeConversion(it.text, reading.length) }.orEmpty()
        val latency = primary.latencyMs + (fallback?.latencyMs ?: 0L)

        val ranked = mergeSpeculativeDrafts(
            drafts = drafts,
            primary = primaryText,
            fallback = fallbackText,
            primaryMargin = primary.margin
        )
        return RankResult(
            candidates = ranked,
            latencyMs = latency,
            usedFallback = fallback != null,
            generated = ranked.firstOrNull().orEmpty(),
            confidenceMargin = primary.margin
        )
    }

    /**
     * Zenzai v3 input-prediction prompt: context tag + committed text + input tag.
     * The neural model predicts the *next kana input*. We then run that reading through the
     * normal Mozc-backed converter, keeping conversion validity separate from language modeling.
     */
    fun predictNext(leftContext: String, fallbackPool: List<String>): NextResult {
        if (!primaryReady || leftContext.isBlank()) return NextResult(fallbackPool, 0, false, "")
        val prompt = ZenzaiPrompt.inputPrediction(leftContext)
        val primary = generate(0, prompt, 12)
        val primaryReading = sanitizePredictedReading(primary.text)

        val shouldFallback = fallbackReady && (primaryReading.length < 2 || primary.margin < WEAK_MARGIN)
        val fallback = if (shouldFallback) generate(1, prompt, 12) else null
        val fallbackReading = fallback?.let { sanitizePredictedReading(it.text) }.orEmpty()

        val readings = linkedSetOf<String>()
        if (primaryReading.isNotBlank()) readings += primaryReading
        if (fallbackReading.isNotBlank()) readings += fallbackReading

        val neuralCandidates = buildList {
            readings.forEach { reading ->
                addAll(CandidateGenerator.candidates(reading).take(4))
            }
        }
        val merged = (neuralCandidates + fallbackPool)
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)

        return NextResult(
            candidates = merged,
            latencyMs = primary.latencyMs + (fallback?.latencyMs ?: 0L),
            usedFallback = fallback != null,
            predictedReading = primaryReading
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
            }.trim().take(20)
            return clipped.map { ch ->
                if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch
            }.joinToString("")
                .filter { it in 'ぁ'..'ゖ' || it == 'ー' }
        }

        /**
         * Zenzai-style speculative verification.
         * The Mozc candidate is the draft. The neural generation acts as a prefix constraint;
         * exact/consensus matches win immediately, otherwise the classical candidate sharing
         * the longest neural prefix is promoted. A weak novel generation is kept behind the
         * classical draft instead of blindly trusting it.
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

    fun conversion(leftContext: String, reading: String): String {
        val context = leftContext.takeLast(48)
        val kata = reading.map { ch ->
            if (ch in 'ぁ'..'ゖ') (ch.code + 0x60).toChar() else ch
        }.joinToString("")
        return buildString {
            if (context.isNotEmpty()) append(CONTEXT_TAG).append(context)
            append(INPUT_TAG).append(kata).append(OUTPUT_TAG)
        }
    }

    fun inputPrediction(leftContext: String): String = buildString {
        val context = leftContext.takeLast(48)
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
    external fun nativeParameterCount(index: Int): Long
    external fun nativeFree()
}
