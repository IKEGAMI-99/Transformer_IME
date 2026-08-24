package com.ikegami.transformerime.model

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

class TinyTransformerModel private constructor(
    val vocab: List<String>,
    private val contextLength: Int,
    private val dim: Int,
    private val heads: Int,
    private val ffDim: Int,
    private val tok: FloatArray,
    private val pos: FloatArray,
    private val ln1w: FloatArray,
    private val ln1b: FloatArray,
    private val qw: FloatArray,
    private val qb: FloatArray,
    private val kw: FloatArray,
    private val kb: FloatArray,
    private val vw: FloatArray,
    private val vb: FloatArray,
    private val ow: FloatArray,
    private val ob: FloatArray,
    private val ln2w: FloatArray,
    private val ln2b: FloatArray,
    private val ff1w: FloatArray,
    private val ff1b: FloatArray,
    private val ff2w: FloatArray,
    private val ff2b: FloatArray,
    private val lnfw: FloatArray,
    private val lnfb: FloatArray,
    private val headw: FloatArray,
    private val headb: FloatArray
) {
    private val stoi = vocab.withIndex().associate { it.value to it.index }
    private val bosId = stoi["<bos>"] ?: 0
    private val specialIds = setOfNotNull(stoi["<bos>"], stoi["<unk>"])
    private val lexicalTokens = vocab.withIndex()
        .filter { it.index !in specialIds }
        .sortedByDescending { it.value.length }

    data class Prediction(val text: String, val score: Float)

    fun rankCandidates(contextText: String, candidates: List<String>): List<String> {
        if (candidates.size <= 1) return candidates
        val logits = nextLogits(tokenize(contextText))
        return candidates.withIndex()
            .sortedByDescending { indexed ->
                val id = stoi[indexed.value]
                val ai = if (id != null) logits[id] else -100f
                val dictionaryPrior = (candidates.size - indexed.index) * 0.12f
                ai + dictionaryPrior
            }
            .map { it.value }
    }

    fun predictNext(contextText: String, count: Int = 5): List<Prediction> {
        val logits = nextLogits(tokenize(contextText))
        return logits.indices.asSequence()
            .filter { it !in specialIds }
            .map { Prediction(vocab[it], logits[it]) }
            .sortedByDescending { it.score }
            .take(count)
            .toList()
    }

    fun scoreNext(contextText: String, candidate: String): Float {
        val id = stoi[candidate] ?: return Float.NEGATIVE_INFINITY
        return nextLogits(tokenize(contextText))[id]
    }

    private fun tokenize(text: String): IntArray {
        val ids = ArrayList<Int>()
        ids += bosId
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace() || c in "、。,.!?！？・:：;；()（）[]［］{}｛｝\n\r\t") {
                i++
                continue
            }
            val match = lexicalTokens.firstOrNull { (_, token) -> text.startsWith(token, i) }
            if (match != null) {
                ids += match.index
                i += match.value.length
            } else {
                i++
            }
        }
        return ids.takeLast(contextLength).toIntArray()
    }

    private fun nextLogits(tokens: IntArray): FloatArray {
        val safeTokens = if (tokens.isEmpty()) intArrayOf(bosId) else tokens.takeLast(contextLength).toIntArray()
        val rows = safeTokens.size
        val x = FloatArray(rows * dim)

        for (r in 0 until rows) {
            val tokenId = safeTokens[r].coerceIn(0, vocab.lastIndex)
            for (d in 0 until dim) {
                x[r * dim + d] = tok[tokenId * dim + d] + pos[r * dim + d]
            }
        }

        val z1 = layerNorm(x, rows, dim, ln1w, ln1b)
        val q = linear(z1, rows, dim, qw, qb, dim)
        val k = linear(z1, rows, dim, kw, kb, dim)
        val v = linear(z1, rows, dim, vw, vb, dim)

        val headDim = dim / heads
        val att = FloatArray(rows * dim)
        val scale = sqrt(headDim.toDouble()).toFloat()

        for (r in 0 until rows) {
            for (h in 0 until heads) {
                val scores = FloatArray(r + 1)
                var maxScore = Float.NEGATIVE_INFINITY
                for (j in 0..r) {
                    var dot = 0f
                    for (hd in 0 until headDim) {
                        val d = h * headDim + hd
                        dot += q[r * dim + d] * k[j * dim + d]
                    }
                    val score = dot / scale
                    scores[j] = score
                    if (score > maxScore) maxScore = score
                }

                var denom = 0.0
                val probs = DoubleArray(scores.size)
                for (j in scores.indices) {
                    val e = exp((scores[j] - maxScore).toDouble())
                    probs[j] = e
                    denom += e
                }

                for (hd in 0 until headDim) {
                    val d = h * headDim + hd
                    var sum = 0.0
                    for (j in probs.indices) {
                        sum += (probs[j] / denom) * v[j * dim + d]
                    }
                    att[r * dim + d] = sum.toFloat()
                }
            }
        }

        val projected = linear(att, rows, dim, ow, ob, dim)
        for (i in x.indices) x[i] += projected[i]

        val z2 = layerNorm(x, rows, dim, ln2w, ln2b)
        val ff1 = linear(z2, rows, dim, ff1w, ff1b, ffDim)
        for (i in ff1.indices) ff1[i] = gelu(ff1[i])
        val ff2 = linear(ff1, rows, ffDim, ff2w, ff2b, dim)
        for (i in x.indices) x[i] += ff2[i]

        val final = layerNorm(x, rows, dim, lnfw, lnfb)
        val lastOffset = (rows - 1) * dim
        val logits = FloatArray(vocab.size)
        for (o in logits.indices) {
            var sum = headb[o]
            val wOffset = o * dim
            for (d in 0 until dim) sum += headw[wOffset + d] * final[lastOffset + d]
            logits[o] = sum
        }
        return logits
    }

    private fun layerNorm(
        input: FloatArray,
        rows: Int,
        width: Int,
        weight: FloatArray,
        bias: FloatArray
    ): FloatArray {
        val out = FloatArray(input.size)
        for (r in 0 until rows) {
            val base = r * width
            var mean = 0.0
            for (d in 0 until width) mean += input[base + d]
            mean /= width

            var variance = 0.0
            for (d in 0 until width) {
                val delta = input[base + d] - mean
                variance += delta * delta
            }
            variance /= width
            val invStd = 1.0 / sqrt(variance + 1e-5)

            for (d in 0 until width) {
                val normalized = ((input[base + d] - mean) * invStd).toFloat()
                out[base + d] = normalized * weight[d] + bias[d]
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

    private fun gelu(x: Float): Float {
        val xd = x.toDouble()
        val inner = 0.7978845608028654 * (xd + 0.044715 * xd * xd * xd)
        return (0.5 * xd * (1.0 + tanh(inner))).toFloat()
    }

    companion object {
        fun load(modelInput: InputStream, vocabJson: String): TinyTransformerModel {
            val bytes = modelInput.use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(4)
            buffer.get(magicBytes)
            require(String(magicBytes, Charsets.US_ASCII) == "TTIM") { "Invalid model magic" }

            val version = buffer.int
            require(version == 1) { "Unsupported model version: $version" }
            val vocabSize = buffer.int
            val contextLength = buffer.int
            val dim = buffer.int
            val heads = buffer.int
            val ffDim = buffer.int
            val vocab = parseJsonStringArray(vocabJson)
            require(vocab.size == vocabSize) { "Vocab/model size mismatch" }

            fun readArray(): FloatArray {
                val length = buffer.int
                return FloatArray(length) { buffer.float }
            }

            return TinyTransformerModel(
                vocab = vocab,
                contextLength = contextLength,
                dim = dim,
                heads = heads,
                ffDim = ffDim,
                tok = readArray(), pos = readArray(),
                ln1w = readArray(), ln1b = readArray(),
                qw = readArray(), qb = readArray(),
                kw = readArray(), kb = readArray(),
                vw = readArray(), vb = readArray(),
                ow = readArray(), ob = readArray(),
                ln2w = readArray(), ln2b = readArray(),
                ff1w = readArray(), ff1b = readArray(),
                ff2w = readArray(), ff2b = readArray(),
                lnfw = readArray(), lnfb = readArray(),
                headw = readArray(), headb = readArray()
            )
        }

        private fun parseJsonStringArray(json: String): List<String> {
            val result = ArrayList<String>()
            val regex = Regex("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
            for (match in regex.findAll(json)) {
                result += match.groupValues[1]
                    .replace("\\\\\\\"", "\\\"")
                    .replace("\\\\\\\\", "\\\\")
                    .replace("\\\\n", "\\n")
                    .replace("\\\\t", "\\t")
            }
            return result
        }
    }
}
