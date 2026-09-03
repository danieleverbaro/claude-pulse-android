package com.ghayyath.claudepulse

import android.content.Context
import org.json.JSONObject

/**
 * Codex usage comes from the same endpoint the CLI polls for its `/status`
 * view: primary window = the 5h session, secondary = the weekly quota.
 */
object CodexApi {

    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

    fun fetch(context: Context): ProviderUsage {
        if (!TokenStore.codex.hasCredentials(context)) return ProviderUsage.failed(Errors.NOT_CONNECTED)
        val token = TokenStore.codex.getAccessToken(context) ?: return ProviderUsage.failed(Errors.AUTH)

        val result = call(token)
        if (result.error != Errors.AUTH) return result

        val fresh = TokenStore.codex.refreshAccessToken(context) ?: return ProviderUsage.failed(Errors.AUTH)
        return call(fresh)
    }

    fun fetchWith(accessToken: String): ProviderUsage = call(accessToken)

    private fun call(accessToken: String): ProviderUsage {
        return when (val res = Http.getJson(USAGE_URL, mapOf("Authorization" to "Bearer $accessToken"))) {
            is HttpResult.Ok -> parse(res.json)
            is HttpResult.Failed -> ProviderUsage.failed(res.error)
        }
    }

    fun parse(json: JSONObject): ProviderUsage {
        val rate = json.optJSONObject("rate_limit")
        return ProviderUsage(
            session = window(rate?.optJSONObject("primary_window")),
            weekly = window(rate?.optJSONObject("secondary_window")),
            planLabel = json.optString("plan_type", "").replaceFirstChar { it.uppercase() }
        )
    }

    /** `reset_at` is epoch seconds here, unlike Anthropic's ISO strings. */
    private fun window(obj: JSONObject?): LimitWindow {
        if (obj == null) return LimitWindow()
        val resetAt = obj.optLong("reset_at", 0L)
        return LimitWindow(
            percent = obj.optDouble("used_percent", 0.0).toInt(),
            resetsAtMs = if (resetAt > 0) resetAt * 1000 else null
        )
    }
}
