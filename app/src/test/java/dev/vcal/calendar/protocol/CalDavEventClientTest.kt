package dev.vcal.calendar.protocol

import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.CalendarServerConfig
import dev.vcal.calendar.domain.VEventItem
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CalDavEventClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CalDavEventClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CalDavEventClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun discoverCalendars_andCrudEvents_overCalDav() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.getHeader("Authorization")
                if (auth == null || !auth.startsWith("Basic ")) return MockResponse().setResponseCode(401)
                return when (request.path) {
                    "/", "" -> MockResponse().setResponseCode(405)
                    "/.well-known/caldav" -> MockResponse().setResponseCode(301).addHeader("Location", "/remote.php/dav/")
                    "/remote.php/dav/" -> MockResponse().setResponseCode(207).setBody(
                        """
                        <?xml version="1.0"?>
                        <d:multistatus xmlns:d="DAV:">
                          <d:response><d:href>/remote.php/dav/</d:href><d:propstat><d:prop>
                            <d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal>
                          </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                        </d:multistatus>
                        """.trimIndent()
                    )
                    "/remote.php/dav/principals/users/alice/" -> MockResponse().setResponseCode(207).setBody(
                        """
                        <?xml version="1.0"?>
                        <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                          <d:response><d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop>
                            <cal:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></cal:calendar-home-set>
                          </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                        </d:multistatus>
                        """.trimIndent()
                    )
                    "/remote.php/dav/calendars/alice/" -> MockResponse().setResponseCode(207).setBody(
                        """
                        <?xml version="1.0"?>
                        <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
                          <d:response><d:href>/remote.php/dav/calendars/alice/personal/</d:href><d:propstat><d:prop>
                            <d:displayname>Personal</d:displayname>
                            <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                            <cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set>
                            <a:calendar-color>#1D4ED8FF</a:calendar-color>
                          </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                          <d:response><d:href>/remote.php/dav/calendars/alice/tasks-only/</d:href><d:propstat><d:prop>
                            <d:displayname>Tasks Only</d:displayname>
                            <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                            <cal:supported-calendar-component-set><cal:comp name="VTODO"/></cal:supported-calendar-component-set>
                          </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                        </d:multistatus>
                        """.trimIndent()
                    )
                    "/remote.php/dav/calendars/alice/personal/" -> MockResponse().setResponseCode(207).setBody(
                        """
                        <?xml version="1.0"?>
                        <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                          <d:response><d:href>/remote.php/dav/calendars/alice/personal/e1.ics</d:href><d:propstat><d:prop>
                            <d:getetag>"v1"</d:getetag>
                            <cal:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:e1
DTSTART:20261123T140000Z
DTEND:20261123T150000Z
SUMMARY:Team Sync
END:VEVENT
END:VCALENDAR</cal:calendar-data>
                          </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                        </d:multistatus>
                        """.trimIndent()
                    )
                    "/remote.php/dav/calendars/alice/personal/e2.ics" -> when (request.method) {
                        "PUT" -> MockResponse().setResponseCode(201).addHeader("ETag", "\"v2\"")
                        "DELETE" -> MockResponse().setResponseCode(204)
                        else -> MockResponse().setResponseCode(405)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val config = CalendarServerConfig(serverUrl = server.url("/").toString(), username = "alice", password = "pw")
        val cals = client.discoverCalendars(config)
        assertEquals(1, cals.size)
        assertEquals("Personal", cals[0].displayName)
        assertEquals("#1D4ED8", cals[0].colorHex)

        val fetched = client.fetchEvents(config, cals[0])
        assertEquals(1, fetched.size)
        assertEquals("Team Sync", fetched[0].summary)

        val created = client.createEvent(
            config,
            cals[0],
            VEventItem(
                uid = "e2",
                href = "",
                calendarUrl = cals[0].url,
                summary = "Lunch",
                startDate = LocalDate.of(2026, 11, 24),
                endDate = LocalDate.of(2026, 11, 24),
                startInstant = Instant.parse("2026-11-24T17:00:00Z"),
                endInstant = Instant.parse("2026-11-24T18:00:00Z")
            )
        )
        assertEquals("\"v2\"", created.etag)
        assertTrue(created.href.endsWith("/e2.ics"))
        client.deleteEvent(config, created)
    }
}
