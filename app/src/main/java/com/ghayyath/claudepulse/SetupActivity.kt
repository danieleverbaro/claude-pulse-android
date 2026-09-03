package com.ghayyath.claudepulse

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SetupActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    private enum class Provider { CLAUDE, CODEX }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val tokenInput = findViewById<EditText>(R.id.token_input)
        val connectButton = findViewById<Button>(R.id.connect_button)
        val statusText = findViewById<TextView>(R.id.status_text)
        val errorBanner = findViewById<LinearLayout>(R.id.error_banner)

        statusText.visibility = View.VISIBLE
        statusText.text = "Checking..."
        statusText.setTextColor(0xFFAAAAAA.toInt())

        val appContext = applicationContext
        executor.execute {
            val snapshot = UsageRepository.fetch(appContext)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                showStatus(statusText, errorBanner, snapshot)
                connectButton.text = if (anyConnected()) "Update Token" else "Connect"
            }
        }

        connectButton.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isEmpty()) {
                statusText.text = "Paste a Claude or Codex token first"
                statusText.setTextColor(0xFFF44336.toInt())
                return@setOnClickListener
            }

            connectButton.isEnabled = false
            statusText.text = "Connecting..."
            statusText.setTextColor(0xFFAAAAAA.toInt())

            executor.execute {
                val provider = connect(token)
                val snapshot = UsageRepository.fetch(appContext)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    connectButton.isEnabled = true
                    if (provider == null) {
                        statusText.text = "Token not accepted by either Claude or Codex."
                        statusText.setTextColor(0xFFF44336.toInt())
                        return@runOnUiThread
                    }
                    tokenInput.text.clear()
                    connectButton.text = "Update Token"
                    showStatus(statusText, errorBanner, snapshot)
                    triggerWidgetUpdate()
                    ensurePeriodicRefresh()
                }
            }
        }
    }

    /**
     * Tokens are self-identifying by prefix — Anthropic issues `sk-ant-…`,
     * OpenAI `rt.…` for refresh tokens and a JWT for access tokens — so one
     * input field is enough. Unknown shapes are simply tried on both.
     */
    private fun detect(token: String): Provider? = when {
        token.startsWith("sk-ant-") -> Provider.CLAUDE
        token.startsWith("rt.") || token.startsWith("eyJ") -> Provider.CODEX
        else -> null
    }

    /** Returns the provider the token was accepted by, or null. */
    private fun connect(token: String): Provider? {
        val order = when (detect(token)) {
            Provider.CLAUDE -> listOf(Provider.CLAUDE)
            Provider.CODEX -> listOf(Provider.CODEX)
            null -> listOf(Provider.CLAUDE, Provider.CODEX)
        }
        for (provider in order) {
            if (tryConnect(provider, token)) return provider
        }
        return null
    }

    private fun tryConnect(provider: Provider, token: String): Boolean {
        val context = applicationContext
        val store = if (provider == Provider.CLAUDE) TokenStore.claude else TokenStore.codex

        // An access token works straight away; a refresh token only after a round trip.
        val direct = if (provider == Provider.CLAUDE) ClaudeApi.fetchWith(token) else CodexApi.fetchWith(token)
        if (direct.isOk) {
            store.saveAccessToken(context, token)
            return true
        }

        val previous = store.snapshot(context)
        store.saveRefreshToken(context, token)
        val viaRefresh = if (provider == Provider.CLAUDE) ClaudeApi.fetch(context) else CodexApi.fetch(context)
        if (viaRefresh.isOk) return true

        // Never let a rejected token evict credentials that were working.
        store.restore(context, previous)
        return false
    }

    private fun anyConnected(): Boolean =
        TokenStore.claude.hasCredentials(this) || TokenStore.codex.hasCredentials(this)

    private fun showStatus(statusText: TextView, errorBanner: LinearLayout, snapshot: PulseSnapshot) {
        statusText.text = "Claude: ${describe(snapshot.claude)}\nCodex: ${describe(snapshot.codex)}"
        statusText.setTextColor(
            if (snapshot.claude.isOk && snapshot.codex.isOk) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt()
        )
        errorBanner.visibility = if (snapshot.needsAuth) View.VISIBLE else View.GONE
    }

    private fun describe(usage: ProviderUsage): String = when (usage.error) {
        null -> "connected ✔  session ${usage.session.percent}% · weekly ${usage.weekly.percent}%"
        Errors.AUTH -> "token expired — paste a new one"
        Errors.NOT_CONNECTED -> "not connected"
        Errors.RATE_LIMITED -> "connected ✔ (rate limited, retrying later)"
        Errors.OFFLINE -> "could not verify (offline?)"
        else -> usage.error ?: "unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun ensurePeriodicRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "pulse_periodic_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    private fun triggerWidgetUpdate() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val widgetComponent = ComponentName(this, PulseWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
        if (widgetIds.isNotEmpty()) {
            val intent = Intent(this, PulseWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            }
            sendBroadcast(intent)
        }
    }
}
