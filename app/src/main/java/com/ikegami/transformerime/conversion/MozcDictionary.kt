package com.ikegami.transformerime.conversion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Read-only compact dictionary generated from Mozc OSS data at CI build time.
 *
 * The APK contains only the compact SQLite database, not Mozc itself. Queries are batched for
 * all substrings needed by one conversion pass and cached so a flick keystroke normally causes
 * at most one small indexed SQLite query.
 */
object MozcDictionary {
    data class Entry(val surface: String, val cost: Float)

    private const val ASSET_NAME = "mozc_compact.db"
    private const val LOCAL_NAME = "mozc_compact_v4.db"
    private const val CACHE_LIMIT = 4096

    @Volatile
    private var database: SQLiteDatabase? = null

    @Volatile
    var isReady: Boolean = false
        private set

    private val cache = object : LinkedHashMap<String, List<Entry>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Entry>>?): Boolean =
            size > CACHE_LIMIT
    }

    fun initialize(context: Context) {
        if (database != null) return
        synchronized(this) {
            if (database != null) return
            runCatching {
                val target = File(context.noBackupFilesDir, LOCAL_NAME)
                if (!target.exists() || target.length() < 1_000_000L) {
                    val temp = File(context.noBackupFilesDir, "$LOCAL_NAME.tmp")
                    context.assets.open(ASSET_NAME).use { input ->
                        temp.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    if (target.exists()) target.delete()
                    check(temp.renameTo(target)) { "Unable to install compact Mozc dictionary" }
                }
                val opened = SQLiteDatabase.openDatabase(
                    target.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
                opened.rawQuery("SELECT value FROM metadata WHERE key='format_version'", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "1") { "Unsupported dictionary format" }
                }
                database = opened
                isReady = true
            }.onFailure {
                isReady = false
            }
        }
    }

    fun lookup(readings: Set<String>, maxCandidates: Int = 8): Map<String, List<Entry>> {
        val db = database ?: return emptyMap()
        if (readings.isEmpty()) return emptyMap()

        val result = HashMap<String, List<Entry>>(readings.size)
        val missing = ArrayList<String>()
        synchronized(cache) {
            readings.forEach { reading ->
                val cached = cache[reading]
                if (cached != null) result[reading] = cached else missing += reading
            }
        }

        if (missing.isNotEmpty()) {
            // SQLite commonly supports at least 999 bound variables. Keep plenty of headroom.
            missing.chunked(400).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val queried = HashMap<String, MutableList<Entry>>()
                db.rawQuery(
                    "SELECT reading, surface, cost FROM entries WHERE reading IN ($placeholders) ORDER BY reading, cost",
                    chunk.toTypedArray()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val reading = cursor.getString(0)
                        val list = queried.getOrPut(reading) { ArrayList(maxCandidates) }
                        if (list.size < maxCandidates) {
                            // Mozc word costs are positive and lower is better. Normalize them to a
                            // range that works with CandidateGenerator's beam-search penalties.
                            list += Entry(cursor.getString(1), cursor.getInt(2).coerceAtLeast(0) / 10_000f)
                        }
                    }
                }

                synchronized(cache) {
                    chunk.forEach { reading ->
                        val values = queried[reading]?.toList().orEmpty()
                        cache[reading] = values
                        result[reading] = values
                    }
                }
            }
        }
        return result
    }
}
