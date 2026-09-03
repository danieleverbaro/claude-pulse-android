package com.ghayyath.claudepulse

import android.content.Context

/**
 * Fetches both providers and keeps the last good numbers per provider, so a
 * transient throttle on one side never blanks the other side's bars.
 */
object UsageRepository {

    private const val PREFS = "pulse_cache"

    fun fetch(context: Context): PulseSnapshot {
        val claude = ClaudeApi.fetch(context)
        val codex = CodexApi.fetch(context)
        store(context, claude, codex)
        return load(context)
    }

    private fun store(context: Context, claude: ProviderUsage, codex: ProviderUsage) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        writeProvider(edit, "c", claude)
        writeProvider(edit, "x", codex)
        if (claude.isOk || codex.isOk) edit.putLong("cached_at", System.currentTimeMillis())
        edit.apply()
    }

    private fun writeProvider(
        edit: android.content.SharedPreferences.Editor,
        key: String,
        usage: ProviderUsage
    ) {
        // Empty string = healthy. Writing null would drop the key, which reads back
        // as "never fetched".
        edit.putString("${key}_err", usage.error ?: "")
        if (!usage.isOk) return
        edit.putInt("${key}_s_pct", usage.session.percent)
        edit.putLong("${key}_s_reset", usage.session.resetsAtMs ?: 0L)
        edit.putInt("${key}_w_pct", usage.weekly.percent)
        edit.putLong("${key}_w_reset", usage.weekly.resetsAtMs ?: 0L)
        edit.putString("${key}_plan", usage.planLabel)
        edit.putBoolean("${key}_has_data", true)
    }

    fun load(context: Context): PulseSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PulseSnapshot(
            claude = readProvider(prefs, "c"),
            codex = readProvider(prefs, "x"),
            cachedAtMs = prefs.getLong("cached_at", 0L)
        )
    }

    private fun readProvider(prefs: android.content.SharedPreferences, key: String): ProviderUsage {
        val error = prefs.getString("${key}_err", Errors.NOT_CONNECTED)
            ?.takeIf { it.isNotEmpty() }
        val hasData = prefs.getBoolean("${key}_has_data", false)
        // Without any stored numbers there is nothing to show but the error.
        if (!hasData) return ProviderUsage(error = error ?: Errors.NOT_CONNECTED)
        return ProviderUsage(
            session = LimitWindow(
                prefs.getInt("${key}_s_pct", 0),
                prefs.getLong("${key}_s_reset", 0L).takeIf { it > 0 }
            ),
            weekly = LimitWindow(
                prefs.getInt("${key}_w_pct", 0),
                prefs.getLong("${key}_w_reset", 0L).takeIf { it > 0 }
            ),
            planLabel = prefs.getString("${key}_plan", "") ?: "",
            error = error
        )
    }
}
