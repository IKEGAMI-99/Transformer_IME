package com.ikegami.transformerime.conversion

object CandidateGenerator {
    private val dictionary = mapOf(
        "きょう" to listOf("今日", "きょう"),
        "あした" to listOf("明日", "あした"),
        "しごと" to listOf("仕事", "しごと"),
        "かいぎ" to listOf("会議", "かいぎ"),
        "よてい" to listOf("予定", "よてい"),
        "てんき" to listOf("天気", "てんき"),
        "とうきょう" to listOf("東京", "とうきょう"),
        "おおさか" to listOf("大阪", "おおさか"),
        "めーる" to listOf("メール", "めーる"),
        "めえる" to listOf("メール", "めえる"),
        "みつもり" to listOf("見積もり", "見積", "みつもり"),
        "しりょう" to listOf("資料", "しりょう"),
        "ないよう" to listOf("内容", "ないよう"),
        "かくにん" to listOf("確認", "かくにん"),
        "れんらく" to listOf("連絡", "れんらく"),
        "にほんご" to listOf("日本語", "にほんご"),
        "もじ" to listOf("文字", "もじ"),
        "にゅうりょく" to listOf("入力", "にゅうりょく"),
        "ひょうじ" to listOf("表示", "ひょうじ"),
        "つぎ" to listOf("次", "つぎ"),
        "こうほ" to listOf("候補", "こうほ"),
        "べんり" to listOf("便利", "べんり"),
        "だいじょうぶ" to listOf("大丈夫", "だいじょうぶ"),
        "りょうかい" to listOf("了解", "りょうかい"),
        "しょうち" to listOf("承知", "しょうち"),
        "おねがい" to listOf("お願い", "おねがい"),
        "ありがとう" to listOf("ありがとう", "有難う"),
        "おつかれさま" to listOf("お疲れさま", "お疲れ様", "おつかれさま"),
        "てらでっく" to listOf("Teradek", "てらでっく"),
        "さいあんびゅー" to listOf("Cyanview", "さいあんびゅー"),
        "すもーるえいちでぃー" to listOf("SmallHD", "すもーるえいちでぃー")
    )

    fun candidates(reading: String): List<String> {
        if (reading.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        dictionary[reading]?.let(result::addAll)
        result += reading
        result += RomajiConverter.toKatakana(reading)
        return result.toList()
    }
}
