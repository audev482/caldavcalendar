package dev.vcal.calendar.protocol

import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.VEventItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RRuleExpanderTest {

    @Test
    fun expandEvents_expandsWeeklyByDayWithExDateAndMultiDayAllDay() {
        val cal = CalendarCollection(url = "https://cloud.test/dav/work/", displayName = "Work", colorHex = "#2563EB")
        val recurring = VEventItem(
            uid = "rec-1",
            href = "/dav/rec-1.ics",
            calendarUrl = cal.url,
            summary = "Standup",
            isAllDay = false,
            startDate = LocalDate.of(2026, 11, 23), // Monday
            endDate = LocalDate.of(2026, 11, 23),
            startInstant = Instant.parse("2026-11-23T15:00:00Z"),
            endInstant = Instant.parse("2026-11-23T15:30:00Z"),
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=4",
            exDates = setOf(LocalDate.of(2026, 11, 25)) // Skip Wednesday
        )
        val allDay = VEventItem(
            uid = "allday-1",
            href = "/dav/allday-1.ics",
            calendarUrl = cal.url,
            summary = "Offsite",
            isAllDay = true,
            startDate = LocalDate.of(2026, 11, 24),
            endDate = LocalDate.of(2026, 11, 26), // Exclusive -> Nov 24 & Nov 25
            startInstant = Instant.parse("2026-11-24T00:00:00Z"),
            endInstant = Instant.parse("2026-11-26T00:00:00Z")
        )

        val expanded = RRuleExpander.expandEvents(
            events = listOf(recurring, allDay),
            calendarsByUrl = mapOf(cal.url to cal),
            windowStart = LocalDate.of(2026, 11, 23),
            windowEnd = LocalDate.of(2026, 12, 1),
            zoneId = ZoneOffset.UTC
        )

        val standupDates = expanded.filter { it.event.uid == "rec-1" }.map { it.occurrenceDate }
        // MO Nov 23, (WE Nov 25 excluded), FR Nov 27, MO Nov 30
        assertEquals(
            listOf(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 30)),
            standupDates
        )

        val offsiteDates = expanded.filter { it.event.uid == "allday-1" }.map { it.occurrenceDate }
        assertEquals(listOf(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)), offsiteDates)
    }
}
