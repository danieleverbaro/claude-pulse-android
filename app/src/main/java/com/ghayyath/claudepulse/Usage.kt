package com.ghayyath.claudepulse

/** One rate-limit window: how much is burnt, and when it goes back to zero. */
data class LimitWindow(
    val percent: Int = 0,
    val resetsAtMs: Long? = null
)

/** Usage for one provider (Claude or Codex). [error] non-null means the numbers are unusable. */
data class ProviderUsage(
    val session: LimitWindow = LimitWindow(),
    val weekly: LimitWindow = LimitWindow(),
    val planLabel: String = "",
    val error: String? = null
) {
    val isOk: Boolean get() = error == null

    companion object {
        fun failed(error: String) = ProviderUsage(error = error)
    }
}

/** Everything the widget paints, for both providers. */
data class PulseSnapshot(
    val claude: ProviderUsage,
    val codex: ProviderUsage,
    val cachedAtMs: Long
) {
    /** True when at least one provider needs the user to paste a fresh token. */
    val needsAuth: Boolean
        get() = claude.error == Errors.AUTH || codex.error == Errors.AUTH
}

object Errors {
    const val AUTH = "auth_error"
    const val RATE_LIMITED = "rate_limited"
    const val OFFLINE = "offline"
    const val NOT_CONNECTED = "not_connected"
}
