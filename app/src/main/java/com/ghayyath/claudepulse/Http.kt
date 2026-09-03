package com.ghayyath.claudepulse

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Result of a plain GET: either a parsed body, or one of [Errors]. */
sealed class HttpResult {
    data class Ok(val json: JSONObject) : HttpResult()
    data class Failed(val error: String) : HttpResult()
}

object Http {

    fun getJson(url: String, headers: Map<String, String>): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode == 200) {
                HttpResult.Ok(JSONObject(conn.inputStream.bufferedReader().readText()))
            } else {
                HttpResult.Failed(classify(conn))
            }
        } catch (e: Exception) {
            HttpResult.Failed(Errors.OFFLINE)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Both APIs answer 429 with a JSON body typed `rate_limit_error`, and some
     * gateways hide it behind a different status — read the body before deciding,
     * so a temporary throttle is never reported as a dead token.
     */
    private fun classify(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code == 401 || code == 403) return Errors.AUTH
        if (code == 429) return Errors.RATE_LIMITED
        val body = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
            ?: return "HTTP $code"
        return try {
            val type = JSONObject(body).optJSONObject("error")?.optString("type")
            if (type == "rate_limit_error") Errors.RATE_LIMITED else "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
    }
}
