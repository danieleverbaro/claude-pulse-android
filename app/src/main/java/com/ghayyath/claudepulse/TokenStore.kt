package com.ghayyath.claudepulse

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OAuth credentials for one provider. Both Anthropic and OpenAI speak the same
 * refresh-token dialect, so the only per-provider bits are the endpoint, the
 * client id and the optional scope.
 *
 * Anthropic rotates refresh tokens hard (the old one dies), OpenAI does not —
 * that is why the phone can keep its own copy of the Codex refresh token
 * without knocking the Pi's `codex` CLI offline.
 */
class TokenStore(
    private val prefsName: String,
    private val refreshUrl: String,
    private val clientId: String,
    private val scope: String? = null,
    private val defaultTtlSeconds: Long
) {
    companion object {
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        /** Kept on the original prefs name so an existing install stays logged in. */
        val claude = TokenStore(
            prefsName = "pulse_credentials",
            refreshUrl = "https://console.anthropic.com/v1/oauth/token",
            clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
            defaultTtlSeconds = 28_800L // 8h
        )

        val codex = TokenStore(
            prefsName = "pulse_credentials_codex",
            refreshUrl = "https://auth.openai.com/oauth/token",
            clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
            scope = "openid profile email",
            defaultTtlSeconds = 864_000L // 10d
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun hasCredentials(context: Context): Boolean {
        val p = prefs(context)
        return p.getString(KEY_REFRESH_TOKEN, null) != null || p.getString(KEY_ACCESS_TOKEN, null) != null
    }

    fun saveRefreshToken(context: Context, refreshToken: String) {
        prefs(context).edit()
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .remove(KEY_ACCESS_TOKEN)
            .putLong(KEY_EXPIRES_AT, 0)
            .commit()
    }

    /** Save an access token as-is — no refresh token, so it dies when it expires. */
    fun saveAccessToken(context: Context, accessToken: String) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + defaultTtlSeconds * 1000)
            .remove(KEY_REFRESH_TOKEN)
            .commit()
    }

    fun clear(context: Context) = prefs(context).edit().clear().commit()

    /** Everything this store holds, so a failed connect attempt can be rolled back. */
    data class Saved(val accessToken: String?, val refreshToken: String?, val expiresAt: Long)

    fun snapshot(context: Context): Saved {
        val p = prefs(context)
        return Saved(
            p.getString(KEY_ACCESS_TOKEN, null),
            p.getString(KEY_REFRESH_TOKEN, null),
            p.getLong(KEY_EXPIRES_AT, 0)
        )
    }

    fun restore(context: Context, saved: Saved) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, saved.accessToken)
            .putString(KEY_REFRESH_TOKEN, saved.refreshToken)
            .putLong(KEY_EXPIRES_AT, saved.expiresAt)
            .commit()
    }

    fun getAccessToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = p.getLong(KEY_EXPIRES_AT, 0)
        if (token != null && System.currentTimeMillis() < expiresAt - 300_000) return token
        return refreshAccessToken(context)
    }

    fun getMaskedToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(KEY_ACCESS_TOKEN, null)
            ?: p.getString(KEY_REFRESH_TOKEN, null)
            ?: return null
        if (token.length < 8) return "****"
        return "${token.take(4)}...${token.takeLast(4)}"
    }

    @Synchronized
    fun refreshAccessToken(context: Context): String? {
        val p = prefs(context)

        // Another thread may have refreshed while we waited on the lock.
        val current = p.getString(KEY_ACCESS_TOKEN, null)
        val currentExpiry = p.getLong(KEY_EXPIRES_AT, 0)
        if (current != null && System.currentTimeMillis() < currentExpiry - 300_000) return current

        val refreshToken = p.getString(KEY_REFRESH_TOKEN, null) ?: return null

        val conn = URL(refreshUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            val body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
                put("client_id", clientId)
                scope?.let { put("scope", it) }
            }
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

            if (conn.responseCode == 200) {
                val response = JSONObject(conn.inputStream.bufferedReader().readText())
                val newAccessToken = response.getString("access_token")
                val newRefreshToken = response.optString("refresh_token", refreshToken)
                val expiresIn = response.optLong("expires_in", defaultTtlSeconds)

                p.edit()
                    .putString(KEY_ACCESS_TOKEN, newAccessToken)
                    .putString(KEY_REFRESH_TOKEN, newRefreshToken)
                    .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
                    .commit()

                newAccessToken
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
