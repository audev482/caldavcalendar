package dev.vcal.calendar.data

import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.CalendarServerConfig
import dev.vcal.calendar.domain.CalendarStoreState
import dev.vcal.calendar.domain.EventStatus
import dev.vcal.calendar.domain.VEventItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate

interface CalendarLocalStore {
    suspend fun loadSnapshot(): CalendarStoreState
    suspend fun saveSnapshot(
        config: CalendarServerConfig,
        calendars: List<CalendarCollection>,
        events: List<VEventItem>,
        lastSyncedAt: Instant?
    )
}

class JsonCalendarLocalStore(
    private val file: File,
    private val cipher: CredentialCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CalendarLocalStore {

    private val mutex = Mutex()

    override suspend fun loadSnapshot(): CalendarStoreState = withContext(ioDispatcher) {
        mutex.withLock {
            if (!file.exists()) return@withLock CalendarStoreState()
            runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val cfgObj = root.optJSONObject("config")
                val config = if (cfgObj != null) {
                    CalendarServerConfig(
                        serverUrl = cfgObj.optString("serverUrl", ""),
                        username = cfgObj.optString("username", ""),
                        password = cipher.decrypt(cfgObj.optString("passwordEnc", "")),
                        directCollectionUrl = cfgObj.optString("directCollectionUrl", ""),
                        selectedCalendarUrl = cfgObj.optString("selectedCalendarUrl", "").ifBlank { null },
                        syncIntervalMinutes = cfgObj.optInt("syncIntervalMinutes", 30)
                    )
                } else {
                    CalendarServerConfig()
                }

                val calendars = mutableListOf<CalendarCollection>()
                val calsArr = root.optJSONArray("calendars")
                if (calsArr != null) {
                    for (i in 0 until calsArr.length()) {
                        val obj = calsArr.optJSONObject(i) ?: continue
                        calendars += CalendarCollection(
                            url = obj.optString("url"),
                            displayName = obj.optString("displayName", "Calendar"),
                            colorHex = obj.optString("colorHex", CalendarCollection.DEFAULT_COLOR_HEX),
                            ctag = obj.optString("ctag", "").ifBlank { null },
                            order = obj.optInt("order", 0),
                            readOnly = obj.optBoolean("readOnly", false),
                            enabled = obj.optBoolean("enabled", true)
                        )
                    }
                }

                val events = mutableListOf<VEventItem>()
                val eventsArr = root.optJSONArray("events")
                if (eventsArr != null) {
                    for (i in 0 until eventsArr.length()) {
                        val obj = eventsArr.optJSONObject(i) ?: continue
                        val startDate = runCatching { LocalDate.parse(obj.optString("startDate")) }.getOrNull() ?: continue
                        val endDate = runCatching { LocalDate.parse(obj.optString("endDate")) }.getOrNull() ?: startDate
                        val startMillis = obj.optLong("startInstantMillis", 0L)
                        val endMillis = obj.optLong("endInstantMillis", startMillis)
                        val exDates = mutableSetOf<LocalDate>()
                        val exArr = obj.optJSONArray("exDates")
                        if (exArr != null) {
                            for (e in 0 until exArr.length()) {
                                runCatching { LocalDate.parse(exArr.optString(e)) }.getOrNull()?.let { exDates += it }
                            }
                        }
                        val createdMillis = obj.optLong("createdAtMillis", Long.MIN_VALUE)
                        val modifiedMillis = obj.optLong("lastModifiedAtMillis", Long.MIN_VALUE)
                        events += VEventItem(
                            uid = obj.optString("uid"),
                            href = obj.optString("href"),
                            etag = obj.optString("etag", "").ifBlank { null },
                            calendarUrl = obj.optString("calendarUrl"),
                            summary = obj.optString("summary"),
                            description = obj.optString("description", ""),
                            location = obj.optString("location", ""),
                            isAllDay = obj.optBoolean("isAllDay", false),
                            startDate = startDate,
                            endDate = endDate,
                            startInstant = Instant.ofEpochMilli(startMillis),
                            endInstant = Instant.ofEpochMilli(endMillis),
                            timeZoneId = obj.optString("timeZoneId", "").ifBlank { null },
                            rrule = obj.optString("rrule", "").ifBlank { null },
                            exDates = exDates,
                            status = EventStatus.fromRfc(obj.optString("status")),
                            createdAt = if (createdMillis != Long.MIN_VALUE) Instant.ofEpochMilli(createdMillis) else null,
                            lastModifiedAt = if (modifiedMillis != Long.MIN_VALUE) Instant.ofEpochMilli(modifiedMillis) else null,
                            rawIcs = obj.optString("rawIcs", "").ifBlank { null }
                        )
                    }
                }

                val lastSyncedMillis = root.optLong("lastSyncedAtMillis", Long.MIN_VALUE)
                CalendarStoreState(
                    config = config,
                    calendars = calendars,
                    events = events,
                    lastSyncedAt = if (lastSyncedMillis != Long.MIN_VALUE) Instant.ofEpochMilli(lastSyncedMillis) else null
                )
            }.getOrElse { CalendarStoreState() }
        }
    }

    override suspend fun saveSnapshot(
        config: CalendarServerConfig,
        calendars: List<CalendarCollection>,
        events: List<VEventItem>,
        lastSyncedAt: Instant?
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val root = JSONObject()
            root.put(
                "config",
                JSONObject().apply {
                    put("serverUrl", config.serverUrl)
                    put("username", config.username)
                    put("passwordEnc", cipher.encrypt(config.password))
                    put("directCollectionUrl", config.directCollectionUrl)
                    put("selectedCalendarUrl", config.selectedCalendarUrl ?: "")
                    put("syncIntervalMinutes", config.syncIntervalMinutes)
                }
            )

            val calsArr = JSONArray()
            for (cal in calendars) {
                calsArr.put(
                    JSONObject().apply {
                        put("url", cal.url)
                        put("displayName", cal.displayName)
                        put("colorHex", cal.colorHex)
                        put("ctag", cal.ctag ?: "")
                        put("order", cal.order)
                        put("readOnly", cal.readOnly)
                        put("enabled", cal.enabled)
                    }
                )
            }
            root.put("calendars", calsArr)

            val eventsArr = JSONArray()
            for (ev in events) {
                eventsArr.put(
                    JSONObject().apply {
                        put("uid", ev.uid)
                        put("href", ev.href)
                        put("etag", ev.etag ?: "")
                        put("calendarUrl", ev.calendarUrl)
                        put("summary", ev.summary)
                        put("description", ev.description)
                        put("location", ev.location)
                        put("isAllDay", ev.isAllDay)
                        put("startDate", ev.startDate.toString())
                        put("endDate", ev.endDate.toString())
                        put("startInstantMillis", ev.startInstant.toEpochMilli())
                        put("endInstantMillis", ev.endInstant.toEpochMilli())
                        put("timeZoneId", ev.timeZoneId ?: "")
                        put("rrule", ev.rrule ?: "")
                        val exArr = JSONArray()
                        ev.exDates.forEach { exArr.put(it.toString()) }
                        put("exDates", exArr)
                        put("status", ev.status.rfcValue)
                        if (ev.createdAt != null) put("createdAtMillis", ev.createdAt.toEpochMilli())
                        if (ev.lastModifiedAt != null) put("lastModifiedAtMillis", ev.lastModifiedAt.toEpochMilli())
                        put("rawIcs", ev.rawIcs ?: "")
                    }
                )
            }
            root.put("events", eventsArr)

            if (lastSyncedAt != null) {
                root.put("lastSyncedAtMillis", lastSyncedAt.toEpochMilli())
            }

            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
        }
    }
}
