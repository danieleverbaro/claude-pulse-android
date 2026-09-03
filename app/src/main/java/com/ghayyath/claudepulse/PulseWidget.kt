package com.ghayyath.claudepulse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PulseWidget : AppWidgetProvider() {

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private const val COLOR_GREEN = 0xFF6ee7b7.toInt()  // 0-49%
        private const val COLOR_YELLOW = 0xFFFF9800.toInt() // 50-74%
        private const val COLOR_ORANGE = 0xFFFF5722.toInt() // 75-89%
        private const val COLOR_RED = 0xFFF44336.toInt()    // 90-100%
        private const val COLOR_MUTED = 0x66FFFFFF
        private const val ACTION_REFRESH = "com.ghayyath.claudepulse.ACTION_REFRESH"
        private const val WORK_NAME = "pulse_periodic_refresh"

        private const val CLAUDE_USAGE_PAGE = "https://claude.ai/settings/usage"
        private const val CODEX_USAGE_PAGE = "https://chatgpt.com/codex/settings/usage"

        // Height in dp below which a layout would be clipped, so the next
        // denser one takes over. Full needs 6 rows, medium 4, two-row 2.
        private const val HEIGHT_TWO_ROW = 95
        private const val HEIGHT_MEDIUM = 155
    }

    private enum class Tier { FULL, MEDIUM, TWO_ROW }

    /** How much room the reset countdown has on this layout. */
    private enum class ResetStyle { WITH_CLOCK, RELATIVE_ONLY }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, PulseWidget::class.java)
            onUpdate(context, appWidgetManager, appWidgetManager.getAppWidgetIds(widgetComponent))
            return
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        enqueuePeriodicRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        enqueuePeriodicRefresh(context)
        for (appWidgetId in appWidgetIds) {
            renderWidget(context, appWidgetManager, appWidgetId)
            scheduleRefresh(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        renderWidget(context, appWidgetManager, appWidgetId)
    }

    private fun enqueuePeriodicRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    private fun renderWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val snapshot = UsageRepository.load(context)
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)

        val tier = when {
            minHeight < HEIGHT_TWO_ROW -> Tier.TWO_ROW
            minHeight < HEIGHT_MEDIUM -> Tier.MEDIUM
            else -> Tier.FULL
        }

        val views = when (tier) {
            Tier.FULL -> RemoteViews(context.packageName, R.layout.widget_layout)
            Tier.MEDIUM -> RemoteViews(context.packageName, R.layout.widget_layout_small)
            Tier.TWO_ROW -> RemoteViews(context.packageName, R.layout.widget_layout_tiny)
        }
        val resetStyle = if (tier == Tier.TWO_ROW) ResetStyle.RELATIVE_ONLY else ResetStyle.WITH_CLOCK

        bindProvider(
            views, snapshot.claude, resetStyle,
            R.id.c_session_bar, R.id.c_session_pct, R.id.c_session_reset,
            R.id.c_weekly_bar, R.id.c_weekly_pct, R.id.c_weekly_reset
        )
        bindProvider(
            views, snapshot.codex, resetStyle,
            R.id.x_session_bar, R.id.x_session_pct, R.id.x_session_reset,
            R.id.x_weekly_bar, R.id.x_weekly_pct, R.id.x_weekly_reset
        )

        if (tier == Tier.FULL) {
            views.setTextViewText(R.id.claude_plan, statusLine(snapshot.claude))
            views.setTextColor(R.id.claude_plan, statusColor(snapshot.claude))
            views.setTextViewText(R.id.codex_plan, statusLine(snapshot.codex))
            views.setTextColor(R.id.codex_plan, statusColor(snapshot.codex))
            views.setTextViewText(R.id.updated_ago, formatTimeSince(snapshot.cachedAtMs))

            views.setOnClickPendingIntent(R.id.refresh_button, refreshIntent(context))
            views.setOnClickPendingIntent(R.id.claude_tag, browserIntent(context, 2, CLAUDE_USAGE_PAGE))
            views.setOnClickPendingIntent(R.id.codex_tag, browserIntent(context, 3, CODEX_USAGE_PAGE))
        }

        // Body tap: recover a dead token when there is one, otherwise refresh.
        views.setOnClickPendingIntent(
            R.id.widget_root,
            if (snapshot.needsAuth) setupIntent(context) else refreshIntent(context)
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /** Paint one provider's two windows, or dashes when its data is unusable. */
    private fun bindProvider(
        views: RemoteViews,
        usage: ProviderUsage,
        resetStyle: ResetStyle,
        sessionBar: Int, sessionPct: Int, sessionReset: Int,
        weeklyBar: Int, weeklyPct: Int, weeklyReset: Int
    ) {
        // OFFLINE / RATE_LIMITED keep the last good numbers on screen: they are
        // still roughly true, and blanking them helps nobody.
        val blank = usage.error == Errors.AUTH || usage.error == Errors.NOT_CONNECTED
        bindWindow(views, usage.session, blank, resetStyle, sessionBar, sessionPct, sessionReset)
        bindWindow(views, usage.weekly, blank, resetStyle, weeklyBar, weeklyPct, weeklyReset)
    }

    private fun bindWindow(
        views: RemoteViews,
        window: LimitWindow,
        blank: Boolean,
        resetStyle: ResetStyle,
        barId: Int, pctId: Int, resetId: Int
    ) {
        if (blank) {
            views.setProgressBar(barId, 100, 0, false)
            setBarTint(views, barId, COLOR_MUTED)
            views.setTextViewText(pctId, "—")
            views.setTextColor(pctId, COLOR_MUTED)
            views.setTextViewText(resetId, "")
            return
        }

        val pct = window.percent.coerceIn(0, 100)
        val color = getColor(pct)
        views.setProgressBar(barId, 100, pct, false)
        setBarTint(views, barId, color)
        views.setTextViewText(pctId, "$pct%")
        views.setTextColor(pctId, color)
        views.setTextViewText(resetId, formatReset(window.resetsAtMs, resetStyle))
        views.setTextColor(resetId, 0xB3FFFFFF.toInt())
    }

    /** Plan badge, or the reason the numbers are missing/stale. */
    private fun statusLine(usage: ProviderUsage): String = when (usage.error) {
        null -> if (usage.planLabel.isEmpty()) "" else "· ${usage.planLabel}"
        Errors.AUTH -> "· token expired, tap to fix"
        Errors.NOT_CONNECTED -> "· not connected"
        Errors.RATE_LIMITED -> "· rate limited"
        Errors.OFFLINE -> "· offline"
        else -> "· ${usage.error}"
    }

    private fun statusColor(usage: ProviderUsage): Int = when (usage.error) {
        null -> 0x99FFFFFF.toInt()
        Errors.AUTH, Errors.NOT_CONNECTED -> COLOR_RED
        else -> COLOR_YELLOW
    }

    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, PulseWidget::class.java).apply { action = ACTION_REFRESH }
        return PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setupIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, SetupActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun browserIntent(context: Context, requestCode: Int, url: String): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun scheduleRefresh(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        try {
            executor.execute {
                try {
                    UsageRepository.fetch(context)
                    renderWidget(context, appWidgetManager, appWidgetId)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Tint the fill (API 31+); older devices keep the drawable's green. */
    private fun setBarTint(views: RemoteViews, barId: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(barId, "setProgressTintList", ColorStateList.valueOf(color))
        }
    }

    private fun getColor(pct: Int): Int = when {
        pct >= 90 -> COLOR_RED
        pct >= 75 -> COLOR_ORANGE
        pct >= 50 -> COLOR_YELLOW
        else -> COLOR_GREEN
    }

    /**
     * "↻3h20 · 14:32" when there is room, "↻3h20" when there is not. Windows
     * further than a day out drop the clock for a weekday, which is the part
     * that actually tells you something at that distance.
     */
    private fun formatReset(resetsAtMs: Long?, style: ResetStyle): String {
        if (resetsAtMs == null || resetsAtMs <= 0L) return ""
        val diffMs = resetsAtMs - System.currentTimeMillis()
        if (diffMs <= 0) return "↻now"

        val totalMinutes = diffMs / 60_000
        val hours = (totalMinutes / 60).toInt()
        val minutes = (totalMinutes % 60).toInt()

        val relative = when {
            hours >= 24 -> "↻${hours / 24}d${hours % 24}h"
            hours >= 1 -> "↻${hours}h${minutes.toString().padStart(2, '0')}"
            else -> "↻${minutes}m"
        }
        if (style == ResetStyle.RELATIVE_ONLY) return relative

        val date = Date(resetsAtMs)
        val pattern = if (hours >= 24) "EEE" else "HH:mm"
        return "$relative · ${SimpleDateFormat(pattern, Locale.getDefault()).format(date)}"
    }

    private fun formatTimeSince(cachedAtMs: Long): String {
        if (cachedAtMs <= 0L) return ""
        val minutes = ((System.currentTimeMillis() - cachedAtMs) / 60_000).toInt()
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            else -> "${minutes / 60}h ago"
        }
    }
}
