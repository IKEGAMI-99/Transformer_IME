package com.ikegami.transformerime.model

import android.content.Context
import java.io.ByteArrayInputStream
import java.util.Base64

object ModelRepository {
    @Volatile private var cached: TinyTransformerModel? = null

    fun get(context: Context): TinyTransformerModel? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val vocab = context.assets.open("vocab.json").bufferedReader().use { it.readText() }
                val encodedModel = buildString {
                    repeat(4) { part ->
                        append(
                            context.assets.open("tiny_transformer.b64.$part")
                                .bufferedReader()
                                .use { it.readText() }
                                .filterNot(Char::isWhitespace)
                        )
                    }
                }
                val modelBytes = Base64.getDecoder().decode(encodedModel)
                TinyTransformerModel.load(ByteArrayInputStream(modelBytes), vocab)
            }.getOrNull()?.also { cached = it }
        }
    }
}
