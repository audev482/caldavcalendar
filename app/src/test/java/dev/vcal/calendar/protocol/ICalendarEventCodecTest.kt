package dev.vcal.calendar.protocol

import dev.vcal.calendar.domain.EventStatus
import dev.vcal.calendar.domain.VEventItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ICalendarEventCodecTest {

    @Test
    fun parseVEvent_parsesTimedEventWithRruleExDateAndValarmIsolation() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Nextcloud Calendar
            BEGIN:VEVENT
            UID:evt-100
            DTSTART:20261123T140000Z
            DTEND:20261123T153000Z
            SUMMARY:Weekly Design\, Sync
            DESCRIPTION:Discuss roadmap\nBring mockups
            LOCATION:Room 4B
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6
            EXDATE;VALUE=DATE:20261125
            STATUS:CONFIRMED
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            SUMMARY:Alarm summary must NOT overwrite event summary
            DESCRIPTION:Alarm description must NOT overwrite event description
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val item = ICalendarEventCodec.parseVEvent(
            ics = ics,
            href = "https://cloud.test/dav/personal/evt-100.ics",
            etag = "\"e1\"",
            calendarUrl = "https://cloud.test/dav/personal/",
            zoneId = ZoneOffset.UTC
        )

        assertNotNull(item)
        item!!
        assertEquals("evt-100", item.uid)
        assertEquals("Weekly Design, Sync", item.summary)
        assertEquals("Discuss roadmap\nBring mockups", item.description)
        assertEquals("Room 4B", item.location)
        assertFalse(item.isAllDay)
        assertEquals(Instant.parse("2026-11-23T14:00:00Z"), item.startInstant)
        assertEquals(Instant.parse("2026-11-23T15:30:00Z"), item.endInstant)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6", item.rrule)
        assertTrue(LocalDate.of(2026, 11, 25) in item.exDates)
        assertEquals(EventStatus.CONFIRMED, item.status)
    }

    @Test
    fun parseVEvent_ignoresVTodoOnlyCalendar() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:todo-1
            SUMMARY:Not an event
            END:VTODO
            END:VCALENDAR
        """.trimIndent()
        assertNull(ICalendarEventCodec.parseVEvent(ics, "/dav/todo-1.ics", null, "/dav/"))
    }

    @Test
    fun serializeAndUpdateRawIcs_preservesValarmAndFoldsLines() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:evt-preserve
            DTSTART;VALUE=DATE:20261124
            DTEND;VALUE=DATE:20261125
            SUMMARY:Original Event
            X-CUSTOM-PROP:keep-me
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:Keep alarm
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val parsed = ICalendarEventCodec.parseVEvent(original, "/dav/e.ics", "\"1\"", "/dav/", ZoneOffset.UTC)!!
        assertTrue(parsed.isAllDay)
        assertEquals(LocalDate.of(2026, 11, 24), parsed.startDate)
        assertEquals(LocalDate.of(2026, 11, 25), parsed.endDate)

        val updated = parsed.copy(summary = "Updated Title", location = "HQ")
        val updatedIcs = ICalendarEventCodec.updateRawIcs(updated, Instant.parse("2026-11-23T00:00:00Z"))
        assertTrue(updatedIcs.contains("SUMMARY:Updated Title\r\n"))
        assertTrue(updatedIcs.contains("LOCATION:HQ\r\n"))
        assertTrue(updatedIcs.contains("X-CUSTOM-PROP:keep-me\r\n"))
        assertTrue(updatedIcs.contains("DESCRIPTION:Keep alarm\r\n"))
    }
}
