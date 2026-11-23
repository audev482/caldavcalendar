package dev.vcal.calendar.data

import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.CalendarServerConfig
import dev.vcal.calendar.domain.CalendarStoreState
import dev.vcal.calendar.domain.CalendarSyncState
import dev.vcal.calendar.domain.VEventItem
import dev.vcal.calendar.protocol.CalDavEventException
import dev.vcal.calendar.protocol.CalDavEventService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface CalendarWidgetUpdaterPort {
    fun notifyWidgetsUpdated(state: CalendarStoreState)
}

interface CalendarSyncSchedulerPort {
    fun schedulePeriodicSync(intervalMinutes: Int)
}

object NoOpCalendarWidgetUpdater : CalendarWidgetUpdaterPort {
    override fun notifyWidgetsUpdated(state: CalendarStoreState) = Unit
}

object NoOpCalendarSyncScheduler : CalendarSyncSchedulerPort {
    override fun schedulePeriodicSync(intervalMinutes: Int) = Unit
}

class CalendarRepository(
    private val calDav: CalDavEventService,
    private val localStore: CalendarLocalStore,
    private val widgetUpdater: CalendarWidgetUpdaterPort = NoOpCalendarWidgetUpdater,
    private val syncScheduler: CalendarSyncSchedulerPort = NoOpCalendarSyncScheduler,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() }
) {
    private val _state = MutableStateFlow(CalendarStoreState())
    val state: StateFlow<CalendarStoreState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        val loaded = localStore.loadSnapshot()
        _state.value = loaded
        initialized = true
        if (loaded.config.isConfigured) {
            syncScheduler.schedulePeriodicSync(loaded.config.syncIntervalMinutes)
        }
    }

    suspend fun testConnection(config: CalendarServerConfig): Result<List<CalendarCollection>> {
        return try {
            Result.success(calDav.discoverCalendars(config))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveServerAndSync(config: CalendarServerConfig): Result<List<CalendarCollection>> {
        _state.update { it.copy(syncState = CalendarSyncState.Syncing) }
        return try {
            val previousEnabled = _state.value.calendars.associateBy({ it.url }, { it.enabled })
            val discovered = calDav.discoverCalendars(config).map { cal ->
                cal.copy(enabled = previousEnabled[cal.url] ?: true)
            }
            val validSelected = config.selectedCalendarUrl?.takeIf { sel -> discovered.any { it.url == sel } }
            val updatedConfig = config.copy(selectedCalendarUrl = validSelected)
            val events = fetchAllEvents(updatedConfig, discovered)
            val now = nowProvider()

            persistAndPublish(updatedConfig, discovered, events, CalendarSyncState.Idle, now)
            syncScheduler.schedulePeriodicSync(updatedConfig.syncIntervalMinutes)
            Result.success(discovered)
        } catch (e: Exception) {
            val msg = friendlyError(e)
            _state.update { it.copy(syncState = CalendarSyncState.Error(msg)) }
            widgetUpdater.notifyWidgetsUpdated(_state.value)
            Result.failure(e)
        }
    }

    suspend fun syncAll(): Result<Unit> {
        initialize()
        val current = _state.value
        if (!current.config.isConfigured) {
            return Result.failure(IllegalStateException("CalDAV server is not configured"))
        }
        _state.update { it.copy(syncState = CalendarSyncState.Syncing) }
        return try {
            val previousEnabled = current.calendars.associateBy({ it.url }, { it.enabled })
            val discovered = calDav.discoverCalendars(current.config).map { cal ->
                cal.copy(enabled = previousEnabled[cal.url] ?: true)
            }
            val validSelected = current.config.selectedCalendarUrl?.takeIf { sel -> discovered.any { it.url == sel } }
            val updatedConfig = current.config.copy(selectedCalendarUrl = validSelected)
            val events = fetchAllEvents(updatedConfig, discovered)
            val now = nowProvider()

            persistAndPublish(updatedConfig, discovered, events, CalendarSyncState.Idle, now)
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = friendlyError(e)
            _state.update { it.copy(syncState = CalendarSyncState.Error(msg)) }
            widgetUpdater.notifyWidgetsUpdated(_state.value)
            Result.failure(e)
        }
    }

    suspend fun createEvent(
        summary: String,
        calendarUrl: String? = null,
        description: String = "",
        location: String = "",
        isAllDay: Boolean = false,
        startDate: LocalDate,
        endDate: LocalDate,
        startInstant: Instant,
        endInstant: Instant,
        rrule: String? = null
    ): Result<VEventItem> {
        initialize()
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Event title cannot be empty"))

        val current = _state.value
        val targetCal = resolveWritableCalendar(current, calendarUrl)
            ?: return Result.failure(IllegalStateException("No writable CalDAV calendar available"))

        val draft = VEventItem(
            uid = uuidProvider(),
            href = "",
            calendarUrl = targetCal.url,
            summary = trimmed,
            description = description.trim(),
            location = location.trim(),
            isAllDay = isAllDay,
            startDate = startDate,
            endDate = endDate,
            startInstant = startInstant,
            endInstant = endInstant,
            rrule = rrule?.takeIf { it.isNotBlank() },
            createdAt = nowProvider(),
            lastModifiedAt = nowProvider()
        )

        return try {
            val created = calDav.createEvent(current.config, targetCal, draft)
            val updatedEvents = _state.value.events + created
            persistAndPublish(
                _state.value.config,
                _state.value.calendars,
                updatedEvents,
                CalendarSyncState.Idle,
                _state.value.lastSyncedAt
            )
            Result.success(created)
        } catch (e: Exception) {
            _state.update { it.copy(syncState = CalendarSyncState.Error(friendlyError(e))) }
            Result.failure(e)
        }
    }

    suspend fun updateEvent(updated: VEventItem): Result<VEventItem> {
        initialize()
        val current = _state.value
        return try {
            val synced = calDav.updateEvent(current.config, updated)
            val newEvents = _state.value.events.map { if (it.uid == updated.uid) synced else it }
            persistAndPublish(
                _state.value.config,
                _state.value.calendars,
                newEvents,
                CalendarSyncState.Idle,
                _state.value.lastSyncedAt
            )
            Result.success(synced)
        } catch (e: Exception) {
            _state.update { it.copy(syncState = CalendarSyncState.Error(friendlyError(e))) }
            Result.failure(e)
        }
    }

    suspend fun deleteEvent(uid: String): Result<Unit> {
        initialize()
        val current = _state.value
        val existing = current.events.firstOrNull { it.uid == uid }
            ?: return Result.failure(NoSuchElementException("Event not found"))
        return try {
            calDav.deleteEvent(current.config, existing)
            val remaining = _state.value.events.filterNot { it.uid == uid }
            persistAndPublish(
                _state.value.config,
                _state.value.calendars,
                remaining,
                CalendarSyncState.Idle,
                _state.value.lastSyncedAt
            )
            Result.success(Unit)
        } catch (e: Exception) {
            _state.update { it.copy(syncState = CalendarSyncState.Error(friendlyError(e))) }
            Result.failure(e)
        }
    }

    suspend fun toggleCalendarEnabled(calendarUrl: String) {
        initialize()
        val current = _state.value
        val updatedCalendars = current.calendars.map {
            if (it.url == calendarUrl) it.copy(enabled = !it.enabled) else it
        }
        persistAndPublish(
            current.config,
            updatedCalendars,
            current.events,
            current.syncState,
            current.lastSyncedAt
        )
    }

    suspend fun selectWidgetCalendar(calendarUrl: String?) {
        initialize()
        val current = _state.value
        val updatedConfig = current.config.copy(selectedCalendarUrl = calendarUrl)
        persistAndPublish(
            updatedConfig,
            current.calendars,
            current.events,
            current.syncState,
            current.lastSyncedAt
        )
    }

    suspend fun cycleWidgetCalendar() {
        initialize()
        val current = _state.value
        val enabledCals = current.calendars.filter { it.enabled }
        if (enabledCals.isEmpty()) return
        val options: List<String?> = listOf(null) + enabledCals.map { it.url }
        val idx = options.indexOf(current.config.selectedCalendarUrl).coerceAtLeast(0)
        selectWidgetCalendar(options[(idx + 1) % options.size])
    }

    fun shiftWidgetMonth(deltaMonths: Int) {
        val next = _state.value.copy(
            widgetMonthOffset = if (deltaMonths == 0) 0 else (_state.value.widgetMonthOffset + deltaMonths).coerceIn(-24, 24)
        )
        _state.value = next
        widgetUpdater.notifyWidgetsUpdated(next)
    }

    suspend fun updateSyncInterval(minutes: Int) {
        initialize()
        val current = _state.value
        val updatedConfig = current.config.copy(syncIntervalMinutes = minutes)
        persistAndPublish(updatedConfig, current.calendars, current.events, current.syncState, current.lastSyncedAt)
        if (updatedConfig.isConfigured) {
            syncScheduler.schedulePeriodicSync(minutes)
        }
    }

    suspend fun disconnectServer() {
        initialize()
        syncScheduler.schedulePeriodicSync(0)
        persistAndPublish(CalendarServerConfig(), emptyList(), emptyList(), CalendarSyncState.Idle, null)
    }

    fun clearError() {
        _state.update { if (it.syncState is CalendarSyncState.Error) it.copy(syncState = CalendarSyncState.Idle) else it }
    }

    private suspend fun fetchAllEvents(
        config: CalendarServerConfig,
        calendars: List<CalendarCollection>
    ): List<VEventItem> = coroutineScope {
        calendars.map { cal ->
            async { calDav.fetchEvents(config, cal) }
        }.awaitAll().flatten()
    }

    private fun resolveWritableCalendar(state: CalendarStoreState, requestedUrl: String?): CalendarCollection? {
        val candidates = listOfNotNull(
            requestedUrl?.let { u -> state.calendars.firstOrNull { it.url == u } },
            state.config.selectedCalendarUrl?.let { u -> state.calendars.firstOrNull { it.url == u } },
            state.calendars.firstOrNull { !it.readOnly && it.enabled },
            state.calendars.firstOrNull { !it.readOnly }
        )
        return candidates.firstOrNull { !it.readOnly }
    }

    private suspend fun persistAndPublish(
        config: CalendarServerConfig,
        calendars: List<CalendarCollection>,
        events: List<VEventItem>,
        syncState: CalendarSyncState,
        lastSyncedAt: Instant?
    ) {
        localStore.saveSnapshot(config, calendars, events, lastSyncedAt)
        val next = CalendarStoreState(
            config = config,
            calendars = calendars,
            events = events,
            syncState = syncState,
            lastSyncedAt = lastSyncedAt,
            widgetMonthOffset = _state.value.widgetMonthOffset
        )
        _state.value = next
        widgetUpdater.notifyWidgetsUpdated(next)
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is CalDavEventException -> e.message ?: "CalDAV sync failed"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Unexpected sync error"
    }
}
