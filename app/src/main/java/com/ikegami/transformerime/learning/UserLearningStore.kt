package com.ikegami.transformerime.learning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.ln

/**
 * On-device personalization and retrieval store.
 *
 * The IME never calls record methods for secure/password fields. All data remains in this local
 * SQLite database. Besides reranking, v0.10 can retrieve learned continuations as a small personal
 * RAG source and feed them back into the candidate pool before neural ranking.
 */
class UserLearningStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "user_learning.db",
    null,
    1
) {
    data class Entry(
        val kind: String,
        val keyText: String,
        val surface: String,
        val useCount: Int,
        val lastUsed: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE learning (
                kind TEXT NOT NULL,
                key_text TEXT NOT NULL,
                surface TEXT NOT NULL,
                use_count INTEGER NOT NULL DEFAULT 0,
                last_used INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(kind, key_text, surface)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX learning_lookup ON learning(kind, key_text)")
        db.execSQL("CREATE INDEX learning_recent ON learning(last_used DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun recordConversion(reading: String, surface: String) {
        if (reading.isBlank() || surface.isBlank()) return
        bump(KIND_CONVERSION, reading.take(64), surface.take(128))
    }

    fun rankConversions(reading: String, candidates: List<String>): List<String> {
        if (reading.isBlank() || candidates.size < 2) return candidates
        val scores = scoresFor(KIND_CONVERSION, listOf(reading.take(64)))
        return stableRank(candidates) { surface -> scores[surface] ?: 0.0 }
    }

    /** Personal-RAG retrieval for the current reading. */
    fun retrieveConversions(reading: String, limit: Int = 6): List<String> {
        if (reading.isBlank()) return emptyList()
        return scoredItems(KIND_CONVERSION, listOf(reading.take(64)))
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(limit)
    }

    fun recordNext(context: String, surface: String) {
        if (context.isBlank() || surface.isBlank()) return
        contextKeys(context).forEach { key -> bump(KIND_NEXT, key, surface.take(128)) }
    }

    /**
     * Records ordinary committed text as a memory edge too, not only explicit next-prediction taps.
     * This makes the RAG useful even when the user types normally and never taps a prediction.
     */
    fun recordCommitted(contextBefore: String, committed: String) {
        if (contextBefore.isBlank() || committed.isBlank()) return
        contextKeys(contextBefore).forEach { key -> bump(KIND_MEMORY, key, committed.take(128)) }
    }

    fun rankNext(context: String, candidates: List<String>): List<String> {
        if (context.isBlank() || candidates.size < 2) return candidates
        val combined = combinedContextScores(context)
        return stableRank(candidates) { surface -> combined[surface] ?: 0.0 }
    }

    /** Personal-RAG retrieval from both explicit next selections and normal committed text. */
    fun retrieveNext(context: String, limit: Int = 8): List<String> {
        if (context.isBlank()) return emptyList()
        return combinedContextScores(context)
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
    }

    fun recordEnglish(prefix: String, word: String, context: String) {
        if (word.isBlank()) return
        if (prefix.isNotBlank()) bump(KIND_ENGLISH, prefix.lowercase().take(48), word.take(96))
        if (context.isNotBlank()) contextKeys(context.lowercase()).forEach {
            bump(KIND_ENGLISH_NEXT, it, word.take(96))
        }
    }

    fun rankEnglish(prefix: String, context: String, candidates: List<String>): List<String> {
        if (candidates.size < 2) return candidates
        val scores = HashMap<String, Double>()
        if (prefix.isNotBlank()) {
            scoresFor(KIND_ENGLISH, listOf(prefix.lowercase().take(48))).forEach { (word, score) ->
                scores[word] = (scores[word] ?: 0.0) + score * 2.0
            }
        }
        if (context.isNotBlank()) {
            val keys = contextKeys(context.lowercase())
            keys.forEachIndexed { index, key ->
                scoresFor(KIND_ENGLISH_NEXT, listOf(key)).forEach { (word, score) ->
                    scores[word] = (scores[word] ?: 0.0) + score * (index + 1)
                }
            }
        }
        return stableRank(candidates) { scores[it] ?: 0.0 }
    }

    fun retrieveEnglish(prefix: String, context: String, limit: Int = 8): List<String> {
        val pool = LinkedHashMap<String, Double>()
        if (prefix.isNotBlank()) scoredItems(KIND_ENGLISH, listOf(prefix.lowercase().take(48))).forEach {
            pool[it.first] = maxOf(pool[it.first] ?: 0.0, it.second)
        }
        if (context.isNotBlank()) contextKeys(context.lowercase()).forEachIndexed { index, key ->
            scoredItems(KIND_ENGLISH_NEXT, listOf(key)).forEach {
                pool[it.first] = (pool[it.first] ?: 0.0) + it.second * (index + 1)
            }
        }
        return pool.entries.sortedByDescending { it.value }.map { it.key }.take(limit)
    }

    fun listEntries(limit: Int = 300): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT kind, key_text, surface, use_count, last_used FROM learning ORDER BY last_used DESC, use_count DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 2000).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += Entry(
                    kind = cursor.getString(0),
                    keyText = cursor.getString(1),
                    surface = cursor.getString(2),
                    useCount = cursor.getInt(3),
                    lastUsed = cursor.getLong(4)
                )
            }
        }
        return out
    }

    fun clearAll() {
        writableDatabase.delete("learning", null, null)
    }

    fun entryCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM learning", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private fun combinedContextScores(context: String): Map<String, Double> {
        val keys = contextKeys(context)
        if (keys.isEmpty()) return emptyMap()
        val combined = HashMap<String, Double>()
        keys.forEachIndexed { index, key ->
            val lengthWeight = (index + 1).toDouble()
            listOf(KIND_NEXT to 1.4, KIND_MEMORY to 1.0).forEach { (kind, kindWeight) ->
                scoresFor(kind, listOf(key)).forEach { (surface, score) ->
                    combined[surface] = (combined[surface] ?: 0.0) + score * lengthWeight * kindWeight
                }
            }
        }
        return combined
    }

    private fun bump(kind: String, key: String, surface: String) {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            """
            INSERT INTO learning(kind, key_text, surface, use_count, last_used)
            VALUES(?, ?, ?, 1, ?)
            ON CONFLICT(kind, key_text, surface)
            DO UPDATE SET use_count = use_count + 1, last_used = excluded.last_used
            """.trimIndent(),
            arrayOf(kind, key, surface, now)
        )
        pruneIfNeeded()
    }

    private fun scoredItems(kind: String, keys: List<String>): List<Pair<String, Double>> =
        scoresFor(kind, keys).entries.map { it.key to it.value }

    private fun scoresFor(kind: String, keys: List<String>): Map<String, Double> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>()
        val now = System.currentTimeMillis()
        keys.forEach { key ->
            readableDatabase.rawQuery(
                "SELECT surface, use_count, last_used FROM learning WHERE kind=? AND key_text=?",
                arrayOf(kind, key)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val surface = cursor.getString(0)
                    val count = cursor.getInt(1).coerceAtLeast(1)
                    val last = cursor.getLong(2)
                    val ageDays = ((now - last).coerceAtLeast(0L) / 86_400_000.0)
                    val frequency = ln(1.0 + count.toDouble()) * 4.0
                    val recency = 3.0 / (1.0 + ageDays / 14.0)
                    out[surface] = (out[surface] ?: 0.0) + frequency + recency
                }
            }
        }
        return out
    }

    private fun contextKeys(context: String): List<String> {
        val clean = context.replace(Regex("\\s+"), " ").trim().takeLast(96)
        if (clean.isEmpty()) return emptyList()
        return listOf(2, 4, 8, 12, 20, 32, 48)
            .map { n -> clean.takeLast(n.coerceAtMost(clean.length)) }
            .distinct()
    }

    private fun stableRank(candidates: List<String>, score: (String) -> Double): List<String> =
        candidates.withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { score(it.value) }.thenBy { it.index })
            .map { it.value }

    private fun pruneIfNeeded() {
        val count = entryCount()
        if (count <= MAX_ROWS) return
        writableDatabase.execSQL(
            "DELETE FROM learning WHERE rowid IN (SELECT rowid FROM learning ORDER BY last_used ASC LIMIT ?)",
            arrayOf((count - TARGET_ROWS).coerceAtLeast(1))
        )
    }

    companion object {
        const val KIND_CONVERSION = "conversion"
        const val KIND_NEXT = "next"
        const val KIND_MEMORY = "memory"
        const val KIND_ENGLISH = "english"
        const val KIND_ENGLISH_NEXT = "english_next"
        private const val MAX_ROWS = 12_000
        private const val TARGET_ROWS = 10_000
    }
}
