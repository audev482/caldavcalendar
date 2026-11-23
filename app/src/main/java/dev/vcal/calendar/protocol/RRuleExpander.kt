package dev.vcal.calendar.protocol

import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.EventOccurrence
import dev.vcal.calendar.domain.VEventItem
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Expands RFC 5545 VEVENT items (single-day, multi-day all-day, and RRULE recurring events)
 * into concrete [EventOccurrence] instances within a requested date window `[windowStart, windowEnd]`.
 */
object RRuleExpander {

    private const val MAX_OCCURRENCES_PER_EVENT = 400
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val UTC_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun expandEvents(
        events: List<VEventItem>,
        calendarsByUrl: Map<String, CalendarCollection>,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<EventOccurrence> {
        val result = mutableListOf<EventOccurrence>()
        for (event in events) {
            if (event.isCancelled) continue
            val cal = calendarsByUrl[event.calendarUrl]
            if (cal != null && !cal.enabled) continue
            val calName = cal?.displayName ?: "Calendar"
            val calColor = cal?.colorHex ?: CalendarCollection.DEFAULT_COLOR_HEX

            result += expandSingleEvent(
                event = event,
                calendarName = calName,
                calendarColorHex = calColor,
                windowStart = windowStart,
                windowEnd = windowEnd,
                zoneId = zoneId
            )
        }
        return result.sortedWith(
            compareBy<EventOccurrence> { it.occurrenceDate }
                .thenBy { if (it.isAllDay) 0 else 1 }
                .thenBy { it.occurrenceStart }
                .thenBy { it.event.summary.lowercase() }
        )
    }

    fun expandSingleEvent(
        event: VEventItem,
        calendarName: String,
        calendarColorHex: String,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<EventOccurrence> {
        if (event.rrule.isNullOrBlank()) {
            return expandNonRecurring(event, calendarName, calendarColorHex, windowStart, windowEnd, zoneId)
        }

        val parts = event.rrule.split(";")
            .mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) null else token.substring(0, idx).trim().uppercase() to token.substring(idx + 1).trim().uppercase()
            }
            .toMap()

        val freq = parts["FREQ"] ?: return expandNonRecurring(event, calendarName, calendarColorHex, windowStart, windowEnd, zoneId)
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = parts["COUNT"]?.toIntOrNull()?.coerceAtLeast(1)
        val untilDate = parts["UNTIL"]?.let { parseUntilDate(it, zoneId) }
        val byDays = parts["BYDAY"]?.split(",")?.mapNotNull { parseDayOfWeek(it) }?.toSet().orEmpty()

        val effectiveEnd = if (untilDate != null && untilDate.isBefore(windowEnd)) untilDate else windowEnd
        if (event.startDate.isAfter(effectiveEnd)) return emptyList()

        val duration = Duration.between(event.startInstant, event.endInstant).let {
            if (it.isNegative) Duration.ZERO else it
        }
        val startLocalTime = event.startInstant.atZone(zoneId).toLocalTime()

        val occurrences = mutableListOf<EventOccurrence>()
        var emittedTotal = 0

        fun emitIfInWindow(date: LocalDate): Boolean {
            if (date.isAfter(effectiveEnd)) return false
            if (date.isBefore(event.startDate)) return true
            emittedTotal++
            if (count != null && emittedTotal > count) return false
            if (date !in event.exDates && !date.isBefore(windowStart)) {
                val occStart = if (event.isAllDay) {
                    date.atStartOfDay(zoneId).toInstant()
                } else {
                    date.atTime(startLocalTime).atZone(zoneId).toInstant()
                }
                val occEnd = if (event.isAllDay) {
                    date.plusDays(1).atStartOfDay(zoneId).toInstant()
                } else {
                    occStart.plus(duration)
                }
                occurrences += EventOccurrence(
                    event = event,
                    occurrenceDate = date,
                    occurrenceStart = occStart,
                    occurrenceEnd = occEnd,
                    isAllDay = event.isAllDay,
                    calendarName = calendarName,
                    calendarColorHex = calendarColorHex
                )
            }
            return occurrences.size < MAX_OCCURRENCES_PER_EVENT
        }

        when (freq) {
            "DAILY" -> {
                var cursor = event.startDate
                var guard = 0
                while (!cursor.isAfter(effectiveEnd) && guard++ < 5000) {
                    if (!emitIfInWindow(cursor)) break
                    cursor = cursor.plusDays(interval.toLong())
                }
            }
            "WEEKLY" -> {
                val targetDays = byDays.ifEmpty { setOf(event.startDate.dayOfWeek) }
                // Walk week-by-week starting from the Monday of event.startDate's week
                var weekStart = event.startDate.with(DayOfWeek.MONDAY)
                var guard = 0
                outer@ while (!weekStart.isAfter(effectiveEnd) && guard++ < 2000) {
                    for (offset in 0L..6L) {
                        val candidate = weekStart.plusDays(offset)
                        if (candidate.dayOfWeek in targetDays && !candidate.isBefore(event.startDate)) {
                            if (!emitIfInWindow(candidate)) break@outer
                        }
                    }
                    weekStart = weekStart.plusWeeks(interval.toLong())
                }
            }
            "MONTHLY" -> {
                var cursor = event.startDate
                var step = 0L
                while (!cursor.isAfter(effectiveEnd) && step < 1200) {
                    if (!emitIfInWindow(cursor)) break
                    step += interval
                    cursor = event.startDate.plusMonths(step)
                }
            }
            "YEARLY" -> {
                var cursor = event.startDate
                var step = 0L
                while (!cursor.isAfter(effectiveEnd) && step < 200) {
                    if (!emitIfInWindow(cursor)) break
                    step += interval
                    cursor = event.startDate.plusYears(step)
                }
            }
            else -> return expandNonRecurring(event, calendarName, calendarColorHex, windowStart, windowEnd, zoneId)
        }

        return occurrences
    }

    private fun expandNonRecurring(
        event: VEventItem,
        calendarName: String,
        calendarColorHex: String,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        zoneId: ZoneId
    ): List<EventOccurrence> {
        if (event.isAllDay) {
            val lastInclusive = if (event.endDate.isAfter(event.startDate)) event.endDate.minusDays(1) else event.startDate
            if (lastInclusive.isBefore(windowStart) || event.startDate.isAfter(windowEnd)) return emptyList()
            val start = if (event.startDate.isBefore(windowStart)) windowStart else event.startDate
            val end = if (lastInclusive.isAfter(windowEnd)) windowEnd else lastInclusive
            val list = mutableListOf<EventOccurrence>()
            var d = start
            while (!d.isAfter(end)) {
                list += EventOccurrence(
                    event = event,
                    occurrenceDate = d,
                    occurrenceStart = d.atStartOfDay(zoneId).toInstant(),
                    occurrenceEnd = d.plusDays(1).atStartOfDay(zoneId).toInstant(),
                    isAllDay = true,
                    calendarName = calendarName,
                    calendarColorHex = calendarColorHex
                )
                d = d.plusDays(1)
            }
            return list
        }

        if (event.startDate.isBefore(windowStart) || event.startDate.isAfter(windowEnd)) return emptyList()
        return listOf(
            EventOccurrence(
                event = event,
                occurrenceDate = event.startDate,
                occurrenceStart = event.startInstant,
                occurrenceEnd = event.endInstant,
                isAllDay = false,
                calendarName = calendarName,
                calendarColorHex = calendarColorHex
            )
        )
    }

    private fun parseUntilDate(raw: String, zoneId: ZoneId): LocalDate? {
        val cleaned = raw.trim()
        return when {
            cleaned.length == 8 -> runCatching { LocalDate.parse(cleaned, DATE_FORMATTER) }.getOrNull()
            cleaned.length >= 15 -> runCatching {
                LocalDateTime.parse(cleaned.substring(0, 15), UTC_FORMATTER)
                    .toInstant(ZoneOffset.UTC)
                    .atZone(zoneId)
                    .toLocalDate()
            }.getOrNull()
            else -> null
        }
    }

    private fun parseDayOfWeek(token: String): DayOfWeek? {
        val code = token.takeLast(2).uppercase()
        return when (code) {
            "MO" -> DayOfWeek.MONDAY
            "TU" -> DayOfWeek.TUESDAY
            "WE" -> DayOfWeek.WEDNESDAY
            "TH" -> DayOfWeek.THURSDAY
            "FR" -> DayOfWeek.FRIDAY
            "SA" -> DayOfWeek.SATURDAY
            "SU" -> DayOfWeek.SUNDAY
            else -> null
        }
    }
}
