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
        // Gboard-like ya layout: up=ゆ, down=よ; brackets remain on left/right.
        "や" to FlickSet("や", "「", "ゆ", "」", "よ"),
        "ら" to FlickSet("ら", "り", "る", "れ", "ろ"),
        "わ" to FlickSet("わ", "を", "ん", "ー", "〜"),
        "、。" to FlickSet("、", "。", "？", "！", "…")
    )

    fun output(label: String, direction: FlickDirection): String =
        keys[label]?.value(direction).orEmpty()

    /** Cycles the last kana through dakuten / handakuten / small-kana variants. */
    fun modifyLast(text: String): String {
        if (text.isEmpty()) return text
        val last = text.last()
        val replacement = modifierCycles.firstNotNullOfOrNull { cycle ->
            val index = cycle.indexOf(last)
            if (index >= 0) cycle[(index + 1) % cycle.size] else null
        } ?: return text
        return text.dropLast(1) + replacement
    }

    private val modifierCycles = listOf(
        charArrayOf('か', 'が'), charArrayOf('き', 'ぎ'), charArrayOf('く', 'ぐ'),
        charArrayOf('け', 'げ'), charArrayOf('こ', 'ご'),
        charArrayOf('さ', 'ざ'), charArrayOf('し', 'じ'), charArrayOf('す', 'ず'),
        charArrayOf('せ', 'ぜ'), charArrayOf('そ', 'ぞ'),
        charArrayOf('た', 'だ'), charArrayOf('ち', 'ぢ'), charArrayOf('つ', 'づ', 'っ'),
        charArrayOf('て', 'で'), charArrayOf('と', 'ど'),
        charArrayOf('は', 'ば', 'ぱ'), charArrayOf('ひ', 'び', 'ぴ'), charArrayOf('ふ', 'ぶ', 'ぷ'),
        charArrayOf('へ', 'べ', 'ぺ'), charArrayOf('ほ', 'ぼ', 'ぽ'),
        charArrayOf('あ', 'ぁ'), charArrayOf('い', 'ぃ'), charArrayOf('う', 'ゔ', 'ぅ'),
        charArrayOf('え', 'ぇ'), charArrayOf('お', 'ぉ'),
        charArrayOf('や', 'ゃ'), charArrayOf('ゆ', 'ゅ'), charArrayOf('よ', 'ょ'),
        charArrayOf('わ', 'ゎ')
    )
}
