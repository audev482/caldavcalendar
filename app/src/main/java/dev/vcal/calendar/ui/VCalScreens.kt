package dev.vcal.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.CalendarServerConfig
import dev.vcal.calendar.domain.CalendarStoreState
import dev.vcal.calendar.domain.CalendarSyncState
import dev.vcal.calendar.domain.EventOccurrence
import dev.vcal.calendar.domain.RecurrencePreset
import dev.vcal.calendar.domain.VEventItem
import dev.vcal.calendar.protocol.RRuleExpander
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MONTH_YEAR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY_HEADER_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun VCalMainScreen(
    state: CalendarStoreState,
    initialOpenSettings: Boolean,
    initialEventUid: String?,
    initialSelectedDate: LocalDate?,
    onSyncNow: () -> Unit,
    onCreateEvent: suspend (
        summary: String,
        calendarUrl: String?,
        description: String,
        location: String,
        isAllDay: Boolean,
        startDate: LocalDate,
        endDate: LocalDate,
        startInstant: Instant,
        endInstant: Instant,
        rrule: String?
    ) -> Result<VEventItem>,
    onUpdateEvent: suspend (VEventItem) -> Result<VEventItem>,
    onDeleteEvent: suspend (uid: String) -> Result<Unit>,
    onToggleCalendarEnabled: (String) -> Unit,
    onTestConnection: suspend (CalendarServerConfig) -> Result<List<CalendarCollection>>,
    onSaveServer: suspend (CalendarServerConfig) -> Result<List<CalendarCollection>>,
    onUpdateSyncInterval: (Int) -> Unit,
    onSelectWidgetCalendar: (String?) -> Unit,
    onDisconnectServer: () -> Unit,
    onClearError: () -> Unit,
    onPinWidget: () -> Unit
) {
    val palette = LocalVCalPalette.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    var showSettings by rememberSaveable { mutableStateOf(initialOpenSettings || !state.config.isConfigured) }
    var selectedDate by remember { mutableStateOf(initialSelectedDate ?: today) }
    var displayedMonth by remember { mutableStateOf(YearMonth.from(initialSelectedDate ?: today)) }
    var editingEvent by remember { mutableStateOf<VEventItem?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(initialOpenSettings) {
        if (initialOpenSettings) showSettings = true
    }
    LaunchedEffect(initialSelectedDate) {
        if (initialSelectedDate != null) {
            selectedDate = initialSelectedDate
            displayedMonth = YearMonth.from(initialSelectedDate)
            showSettings = false
        }
    }
    LaunchedEffect(initialEventUid, state.events) {
        if (!initialEventUid.isNullOrBlank()) {
            state.events.firstOrNull { it.uid == initialEventUid }?.let {
                editingEvent = it
                showSettings = false
            }
        }
    }

    val safeInsets = WindowInsets.systemBars.union(WindowInsets.captionBar).asPaddingValues()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.windowBg)
            .padding(safeInsets)
    ) {
        val isWide = maxWidth >= 720.dp
        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                CalendarSidebar(
                    state = state,
                    showSettings = showSettings,
                    onToggleCalendar = onToggleCalendarEnabled,
                    onOpenSettings = { showSettings = true },
                    onBackToCalendar = { showSettings = false },
                    onPinWidget = onPinWidget
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp)
                ) {
                    if (showSettings) {
                        CalendarSettingsPane(
                            state = state,
                            onTestConnection = onTestConnection,
                            onSaveServer = { cfg ->
                                val res = onSaveServer(cfg)
                                if (res.isSuccess) showSettings = false
                                res
                            },
                            onUpdateSyncInterval = onUpdateSyncInterval,
                            onSelectWidgetCalendar = onSelectWidgetCalendar,
                            onDisconnectServer = {
                                onDisconnectServer()
                                showSettings = true
                            },
                            onPinWidget = onPinWidget,
                            onBack = if (state.config.isConfigured) ({ showSettings = false }) else null
                        )
                    } else {
                        CalendarWorkspacePane(
                            state = state,
                            today = today,
                            displayedMonth = displayedMonth,
                            selectedDate = selectedDate,
                            onMonthChange = { displayedMonth = it },
                            onSelectDate = {
                                selectedDate = it
                                displayedMonth = YearMonth.from(it)
                            },
                            onSyncNow = onSyncNow,
                            onNewEvent = { showCreateDialog = true },
                            onClickEvent = { editingEvent = it },
                            onClearError = onClearError,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "VCal",
                        color = palette.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onPinWidget) { Text("Pin Widget", fontSize = 12.sp) }
                    IconButton(onClick = { showSettings = !showSettings }, modifier = Modifier.testTag("btn_server_settings")) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = if (showSettings) palette.primary else palette.textSecondary
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (showSettings) {
                        CalendarSettingsPane(
                            state = state,
                            onTestConnection = onTestConnection,
                            onSaveServer = { cfg ->
                                val res = onSaveServer(cfg)
                                if (res.isSuccess) showSettings = false
                                res
                            },
                            onUpdateSyncInterval = onUpdateSyncInterval,
                            onSelectWidgetCalendar = onSelectWidgetCalendar,
                            onDisconnectServer = {
                                onDisconnectServer()
                                showSettings = true
                            },
                            onPinWidget = onPinWidget,
                            onBack = if (state.config.isConfigured) ({ showSettings = false }) else null
                        )
                    } else {
                        CalendarWorkspacePane(
                            state = state,
                            today = today,
                            displayedMonth = displayedMonth,
                            selectedDate = selectedDate,
                            onMonthChange = { displayedMonth = it },
                            onSelectDate = {
                                selectedDate = it
                                displayedMonth = YearMonth.from(it)
                            },
                            onSyncNow = onSyncNow,
                            onNewEvent = { showCreateDialog = true },
                            onClickEvent = { editingEvent = it },
                            onClearError = onClearError,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        EventEditorDialog(
            existing = null,
            defaultDate = selectedDate,
            calendars = state.calendars,
            defaultCalendarUrl = state.config.selectedCalendarUrl,
            onDismiss = { showCreateDialog = false },
            onSaveNew = { summary, calUrl, desc, loc, allDay, sDate, eDate, sInst, eInst, rrule ->
                scope.launch {
                    val res = onCreateEvent(summary, calUrl, desc, loc, allDay, sDate, eDate, sInst, eInst, rrule)
                    if (res.isSuccess) showCreateDialog = false
                }
            },
            onSaveEdit = {},
            onDelete = {}
        )
    }

    editingEvent?.let { ev ->
        EventEditorDialog(
            existing = ev,
            defaultDate = ev.startDate,
            calendars = state.calendars,
            defaultCalendarUrl = ev.calendarUrl,
            onDismiss = { editingEvent = null },
            onSaveNew = { _, _, _, _, _, _, _, _, _, _ -> },
            onSaveEdit = { updated ->
                scope.launch {
                    val res = onUpdateEvent(updated)
                    if (res.isSuccess) editingEvent = null
                }
            },
            onDelete = { uid ->
                scope.launch {
                    val res = onDeleteEvent(uid)
                    if (res.isSuccess) editingEvent = null
                }
            }
        )
    }
}

@Composable
private fun CalendarSidebar(
    state: CalendarStoreState,
    showSettings: Boolean,
    onToggleCalendar: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBackToCalendar: () -> Unit,
    onPinWidget: () -> Unit
) {
    val palette = LocalVCalPalette.current
    Column(
        modifier = Modifier
            .width(248.dp)
            .fillMaxHeight()
            .padding(start = 14.dp, top = 16.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.cardStroke, RoundedCornerShape(22.dp))
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBackToCalendar)
                .padding(6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.primary)
            ) {
                Text(
                    text = LocalDate.now().dayOfMonth.toString(),
                    color = palette.onPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("VCal", color = palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("CalDAV Calendar", color = palette.textMuted, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = "CALENDARS",
            color = palette.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (state.calendars.isEmpty()) {
                Text(
                    text = "No calendars synced yet",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            } else {
                for (cal in state.calendars) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onToggleCalendar(cal.url) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = cal.enabled,
                            onCheckedChange = { onToggleCalendar(cal.url) }
                        )
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(parseHexColorSafe(cal.colorHex, palette.primary))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (cal.readOnly) "${cal.displayName} (RO)" else cal.displayName,
                            color = palette.textPrimary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = palette.cardStroke, modifier = Modifier.padding(vertical = 10.dp))

        OutlinedButton(
            onClick = onPinWidget,
            modifier = Modifier.fillMaxWidth().testTag("btn_pin_widget"),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("Pin Desktop Widget", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (showSettings) palette.primaryContainer else palette.tonalBg)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("btn_server_settings")
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Server Settings",
                tint = if (showSettings) palette.onPrimaryContainer else palette.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.config.isConfigured) "Server & Widget" else "Connect Server",
                color = if (showSettings) palette.onPrimaryContainer else palette.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CalendarWorkspacePane(
    state: CalendarStoreState,
    today: LocalDate,
    displayedMonth: YearMonth,
    selectedDate: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onSyncNow: () -> Unit,
    onNewEvent: () -> Unit,
    onClickEvent: (VEventItem) -> Unit,
    onClearError: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val palette = LocalVCalPalette.current
    val zoneId = remember { ZoneId.systemDefault() }
    val calendarsByUrl = remember(state.calendars) { state.calendars.associateBy { it.url } }

    val firstOfMonth = displayedMonth.atDay(1)
    val leading = firstOfMonth.dayOfWeek.value % 7
    val gridStart = firstOfMonth.minusDays(leading.toLong())
    val gridEnd = gridStart.plusDays(41)

    val monthOccurrences = remember(state.events, state.calendars, gridStart, gridEnd) {
        RRuleExpander.expandEvents(state.events, calendarsByUrl, gridStart, gridEnd, zoneId)
    }
    val eventsByDate = remember(monthOccurrences) { monthOccurrences.groupBy { it.occurrenceDate } }

    val selectedDayEvents = remember(state.events, state.calendars, selectedDate) {
        RRuleExpander.expandEvents(state.events, calendarsByUrl, selectedDate, selectedDate, zoneId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.cardStroke, RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        // Header Row
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { onMonthChange(displayedMonth.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                text = displayedMonth.format(MONTH_YEAR_FMT),
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { onMonthChange(displayedMonth.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
            TextButton(onClick = { onSelectDate(today) }) {
                Text("Today")
            }
            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = onSyncNow,
                enabled = state.syncState != CalendarSyncState.Syncing && state.config.isConfigured,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(palette.tonalBg)
            ) {
                if (state.syncState == CalendarSyncState.Syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync now", tint = palette.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onNewEvent,
                enabled = state.config.isConfigured && state.calendars.any { !it.readOnly },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("btn_new_event")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Event")
            }
        }

        val err = state.syncState as? CalendarSyncState.Error
        if (err != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.errorBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(err.message, color = palette.errorText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onSyncNow) { Text("Retry", color = palette.errorText) }
                IconButton(onClick = onClearError, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = palette.errorText, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (!state.config.isConfigured) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connect your Nextcloud or CalDAV Calendar", color = palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("VCal syncs RFC 5545 VEVENT calendars over RFC 4791 CalDAV.", color = palette.textSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                        Text("Configure CalDAV Server")
                    }
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))

            // Weekday Header
            Row(modifier = Modifier.fillMaxWidth()) {
                for (d in listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")) {
                    Text(
                        text = d,
                        color = palette.textMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(bottom = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // 6-row Month Grid
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (week in 0 until 6) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (dow in 0 until 7) {
                            val date = gridStart.plusDays((week * 7 + dow).toLong())
                            val inMonth = date.month == displayedMonth.month
                            val isToday = date == today
                            val isSelected = date == selectedDate
                            val dayEvs = eventsByDate[date].orEmpty()

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            isSelected -> palette.primaryContainer
                                            else -> Color.Transparent
                                        }
                                    )
                                    .border(
                                        width = if (isToday) 1.5.dp else 0.dp,
                                        color = if (isToday) palette.primary else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onSelectDate(date) }
                                    .padding(top = 5.dp)
                            ) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    color = when {
                                        isSelected -> palette.onPrimaryContainer
                                        inMonth -> palette.textPrimary
                                        else -> palette.textMuted
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                if (dayEvs.isNotEmpty()) {
                                    Spacer(Modifier.height(3.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        for (occ in dayEvs.take(3)) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(parseHexColorSafe(occ.calendarColorHex, palette.primary))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = palette.cardStroke, modifier = Modifier.padding(vertical = 12.dp))

            // Selected Day Agenda
            Text(
                text = selectedDate.format(DAY_HEADER_FMT),
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            if (selectedDayEvents.isEmpty()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text("No events scheduled for this day", color = palette.textMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedDayEvents, key = { "${it.event.uid}|${it.occurrenceDate}|${it.occurrenceStart}" }) { occ ->
                        EventOccurrenceCard(occ = occ, zoneId = zoneId, onClick = { onClickEvent(occ.event) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EventOccurrenceCard(
    occ: EventOccurrence,
    zoneId: ZoneId,
    onClick: () -> Unit
) {
    val palette = LocalVCalPalette.current
    val calColor = parseHexColorSafe(occ.calendarColorHex, palette.primary)
    val timeText = if (occ.isAllDay) {
        "All day"
    } else {
        "${TIME_FMT.format(occ.occurrenceStart.atZone(zoneId))} – ${TIME_FMT.format(occ.occurrenceEnd.atZone(zoneId))}"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.rowBg)
            .border(1.dp, palette.cardStroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("event_row_${occ.event.uid}")
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(calColor)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = occ.event.summary.ifBlank { "(Untitled event)" },
                color = palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildList {
                add(timeText)
                if (occ.event.location.isNotBlank()) add(occ.event.location)
                if (occ.event.isRecurring) add("Repeats")
            }.joinToString(" · ")
            Text(meta, color = palette.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(occ.calendarName, color = palette.textMuted, fontSize = 11.sp)
    }
}

@Composable
fun CalendarSettingsPane(
    state: CalendarStoreState,
    onTestConnection: suspend (CalendarServerConfig) -> Result<List<CalendarCollection>>,
    onSaveServer: suspend (CalendarServerConfig) -> Result<List<CalendarCollection>>,
    onUpdateSyncInterval: (Int) -> Unit,
    onSelectWidgetCalendar: (String?) -> Unit,
    onDisconnectServer: () -> Unit,
    onPinWidget: () -> Unit,
    onBack: (() -> Unit)?
) {
    val palette = LocalVCalPalette.current
    val scope = rememberCoroutineScope()

    var serverUrl by rememberSaveable(state.config.serverUrl) { mutableStateOf(state.config.serverUrl) }
    var username by rememberSaveable(state.config.username) { mutableStateOf(state.config.username) }
    var password by rememberSaveable(state.config.password) { mutableStateOf(state.config.password) }
    var directUrl by rememberSaveable(state.config.directCollectionUrl) { mutableStateOf(state.config.directCollectionUrl) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(state.config.directCollectionUrl.isNotBlank()) }

    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }

    fun draft() = state.config.copy(
        serverUrl = serverUrl.trim(),
        username = username.trim(),
        password = password,
        directCollectionUrl = directUrl.trim()
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.cardStroke, RoundedCornerShape(22.dp))
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("screen_server_settings")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("CalDAV Calendar Server & Widget", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("RFC 4791 CalDAV + RFC 5545 VEVENT (Nextcloud Calendar, Radicale, Baïkal, SabreDAV)", color = palette.textSecondary, fontSize = 12.sp)
            }
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("Done") }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://cloud.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("input_server_url")
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).testTag("input_username")
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("App Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show", fontSize = 11.sp)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).testTag("input_password")
            )
        }
        Text(
            text = "For Nextcloud, use an App Password from Settings → Security → Devices & sessions. Stored encrypted via AndroidKeyStore.",
            color = palette.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )

        TextButton(onClick = { showAdvanced = !showAdvanced }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(if (showAdvanced) "Hide direct collection URL" else "Advanced: Direct VEVENT collection URL", fontSize = 12.sp)
        }
        if (showAdvanced) {
            OutlinedTextField(
                value = directUrl,
                onValueChange = { directUrl = it },
                label = { Text("Direct Collection URL (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (feedback != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (feedbackError) palette.errorBg else palette.primaryContainer)
                    .padding(12.dp)
            ) {
                Text(feedback!!, color = if (feedbackError) palette.errorText else palette.onPrimaryContainer, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    if (testing || saving) return@OutlinedButton
                    testing = true
                    feedback = null
                    scope.launch {
                        val res = onTestConnection(draft())
                        testing = false
                        res.fold(
                            onSuccess = { cals ->
                                feedbackError = false
                                feedback = "Connected! Discovered ${cals.size} calendar(s): ${cals.joinToString { it.displayName }}"
                            },
                            onFailure = { e ->
                                feedbackError = true
                                feedback = e.message ?: "Connection failed"
                            }
                        )
                    }
                },
                enabled = (serverUrl.isNotBlank() || directUrl.isNotBlank()) && !testing && !saving,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (testing) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                Text("Test Connection")
            }
            Button(
                onClick = {
                    if (testing || saving) return@Button
                    saving = true
                    feedback = null
                    scope.launch {
                        val res = onSaveServer(draft())
                        saving = false
                        res.fold(
                            onSuccess = { cals ->
                                feedbackError = false
                                feedback = "Saved & synced ${cals.size} calendar(s)."
                            },
                            onFailure = { e ->
                                feedbackError = true
                                feedback = e.message ?: "Failed to sync"
                            }
                        )
                    }
                },
                enabled = (serverUrl.isNotBlank() || directUrl.isNotBlank()) && !testing && !saving,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("btn_save_server")
            ) {
                if (saving) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.onPrimary); Spacer(Modifier.width(6.dp)) }
                Text("Save & Sync")
            }
        }

        HorizontalDivider(color = palette.cardStroke, modifier = Modifier.padding(vertical = 16.dp))

        Text("Background sync interval", color = palette.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mins, label) in listOf(0 to "Manual", 15 to "15 min", 30 to "30 min", 60 to "1 hour")) {
                FilterChip(
                    selected = state.config.syncIntervalMinutes == mins,
                    onClick = { onUpdateSyncInterval(mins) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        if (state.calendars.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Default widget calendar", color = palette.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = state.config.selectedCalendarUrl == null,
                    onClick = { onSelectWidgetCalendar(null) },
                    label = { Text("All Calendars", fontSize = 12.sp) }
                )
                for (cal in state.calendars) {
                    FilterChip(
                        selected = state.config.selectedCalendarUrl == cal.url,
                        onClick = { onSelectWidgetCalendar(cal.url) },
                        label = { Text(cal.displayName, fontSize = 12.sp) }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPinWidget, shape = RoundedCornerShape(12.dp)) {
                Text("Pin VCal Widget to Desktop")
            }
            if (state.config.isConfigured) {
                TextButton(
                    onClick = onDisconnectServer,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.errorText)
                ) { Text("Disconnect Server") }
            }
        }
    }
}

@Composable
fun EventEditorDialog(
    existing: VEventItem?,
    defaultDate: LocalDate,
    calendars: List<CalendarCollection>,
    defaultCalendarUrl: String?,
    onDismiss: () -> Unit,
    onSaveNew: (String, String?, String, String, Boolean, LocalDate, LocalDate, Instant, Instant, String?) -> Unit,
    onSaveEdit: (VEventItem) -> Unit,
    onDelete: (String) -> Unit
) {
    val palette = LocalVCalPalette.current
    val zoneId = remember { ZoneId.systemDefault() }
    val writableCals = remember(calendars) { calendars.filter { !it.readOnly } }
    val isReadOnly = existing != null && calendars.firstOrNull { it.url == existing.calendarUrl }?.readOnly == true

    var summary by remember(existing?.uid) { mutableStateOf(existing?.summary.orEmpty()) }
    var location by remember(existing?.uid) { mutableStateOf(existing?.location.orEmpty()) }
    var description by remember(existing?.uid) { mutableStateOf(existing?.description.orEmpty()) }
    var isAllDay by remember(existing?.uid) { mutableStateOf(existing?.isAllDay ?: false) }
    var dateText by remember(existing?.uid) { mutableStateOf((existing?.startDate ?: defaultDate).toString()) }
    var startTimeText by remember(existing?.uid) {
        mutableStateOf(
            if (existing != null && !existing.isAllDay) TIME_FMT.format(existing.startInstant.atZone(zoneId)) else "09:00"
        )
    }
    var endTimeText by remember(existing?.uid) {
        mutableStateOf(
            if (existing != null && !existing.isAllDay) TIME_FMT.format(existing.endInstant.atZone(zoneId)) else "10:00"
        )
    }
    var selectedCalUrl by remember(existing?.uid) {
        mutableStateOf(
            existing?.calendarUrl
                ?: defaultCalendarUrl?.takeIf { u -> writableCals.any { it.url == u } }
                ?: writableCals.firstOrNull()?.url
        )
    }
    var recurrence by remember(existing?.uid) { mutableStateOf(RecurrencePreset.fromRrule(existing?.rrule)) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = palette.cardBg,
            modifier = Modifier
                .widthIn(min = 340.dp, max = 520.dp)
                .border(1.dp, palette.cardStroke, RoundedCornerShape(22.dp))
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            existing == null -> "New Event"
                            isReadOnly -> "Event Details (Read-Only)"
                            else -> "Edit Event"
                        },
                        color = palette.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    enabled = !isReadOnly,
                    label = { Text("Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("event_editor_title")
                )

                if (existing == null && writableCals.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for (cal in writableCals) {
                            FilterChip(
                                selected = selectedCalUrl == cal.url,
                                onClick = { selectedCalUrl = cal.url },
                                label = { Text(cal.displayName, fontSize = 12.sp) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All-day event", color = palette.textPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = isAllDay, enabled = !isReadOnly, onCheckedChange = { isAllDay = it })
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it; validationError = null },
                    enabled = !isReadOnly,
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isAllDay) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = startTimeText,
                            onValueChange = { startTimeText = it; validationError = null },
                            enabled = !isReadOnly,
                            label = { Text("Start (HH:mm)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endTimeText,
                            onValueChange = { endTimeText = it; validationError = null },
                            enabled = !isReadOnly,
                            label = { Text("End (HH:mm)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Repeat", color = palette.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (preset in RecurrencePreset.entries) {
                        FilterChip(
                            selected = recurrence == preset,
                            onClick = { if (!isReadOnly) recurrence = preset },
                            label = { Text(preset.label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    enabled = !isReadOnly,
                    label = { Text("Location") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    enabled = !isReadOnly,
                    label = { Text("Notes") },
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (validationError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(validationError!!, color = palette.errorText, fontSize = 12.sp)
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (existing != null && !isReadOnly) {
                        TextButton(
                            onClick = { onDelete(existing.uid) },
                            colors = ButtonDefaults.textButtonColors(contentColor = palette.errorText)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    if (!isReadOnly) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val parsedDate = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
                                if (parsedDate == null) {
                                    validationError = "Date must be YYYY-MM-DD"
                                    return@Button
                                }
                                val startInst: Instant
                                val endInst: Instant
                                val endDate: LocalDate
                                if (isAllDay) {
                                    endDate = parsedDate.plusDays(1)
                                    startInst = parsedDate.atStartOfDay(zoneId).toInstant()
                                    endInst = endDate.atStartOfDay(zoneId).toInstant()
                                } else {
                                    val sTime = runCatching { LocalTime.parse(startTimeText.trim()) }.getOrNull()
                                    val eTime = runCatching { LocalTime.parse(endTimeText.trim()) }.getOrNull()
                                    if (sTime == null || eTime == null) {
                                        validationError = "Time must be HH:mm (24-hour)"
                                        return@Button
                                    }
                                    startInst = parsedDate.atTime(sTime).atZone(zoneId).toInstant()
                                    val candidateEnd = parsedDate.atTime(eTime).atZone(zoneId).toInstant()
                                    endInst = if (candidateEnd.isAfter(startInst)) candidateEnd else startInst.plusSeconds(3600)
                                    endDate = endInst.atZone(zoneId).toLocalDate()
                                }
                                val effectiveRrule = if (existing != null && RecurrencePreset.fromRrule(existing.rrule) == recurrence) {
                                    existing.rrule
                                } else {
                                    recurrence.rrule
                                }
                                if (existing == null) {
                                    onSaveNew(summary.trim(), selectedCalUrl, description.trim(), location.trim(), isAllDay, parsedDate, endDate, startInst, endInst, effectiveRrule)
                                } else {
                                    onSaveEdit(
                                        existing.copy(
                                            summary = summary.trim(),
                                            description = description.trim(),
                                            location = location.trim(),
                                            isAllDay = isAllDay,
                                            startDate = parsedDate,
                                            endDate = endDate,
                                            startInstant = startInst,
                                            endInstant = endInst,
                                            rrule = effectiveRrule
                                        )
                                    )
                                }
                            },
                            enabled = summary.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("event_editor_save")
                        ) {
                            Text(if (existing == null) "Create" else "Save")
                        }
                    }
                }
            }
        }
    }
}
