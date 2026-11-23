package dev.vcal.calendar.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.vcal.calendar.VCalApp
import dev.vcal.calendar.widget.VCalWidgetProvider
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    private var openSettingsRequest by mutableStateOf(false)
    private var openEventUidRequest by mutableStateOf<String?>(null)
    private var selectedDateRequest by mutableStateOf<LocalDate?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleLaunchIntent(intent)
        val app = applicationContext as VCalApp

        setContent {
            val state by app.repository.state.collectAsState()

            LaunchedEffect(Unit) {
                app.repository.initialize()
                if (app.repository.state.value.config.isConfigured &&
                    app.repository.state.value.events.isEmpty()
                ) {
                    app.repository.syncAll()
                }
            }

            VCalTheme {
                VCalMainScreen(
                    state = state,
                    initialOpenSettings = openSettingsRequest,
                    initialEventUid = openEventUidRequest,
                    initialSelectedDate = selectedDateRequest,
                    onSyncNow = { app.appScope.launch { app.repository.syncAll() } },
                    onCreateEvent = { summary, calUrl, desc, loc, allDay, sDate, eDate, sInst, eInst, rrule ->
                        app.repository.createEvent(
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
                    },
                    onUpdateEvent = { app.repository.updateEvent(it) },
                    onDeleteEvent = { app.repository.deleteEvent(it) },
                    onToggleCalendarEnabled = { url -> app.appScope.launch { app.repository.toggleCalendarEnabled(url) } },
                    onTestConnection = { app.repository.testConnection(it) },
                    onSaveServer = { app.repository.saveServerAndSync(it) },
                    onUpdateSyncInterval = { mins -> app.appScope.launch { app.repository.updateSyncInterval(mins) } },
                    onSelectWidgetCalendar = { url -> app.appScope.launch { app.repository.selectWidgetCalendar(url) } },
                    onDisconnectServer = { app.appScope.launch { app.repository.disconnectServer() } },
                    onClearError = { app.repository.clearError() },
                    onPinWidget = { requestPinVCalWidget() }
                )
            }
        }

        enableSeamlessDesktopWindowHeader()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(VCalWidgetProvider.EXTRA_OPEN_SETTINGS, false)) {
            openSettingsRequest = true
        }
        val uid = intent.getStringExtra(VCalWidgetProvider.EXTRA_EVENT_UID)
        if (!uid.isNullOrBlank()) {
            openEventUidRequest = uid
        }
        val dateStr = intent.getStringExtra(VCalWidgetProvider.EXTRA_SELECTED_DATE)
        if (!dateStr.isNullOrBlank()) {
            runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { selectedDateRequest = it }
        }
    }

    private fun enableSeamlessDesktopWindowHeader() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val controller = window.decorView.windowInsetsController ?: window.insetsController ?: return
            val clearCaption = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
            val lightCaption = WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
            val appearance = if (!isNight) clearCaption or lightCaption else clearCaption
            controller.setSystemBarsAppearance(appearance, clearCaption or lightCaption)
        }
    }

    private fun requestPinVCalWidget() {
        runCatching {
            val manager = AppWidgetManager.getInstance(this)
            val provider = ComponentName(this, VCalWidgetProvider::class.java)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(provider, null, null)
            } else {
                Toast.makeText(
                    this,
                    "Long-press the desktop to add the VCal Calendar widget.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
