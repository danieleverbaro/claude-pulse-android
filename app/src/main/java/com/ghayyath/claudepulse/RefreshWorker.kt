package com.ghayyath.claudepulse

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RefreshWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val snapshot = UsageRepository.fetch(context)

        // Always repaint, error or not, so "Xm ago" and the countdowns stay honest.
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, PulseWidget::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isNotEmpty()) {
            val intent = Intent(context, PulseWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }

        // A dead or missing token will not fix itself on retry; a throttle or a
        // dropped connection will.
        val transient = listOf(snapshot.claude, snapshot.codex).any {
            it.error != null && it.error != Errors.AUTH && it.error != Errors.NOT_CONNECTED
        }
        return if (transient) Result.retry() else Result.success()
    }
}
