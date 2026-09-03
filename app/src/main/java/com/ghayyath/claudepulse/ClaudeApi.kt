package com.ghayyath.claudepulse

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object ClaudeApi {

    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val API_BETA = "oauth-2025-04-20"

    fun fetch(context: Context): ProviderUsage {
        if (!TokenStore.claude.hasCredentials(context)) return ProviderUsage.failed(Errors.NOT_CONNECTED)
        val token = TokenStore.claude.getAccessToken(context) ?: return ProviderUsage.failed(Errors.AUTH)

        val result = call(token)
        if (result.error != Errors.AUTH) return result

        val fresh = TokenStore.claude.refreshAccessToken(context) ?: return ProviderUsage.failed(Errors.AUTH)
        return call(fresh)
    }

    fun fetchWith(accessToken: String): ProviderUsage = call(accessToken)

    private fun call(accessToken: String): ProviderUsage {
        return when (val res = Http.getJson(
            USAGE_URL,
            mapOf(
                "Authorization" to "Bearer $accessToken",
                "anthropic-beta" to API_BETA,
                "Content-Type" to "application/json"
            )
        )) {
            is HttpResult.Ok -> parse(res.json)
            is HttpResult.Failed -> ProviderUsage.failed(res.error)
        }
    }

    /**
     * The endpoint serves both shapes: the self-describing `limits[]` array
     * (current) and the flat `five_hour` / `seven_day` objects (legacy, still
     * populated). Prefer `limits[]` and fall back, so a further schema move
     * that empties the flat fields doesn't blank the widget.
     */
    fun parse(json: JSONObject): ProviderUsage {
        var session: LimitWindow? = null
        var weekly: LimitWindow? = null

        val limits = json.optJSONArray("limits")
        if (limits != null) {
            for (i in 0 until limits.length()) {
                val limit = limits.optJSONObject(i) ?: continue
                val window = LimitWindow(
                    percent = limit.optDouble("percent", 0.0).toInt(),
                    resetsAtMs = parseIso(limit.optString("resets_at", null))
                )
                when (limit.optString("kind")) {
                    "session" -> session = window
                    "weekly_all" -> weekly = window
                }
            }
        }

        if (session == null) session = flatWindow(json.optJSONObject("five_hour"))
        if (weekly == null) weekly = flatWindow(json.optJSONObject("seven_day"))

        return ProviderUsage(
            session = session ?: LimitWindow(),
            weekly = weekly ?: LimitWindow(),
            planLabel = planLabel(json)
        )
    }

    private fun flatWindow(obj: JSONObject?): LimitWindow? {
        if (obj == null) return null
        return LimitWindow(
            percent = obj.optDouble("utilization", 0.0).toInt(),
            resetsAtMs = parseIso(obj.optString("resets_at", null))
        )
    }

    private fun planLabel(json: JSONObject): String {
        val extra = json.optJSONObject("extra_usage")
        if (extra != null && extra.optBoolean("is_enabled", false)) {
            return if (extra.optInt("monthly_limit", 0) >= 20000) "Max 20x" else "Max 5x"
        }
        val hasWindows = json.optJSONObject("five_hour") != null || json.optJSONArray("limits") != null
        return if (hasWindows) "Pro" else "Free"
    }

    /** "2026-09-03T19:29:59.883012+00:00" -> epoch millis. The API always speaks UTC. */
    private fun parseIso(value: String?): Long? {
        if (value.isNullOrEmpty() || value == "null") return null
        return try {
            val cleaned = value
                .replace(Regex("[+-]\\d{2}:?\\d{2}$"), "")
                .removeSuffix("Z")
                .substringBefore('.')
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(cleaned)?.time
        } catch (e: Exception) {
            null
        }
    }
}
