package com.ikegami.transformerime.conversion

/**
 * Tiny offline English predictor used by the QWERTY layout.
 * It intentionally stays simple: prefix completion + a few common bigrams + typo-distance fallback.
 * UserLearningStore is layered on top by the IME so personal words quickly outrank this base list.
 */
object EnglishPredictor {
    private val words = listOf(
        "a","about","after","again","all","also","always","am","an","and","android","another","any","app","are","around","as","at",
        "back","be","because","been","before","best","better","build","but","by",
        "camera","can","change","check","code","could","day","debug","did","do","does","done","email","even","every","feel","file","find","first","for","from",
        "get","github","give","go","going","good","great","had","has","have","he","hello","help","here","how","i","if","in","input","is","it","its","just",
        "keyboard","know","like","little","look","make","maybe","me","model","more","most","much","my","need","new","next","no","not","now","of","on","one","only","open","or","other","our","out","please","project","really","right","same","see","send","should","so","some","something","still","test","than","thank","thanks","that","the","their","them","then","there","these","they","think","this","time","to","today","tomorrow","too","try","up","use","very","want","was","way","we","well","were","what","when","where","which","will","with","work","would","yes","you","your",
        "ai","audio","candidate","context","conversion","dictionary","english","learning","memory","prediction","transformer","teradek","smallhd","cyanview"
    ).distinct().sorted()

    private val next = mapOf(
        "thank" to listOf("you"),
        "thank you" to listOf("for", "very", "so"),
        "thanks" to listOf("for", "again"),
        "please" to listOf("check", "let", "send"),
        "let" to listOf("me", "us"),
        "let me" to listOf("know", "check"),
        "i" to listOf("think", "will", "can", "have", "need", "want"),
        "we" to listOf("can", "will", "need", "have"),
        "can" to listOf("you", "we", "be"),
        "could" to listOf("you", "be"),
        "see" to listOf("you", "the"),
        "see you" to listOf("soon", "tomorrow"),
        "good" to listOf("morning", "job", "idea"),
        "looking" to listOf("good", "forward"),
        "for" to listOf("the", "your", "this"),
        "on" to listOf("the", "this"),
        "in" to listOf("the", "this"),
        "to" to listOf("the", "be", "use", "check"),
        "the" to listOf("app", "model", "keyboard", "file", "next"),
        "this" to listOf("is", "app", "model"),
        "it" to listOf("is", "looks", "works"),
        "looks" to listOf("good", "great"),
        "works" to listOf("well", "great")
    )

    fun suggestions(prefix: String, context: String, limit: Int = 8): List<String> {
        val p = prefix.lowercase()
        if (p.isBlank()) return nextWords(context, limit)
        val out = LinkedHashSet<String>()
        words.asSequence().filter { it.startsWith(p) && it != p }.take(limit * 2).forEach(out::add)
        if (p.length >= 3 && out.size < limit) {
            words.asSequence()
                .filter { kotlin.math.abs(it.length - p.length) <= 1 && editDistanceAtMostOne(p, it) }
                .take(limit)
                .forEach(out::add)
        }
        return out.take(limit)
    }

    fun nextWords(context: String, limit: Int = 8): List<String> {
        val clean = context.lowercase().replace(Regex("[^a-z' ]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return listOf("the", "i", "you", "we", "this", "please").take(limit)
        val tokens = clean.split(' ')
        val keys = listOfNotNull(
            tokens.takeLast(2).joinToString(" ").takeIf { tokens.size >= 2 },
            tokens.lastOrNull()
        )
        val out = LinkedHashSet<String>()
        keys.forEach { key -> next[key]?.forEach(out::add) }
        if (out.size < limit) listOf("the", "to", "and", "for", "is", "with", "this", "please").forEach(out::add)
        return out.take(limit)
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }
}
