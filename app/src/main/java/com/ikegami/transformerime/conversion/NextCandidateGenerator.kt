package com.ikegami.transformerime.conversion

/**
 * Produces a compact candidate pool for post-commit prediction.
 * The Japanese corpus-trained MediumMoE model then reranks this pool from the live editor context.
 */
object NextCandidateGenerator {
    private val common = listOf(
        "です", "ます", "でした", "ました", "ません", "します", "しました",
        "と思います", "お願いします", "お願いいたします", "ありがとうございます",
        "ください", "でしょう", "かもしれません", "ですね", "ですよ", "ね", "よ",
        "ので", "から", "けど", "が", "を", "に", "で", "と", "は", "も",
        "。", "、", "！", "？"
    )

    fun candidates(context: String, tinyPredictions: List<String>, limit: Int = 24): List<String> {
        val recent = context.takeLast(96)
        val result = LinkedHashSet<String>()

        when {
            recent.endsWith("ありがとう") -> result.addAll(listOf("ございます", "！", "。", "助かります"))
            recent.endsWith("ありがとうございます") -> result.addAll(listOf("！", "。", "助かります", "よろしくお願いします"))
            recent.endsWith("よろしく") -> result.addAll(listOf("お願いします", "お願いいたします", "！"))
            recent.endsWith("お疲れ") -> result.addAll(listOf("さまです", "様です", "！"))
            recent.endsWith("お疲れさま") || recent.endsWith("お疲れ様") -> result.addAll(listOf("です", "でした", "！"))
            recent.endsWith("確認") -> result.addAll(listOf("しました", "します", "お願いします", "いたします"))
            recent.endsWith("添付") -> result.addAll(listOf("します", "しました", "にて送付します", "しています"))
            recent.endsWith("送付") -> result.addAll(listOf("します", "いたします", "しました"))
            recent.endsWith("今日") || recent.endsWith("今日は") -> result.addAll(listOf("よろしくお願いします", "天気が", "撮影です", "仕事です"))
            recent.endsWith("明日") || recent.endsWith("明日は") -> result.addAll(listOf("よろしくお願いします", "仕事です", "撮影です", "予定です"))
            recent.endsWith("です") -> result.addAll(listOf("。", "ね", "！", "ので", "が"))
            recent.endsWith("ます") -> result.addAll(listOf("。", "！", "ので", "ね"))
        }

        tinyPredictions
            .filter { it.isNotBlank() && it.length <= 24 }
            .forEach(result::add)

        common.forEach(result::add)
        return result.take(limit)
    }
}
