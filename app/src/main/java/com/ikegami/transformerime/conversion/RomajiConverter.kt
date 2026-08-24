package com.ikegami.transformerime.conversion

object RomajiConverter {
    private val table = mapOf(
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ", "te" to "て", "to" to "と",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ"
    )

    private val vowels = setOf('a', 'i', 'u', 'e', 'o')

    fun convert(raw: String): String {
        val s = raw.lowercase()
        val out = StringBuilder()
        var i = 0

        while (i < s.length) {
            val c = s[i]

            if (i + 1 < s.length && c == s[i + 1] && c !in vowels && c != 'n') {
                out.append('っ')
                i++
                continue
            }

            if (c == 'n') {
                if (i + 1 >= s.length) {
                    out.append('ん')
                    i++
                    continue
                }
                val next = s[i + 1]
                if (next == 'n') {
                    out.append('ん')
                    i++
                    continue
                }
                if (next !in vowels && next != 'y') {
                    out.append('ん')
                    i++
                    continue
                }
            }

            var matched = false
            for (len in 3 downTo 1) {
                if (i + len > s.length) continue
                val key = s.substring(i, i + len)
                val kana = table[key] ?: continue
                out.append(kana)
                i += len
                matched = true
                break
            }
            if (!matched) {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    fun toKatakana(hiragana: String): String = buildString {
        hiragana.forEach { ch ->
            append(if (ch.code in 0x3041..0x3096) (ch.code + 0x60).toChar() else ch)
        }
    }
}
