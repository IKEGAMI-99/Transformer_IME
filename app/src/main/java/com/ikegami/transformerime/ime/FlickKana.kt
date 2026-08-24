package com.ikegami.transformerime.ime

enum class FlickDirection { CENTER, LEFT, UP, RIGHT, DOWN }

data class FlickSet(
    val center: String,
    val left: String = center,
    val up: String = center,
    val right: String = center,
    val down: String = center
) {
    fun value(direction: FlickDirection): String = when (direction) {
        FlickDirection.CENTER -> center
        FlickDirection.LEFT -> left
        FlickDirection.UP -> up
        FlickDirection.RIGHT -> right
        FlickDirection.DOWN -> down
    }
}

object FlickKana {
    val keys: Map<String, FlickSet> = linkedMapOf(
        "あ" to FlickSet("あ", "い", "う", "え", "お"),
        "か" to FlickSet("か", "き", "く", "け", "こ"),
        "さ" to FlickSet("さ", "し", "す", "せ", "そ"),
        "た" to FlickSet("た", "ち", "つ", "て", "と"),
        "な" to FlickSet("な", "に", "ぬ", "ね", "の"),
        "は" to FlickSet("は", "ひ", "ふ", "へ", "ほ"),
        "ま" to FlickSet("ま", "み", "む", "め", "も"),
        "や" to FlickSet("や", "「", "ゆ", "」", "よ"),
        "ら" to FlickSet("ら", "り", "る", "れ", "ろ"),
        "わ" to FlickSet("わ", "を", "ん", "ー", "〜"),
        "、。" to FlickSet("、", "。", "？", "！", "…")
    )

    fun output(label: String, direction: FlickDirection): String =
        keys[label]?.value(direction).orEmpty()

    /** Center modifier: small kana first when available, then voiced variants. */
    fun modifyLast(text: String): String {
        if (text.isEmpty()) return text
        val last = text.last()
        val replacement = modifierCycles.firstNotNullOfOrNull { cycle ->
            val index = cycle.indexOf(last)
            if (index >= 0) cycle[(index + 1) % cycle.size] else null
        } ?: return text
        return text.dropLast(1) + replacement
    }

    /** Left flick on the modifier key: directly apply dakuten where possible. */
    fun applyDakuten(text: String): String = applyDirect(text, dakutenMap)

    /** Right flick on the modifier key: directly apply handakuten where possible. */
    fun applyHandakuten(text: String): String = applyDirect(text, handakutenMap)

    private fun applyDirect(text: String, table: Map<Char, Char>): String {
        if (text.isEmpty()) return text
        val replacement = table[text.last()] ?: return text
        return text.dropLast(1) + replacement
    }

    private val dakutenMap = buildMap {
        listOf(
            "かが", "きぎ", "くぐ", "けげ", "こご",
            "さざ", "しじ", "すず", "せぜ", "そぞ",
            "ただ", "ちぢ", "てで", "とど"
        ).forEach { pair -> put(pair[0], pair[1]); put(pair[1], pair[1]) }
        put('つ', 'づ'); put('っ', 'づ'); put('づ', 'づ')
        put('う', 'ゔ'); put('ぅ', 'ゔ'); put('ゔ', 'ゔ')
        listOf("はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ").forEach { group ->
            put(group[0], group[1]); put(group[1], group[1]); put(group[2], group[1])
        }
    }

    private val handakutenMap = buildMap {
        listOf("はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ").forEach { group ->
            put(group[0], group[2]); put(group[1], group[2]); put(group[2], group[2])
        }
    }

    private val modifierCycles = listOf(
        charArrayOf('か', 'が'), charArrayOf('き', 'ぎ'), charArrayOf('く', 'ぐ'),
        charArrayOf('け', 'げ'), charArrayOf('こ', 'ご'),
        charArrayOf('さ', 'ざ'), charArrayOf('し', 'じ'), charArrayOf('す', 'ず'),
        charArrayOf('せ', 'ぜ'), charArrayOf('そ', 'ぞ'),
        charArrayOf('た', 'だ'), charArrayOf('ち', 'ぢ'), charArrayOf('つ', 'っ', 'づ'),
        charArrayOf('て', 'で'), charArrayOf('と', 'ど'),
        charArrayOf('は', 'ば', 'ぱ'), charArrayOf('ひ', 'び', 'ぴ'), charArrayOf('ふ', 'ぶ', 'ぷ'),
        charArrayOf('へ', 'べ', 'ぺ'), charArrayOf('ほ', 'ぼ', 'ぽ'),
        charArrayOf('あ', 'ぁ'), charArrayOf('い', 'ぃ'), charArrayOf('う', 'ぅ', 'ゔ'),
        charArrayOf('え', 'ぇ'), charArrayOf('お', 'ぉ'),
        charArrayOf('や', 'ゃ'), charArrayOf('ゆ', 'ゅ'), charArrayOf('よ', 'ょ'),
        charArrayOf('わ', 'ゎ')
    )
}
