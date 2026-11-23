package dev.vcal.calendar.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.vcal.calendar.VCalApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Non-exported internal receiver for VCal widget background controls
 * (month navigation, calendar cycling, and manual CalDAV sync).
 */
class VCalWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? VCalApp ?: return
        when (intent.action) {
            VCalWidgetProvider.ACTION_WIDGET_PREV_MONTH -> app.repository.shiftWidgetMonth(-1)
            VCalWidgetProvider.ACTION_WIDGET_NEXT_MONTH -> app.repository.shiftWidgetMonth(1)
            VCalWidgetProvider.ACTION_WIDGET_TODAY -> app.repository.shiftWidgetMonth(0)
            VCalWidgetProvider.ACTION_WIDGET_CYCLE_CAL -> {
                val pendingResult = goAsync()
                app.appScope.launch {
                    try {
                        withTimeoutOrNull(8_000L) { app.repository.cycleWidgetCalendar() }
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
            VCalWidgetProvider.ACTION_WIDGET_SYNC -> {
                val pendingResult = goAsync()
                app.appScope.launch {
                    try {
                        withTimeoutOrNull(20_000L) { app.repository.syncAll() }
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
        }
    }
}
