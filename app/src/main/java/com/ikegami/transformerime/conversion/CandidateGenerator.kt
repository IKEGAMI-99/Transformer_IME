package com.ikegami.transformerime.conversion

import kotlin.math.min

object CandidateGenerator {
    private data class Choice(val surface: String, val cost: Float)
    private data class State(val pos: Int, val text: String, val cost: Float, val convertedChars: Int)

    private fun choices(vararg surfaces: String): List<Choice> =
        surfaces.mapIndexed { index, surface -> Choice(surface, index * 0.24f) }

    private val dictionary: Map<String, List<Choice>> = mapOf(
        "きょう" to choices("今日", "きょう"),
        "きょうは" to choices("今日は", "きょうは"),
        "あした" to choices("明日", "あした"),
        "あしたは" to choices("明日は", "あしたは"),
        "きのう" to choices("昨日", "きのう"),
        "いま" to choices("今", "いま"),
        "こんしゅう" to choices("今週", "こんしゅう"),
        "らいしゅう" to choices("来週", "らいしゅう"),
        "せんしゅう" to choices("先週", "せんしゅう"),
        "こんげつ" to choices("今月", "こんげつ"),
        "らいげつ" to choices("来月", "らいげつ"),
        "ことし" to choices("今年", "ことし"),
        "らいねん" to choices("来年", "らいねん"),
        "ごぜん" to choices("午前", "ごぜん"),
        "ごご" to choices("午後", "ごご"),
        "じかん" to choices("時間", "じかん"),
        "じ" to choices("時", "じ"),
        "ふん" to choices("分", "ふん"),
        "ぷん" to choices("分", "ぷん"),

        "しごと" to choices("仕事", "しごと"),
        "かいぎ" to choices("会議", "かいぎ"),
        "よてい" to choices("予定", "よてい"),
        "さつえい" to choices("撮影", "さつえい"),
        "しゅうごう" to choices("集合", "しゅうごう"),
        "しゅうごうじかん" to choices("集合時間", "しゅうごうじかん"),
        "げんば" to choices("現場", "げんば"),
        "かめら" to choices("カメラ", "かめら"),
        "えいぞう" to choices("映像", "えいぞう"),
        "おんせい" to choices("音声", "おんせい"),
        "しゅうろく" to choices("収録", "しゅうろく"),
        "はいしん" to choices("配信", "はいしん"),
        "ちゅうけい" to choices("中継", "ちゅうけい"),
        "へんしゅう" to choices("編集", "へんしゅう"),
        "きざい" to choices("機材", "きざい"),
        "でんげん" to choices("電源", "でんげん"),
        "せつぞく" to choices("接続", "せつぞく"),
        "せってい" to choices("設定", "せってい"),
        "どうさ" to choices("動作", "どうさ"),
        "かくにん" to choices("確認", "かくにん"),
        "てすと" to choices("テスト", "てすと"),

        "てんき" to choices("天気", "てんき"),
        "あめ" to choices("雨", "飴", "あめ"),
        "はれ" to choices("晴れ", "はれ"),
        "くもり" to choices("曇り", "くもり"),
        "あつい" to choices("暑い", "熱い", "あつい"),
        "さむい" to choices("寒い", "さむい"),
        "いい" to choices("良い", "いい"),
        "よい" to choices("良い", "よい"),
        "わるい" to choices("悪い", "わるい"),
        "はやい" to choices("早い", "速い", "はやい"),
        "はやく" to choices("早く", "速く", "はやく"),
        "おそい" to choices("遅い", "おそい"),
        "おおきい" to choices("大きい", "おおきい"),
        "ちいさい" to choices("小さい", "ちいさい"),
        "あたらしい" to choices("新しい", "あたらしい"),
        "ふるい" to choices("古い", "ふるい"),

        "とうきょう" to choices("東京", "とうきょう"),
        "おおさか" to choices("大阪", "おおさか"),
        "にほん" to choices("日本", "にほん"),
        "にほんご" to choices("日本語", "にほんご"),
        "かいしゃ" to choices("会社", "かいしゃ"),
        "がっこう" to choices("学校", "がっこう"),
        "いえ" to choices("家", "いえ"),
        "えき" to choices("駅", "えき"),
        "みせ" to choices("店", "みせ"),

        "めーる" to choices("メール", "めーる"),
        "めえる" to choices("メール", "めえる"),
        "でんわ" to choices("電話", "でんわ"),
        "れんらく" to choices("連絡", "れんらく"),
        "へんしん" to choices("返信", "へんしん"),
        "そうしん" to choices("送信", "そうしん"),
        "そうふ" to choices("送付", "そうふ"),
        "てんぷ" to choices("添付", "てんぷ"),
        "てんぷにて" to choices("添付にて", "てんぷにて"),
        "みつもり" to choices("見積もり", "見積", "みつもり"),
        "みつもりしょ" to choices("見積書", "みつもりしょ"),
        "しりょう" to choices("資料", "しりょう"),
        "しょるい" to choices("書類", "しょるい"),
        "ないよう" to choices("内容", "ないよう"),
        "しょうさい" to choices("詳細", "しょうさい"),
        "けんとう" to choices("検討", "けんとう"),
        "たいおう" to choices("対応", "たいおう"),
        "へんこう" to choices("変更", "へんこう"),
        "ついか" to choices("追加", "ついか"),
        "こうにゅう" to choices("購入", "こうにゅう"),
        "はっちゅう" to choices("発注", "はっちゅう"),
        "らいせんす" to choices("ライセンス", "らいせんす"),

        "もじ" to choices("文字", "もじ"),
        "ぶんしょう" to choices("文章", "ぶんしょう"),
        "たんご" to choices("単語", "たんご"),
        "にゅうりょく" to choices("入力", "にゅうりょく"),
        "へんかん" to choices("変換", "へんかん"),
        "こうほ" to choices("候補", "こうほ"),
        "ひょうじ" to choices("表示", "ひょうじ"),
        "つぎ" to choices("次", "つぎ"),
        "よそく" to choices("予測", "よそく"),
        "ぶんみゃく" to choices("文脈", "ぶんみゃく"),
        "がくしゅう" to choices("学習", "がくしゅう"),
        "もでる" to choices("モデル", "もでる"),
        "とらんすふぉーまー" to choices("Transformer", "トランスフォーマー", "とらんすふぉーまー"),
        "えーあい" to choices("AI", "エーアイ", "えーあい"),
        "あぷり" to choices("アプリ", "あぷり"),
        "あんどろいど" to choices("Android", "アンドロイド", "あんどろいど"),
        "しすてむ" to choices("システム", "しすてむ"),
        "でーた" to choices("データ", "でーた"),
        "ねっとわーく" to choices("ネットワーク", "ねっとわーく"),

        "だいじょうぶ" to choices("大丈夫", "だいじょうぶ"),
        "りょうかい" to choices("了解", "りょうかい"),
        "しょうち" to choices("承知", "しょうち"),
        "おねがい" to choices("お願い", "おねがい"),
        "おねがいします" to choices("お願いします", "おねがいします"),
        "よろしく" to choices("よろしく", "宜しく"),
        "よろしくおねがいします" to choices("よろしくお願いします", "宜しくお願いします"),
        "ありがとう" to choices("ありがとう", "有難う"),
        "ありがとうございます" to choices("ありがとうございます", "有難うございます"),
        "おつかれさま" to choices("お疲れさま", "お疲れ様", "おつかれさま"),
        "おつかれさまです" to choices("お疲れさまです", "お疲れ様です", "おつかれさまです"),
        "かしこまりました" to choices("かしこまりました", "畏まりました"),
        "おくります" to choices("送ります", "おくります"),
        "お送りします" to choices("お送りします", "おおくりします"),

        "てらでっく" to choices("Teradek", "てらでっく"),
        "さいあんびゅー" to choices("Cyanview", "さいあんびゅー"),
        "すもーるえいちでぃー" to choices("SmallHD", "すもーるえいちでぃー"),
        "きゃのん" to choices("Canon", "キヤノン", "きゃのん"),
        "そにー" to choices("Sony", "ソニー", "そにー"),
        "ぶらっくまじっく" to choices("Blackmagic", "ブラックマジック", "ぶらっくまじっく"),

        "わたし" to choices("私", "わたし"),
        "ぼく" to choices("僕", "ぼく"),
        "ひと" to choices("人", "ひと"),
        "もの" to choices("物", "もの"),
        "こと" to choices("事", "こと"),
        "ほうほう" to choices("方法", "ほうほう"),
        "りゆう" to choices("理由", "りゆう"),
        "もんだい" to choices("問題", "もんだい"),
        "けっか" to choices("結果", "けっか"),
        "ひつよう" to choices("必要", "ひつよう"),
        "かのう" to choices("可能", "かのう"),
        "むずかしい" to choices("難しい", "むずかしい"),
        "かんたん" to choices("簡単", "かんたん"),
        "べんり" to choices("便利", "べんり"),
        "おもしろい" to choices("面白い", "おもしろい"),
        "すごい" to choices("凄い", "すごい")
    )

    private const val BEAM_WIDTH = 24
    private const val MAX_WORD_LENGTH = 12

    fun candidates(reading: String, limit: Int = 12): List<String> {
        if (reading.isBlank()) return emptyList()

        val result = LinkedHashSet<String>()
        dictionary[reading]?.forEach { result += it.surface }

        val beams = Array(reading.length + 1) { mutableListOf<State>() }
        beams[0] += State(0, "", 0f, 0)

        for (pos in reading.indices) {
            val states = beams[pos]
                .sortedWith(compareBy<State> { it.cost }.thenByDescending { it.convertedChars })
                .take(BEAM_WIDTH)

            if (states.isEmpty()) continue

            for (state in states) {
                val maxEnd = min(reading.length, pos + MAX_WORD_LENGTH)
                var foundDictionaryWord = false

                for (end in pos + 1..maxEnd) {
                    val key = reading.substring(pos, end)
                    val wordChoices = dictionary[key] ?: continue
                    foundDictionaryWord = true

                    wordChoices.take(3).forEachIndexed { index, choice ->
                        val converted = choice.surface != key
                        val next = State(
                            pos = end,
                            text = state.text + choice.surface,
                            cost = state.cost + choice.cost + 0.035f + index * 0.01f,
                            convertedChars = state.convertedChars + if (converted) key.length else 0
                        )
                        addState(beams[end], next)
                    }
                }

                val rawPenalty = if (foundDictionaryWord) 0.52f else 0.27f
                val raw = State(
                    pos = pos + 1,
                    text = state.text + reading[pos],
                    cost = state.cost + rawPenalty,
                    convertedChars = state.convertedChars
                )
                addState(beams[pos + 1], raw)
            }
        }

        beams[reading.length]
            .sortedWith(compareBy<State> { it.cost - it.convertedChars * 0.018f }.thenBy { it.text.length })
            .take(limit * 2)
            .forEach { result += it.text }

        result += reading
        result += RomajiConverter.toKatakana(reading)
        return result.take(limit)
    }

    private fun addState(bucket: MutableList<State>, state: State) {
        val sameTextIndex = bucket.indexOfFirst { it.text == state.text }
        if (sameTextIndex >= 0) {
            if (state.cost < bucket[sameTextIndex].cost) bucket[sameTextIndex] = state
            return
        }
        bucket += state
        if (bucket.size > BEAM_WIDTH * 3) {
            val trimmed = bucket
                .sortedWith(compareBy<State> { it.cost }.thenByDescending { it.convertedChars })
                .take(BEAM_WIDTH * 2)
            bucket.clear()
            bucket.addAll(trimmed)
        }
    }
}
