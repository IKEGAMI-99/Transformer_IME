package com.ikegami.transformerime.model

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Experimental on-device Transformer core for v0.2.
 *
 * This is intentionally a Mixture-of-Experts architecture: all expert weights are real
 * parameters, but only the top-1 FFN expert is executed for each token. That lets us test a
 * ~5M parameter model without forcing every key press through all 5M parameters.
 *
 * The weights are deterministic procedural benchmark weights in v0.2. They are not a fully
 * corpus-trained production language model yet. The existing trained TinyTransformer remains
 * the semantic signal in the hybrid ranker; this model validates the larger architecture,
 * memory footprint and latency on real Android devices.
 */
class MediumMoETransformer private constructor(
    private val tokenEmbedding: FloatArray,
    private val positionEmbedding: FloatArray,
    private val blocks: Array<Block>,
    private val outputHead: FloatArray,
    private val outputBias: FloatArray
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
        val context = buildString {
            append(contextText.takeLast(80))
            append(' ')
            append(reading)
        }
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

        val ranked = candidates.withIndex()
            .sortedByDescending { indexed ->
                // Keep dictionary/tiny-model ordering as a strong prior. The medium model may
                // swap close candidates, but should not turn a benchmark backbone into chaos.
                val prior = (candidates.size - indexed.index) * 0.58f
                val normalizedAi = (rawScores[indexed.index] - mean) / std
                prior + normalizedAi * 0.22f
            }
            .map { it.value }

        val elapsed = (System.nanoTime() - started) / 1_000_000L
        lastInferenceMs = elapsed
        return RankResult(ranked, elapsed)
    }

    private fun encode(text: String): FloatArray {
        val tokenIds = tokenize(text)
        val rows = tokenIds.size
        var x = FloatArray(rows * DIM)

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

    private fun candidateScore(hidden: FloatArray, candidate: String): Float {
        if (candidate.isEmpty()) return -100f
        val bucket = candidateBucket(candidate)
        var outputScore = outputBias[bucket]
        val headBase = bucket * DIM
        for (d in 0 until DIM) outputScore += outputHead[headBase + d] * hidden[d]

        // Character-embedding compatibility gives the benchmark backbone a stable structural
        // signal even before full Japanese corpus training is introduced in a later version.
        val candidateVector = FloatArray(DIM)
        var count = 0
        candidate.forEach { ch ->
            val id = charBucket(ch)
            val tokenBase = id * DIM
            for (d in 0 until DIM) candidateVector[d] += tokenEmbedding[tokenBase + d]
            count++
        }
        if (count > 0) {
            var similarity = 0f
            for (d in 0 until DIM) similarity += hidden[d] * (candidateVector[d] / count)
            outputScore += similarity * 0.35f
        }
        return outputScore
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
                    val score = dot / scale
                    scores[j] = score
                    if (score > maxScore) maxScore = score
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

    private fun candidateBucket(text: String): Int {
        var hash = 0x13579bdf
        text.forEach { ch -> hash = (hash * 31) xor ch.code }
        return (hash and Int.MAX_VALUE) % VOCAB_BUCKETS
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
        const val FF_DIM = 256
        const val LAYERS = 8
        const val EXPERTS = 8
        const val VOCAB_BUCKETS = 1024
        const val CONTEXT_LENGTH = 24
        private const val BOS_BUCKET = 1

        fun create(seed: Int = 0x534f4c32): MediumMoETransformer {
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

            val model = MediumMoETransformer(
                tokenEmbedding = tokenEmbedding,
                positionEmbedding = positionEmbedding,
                blocks = blocks,
                outputHead = randomArray(VOCAB_BUCKETS * DIM, 0.035f),
                outputBias = zeros(VOCAB_BUCKETS)
            )
            require(model.parameterCount >= 5_000_000) {
                "Expected >= 5M parameters, got ${model.parameterCount}"
            }
            return model
        }
    }
}
