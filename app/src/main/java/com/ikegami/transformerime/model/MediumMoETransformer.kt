package com.ikegami.transformerime.model

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * On-device sparse Transformer used for the second-stage IME reranker.
 *
 * v0.3 loads an INT8 model trained with next-character prediction on a Japanese sentence
 * corpus. A deterministic benchmark model remains available as a fallback for source builds
 * where the generated training asset has not been created yet.
 */
class MediumMoETransformer private constructor(
    private val tokenEmbedding: FloatArray,
    private val positionEmbedding: FloatArray,
    private val blocks: Array<Block>,
    private val outputHead: FloatArray,
    private val outputBias: FloatArray,
    val corpusTrained: Boolean,
    val sourceLabel: String
) {
    data class RankResult(
        val candidates: List<String>,
        val latencyMs: Long
    )

    private data class Expert(
        val w1: FloatArray,
        val b1: FloatArray,
        val w2: FloatArray,
        val b2: FloatArray
    )

    private data class Block(
        val ln1w: FloatArray,
        val ln1b: FloatArray,
        val qw: FloatArray,
        val qb: FloatArray,
        val kw: FloatArray,
        val kb: FloatArray,
        val vw: FloatArray,
        val vb: FloatArray,
        val ow: FloatArray,
        val ob: FloatArray,
        val ln2w: FloatArray,
        val ln2b: FloatArray,
        val routerW: FloatArray,
        val routerB: FloatArray,
        val experts: Array<Expert>
    )

    @Volatile
    var lastInferenceMs: Long = 0
        private set

    val parameterCount: Int = countParameters()

    fun rerank(contextText: String, reading: String, candidates: List<String>): RankResult {
        if (candidates.size <= 1) return RankResult(candidates, 0)
        val started = System.nanoTime()

        // The model was trained as a Japanese language model, not as a reading encoder.
        // Use already-committed text as the linguistic context and leave kana->kanji validity
        // to CandidateGenerator. This avoids teaching the LM that the unconverted reading is
        // part of the final sentence.
        val context = contextText.takeLast(80)
        val hidden = encode(context)
        val rawScores = candidates.map { candidateScore(hidden, it) }
        val mean = rawScores.average().toFloat()
        var variance = 0f
        rawScores.forEach { value ->
            val d = value - mean
            variance += d * d
        }
        variance /= max(1, rawScores.size)
        val std = sqrt(variance + 1e-6f)

        val aiWeight = if (corpusTrained) 0.42f else 0.18f
        val priorWeight = if (corpusTrained) 0.50f else 0.58f
        val ranked = candidates.withIndex()
            .sortedByDescending { indexed ->
                val prior = (candidates.size - indexed.index) * priorWeight
                val normalizedAi = (rawScores[indexed.index] - mean) / std
                prior + normalizedAi * aiWeight
            }
            .map { it.value }

        val elapsed = (System.nanoTime() - started) / 1_000_000L
        lastInferenceMs = elapsed
        return RankResult(ranked, elapsed)
    }

    private fun encode(text: String): FloatArray {
        val tokenIds = tokenize(text)
        val rows = tokenIds.size
        val x = FloatArray(rows * DIM)

        for (r in 0 until rows) {
            val token = tokenIds[r]
            val tokenBase = token * DIM
            val posBase = r * DIM
            val outBase = r * DIM
            for (d in 0 until DIM) {
                x[outBase + d] = tokenEmbedding[tokenBase + d] + positionEmbedding[posBase + d]
            }
        }

        blocks.forEach { block ->
            val z1 = layerNorm(x, rows, block.ln1w, block.ln1b)
            val q = linear(z1, rows, DIM, block.qw, block.qb, DIM)
            val k = linear(z1, rows, DIM, block.kw, block.kb, DIM)
            val v = linear(z1, rows, DIM, block.vw, block.vb, DIM)
            val attended = causalAttention(q, k, v, rows)
            val projected = linear(attended, rows, DIM, block.ow, block.ob, DIM)
            for (i in x.indices) x[i] += projected[i]

            val z2 = layerNorm(x, rows, block.ln2w, block.ln2b)
            applyTop1Expert(x, z2, rows, block)
        }

        val lastBase = (rows - 1) * DIM
        return FloatArray(DIM) { d -> x[lastBase + d] }
    }

    private fun applyTop1Expert(x: FloatArray, normalized: FloatArray, rows: Int, block: Block) {
        val ff = FloatArray(FF_DIM)
        for (r in 0 until rows) {
            val base = r * DIM
            val expertIndex = chooseExpert(normalized, base, block)
            val expert = block.experts[expertIndex]

            for (o in 0 until FF_DIM) {
                var sum = expert.b1[o]
                val wBase = o * DIM
                for (i in 0 until DIM) sum += normalized[base + i] * expert.w1[wBase + i]
                ff[o] = gelu(sum)
            }

            for (o in 0 until DIM) {
                var sum = expert.b2[o]
                val wBase = o * FF_DIM
                for (i in 0 until FF_DIM) sum += ff[i] * expert.w2[wBase + i]
                x[base + o] += sum
            }
        }
    }

    private fun chooseExpert(input: FloatArray, base: Int, block: Block): Int {
        var bestExpert = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (expert in 0 until EXPERTS) {
            var score = block.routerB[expert]
            val wBase = expert * DIM
            for (d in 0 until DIM) score += input[base + d] * block.routerW[wBase + d]
            if (score > bestScore) {
                bestScore = score
                bestExpert = expert
            }
        }
        return bestExpert
    }

    /**
     * The LM head predicts the next character bucket. The first candidate character therefore
     * carries the most weight; a short decaying look-ahead keeps compounds distinguishable
     * without running a full Transformer pass for every candidate.
     */
    private fun candidateScore(hidden: FloatArray, candidate: String): Float {
        if (candidate.isEmpty()) return -100f
        val weights = floatArrayOf(1f, 0.45f, 0.22f, 0.11f)
        var score = 0f
        var weightSum = 0f
        candidate.take(weights.size).forEachIndexed { index, ch ->
            val bucket = charBucket(ch)
            var logit = outputBias[bucket]
            val headBase = bucket * DIM
            for (d in 0 until DIM) logit += outputHead[headBase + d] * hidden[d]
            score += logit * weights[index]
            weightSum += weights[index]
        }

        // A small embedding compatibility term is useful for candidates sharing the same first
        // character, and is learned too when corpus weights are loaded.
        val vector = FloatArray(DIM)
        var count = 0
        candidate.take(6).forEach { ch ->
            val tokenBase = charBucket(ch) * DIM
            for (d in 0 until DIM) vector[d] += tokenEmbedding[tokenBase + d]
            count++
        }
        var compatibility = 0f
        if (count > 0) {
            for (d in 0 until DIM) compatibility += hidden[d] * (vector[d] / count)
        }
        return score / weightSum.coerceAtLeast(1e-6f) + compatibility * 0.05f
    }

    private fun tokenize(text: String): IntArray {
        val filtered = text.filterNot { it == '\n' || it == '\r' || it == '\t' }
        if (filtered.isEmpty()) return intArrayOf(BOS_BUCKET)
        val chars = filtered.takeLast(CONTEXT_LENGTH - 1)
        val out = IntArray(chars.length + 1)
        out[0] = BOS_BUCKET
        chars.forEachIndexed { index, ch -> out[index + 1] = charBucket(ch) }
        return out
    }

    private fun causalAttention(q: FloatArray, k: FloatArray, v: FloatArray, rows: Int): FloatArray {
        val out = FloatArray(rows * DIM)
        val headDim = DIM / HEADS
        val scale = sqrt(headDim.toFloat())

        for (r in 0 until rows) {
            for (h in 0 until HEADS) {
                val scores = FloatArray(r + 1)
                var maxScore = Float.NEGATIVE_INFINITY
                for (j in 0..r) {
                    var dot = 0f
                    for (hd in 0 until headDim) {
                        val d = h * headDim + hd
                        dot += q[r * DIM + d] * k[j * DIM + d]
                    }
                    val value = dot / scale
                    scores[j] = value
                    if (value > maxScore) maxScore = value
                }

                var denom = 0.0
                val probs = DoubleArray(scores.size)
                for (j in scores.indices) {
                    val p = exp((scores[j] - maxScore).toDouble())
                    probs[j] = p
                    denom += p
                }

                for (hd in 0 until headDim) {
                    val d = h * headDim + hd
                    var sum = 0.0
                    for (j in probs.indices) sum += (probs[j] / denom) * v[j * DIM + d]
                    out[r * DIM + d] = sum.toFloat()
                }
            }
        }
        return out
    }

    private fun layerNorm(
        input: FloatArray,
        rows: Int,
        weight: FloatArray,
        bias: FloatArray
    ): FloatArray {
        val out = FloatArray(input.size)
        for (r in 0 until rows) {
            val base = r * DIM
            var mean = 0.0
            for (d in 0 until DIM) mean += input[base + d]
            mean /= DIM

            var variance = 0.0
            for (d in 0 until DIM) {
                val delta = input[base + d] - mean
                variance += delta * delta
            }
            variance /= DIM
            val invStd = 1.0 / sqrt(variance + 1e-5)

            for (d in 0 until DIM) {
                val value = ((input[base + d] - mean) * invStd).toFloat()
                out[base + d] = value * weight[d] + bias[d]
            }
        }
        return out
    }

    private fun linear(
        input: FloatArray,
        rows: Int,
        inDim: Int,
        weight: FloatArray,
        bias: FloatArray,
        outDim: Int
    ): FloatArray {
        val out = FloatArray(rows * outDim)
        for (r in 0 until rows) {
            val inBase = r * inDim
            val outBase = r * outDim
            for (o in 0 until outDim) {
                var sum = bias[o]
                val wBase = o * inDim
                for (i in 0 until inDim) sum += input[inBase + i] * weight[wBase + i]
                out[outBase + o] = sum
            }
        }
        return out
    }

    private fun countParameters(): Int {
        var total = tokenEmbedding.size + positionEmbedding.size + outputHead.size + outputBias.size
        blocks.forEach { block ->
            total += block.ln1w.size + block.ln1b.size
            total += block.qw.size + block.qb.size + block.kw.size + block.kb.size
            total += block.vw.size + block.vb.size + block.ow.size + block.ob.size
            total += block.ln2w.size + block.ln2b.size
            total += block.routerW.size + block.routerB.size
            block.experts.forEach { expert ->
                total += expert.w1.size + expert.b1.size + expert.w2.size + expert.b2.size
            }
        }
        return total
    }

    private fun gelu(x: Float): Float {
        val xd = x.toDouble()
        val inner = 0.7978845608028654 * (xd + 0.044715 * xd * xd * xd)
        return (0.5 * xd * (1.0 + tanh(inner))).toFloat()
    }

    private fun charBucket(ch: Char): Int {
        val code = ch.code
        val mixed = code * 0x45d9f3b xor (code ushr 7) xor 0x5f356495
        return (mixed and Int.MAX_VALUE) % VOCAB_BUCKETS
    }

    private class FastRng(seed: Int) {
        private var state = seed

        fun nextSigned(scale: Float): Float {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            val unit = ((x ushr 8) and 0x00ffffff) / 16777215f
            return (unit * 2f - 1f) * scale
        }
    }

    companion object {
        const val DIM = 128
        const val HEADS = 4
        const val FF_DIM = 272
        const val LAYERS = 4
        const val EXPERTS = 16
        const val VOCAB_BUCKETS = 1024
        const val CONTEXT_LENGTH = 24
        private const val BOS_BUCKET = 1
        private const val ASSET_NAME = "medium_moe_jpn.q8"

        fun load(context: Context): MediumMoETransformer {
            return runCatching {
                context.assets.open(ASSET_NAME).use(::loadQuantized)
            }.getOrElse {
                create()
            }
        }

        internal fun loadQuantized(input: InputStream): MediumMoETransformer {
            val bytes = input.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buffer.get(magic)
            require(String(magic, Charsets.US_ASCII) == "MMJQ") { "Invalid Medium model magic" }
            val version = buffer.int
            require(version == 1) { "Unsupported Medium model version: $version" }
            require(buffer.int == VOCAB_BUCKETS) { "Vocabulary mismatch" }
            require(buffer.int == CONTEXT_LENGTH) { "Context mismatch" }
            require(buffer.int == DIM) { "Dimension mismatch" }
            require(buffer.int == HEADS) { "Head count mismatch" }
            require(buffer.int == LAYERS) { "Layer count mismatch" }
            require(buffer.int == FF_DIM) { "FF dimension mismatch" }
            require(buffer.int == EXPERTS) { "Expert count mismatch" }

            fun readArray(expected: Int): FloatArray {
                val length = buffer.int
                require(length == expected) { "Array size mismatch: expected $expected, got $length" }
                val scale = buffer.float
                return FloatArray(length) { buffer.get().toInt() * scale }
            }

            val tokenEmbedding = readArray(VOCAB_BUCKETS * DIM)
            val positionEmbedding = readArray(CONTEXT_LENGTH * DIM)
            val blocks = Array(LAYERS) {
                Block(
                    ln1w = readArray(DIM),
                    ln1b = readArray(DIM),
                    qw = readArray(DIM * DIM), qb = readArray(DIM),
                    kw = readArray(DIM * DIM), kb = readArray(DIM),
                    vw = readArray(DIM * DIM), vb = readArray(DIM),
                    ow = readArray(DIM * DIM), ob = readArray(DIM),
                    ln2w = readArray(DIM),
                    ln2b = readArray(DIM),
                    routerW = readArray(EXPERTS * DIM),
                    routerB = readArray(EXPERTS),
                    experts = Array(EXPERTS) {
                        Expert(
                            w1 = readArray(FF_DIM * DIM),
                            b1 = readArray(FF_DIM),
                            w2 = readArray(DIM * FF_DIM),
                            b2 = readArray(DIM)
                        )
                    }
                )
            }
            val outputHead = readArray(VOCAB_BUCKETS * DIM)
            val outputBias = readArray(VOCAB_BUCKETS)
            require(!buffer.hasRemaining()) { "Unexpected bytes at end of Medium model" }

            return MediumMoETransformer(
                tokenEmbedding = tokenEmbedding,
                positionEmbedding = positionEmbedding,
                blocks = blocks,
                outputHead = outputHead,
                outputBias = outputBias,
                corpusTrained = true,
                sourceLabel = "Tatoeba Japanese · INT8"
            ).also {
                require(it.parameterCount >= 5_000_000) {
                    "Expected >= 5M parameters, got ${it.parameterCount}"
                }
            }
        }

        /** Deterministic fallback used when the generated corpus-trained asset is absent. */
        fun create(seed: Int = 0x534f4c33): MediumMoETransformer {
            val rng = FastRng(seed)
            fun randomArray(size: Int, scale: Float = 0.022f) =
                FloatArray(size) { rng.nextSigned(scale) }
            fun zeros(size: Int) = FloatArray(size)
            fun ones(size: Int) = FloatArray(size) { 1f }

            val tokenEmbedding = randomArray(VOCAB_BUCKETS * DIM, 0.045f)
            val positionEmbedding = randomArray(CONTEXT_LENGTH * DIM, 0.018f)
            val blocks = Array(LAYERS) {
                Block(
                    ln1w = ones(DIM),
                    ln1b = zeros(DIM),
                    qw = randomArray(DIM * DIM), qb = zeros(DIM),
                    kw = randomArray(DIM * DIM), kb = zeros(DIM),
                    vw = randomArray(DIM * DIM), vb = zeros(DIM),
                    ow = randomArray(DIM * DIM), ob = zeros(DIM),
                    ln2w = ones(DIM),
                    ln2b = zeros(DIM),
                    routerW = randomArray(EXPERTS * DIM, 0.03f),
                    routerB = zeros(EXPERTS),
                    experts = Array(EXPERTS) {
                        Expert(
                            w1 = randomArray(FF_DIM * DIM, 0.024f),
                            b1 = zeros(FF_DIM),
                            w2 = randomArray(DIM * FF_DIM, 0.024f),
                            b2 = zeros(DIM)
                        )
                    }
                )
            }

            return MediumMoETransformer(
                tokenEmbedding = tokenEmbedding,
                positionEmbedding = positionEmbedding,
                blocks = blocks,
                outputHead = randomArray(VOCAB_BUCKETS * DIM, 0.035f),
                outputBias = zeros(VOCAB_BUCKETS),
                corpusTrained = false,
                sourceLabel = "benchmark fallback"
            ).also {
                require(it.parameterCount >= 5_000_000) {
                    "Expected >= 5M parameters, got ${it.parameterCount}"
                }
            }
        }
    }
}
