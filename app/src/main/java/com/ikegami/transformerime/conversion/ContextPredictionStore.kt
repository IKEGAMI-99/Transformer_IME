package com.ikegami.transformerime.conversion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.ln

/** Read-only context -> continuation database generated from Japanese Tatoeba sentences. */
object ContextPredictionStore {
    data class Suggestion(
        val continuation: String,
        val score: Double,
        val matchedContext: String,
        val frequency: Int
    )

    private const val ASSET_NAME = "context_predictions.db"
    private const val LOCAL_NAME = "context_predictions_v1.db"

    @Volatile
    private var database: SQLiteDatabase? = null

    @Volatile
    var isReady: Boolean = false
        private set

    fun initialize(context: Context) {
        if (database != null) return
        synchronized(this) {
            if (database != null) return
            runCatching {
                val target = File(context.noBackupFilesDir, LOCAL_NAME)
                if (!target.exists() || target.length() < 100_000L) {
                    val temp = File(context.noBackupFilesDir, "$LOCAL_NAME.tmp")
                    context.assets.open(ASSET_NAME).use { input ->
                        temp.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    if (target.exists()) target.delete()
                    check(temp.renameTo(target)) { "Unable to install context prediction DB" }
                }
                val opened = SQLiteDatabase.openDatabase(
                    target.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
                opened.rawQuery("SELECT value FROM metadata WHERE key='format_version'", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "1") { "Unsupported context DB format" }
                }
                database = opened
                isReady = true
            }.onFailure {
                isReady = false
            }
        }
    }

    /**
     * Match every suffix of the recent editor text up to 24 chars.  The DB contexts were built
     * from 1..3 actual Japanese word tokens, so the longest matching suffix is usually the most
     * informative.  Frequency prevents one-off corpus accidents from dominating.
     */
    fun predict(context: String, limit: Int = 14): List<Suggestion> {
        val db = database ?: return emptyList()
        val recent = context.takeLast(96)
        if (recent.isBlank()) return emptyList()

        val suffixes = (1..minOf(24, recent.length))
            .map { recent.takeLast(it) }
            .distinct()
        if (suffixes.isEmpty()) return emptyList()

        val placeholders = suffixes.joinToString(",") { "?" }
        val best = LinkedHashMap<String, Suggestion>()
        db.rawQuery(
            "SELECT context, continuation, freq FROM predictions WHERE context IN ($placeholders)",
            suffixes.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val matched = cursor.getString(0)
                val continuation = cursor.getString(1)
                val frequency = cursor.getInt(2)
                if (continuation.isBlank()) continue
                // Long exact context matters more; frequency still provides strong smoothing.
                val score = matched.length * 1.15 + ln(frequency.toDouble() + 1.0) * 2.0
                val suggestion = Suggestion(continuation, score, matched, frequency)
                val old = best[continuation]
                if (old == null || suggestion.score > old.score) best[continuation] = suggestion
            }
        }
        return best.values
            .sortedWith(compareByDescending<Suggestion> { it.score }
                .thenByDescending { it.matchedContext.length }
                .thenByDescending { it.frequency }
                .thenBy { it.continuation.length })
            .take(limit)
    }
}
