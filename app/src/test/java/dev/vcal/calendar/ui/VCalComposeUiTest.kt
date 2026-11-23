package dev.vcal.calendar.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.vcal.calendar.VCalApp
import dev.vcal.calendar.data.JsonCalendarLocalStore
import dev.vcal.calendar.data.NoOpCalendarSyncScheduler
import dev.vcal.calendar.data.NoOpCalendarWidgetUpdater
import dev.vcal.calendar.data.PassthroughCredentialCipher
import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.CalendarServerConfig
import dev.vcal.calendar.domain.VEventItem
import dev.vcal.calendar.protocol.CalDavEventService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h720dp", application = VCalApp::class)
class VCalComposeUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun configureServer_syncsAndDisplaysCalendarEvent() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val app = ApplicationProvider.getApplicationContext<VCalApp>()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val cal = CalendarCollection("https://cloud.test/remote.php/dav/calendars/alice/personal/", "Personal", "#2563EB")
        val events = mutableListOf(
            VEventItem(
                uid = "evt-today",
                href = "${cal.url}evt-today.ics",
                etag = "\"e1\"",
                calendarUrl = cal.url,
                summary = "Sprint Planning",
                startDate = today,
                endDate = today,
                startInstant = today.atTime(10, 0).atZone(zone).toInstant(),
                endInstant = today.atTime(11, 0).atZone(zone).toInstant()
            )
        )
        val fakeCalDav = object : CalDavEventService {
            override suspend fun discoverCalendars(config: CalendarServerConfig) = listOf(cal)
            override suspend fun fetchEvents(config: CalendarServerConfig, calendar: CalendarCollection) = events.toList()
            override suspend fun createEvent(config: CalendarServerConfig, calendar: CalendarCollection, item: VEventItem): VEventItem {
                val created = item.copy(href = "${calendar.url}${item.uid}.ics", etag = "\"e2\"")
                events += created
                return created
            }
            override suspend fun updateEvent(config: CalendarServerConfig, item: VEventItem) = item
            override suspend fun deleteEvent(config: CalendarServerConfig, item: VEventItem) = Unit
        }

        val store = JsonCalendarLocalStore(File(tempFolder.root, "ui_cal.json"), PassthroughCredentialCipher(), dispatcher)
        app.configureForTesting(fakeCalDav, store, NoOpCalendarWidgetUpdater, NoOpCalendarSyncScheduler, backgroundScope)

        Robolectric.buildActivity(MainActivity::class.java).setup().get()

        composeRule.onNodeWithTag("screen_server_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("input_server_url").performTextInput("https://cloud.test")
        composeRule.onNodeWithTag("input_username").performTextInput("alice")
        composeRule.onNodeWithTag("input_password").performTextInput("app-pass")
        composeRule.onNodeWithTag("btn_save_server").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Sprint Planning").assertIsDisplayed()
        assertEquals(1, app.repository.state.value.events.size)
    }
}
