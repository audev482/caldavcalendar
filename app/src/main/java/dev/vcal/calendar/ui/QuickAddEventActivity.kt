package dev.vcal.calendar.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.vcal.calendar.VCalApp
import kotlinx.coroutines.launch
import java.time.LocalDate

class QuickAddEventActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as VCalApp

        setContent {
            val state by app.repository.state.collectAsState()
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                app.repository.initialize()
            }

            VCalTheme {
                EventEditorDialog(
                    existing = null,
                    defaultDate = LocalDate.now(),
                    calendars = state.calendars,
                    defaultCalendarUrl = state.config.selectedCalendarUrl,
                    onDismiss = { finish() },
                    onSaveNew = { summary, calUrl, desc, loc, allDay, sDate, eDate, sInst, eInst, rrule ->
                        scope.launch {
                            val res = app.repository.createEvent(
                                summary = summary,
                                calendarUrl = calUrl,
                                description = desc,
                                location = loc,
                                isAllDay = allDay,
                                startDate = sDate,
                                endDate = eDate,
                                startInstant = sInst,
                                endInstant = eInst,
                                rrule = rrule
                            )
                            if (res.isSuccess) finish()
                        }
                    },
                    onSaveEdit = {},
                    onDelete = {}
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
