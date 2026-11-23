package dev.vcal.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import dev.vcal.calendar.R
import dev.vcal.calendar.VCalApp
import dev.vcal.calendar.domain.CalendarStoreState
import dev.vcal.calendar.domain.CalendarSyncState
import dev.vcal.calendar.domain.EventOccurrence
import dev.vcal.calendar.protocol.RRuleExpander
import dev.vcal.calendar.ui.MainActivity
import dev.vcal.calendar.ui.QuickAddEventActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Responsive Googlebook Desktop CalDAV Calendar Widget (RFC 4791 / RFC 5545 VEVENT).
 * - Compact 1x1 (`SizeF(56f, 56f)`): Today tear-off calendar badge (month + day + event count).
 * - Medium 2x2 (`SizeF(180f, 120f)`): Upcoming agenda list with calendar switcher, sync, and quick-add.
 * - Expanded 4x3+ (`SizeF(280f, 220f)`): Split Month Grid (with event indicator dots and < / > navigation)
 *   + Scrollable Upcoming Agenda list.
 */
class VCalWidgetProvider : AppWidgetProvider() {

    enum class WidgetSizeBucket(@param:LayoutRes val layoutRes: Int) {
        COMPACT_1X1(R.layout.widget_vcal_compact),
        MEDIUM_2X2(R.layout.widget_vcal_medium),
        EXPANDED_4X3(R.layout.widget_vcal_expanded)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val app = context.applicationContext as? VCalApp ?: return
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS) {
                    app.repository.initialize()
                    val state = app.repository.state.value
                    val views = buildResponsiveRemoteViews(context, state)
                    for (widgetId in appWidgetIds) {
                        appWidgetManager.updateAppWidget(widgetId, views)
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val app = context.applicationContext as? VCalApp ?: return
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS) {
                    app.repository.initialize()
                    appWidgetManager.updateAppWidget(
                        appWidgetId,
                        buildResponsiveRemoteViews(context, app.repository.state.value)
                    )
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_SYNC = "dev.vcal.calendar.action.WIDGET_SYNC"
        const val ACTION_WIDGET_CYCLE_CAL = "dev.vcal.calendar.action.WIDGET_CYCLE_CAL"
        const val ACTION_WIDGET_PREV_MONTH = "dev.vcal.calendar.action.WIDGET_PREV_MONTH"
        const val ACTION_WIDGET_NEXT_MONTH = "dev.vcal.calendar.action.WIDGET_NEXT_MONTH"
        const val ACTION_WIDGET_TODAY = "dev.vcal.calendar.action.WIDGET_TODAY"

        const val EXTRA_EVENT_UID = "extra_event_uid"
        const val EXTRA_SELECTED_DATE = "extra_selected_date"
        const val EXTRA_OPEN_SETTINGS = "extra_open_settings"

        private const val WIDGET_UPDATE_TIMEOUT_MS = 8_000L
        private const val MAX_AGENDA_ITEMS = 45

        private const val RC_OPEN_APP = 2001
        private const val RC_OPEN_SETTINGS = 2002
        private const val RC_QUICK_ADD = 2003
        private const val RC_SYNC = 2004
        private const val RC_CYCLE_CAL = 2005
        private const val RC_PREV_MONTH = 2006
        private const val RC_NEXT_MONTH = 2007
        private const val RC_TODAY = 2008
        private const val RC_AGENDA_TEMPLATE = 2009
        private const val RC_DAY_TEMPLATE = 2010

        private val MONTH_YEAR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
        private val SHORT_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun updateAllWidgets(context: Context, state: CalendarStoreState) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, VCalWidgetProvider::class.java)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val views = buildResponsiveRemoteViews(context, state)
            for (widgetId in ids) {
                manager.updateAppWidget(widgetId, views)
            }
        }

        internal fun buildResponsiveRemoteViews(
            context: Context,
            state: CalendarStoreState,
            today: LocalDate = LocalDate.now(),
            zoneId: ZoneId = ZoneId.systemDefault()
        ): RemoteViews {
            val compact = buildBucketRemoteViews(context, state, WidgetSizeBucket.COMPACT_1X1, today, zoneId)
            val medium = buildBucketRemoteViews(context, state, WidgetSizeBucket.MEDIUM_2X2, today, zoneId)
            val expanded = buildBucketRemoteViews(context, state, WidgetSizeBucket.EXPANDED_4X3, today, zoneId)
            return RemoteViews(
                mapOf(
                    SizeF(56f, 56f) to compact,
                    SizeF(180f, 120f) to medium,
                    SizeF(280f, 220f) to expanded
                )
            )
        }

        internal fun buildBucketRemoteViews(
            context: Context,
            state: CalendarStoreState,
            bucket: WidgetSizeBucket,
            today: LocalDate = LocalDate.now(),
            zoneId: ZoneId = ZoneId.systemDefault()
        ): RemoteViews {
            val views = RemoteViews(context.packageName, bucket.layoutRes)
            val selectedCal = state.config.selectedCalendarUrl?.let { url ->
                state.calendars.firstOrNull { it.url == url }
            }
            val scopedEvents = if (selectedCal != null) {
                state.events.filter { it.calendarUrl == selectedCal.url }
            } else {
                state.events
            }
            val calendarsByUrl = state.calendars.associateBy { it.url }

            if (bucket == WidgetSizeBucket.COMPACT_1X1) {
                val todayEvents = RRuleExpander.expandEvents(scopedEvents, calendarsByUrl, today, today, zoneId)
                views.setTextViewText(
                    R.id.txt_compact_month,
                    today.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
                )
                views.setTextViewText(R.id.txt_compact_day, today.dayOfMonth.toString())
                val sub = when {
                    !state.config.isConfigured -> "Setup"
                    todayEvents.isEmpty() -> today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    todayEvents.size == 1 -> "1 event"
                    else -> "${todayEvents.size} events"
                }
                views.setTextViewText(R.id.txt_compact_sub, sub)
                views.setOnClickPendingIntent(
                    R.id.widget_compact_root,
                    createOpenAppPendingIntent(context, openSettings = !state.config.isConfigured)
                )
                return views
            }

            // Shared header controls (Medium & Expanded)
            val calTitle = selectedCal?.displayName ?: context.getString(R.string.widget_all_calendars)
            views.setTextViewText(R.id.txt_widget_cal_title, calTitle)
            val dotColor = parseColorOrDefault(
                selectedCal?.colorHex,
                ContextCompat.getColor(context, R.color.accent_primary)
            )
            views.setInt(R.id.img_widget_cal_dot, "setColorFilter", dotColor)

            views.setOnClickPendingIntent(R.id.btn_widget_switch_cal, createActionBroadcast(context, ACTION_WIDGET_CYCLE_CAL, RC_CYCLE_CAL))
            views.setOnClickPendingIntent(R.id.btn_widget_refresh, createActionBroadcast(context, ACTION_WIDGET_SYNC, RC_SYNC))
            views.setOnClickPendingIntent(
                R.id.btn_widget_add,
                if (state.config.isConfigured) createQuickAddPendingIntent(context) else createOpenAppPendingIntent(context, openSettings = true)
            )

            // Upcoming Agenda (next 60 days from today)
            val upcoming = RRuleExpander.expandEvents(
                events = scopedEvents,
                calendarsByUrl = calendarsByUrl,
                windowStart = today,
                windowEnd = today.plusDays(60),
                zoneId = zoneId
            ).take(MAX_AGENDA_ITEMS)

            if (!state.config.isConfigured || upcoming.isEmpty()) {
                views.setViewVisibility(R.id.widget_agenda_list, View.GONE)
                views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
                if (!state.config.isConfigured) {
                    views.setTextViewText(R.id.txt_empty_title, context.getString(R.string.widget_empty_unconfigured_title))
                    views.setTextViewText(R.id.txt_empty_subtitle, context.getString(R.string.widget_empty_unconfigured_subtitle))
                    views.setOnClickPendingIntent(R.id.widget_empty_view, createOpenAppPendingIntent(context, openSettings = true))
                } else {
                    views.setTextViewText(R.id.txt_empty_title, context.getString(R.string.widget_empty_no_events_title))
                    views.setTextViewText(R.id.txt_empty_subtitle, context.getString(R.string.widget_empty_no_events_subtitle))
                    views.setOnClickPendingIntent(R.id.widget_empty_view, createQuickAddPendingIntent(context))
                }
            } else {
                views.setViewVisibility(R.id.widget_empty_view, View.GONE)
                views.setViewVisibility(R.id.widget_agenda_list, View.VISIBLE)

                val agendaBuilder = RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true)
                    .setViewTypeCount(1)
                upcoming.forEachIndexed { idx, occ ->
                    val row = buildAgendaRowRemoteViews(context, occ, today, zoneId)
                    agendaBuilder.addItem((occ.event.uid.hashCode().toLong() shl 16) xor idx.toLong(), row)
                }
                views.setRemoteAdapter(R.id.widget_agenda_list, agendaBuilder.build())
                views.setPendingIntentTemplate(R.id.widget_agenda_list, createOpenActivityTemplate(context, RC_AGENDA_TEMPLATE))
            }

            if (bucket == WidgetSizeBucket.EXPANDED_4X3) {
                val displayedMonth = YearMonth.from(today).plusMonths(state.widgetMonthOffset.toLong())
                views.setTextViewText(R.id.txt_widget_month_title, displayedMonth.format(MONTH_YEAR_FMT))
                views.setOnClickPendingIntent(R.id.txt_widget_month_title, createActionBroadcast(context, ACTION_WIDGET_TODAY, RC_TODAY))
                views.setOnClickPendingIntent(R.id.btn_widget_prev_month, createActionBroadcast(context, ACTION_WIDGET_PREV_MONTH, RC_PREV_MONTH))
                views.setOnClickPendingIntent(R.id.btn_widget_next_month, createActionBroadcast(context, ACTION_WIDGET_NEXT_MONTH, RC_NEXT_MONTH))
                views.setOnClickPendingIntent(R.id.btn_widget_open_app, createOpenAppPendingIntent(context, openSettings = false))

                // Populate 7x6 Month Grid via API 31 RemoteCollectionItems
                val firstOfMonth = displayedMonth.atDay(1)
                val leadingDays = firstOfMonth.dayOfWeek.value % 7 // Sunday = 0, Monday = 1 ... Saturday = 6
                val gridStart = firstOfMonth.minusDays(leadingDays.toLong())
                val gridEnd = gridStart.plusDays(41)

                val monthOccurrences = RRuleExpander.expandEvents(
                    events = scopedEvents,
                    calendarsByUrl = calendarsByUrl,
                    windowStart = gridStart,
                    windowEnd = gridEnd,
                    zoneId = zoneId
                )
                val firstColorByDate = monthOccurrences.groupBy { it.occurrenceDate }
                    .mapValues { (_, list) -> list.first().calendarColorHex }

                val gridBuilder = RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true)
                    .setViewTypeCount(1)

                for (i in 0 until 42) {
                    val cellDate = gridStart.plusDays(i.toLong())
                    val cellView = buildDayCellRemoteViews(
                        context = context,
                        date = cellDate,
                        inCurrentMonth = cellDate.month == displayedMonth.month,
                        isToday = cellDate == today,
                        eventDotHex = firstColorByDate[cellDate]
                    )
                    gridBuilder.addItem(cellDate.toEpochDay(), cellView)
                }
                views.setRemoteAdapter(R.id.widget_month_grid, gridBuilder.build())
                views.setPendingIntentTemplate(R.id.widget_month_grid, createOpenActivityTemplate(context, RC_DAY_TEMPLATE))

                bindFooter(context, views, state, zoneId)
            }

            return views
        }

        private fun buildDayCellRemoteViews(
            context: Context,
            date: LocalDate,
            inCurrentMonth: Boolean,
            isToday: Boolean,
            eventDotHex: String?
        ): RemoteViews {
            val cell = RemoteViews(context.packageName, R.layout.widget_vcal_day_cell)
            cell.setTextViewText(R.id.txt_day_number, date.dayOfMonth.toString())

            if (isToday) {
                cell.setInt(R.id.txt_day_number, "setBackgroundResource", R.drawable.bg_day_today)
                cell.setTextColor(R.id.txt_day_number, ContextCompat.getColor(context, R.color.accent_on_primary))
            } else {
                cell.setInt(R.id.txt_day_number, "setBackgroundResource", 0)
                val colorRes = if (inCurrentMonth) R.color.text_primary else R.color.text_muted
                cell.setTextColor(R.id.txt_day_number, ContextCompat.getColor(context, colorRes))
            }

            if (eventDotHex != null) {
                cell.setViewVisibility(R.id.img_day_event_dot, View.VISIBLE)
                val dotColor = parseColorOrDefault(eventDotHex, ContextCompat.getColor(context, R.color.accent_primary))
                cell.setInt(R.id.img_day_event_dot, "setColorFilter", dotColor)
            } else {
                cell.setViewVisibility(R.id.img_day_event_dot, View.INVISIBLE)
            }

            cell.setOnClickFillInIntent(
                R.id.widget_day_cell_root,
                Intent().apply { putExtra(EXTRA_SELECTED_DATE, date.toString()) }
            )
            return cell
        }

        private fun buildAgendaRowRemoteViews(
            context: Context,
            occ: EventOccurrence,
            today: LocalDate,
            zoneId: ZoneId
        ): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_vcal_agenda_row)
            val barColor = parseColorOrDefault(occ.calendarColorHex, ContextCompat.getColor(context, R.color.accent_primary))
            row.setInt(R.id.img_agenda_color_bar, "setColorFilter", barColor)
            row.setTextViewText(R.id.txt_agenda_summary, occ.event.summary.ifBlank { "(Untitled event)" })

            val dayLabel = when (occ.occurrenceDate) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> SHORT_DATE_FMT.format(occ.occurrenceDate)
            }
            val timeLabel = if (occ.isAllDay) {
                "All day"
            } else {
                TIME_FMT.format(occ.occurrenceStart.atZone(zoneId))
            }
            val meta = buildList {
                add("$dayLabel · $timeLabel")
                if (occ.event.location.isNotBlank()) add(occ.event.location)
            }.joinToString(" · ")
            row.setTextViewText(R.id.txt_agenda_meta, meta)

            row.setOnClickFillInIntent(
                R.id.widget_agenda_row_root,
                Intent().apply {
                    putExtra(EXTRA_EVENT_UID, occ.event.uid)
                    putExtra(EXTRA_SELECTED_DATE, occ.occurrenceDate.toString())
                }
            )
            return row
        }

        private fun bindFooter(
            context: Context,
            views: RemoteViews,
            state: CalendarStoreState,
            zoneId: ZoneId
        ) {
            val footerText = when (val sync = state.syncState) {
                CalendarSyncState.Syncing -> "Syncing calendars…"
                is CalendarSyncState.Error -> "Sync error: ${sync.message}"
                CalendarSyncState.Idle -> {
                    if (!state.config.isConfigured) {
                        "Not connected"
                    } else if (state.lastSyncedAt != null) {
                        "Synced at ${state.lastSyncedAt.atZone(zoneId).format(TIME_FMT)}"
                    } else {
                        "Tap sync to refresh"
                    }
                }
            }
            views.setTextViewText(R.id.txt_widget_footer, footerText)
            val colorRes = if (state.syncState is CalendarSyncState.Error) R.color.error_text else R.color.text_muted
            views.setTextColor(R.id.txt_widget_footer, ContextCompat.getColor(context, colorRes))
        }

        private fun parseColorOrDefault(hex: String?, fallback: Int): Int {
            if (hex.isNullOrBlank()) return fallback
            return runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
        }

        private fun createOpenAppPendingIntent(context: Context, openSettings: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_SETTINGS, openSettings)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                if (openSettings) RC_OPEN_SETTINGS else RC_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createQuickAddPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuickAddEventActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                RC_QUICK_ADD,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createOpenActivityTemplate(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            // Explicit Activity PendingIntent: 100% exempt from Android 15+ BAL restrictions
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        private fun createActionBroadcast(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, VCalWidgetActionReceiver::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
