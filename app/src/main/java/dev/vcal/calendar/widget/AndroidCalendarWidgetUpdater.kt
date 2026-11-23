package dev.vcal.calendar.widget

import android.content.Context
import dev.vcal.calendar.data.CalendarWidgetUpdaterPort
import dev.vcal.calendar.domain.CalendarStoreState

class AndroidCalendarWidgetUpdater(
    private val context: Context
) : CalendarWidgetUpdaterPort {
    override fun notifyWidgetsUpdated(state: CalendarStoreState) {
        VCalWidgetProvider.updateAllWidgets(context, state)
    }
}
