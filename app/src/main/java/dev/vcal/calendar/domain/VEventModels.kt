package dev.vcal.calendar.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class EventStatus(val rfcValue: String) {
    CONFIRMED("CONFIRMED"),
    TENTATIVE("TENTATIVE"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromRfc(raw: String?): EventStatus = when (raw?.trim()?.uppercase()) {
            "TENTATIVE" -> TENTATIVE
            "CANCELLED" -> CANCELLED
            else -> CONFIRMED
        }
    }
}

enum class RecurrencePreset(val label: String, val rrule: String?) {
    NONE("Does not repeat", null),
    DAILY("Daily", "FREQ=DAILY"),
    WEEKLY("Weekly", "FREQ=WEEKLY"),
    MONTHLY("Monthly", "FREQ=MONTHLY"),
    YEARLY("Yearly", "FREQ=YEARLY");

    companion object {
        fun fromRrule(raw: String?): RecurrencePreset {
            val upper = raw?.uppercase().orEmpty()
            return when {
                upper.contains("FREQ=DAILY") -> DAILY
                upper.contains("FREQ=WEEKLY") -> WEEKLY
                upper.contains("FREQ=MONTHLY") -> MONTHLY
                upper.contains("FREQ=YEARLY") -> YEARLY
                else -> NONE
            }
        }
    }
}

data class CalendarCollection(
    val url: String,
    val displayName: String,
    val colorHex: String = DEFAULT_COLOR_HEX,
    val ctag: String? = null,
    val order: Int = 0,
    val readOnly: Boolean = false,
    val enabled: Boolean = true
) {
    val id: String
        get() = url.trimEnd('/').substringAfterLast('/').ifBlank { url }

    companion object {
        const val DEFAULT_COLOR_HEX = "#2563EB"
    }
}

data class VEventItem(
    val uid: String,
    val href: String,
    val etag: String? = null,
    val calendarUrl: String,
    val summary: String,
    val description: String = "",
    val location: String = "",
    val isAllDay: Boolean = false,
    val startDate: LocalDate,
    val endDate: LocalDate, // Exclusive for all-day (RFC 5545), inclusive date of endInstant for timed
    val startInstant: Instant,
    val endInstant: Instant,
    val timeZoneId: String? = null,
    val rrule: String? = null,
    val exDates: Set<LocalDate> = emptySet(),
    val status: EventStatus = EventStatus.CONFIRMED,
    val createdAt: Instant? = null,
    val lastModifiedAt: Instant? = null,
    val rawIcs: String? = null
) {
    val isRecurring: Boolean get() = !rrule.isNullOrBlank()
    val isCancelled: Boolean get() = status == EventStatus.CANCELLED
}

data class EventOccurrence(
    val event: VEventItem,
    val occurrenceDate: LocalDate,
    val occurrenceStart: Instant,
    val occurrenceEnd: Instant,
    val isAllDay: Boolean,
    val calendarName: String,
    val calendarColorHex: String
) {
    fun startLocalDateTime(zoneId: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        occurrenceStart.atZone(zoneId).toLocalDateTime()

    fun endLocalDateTime(zoneId: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        occurrenceEnd.atZone(zoneId).toLocalDateTime()
}

data class CalendarServerConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val directCollectionUrl: String = "",
    val selectedCalendarUrl: String? = null,
    val syncIntervalMinutes: Int = 30
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() || directCollectionUrl.isNotBlank()
}

sealed interface CalendarSyncState {
    data object Idle : CalendarSyncState
    data object Syncing : CalendarSyncState
    data class Error(val message: String) : CalendarSyncState
}

data class CalendarStoreState(
    val config: CalendarServerConfig = CalendarServerConfig(),
    val calendars: List<CalendarCollection> = emptyList(),
    val events: List<VEventItem> = emptyList(),
    val syncState: CalendarSyncState = CalendarSyncState.Idle,
    val lastSyncedAt: Instant? = null,
    val widgetMonthOffset: Int = 0
)
