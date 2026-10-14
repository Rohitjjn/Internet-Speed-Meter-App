package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.DataUsageEntity
import com.example.util.BytesFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget with **today's real total** (mobile + Wi-Fi), rendered with plain
 * [RemoteViews] — no Glance, no new dependency.
 *
 * Three things keep it fresh, all cheap:
 *  - the running [com.example.services.SpeedMeterService] pushes the row it just wrote
 *    (at most once a minute, and only when a widget instance exists);
 *  - [WidgetRefreshWorker] runs every 30 min for the case the service is not running
 *    (it reads the persisted row — still a real counter, never an estimate);
 *  - the launcher's own `updatePeriodMillis` / add / resize call [onUpdate].
 */
class TodayUsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Rendering needs the database → never on the main thread inside a receiver.
        WidgetRefreshWorker.refreshNow(context)
        WidgetRefreshWorker.schedule(context)
    }

    override fun onEnabled(context: Context) {
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        WidgetRefreshWorker.cancel(context)
    }

    companion object {
        /** Minimum spacing between two pushes from the service's accounting loop. */
        private const val PUSH_INTERVAL_MS = 60_000L

        @Volatile
        private var lastPushElapsedMs = 0L

        fun hasInstances(context: Context): Boolean = try {
            widgetIds(context).isNotEmpty()
        } catch (e: Exception) {
            false
        }

        /**
         * Called by the service after every accounting pass. Throttled, and a no-op when no
         * widget has been placed, so the 15 s loop never pays for a binder call it does
         * not need.
         */
        fun pushIfPresent(context: Context, usage: DataUsageEntity) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPushElapsedMs < PUSH_INTERVAL_MS) return
            // Stamped before the instance check, so even the "is there a widget?" binder
            // call happens at most once a minute, not on every 15 s accounting pass.
            lastPushElapsedMs = now
            try {
                if (!hasInstances(context)) return
                render(context, usage)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /** Builds the views for [usage] and hands them to every placed instance. */
        fun render(context: Context, usage: DataUsageEntity, updatedAt: Date = Date()) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = widgetIds(context, manager)
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, buildViews(context, usage, updatedAt))
        }

        internal fun buildViews(context: Context, usage: DataUsageEntity, updatedAt: Date): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setTextViewText(R.id.widget_eyebrow, context.getString(R.string.widget_today))
            views.setTextViewText(R.id.widget_total, BytesFormatter.format(usage.total))
            views.setTextViewText(
                R.id.widget_breakdown,
                context.getString(
                    R.string.widget_breakdown,
                    BytesFormatter.format(usage.mobileTotal),
                    BytesFormatter.format(usage.wifiTotal)
                )
            )
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(updatedAt)
            views.setTextViewText(R.id.widget_updated, context.getString(R.string.widget_updated, time))
            views.setContentDescription(
                R.id.widget_root,
                context.getString(R.string.a11y_widget_summary, BytesFormatter.format(usage.total))
            )

            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            return views
        }

        private fun widgetIds(
            context: Context,
            manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        ): IntArray = manager.getAppWidgetIds(ComponentName(context, TodayUsageWidget::class.java)) ?: IntArray(0)
    }
}
