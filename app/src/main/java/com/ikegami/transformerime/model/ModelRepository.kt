package com.ikegami.transformerime.model

import android.content.Context

object ModelRepository {
    @Volatile private var cached: TinyTransformerModel? = null

    fun get(context: Context): TinyTransformerModel? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val vocab = context.assets.open("vocab.json").bufferedReader().use { it.readText() }
                context.assets.open("tiny_transformer.bin").use { model ->
                    TinyTransformerModel.load(model, vocab)
                }
            }.getOrNull()?.also { cached = it }
        }
    }
}
