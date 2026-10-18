package org.linhome.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import org.linhome.LinhomeApplication
import org.linhome.R
import org.linhome.customisation.Texts
import org.linhome.entities.LinhomeAccount
import org.linhome.quicksettings.ReceiveCallsTileService
import org.linphone.core.tools.Log

class ReceiveCallsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_RECEIVE_CALLS) {
            val currentEnabled = if (LinhomeApplication.contextExists()) {
                LinhomeApplication.corePreferences.appEnabled
            } else {
                true
            }
            val newEnabled = !currentEnabled
            Log.i("[ReceiveCallsWidget] Toggling receive calls from widget: $currentEnabled -> $newEnabled")

            if (LinhomeApplication.contextExists()) {
                LinhomeAccount.setAppEnabled(newEnabled)
            }
            updateAllWidgets(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    android.service.quicksettings.TileService.requestListeningState(
                        context,
                        ComponentName(context, ReceiveCallsTileService::class.java)
                    )
                } catch (e: Exception) {
                    Log.e("[ReceiveCallsWidget] Could not update TileService: ${e.message}")
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_RECEIVE_CALLS = "org.linhome.action.TOGGLE_RECEIVE_CALLS"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val isEnabled = if (LinhomeApplication.contextExists()) {
                LinhomeApplication.corePreferences.appEnabled
            } else {
                true
            }

            val views = RemoteViews(context.packageName, R.layout.widget_receive_calls)

            val activeText = Texts.get("widget_calls_active") ?: "Receiving calls"
            val pausedText = Texts.get("widget_calls_paused") ?: "Calls paused"

            views.setTextViewText(R.id.widget_title, Texts.get("widget_receive_calls_title") ?: "Adonis Calls")
            views.setTextViewText(R.id.widget_status_text, if (isEnabled) activeText else pausedText)
            views.setImageViewResource(
                R.id.widget_status_dot,
                if (isEnabled) R.drawable.ic_widget_circle_green else R.drawable.ic_widget_circle_red
            )
            views.setImageViewResource(
                R.id.widget_switch_indicator,
                if (isEnabled) R.drawable.ic_qs_adonis_tile else R.drawable.ic_qs_adonis_tile_off
            )
            views.setInt(
                R.id.widget_switch_indicator,
                "setColorFilter",
                if (isEnabled) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
            )

            val intent = Intent(context, ReceiveCallsWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_RECEIVE_CALLS
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, ReceiveCallsWidgetProvider::class.java)
                val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                for (widgetId in allWidgetIds) {
                    updateAppWidget(context, appWidgetManager, widgetId)
                }
            } catch (e: Exception) {
                Log.e("[ReceiveCallsWidget] Error updating widgets: ${e.message}")
            }
        }
    }
}
