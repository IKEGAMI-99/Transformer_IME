package com.ikegami.transformerime.learning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.ln

/**
 * Small on-device personalization database.
 *
 * It never stores password-field input because the IME service simply does not call this class
 * while secureField is active. No network permission is required and nothing leaves the device.
 */
class UserLearningStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "user_learning.db",
    null,
    1
) {
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

    fun recordNext(context: String, surface: String) {
        if (context.isBlank() || surface.isBlank()) return
        contextKeys(context).forEach { key -> bump(KIND_NEXT, key, surface.take(128)) }
    }

    fun rankNext(context: String, candidates: List<String>): List<String> {
        if (context.isBlank() || candidates.size < 2) return candidates
        val keys = contextKeys(context)
        if (keys.isEmpty()) return candidates
        val combined = HashMap<String, Double>()
        keys.forEachIndexed { index, key ->
            val lengthWeight = (index + 1).toDouble()
            scoresFor(KIND_NEXT, listOf(key)).forEach { (surface, score) ->
                combined[surface] = (combined[surface] ?: 0.0) + score * lengthWeight
            }
        }
        return stableRank(candidates) { surface -> combined[surface] ?: 0.0 }
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
        val clean = context.replace(Regex("\\s+"), " ").trim().takeLast(64)
        if (clean.isEmpty()) return emptyList()
        return listOf(2, 4, 8, 12, 20)
            .map { n -> clean.takeLast(n.coerceAtMost(clean.length)) }
            .distinct()
    }

    private fun stableRank(candidates: List<String>, score: (String) -> Double): List<String> =
        candidates.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> { score(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }

    private fun pruneIfNeeded() {
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM learning", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (count <= MAX_ROWS) return
        writableDatabase.execSQL(
            "DELETE FROM learning WHERE rowid IN (SELECT rowid FROM learning ORDER BY last_used ASC LIMIT ?)",
            arrayOf((count - TARGET_ROWS).coerceAtLeast(1))
        )
    }

    companion object {
        private const val KIND_CONVERSION = "conversion"
        private const val KIND_NEXT = "next"
        private const val MAX_ROWS = 6000
        private const val TARGET_ROWS = 5000
    }
}
