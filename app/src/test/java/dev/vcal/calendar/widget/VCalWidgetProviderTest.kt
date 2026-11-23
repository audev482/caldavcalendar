package dev.vcal.calendar.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.vcal.calendar.R
import dev.vcal.calendar.VCalApp
import dev.vcal.calendar.data.CalendarWidgetUpdaterPort
import dev.vcal.calendar.data.JsonCalendarLocalStore
import dev.vcal.calendar.data.NoOpCalendarSyncScheduler
import dev.vcal.calendar.data.PassthroughCredentialCipher
import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.CalendarServerConfig
import dev.vcal.calendar.domain.CalendarStoreState
import dev.vcal.calendar.domain.VEventItem
import dev.vcal.calendar.protocol.CalDavEventService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = VCalApp::class)
class VCalWidgetProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: VCalApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun allThreeWidgetBuckets_inflateCleanlyWithMonthGridAndAgenda() {
        val parent = FrameLayout(app)
        val today = LocalDate.of(2026, 11, 23)
        val cal = CalendarCollection("https://cloud.test/dav/personal/", "Personal", "#2563EB")
        val state = CalendarStoreState(
            config = CalendarServerConfig(serverUrl = "https://cloud.test", username = "u", password = "p"),
            calendars = listOf(cal),
            events = listOf(
                VEventItem(
                    uid = "e1",
                    href = "${cal.url}e1.ics",
                    calendarUrl = cal.url,
                    summary = "Product Demo",
                    startDate = today,
                    endDate = today,
                    startInstant = Instant.parse("2026-11-23T15:00:00Z"),
                    endInstant = Instant.parse("2026-11-23T16:00:00Z")
                )
            ),
            lastSyncedAt = Instant.parse("2026-11-23T12:00:00Z")
        )

        for (bucket in VCalWidgetProvider.WidgetSizeBucket.entries) {
            val view = VCalWidgetProvider.buildBucketRemoteViews(app, state, bucket, today, ZoneOffset.UTC)
                .apply(app, parent)
            assertNotNull(view)
            when (bucket) {
                VCalWidgetProvider.WidgetSizeBucket.COMPACT_1X1 -> {
                    assertEquals("23", view.findViewById<TextView>(R.id.txt_compact_day).text.toString())
                    assertEquals("1 event", view.findViewById<TextView>(R.id.txt_compact_sub).text.toString())
                }
                VCalWidgetProvider.WidgetSizeBucket.MEDIUM_2X2 -> {
                    assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_agenda_list).visibility)
                }
                VCalWidgetProvider.WidgetSizeBucket.EXPANDED_4X3 -> {
                    assertEquals("November 2026", view.findViewById<TextView>(R.id.txt_widget_month_title).text.toString())
                    assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_month_grid).visibility)
                    assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_agenda_list).visibility)
                }
            }
        }
    }

    @Test
    fun widgetActionReceiver_navigatesMonthsAndCyclesCalendars() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val cal1 = CalendarCollection("https://cloud.test/dav/work/", "Work", "#2563EB")
        val cal2 = CalendarCollection("https://cloud.test/dav/home/", "Home", "#059669")
        val fakeCalDav = object : CalDavEventService {
            override suspend fun discoverCalendars(config: CalendarServerConfig) = listOf(cal1, cal2)
            override suspend fun fetchEvents(config: CalendarServerConfig, calendar: CalendarCollection) = emptyList<VEventItem>()
            override suspend fun createEvent(config: CalendarServerConfig, calendar: CalendarCollection, item: VEventItem) = item
            override suspend fun updateEvent(config: CalendarServerConfig, item: VEventItem) = item
            override suspend fun deleteEvent(config: CalendarServerConfig, item: VEventItem) = Unit
        }
        val store = JsonCalendarLocalStore(File(tempFolder.root, "cal_widget.json"), PassthroughCredentialCipher(), dispatcher)
        app.configureForTesting(
            calDav = fakeCalDav,
            localStore = store,
            widgetUpdater = object : CalendarWidgetUpdaterPort {
                override fun notifyWidgetsUpdated(state: CalendarStoreState) = Unit
            },
            syncScheduler = NoOpCalendarSyncScheduler,
            scope = backgroundScope
        )
        app.repository.saveServerAndSync(CalendarServerConfig(serverUrl = "https://cloud.test", username = "u", password = "p"))

        val receiver = VCalWidgetActionReceiver()
        receiver.onReceive(app, Intent(app, VCalWidgetActionReceiver::class.java).apply { action = VCalWidgetProvider.ACTION_WIDGET_NEXT_MONTH })
        assertEquals(1, app.repository.state.value.widgetMonthOffset)

        receiver.onReceive(app, Intent(app, VCalWidgetActionReceiver::class.java).apply { action = VCalWidgetProvider.ACTION_WIDGET_TODAY })
        assertEquals(0, app.repository.state.value.widgetMonthOffset)

        receiver.onReceive(app, Intent(app, VCalWidgetActionReceiver::class.java).apply { action = VCalWidgetProvider.ACTION_WIDGET_CYCLE_CAL })
        assertEquals(cal1.url, app.repository.state.value.config.selectedCalendarUrl)
    }

    @Test
    fun widgetProviderInfoXml_definesResizable4x3DefaultDownTo1x1() {
        val parser = app.resources.getXml(R.xml.vcal_widget_info)
        val androidNs = "http://schemas.android.com/apk/res/android"
        var targetW = 0
        var targetH = 0
        var resizeMode = 0
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "appwidget-provider") {
                targetW = parser.getAttributeIntValue(androidNs, "targetCellWidth", 0)
                targetH = parser.getAttributeIntValue(androidNs, "targetCellHeight", 0)
                resizeMode = parser.getAttributeIntValue(androidNs, "resizeMode", 0)
            }
        }
        assertEquals(4, targetW)
        assertEquals(3, targetH)
        assertEquals(AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL, resizeMode)
    }
}
