package org.adaway.ui.compose

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.IOException

internal object PortableUiBackup {
    private val ints = setOf("theme_mode", "chart_style", "chart_range")
    private val booleans = setOf("chart_rate", "chart_donut", "chart_trend", "chart_conn",
        "chart_apps", "chart_certs", "chart_top_hosts", "chart_qlog")

    fun decode(json: JSONObject): Map<String, Any> {
        val section = json.optJSONObject(PREFS_GENERAL) ?: throw IOException("Missing UI settings")
        val result = mutableMapOf<String, Any>()
        for (key in ints + booleans) {
            if (!section.has(key)) continue
            val value = section.get(key)
            if (key in ints) {
                if (value !is Int || value !in 0..2) throw IOException("Invalid setting: $key")
            } else if (value !is Boolean) throw IOException("Invalid setting: $key")
            result[key] = value
        }
        if (result.isEmpty()) throw IOException("No supported UI settings")
        return result
    }

    fun export(context: Context, uri: Uri) {
        val section = JSONObject()
        val prefs = context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        for (key in ints) section.put(key, prefs.getInt(key, 0).coerceIn(0, 2))
        for (key in booleans) section.put(key, prefs.getBoolean(key, true))
        val json = JSONObject().put("format", "adblock-ui-v1").put(PREFS_GENERAL, section)
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Cannot open output")
    }

    fun restore(context: Context, uri: Uri) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val out = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (out.length + count > 2 * 1024 * 1024) throw IOException("Backup too large")
                out.append(buffer, 0, count)
            }
            out.toString()
        } ?: throw IOException("Cannot open backup")
        val values = decode(JSONObject(text))
        val editor = context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE).edit()
        for ((key, value) in values) when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
        }
        if (!editor.commit()) throw IOException("Could not confirm settings write")
    }
}
