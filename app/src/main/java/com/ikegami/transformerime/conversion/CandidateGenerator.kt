package com.ikegami.transformerime.conversion

import android.content.Context
import kotlin.math.min

/**
 * Kana -> surface conversion.
 *
 * v0.4 keeps a tiny high-priority fallback/technical dictionary, then augments it with the
 * compact Mozc OSS dictionary generated during CI. One batched lookup fetches every substring
 * the beam search can use, so names and places no longer depend on a hand-written list.
 */
object CandidateGenerator {
    private data class Choice(val surface: String, val cost: Float)
    private data class State(val pos: Int, val text: String, val cost: Float, val convertedChars: Int)

    private fun choices(vararg surfaces: String): List<Choice> =
        surfaces.mapIndexed { index, surface -> Choice(surface, index * 0.22f) }

    // Deliberately small. General Japanese now comes from Mozc; these are fallback entries and
    // project/domain-specific spellings we want to rank ahead of a generic dictionary.
    private val priorityDictionary: Map<String, List<Choice>> = mapOf(
        "きょう" to choices("今日", "きょう"),
        "きょうは" to choices("今日は", "きょうは"),
        "あした" to choices("明日", "あした"),
        "あしたは" to choices("明日は", "あしたは"),
        "きのう" to choices("昨日", "きのう"),
        "いま" to choices("今", "いま"),
        "てんき" to choices("天気", "てんき"),
        "いい" to choices("良い", "いい"),
        "よい" to choices("良い", "よい"),
        "はやい" to choices("早い", "速い", "はやい"),
        "はやく" to choices("早く", "速く", "はやく"),
        "しごと" to choices("仕事", "しごと"),
        "かいぎ" to choices("会議", "かいぎ"),
        "よてい" to choices("予定", "よてい"),
        "さつえい" to choices("撮影", "さつえい"),
        "げんば" to choices("現場", "げんば"),
        "かめら" to choices("カメラ", "かめら"),
        "えいぞう" to choices("映像", "えいぞう"),
        "おんせい" to choices("音声", "おんせい"),
        "しゅうろく" to choices("収録", "しゅうろく"),
        "はいしん" to choices("配信", "はいしん"),
        "ちゅうけい" to choices("中継", "ちゅうけい"),
        "きざい" to choices("機材", "きざい"),
        "せってい" to choices("設定", "せってい"),
        "かくにん" to choices("確認", "かくにん"),
        "みつもり" to choices("見積もり", "見積", "みつもり"),
        "みつもりしょ" to choices("見積書", "みつもりしょ"),
        "てんぷ" to choices("添付", "てんぷ"),
        "そうふ" to choices("送付", "そうふ"),
        "へんしん" to choices("返信", "へんしん"),
        "はっちゅう" to choices("発注", "はっちゅう"),
        "こうにゅう" to choices("購入", "こうにゅう"),
        "らいせんす" to choices("ライセンス", "らいせんす"),
        "とうきょう" to choices("東京", "とうきょう"),
        "おおさか" to choices("大阪", "おおさか"),
        "にほん" to choices("日本", "にほん"),
        "にほんご" to choices("日本語", "にほんご"),
        "おねがいします" to choices("お願いします", "おねがいします"),
        "よろしくおねがいします" to choices("よろしくお願いします", "宜しくお願いします"),
        "ありがとうございます" to choices("ありがとうございます", "有難うございます"),
        "おつかれさまです" to choices("お疲れさまです", "お疲れ様です", "おつかれさまです"),
        "てらでっく" to choices("Teradek", "てらでっく"),
        "さいあんびゅー" to choices("Cyanview", "さいあんびゅー"),
        "すもーるえいちでぃー" to choices("SmallHD", "すもーるえいちでぃー"),
        "きゃのん" to choices("Canon", "キヤノン", "きゃのん"),
        "そにー" to choices("Sony", "ソニー", "そにー"),
        "ぶらっくまじっく" to choices("Blackmagic", "ブラックマジック", "ぶらっくまじっく"),
        "とらんすふぉーまー" to choices("Transformer", "トランスフォーマー", "とらんすふぉーまー"),
        "えーあい" to choices("AI", "エーアイ", "えーあい"),
        "あんどろいど" to choices("Android", "アンドロイド", "あんどろいど")
    )

    private const val BEAM_WIDTH = 28
    private const val MAX_WORD_LENGTH = 18

    fun initialize(context: Context) = MozcDictionary.initialize(context)

    val extendedDictionaryReady: Boolean
        get() = MozcDictionary.isReady

    fun candidates(reading: String, limit: Int = 12): List<String> {
        if (reading.isBlank()) return emptyList()

        val substringKeys = LinkedHashSet<String>()
        for (pos in reading.indices) {
            val maxEnd = min(reading.length, pos + MAX_WORD_LENGTH)
            for (end in pos + 1..maxEnd) substringKeys += reading.substring(pos, end)
        }
        val mozc = MozcDictionary.lookup(substringKeys, maxCandidates = 8)

        fun wordChoices(key: String): List<Choice> {
            val result = ArrayList<Choice>(12)
            priorityDictionary[key]?.let(result::addAll)
            val seen = result.mapTo(HashSet()) { it.surface }
            mozc[key].orEmpty().forEach { entry ->
                if (seen.add(entry.surface)) {
                    // Preserve Mozc ordering while fitting its word costs into our small beam-cost scale.
                    result += Choice(entry.surface, (entry.cost * 0.45f).coerceIn(0.08f, 0.62f))
                }
            }
            return result
        }

        val result = LinkedHashSet<String>()
        wordChoices(reading).forEach { result += it.surface }

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
                    val choices = wordChoices(key)
                    if (choices.isEmpty()) continue
                    foundDictionaryWord = true

                    choices.take(6).forEachIndexed { index, choice ->
                        val converted = choice.surface != key
                        addState(
                            beams[end],
                            State(
                                pos = end,
                                text = state.text + choice.surface,
                                cost = state.cost + choice.cost + 0.025f + index * 0.008f,
                                convertedChars = state.convertedChars + if (converted) key.length else 0
                            )
                        )
                    }
                }

                val rawPenalty = if (foundDictionaryWord) 0.56f else 0.30f
                addState(
                    beams[pos + 1],
                    State(
                        pos = pos + 1,
                        text = state.text + reading[pos],
                        cost = state.cost + rawPenalty,
                        convertedChars = state.convertedChars
                    )
                )
            }
        }

        beams[reading.length]
            .sortedWith(compareBy<State> { it.cost - it.convertedChars * 0.020f }.thenBy { it.text.length })
            .take(limit * 3)
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
