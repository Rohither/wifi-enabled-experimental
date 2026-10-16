package com.nexconnect.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.time.Instant

data class BatteryHistoryPoint(
    val timestampMillis: Long,
    val voltageV: Double,
    val currentA: Double,
    val socPercent: Double,
    val mode: String
)

private class BatteryHistoryDatabase(context: Context) : SQLiteOpenHelper(context, "nex_battery_history.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (timestamp INTEGER NOT NULL, device_slot INTEGER NOT NULL DEFAULT 0, voltage REAL NOT NULL, current REAL NOT NULL, soc REAL NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(device_slot, timestamp))")
        db.execSQL("CREATE INDEX samples_slot_timestamp_idx ON samples(device_slot, timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE samples RENAME TO samples_v1")
            onCreate(db)
            db.execSQL("INSERT INTO samples(timestamp, device_slot, voltage, current, soc, mode) SELECT timestamp, 0, voltage, current, soc, mode FROM samples_v1")
            db.execSQL("DROP TABLE samples_v1")
        }
    }
}

object BatteryHistoryStore {
    private const val PREFS = "nex_battery_history"
    private const val KEY_POINTS = "points"
    private const val KEY_MIGRATED = "migrated_to_sqlite"
    private const val MAX_RECENT_POINTS = 20_000
    private const val RETENTION_MILLIS = 366L * 24 * 60 * 60 * 1000
    @Volatile private var database: BatteryHistoryDatabase? = null

    private fun db(context: Context): BatteryHistoryDatabase = database ?: synchronized(this) {
        database ?: BatteryHistoryDatabase(context.applicationContext).also { database = it }
    }

    @Synchronized
    private fun migrateOldHistory(context: Context, helper: BatteryHistoryDatabase) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val raw = prefs.getString(KEY_POINTS, "[]") ?: "[]"
        val oldPoints = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val v = item.optDouble("v", Double.NaN)
                val a = item.optDouble("a", Double.NaN)
                val soc = item.optDouble("soc", Double.NaN)
                if (!v.isFinite() || !a.isFinite() || !soc.isFinite()) null
                else BatteryHistoryPoint(item.optLong("t"), v, a, soc, item.optString("mode", "Unknown"))
            }
        }.getOrDefault(emptyList())
        val writable = helper.writableDatabase
        writable.beginTransaction()
        try {
            oldPoints.forEach { insert(writable, it) }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        prefs.edit().remove(KEY_POINTS).putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun insert(db: SQLiteDatabase, point: BatteryHistoryPoint, deviceSlot: Int = 0) {
        val values = android.content.ContentValues().apply {
            put("timestamp", point.timestampMillis)
            put("device_slot", deviceSlot)
            put("voltage", point.voltageV)
            put("current", point.currentA)
            put("soc", point.socPercent)
            put("mode", point.mode)
        }
        db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun load(context: Context, deviceSlot: Int = 0): List<BatteryHistoryPoint> {
        val helper = db(context)
        migrateOldHistory(context, helper)
        val result = mutableListOf<BatteryHistoryPoint>()
        helper.readableDatabase.rawQuery(
            "SELECT timestamp, voltage, current, soc, mode FROM samples WHERE device_slot = ? ORDER BY timestamp DESC LIMIT ?",
            arrayOf(deviceSlot.toString(), MAX_RECENT_POINTS.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result += BatteryHistoryPoint(cursor.getLong(0), cursor.getDouble(1), cursor.getDouble(2), cursor.getDouble(3), cursor.getString(4))
        }
        return result.asReversed()
    }

    fun loadBetween(context: Context, startMillis: Long, endMillis: Long, deviceSlot: Int = 0): List<BatteryHistoryPoint> {
        val helper = db(context)
        migrateOldHistory(context, helper)
        val result = mutableListOf<BatteryHistoryPoint>()
        helper.readableDatabase.rawQuery(
            "SELECT timestamp, voltage, current, soc, mode FROM samples WHERE timestamp >= ? AND timestamp < ? AND device_slot = ? ORDER BY timestamp",
            arrayOf(startMillis.toString(), endMillis.toString(), deviceSlot.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result += BatteryHistoryPoint(cursor.getLong(0), cursor.getDouble(1), cursor.getDouble(2), cursor.getDouble(3), cursor.getString(4))
        }
        return result
    }

    fun count(context: Context, deviceSlot: Int = 0): Int {
        val helper = db(context)
        migrateOldHistory(context, helper)
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM samples WHERE device_slot = ?", arrayOf(deviceSlot.toString())).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun bounds(context: Context, deviceSlot: Int = 0): Pair<Long?, Long?> {
        val helper = db(context)
        migrateOldHistory(context, helper)
        helper.readableDatabase.rawQuery("SELECT MIN(timestamp), MAX(timestamp) FROM samples WHERE device_slot = ?", arrayOf(deviceSlot.toString())).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) return null to null
            return cursor.getLong(0) to cursor.getLong(1)
        }
    }

    @Synchronized
    fun append(context: Context, point: BatteryHistoryPoint, deviceSlot: Int = 0) {
        val helper = db(context)
        migrateOldHistory(context, helper)
        helper.writableDatabase.beginTransaction()
        try {
            insert(helper.writableDatabase, point, deviceSlot)
            val cutoff = Instant.ofEpochMilli(point.timestampMillis).toEpochMilli() - RETENTION_MILLIS
            helper.writableDatabase.delete("samples", "timestamp < ?", arrayOf(cutoff.toString()))
            helper.writableDatabase.setTransactionSuccessful()
        } finally {
            helper.writableDatabase.endTransaction()
        }
    }

    fun clear(context: Context, deviceSlot: Int = 0) {
        val helper = db(context)
        migrateOldHistory(context, helper)
        helper.writableDatabase.delete("samples", "device_slot = ?", arrayOf(deviceSlot.toString()))
    }
}
