package com.nexconnect.app

import android.content.Context
import org.json.JSONObject

object AppSettingsStore {
    private const val PREFS = "nex_local_settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun capacity(context: Context): String = prefs(context).getString("capacity_ah", "100") ?: "100"
    fun saveCapacity(context: Context, value: String) { prefs(context).edit().putString("capacity_ah", value).apply() }

    fun protocol(context: Context): String = prefs(context).getString("inverter_protocol", "PYLON") ?: "PYLON"
    fun saveProtocol(context: Context, value: String) { prefs(context).edit().putString("inverter_protocol", value).apply() }

    fun modelKey(context: Context): String = prefs(context).getString("bms_model", "Daly|Smart BMS · 4S–24S family") ?: "Daly|Smart BMS · 4S–24S family"
    fun saveModelKey(context: Context, value: String) { prefs(context).edit().putString("bms_model", value).apply() }

    fun darkTheme(context: Context): Boolean = prefs(context).getBoolean("dark_theme", false)
    fun saveDarkTheme(context: Context, value: Boolean) { prefs(context).edit().putBoolean("dark_theme", value).apply() }

    fun parameters(context: Context): Map<String, String> {
        val raw = prefs(context).getString("parameters", "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveParameters(context: Context, values: Map<String, String>) {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        prefs(context).edit().putString("parameters", json.toString()).apply()
    }
}
