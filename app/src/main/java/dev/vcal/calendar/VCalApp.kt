package dev.vcal.calendar

import android.app.Application
import dev.vcal.calendar.data.AndroidKeyStoreCipher
import dev.vcal.calendar.data.CalendarLocalStore
import dev.vcal.calendar.data.CalendarRepository
import dev.vcal.calendar.data.CalendarSyncSchedulerPort
import dev.vcal.calendar.data.CalendarWidgetUpdaterPort
import dev.vcal.calendar.data.JsonCalendarLocalStore
import dev.vcal.calendar.protocol.CalDavEventClient
import dev.vcal.calendar.protocol.CalDavEventService
import dev.vcal.calendar.sync.WorkManagerCalendarSyncScheduler
import dev.vcal.calendar.widget.AndroidCalendarWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class VCalApp : Application() {

    var appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private set

    lateinit var repository: CalendarRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val store = JsonCalendarLocalStore(
            file = File(filesDir, "vcal_state.json"),
            cipher = AndroidKeyStoreCipher()
        )
        repository = CalendarRepository(
            calDav = CalDavEventClient(),
            localStore = store,
            widgetUpdater = AndroidCalendarWidgetUpdater(this),
            syncScheduler = WorkManagerCalendarSyncScheduler(this)
        )
        appScope.launch {
            repository.initialize()
        }
    }

    fun configureForTesting(
        calDav: CalDavEventService,
        localStore: CalendarLocalStore,
        widgetUpdater: CalendarWidgetUpdaterPort,
        syncScheduler: CalendarSyncSchedulerPort,
        scope: CoroutineScope
    ) {
        appScope = scope
        repository = CalendarRepository(
            calDav = calDav,
            localStore = localStore,
            widgetUpdater = widgetUpdater,
            syncScheduler = syncScheduler
        )
    }
}
