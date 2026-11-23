package dev.vcal.calendar.protocol

import dev.vcal.calendar.domain.EventStatus
import dev.vcal.calendar.domain.VEventItem
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * RFC 5545 iCalendar VEVENT parser and lossless serializer compatible with Nextcloud Calendar
 * and standard RFC 4791 CalDAV servers.
 */
object ICalendarEventCodec {

    private val UTC_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd")

    private val MANAGED_UPDATE_PROPS = setOf(
        "SUMMARY",
        "DESCRIPTION",
        "LOCATION",
        "DTSTART",
        "DTEND",
        "DURATION",
        "RRULE",
        "STATUS",
        "LAST-MODIFIED",
        "DTSTAMP"
    )

    internal data class ContentLine(
        val name: String,
        val params: Map<String, String>,
        val value: String,
        val rawLine: String
    )

    fun parseVEvent(
        ics: String,
        href: String,
        etag: String?,
        calendarUrl: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): VEventItem? {
        if (ics.isBlank()) return null

        val lines = unfoldLines(ics)
        val stack = ArrayDeque<String>()
        var foundVEvent = false

        var uid: String? = null
        var summary = ""
        var description = ""
        var location = ""
        var status = EventStatus.CONFIRMED
        var rrule: String? = null
        val exDates = mutableSetOf<LocalDate>()

        var startIsDate = false
        var startDate: LocalDate? = null
        var startInstant: Instant? = null
        var startTzId: String? = null

        var endDate: LocalDate? = null
        var endInstant: Instant? = null
        var durationStr: String? = null

        var createdAt: Instant? = null
        var lastModifiedAt: Instant? = null

        for (rawLine in lines) {
            if (rawLine.isBlank()) continue
            val parsed = parseContentLine(rawLine) ?: continue
            val upperName = parsed.name.uppercase()

            if (upperName == "BEGIN") {
                val component = parsed.value.trim().uppercase()
                stack.addLast(component)
                if (component == "VEVENT" && !foundVEvent) {
                    foundVEvent = true
                }
                continue
            }
            if (upperName == "END") {
                val component = parsed.value.trim().uppercase()
                if (stack.isNotEmpty() && stack.last() == component) {
                    stack.removeLast()
                }
                if (component == "VEVENT" && foundVEvent) {
                    break
                }
                continue
            }

            // Only extract direct properties of the primary VEVENT (ignore VALARM / VTIMEZONE)
            if (stack.lastOrNull() != "VEVENT") continue

            when (upperName) {
                "UID" -> uid = parsed.value.trim()
                "SUMMARY" -> summary = unescapeText(parsed.value)
                "DESCRIPTION" -> description = unescapeText(parsed.value)
                "LOCATION" -> location = unescapeText(parsed.value)
                "STATUS" -> status = EventStatus.fromRfc(parsed.value)
                "RRULE" -> rrule = parsed.value.trim().takeIf { it.isNotEmpty() }
                "EXDATE" -> {
                    for (token in parsed.value.split(",")) {
                        val parsedEx = parseDateOrDateTime(token.trim(), parsed.params, zoneId)
                        if (parsedEx != null) exDates.add(parsedEx.first)
                    }
                }
                "DTSTART" -> {
                    val res = parseDateOrDateTime(parsed.value, parsed.params, zoneId)
                    if (res != null) {
                        startDate = res.first
                        startInstant = res.second
                        startIsDate = res.third
                        startTzId = parsed.params["TZID"]
                    }
                }
                "DTEND" -> {
                    val res = parseDateOrDateTime(parsed.value, parsed.params, zoneId)
                    if (res != null) {
                        endDate = res.first
                        endInstant = res.second
                    }
                }
                "DURATION" -> durationStr = parsed.value.trim()
                "CREATED" -> createdAt = parseDateOrDateTime(parsed.value, parsed.params, zoneId)?.second
                "LAST-MODIFIED" -> lastModifiedAt = parseDateOrDateTime(parsed.value, parsed.params, zoneId)?.second
            }
        }

        if (!foundVEvent || startDate == null || startInstant == null) return null

        val resolvedEndDate: LocalDate
        val resolvedEndInstant: Instant
        if (endInstant != null && endDate != null) {
            resolvedEndDate = if (startIsDate && !endDate.isAfter(startDate)) startDate.plusDays(1) else endDate
            resolvedEndInstant = if (endInstant.isBefore(startInstant)) startInstant else endInstant
        } else if (!durationStr.isNullOrBlank()) {
            val dur = parseIsoDuration(durationStr) ?: Duration.ofHours(1)
            resolvedEndInstant = startInstant.plus(dur)
            resolvedEndDate = if (startIsDate) {
                val days = dur.toDays().coerceAtLeast(1)
                startDate.plusDays(days)
            } else {
                resolvedEndInstant.atZone(zoneId).toLocalDate()
            }
        } else {
            if (startIsDate) {
                resolvedEndDate = startDate.plusDays(1)
                resolvedEndInstant = resolvedEndDate.atStartOfDay(zoneId).toInstant()
            } else {
                resolvedEndInstant = startInstant.plus(Duration.ofHours(1))
                resolvedEndDate = resolvedEndInstant.atZone(zoneId).toLocalDate()
            }
        }

        return VEventItem(
            uid = uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            href = href,
            etag = etag,
            calendarUrl = calendarUrl,
            summary = summary,
            description = description,
            location = location,
            isAllDay = startIsDate,
            startDate = startDate,
            endDate = resolvedEndDate,
            startInstant = startInstant,
            endInstant = resolvedEndInstant,
            timeZoneId = startTzId,
            rrule = rrule,
            exDates = exDates,
            status = status,
            createdAt = createdAt,
            lastModifiedAt = lastModifiedAt,
            rawIcs = ics
        )
    }

    fun serializeNewVEvent(
        item: VEventItem,
        now: Instant = Instant.now()
    ): String {
        val nowStr = UTC_FORMATTER.format(now)
        val createdStr = UTC_FORMATTER.format(item.createdAt ?: now)
        val lines = mutableListOf<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//VCal//Googlebook Calendar 1.0//EN"
        lines += "BEGIN:VEVENT"
        lines += "UID:${item.uid}"
        lines += "CREATED:$createdStr"
        lines += "DTSTAMP:$nowStr"
        lines += "LAST-MODIFIED:$nowStr"
        appendManagedProperties(lines, item, nowStr, includeTimestamps = false)
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { foldLine(it) } + "\r\n"
    }

    fun updateRawIcs(
        item: VEventItem,
        now: Instant = Instant.now()
    ): String {
        val existing = item.rawIcs
        if (existing.isNullOrBlank()) return serializeNewVEvent(item, now)

        val nowStr = UTC_FORMATTER.format(now)
        val unfolded = unfoldLines(existing)
        val stack = ArrayDeque<String>()
        val output = mutableListOf<String>()
        var updatedFirstVEvent = false
        var insertedProps = false

        for (rawLine in unfolded) {
            if (rawLine.isBlank()) continue
            val parsed = parseContentLine(rawLine)
            val upperName = parsed?.name?.uppercase()

            if (upperName == "BEGIN") {
                val component = parsed.value.trim().uppercase()
                if (!updatedFirstVEvent && !insertedProps && stack.lastOrNull() == "VEVENT") {
                    appendManagedProperties(output, item, nowStr, includeTimestamps = true)
                    insertedProps = true
                }
                stack.addLast(component)
                output += rawLine
                continue
            }

            if (upperName == "END") {
                val component = parsed.value.trim().uppercase()
                if (component == "VEVENT" && !updatedFirstVEvent && stack.lastOrNull() == "VEVENT") {
                    if (!insertedProps) {
                        appendManagedProperties(output, item, nowStr, includeTimestamps = true)
                        insertedProps = true
                    }
                    updatedFirstVEvent = true
                }
                if (stack.isNotEmpty() && stack.last() == component) {
                    stack.removeLast()
                }
                output += rawLine
                continue
            }

            if (!updatedFirstVEvent && stack.lastOrNull() == "VEVENT" && upperName in MANAGED_UPDATE_PROPS) {
                continue
            }

            output += rawLine
        }

        if (!updatedFirstVEvent) return serializeNewVEvent(item, now)
        return output.joinToString("\r\n") { foldLine(it) } + "\r\n"
    }

    private fun appendManagedProperties(
        lines: MutableList<String>,
        item: VEventItem,
        nowStr: String,
        includeTimestamps: Boolean
    ) {
        if (includeTimestamps) {
            lines += "DTSTAMP:$nowStr"
            lines += "LAST-MODIFIED:$nowStr"
        }
        lines += "SUMMARY:${escapeText(item.summary)}"
        if (item.description.isNotEmpty()) {
            lines += "DESCRIPTION:${escapeText(item.description)}"
        }
        if (item.location.isNotEmpty()) {
            lines += "LOCATION:${escapeText(item.location)}"
        }
        if (item.isAllDay) {
            val effectiveEnd = if (item.endDate.isAfter(item.startDate)) item.endDate else item.startDate.plusDays(1)
            lines += "DTSTART;VALUE=DATE:${DATE_FORMATTER.format(item.startDate)}"
            lines += "DTEND;VALUE=DATE:${DATE_FORMATTER.format(effectiveEnd)}"
        } else {
            lines += "DTSTART:${UTC_FORMATTER.format(item.startInstant)}"
            lines += "DTEND:${UTC_FORMATTER.format(item.endInstant)}"
        }
        if (!item.rrule.isNullOrBlank()) {
            lines += "RRULE:${item.rrule.trim()}"
        }
        lines += "STATUS:${item.status.rfcValue}"
    }

    fun unfoldLines(ics: String): List<String> {
        val normalized = ics.replace("\r\n", "\n").replace("\r", "\n")
        val rawLines = normalized.split("\n")
        val unfolded = mutableListOf<String>()
        for (line in rawLines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] = unfolded.last() + line.substring(1)
            } else {
                unfolded.add(line)
            }
        }
        return unfolded
    }

    fun foldLine(line: String): String {
        val maxOctets = 75
        if (line.toByteArray(Charsets.UTF_8).size <= maxOctets) return line
        val result = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < line.length) {
            val codePoint = line.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val segment = line.substring(i, i + charCount)
            val segmentBytes = segment.toByteArray(Charsets.UTF_8).size
            if (currentBytes + segmentBytes > maxOctets && currentBytes > 0) {
                result.append("\r\n ")
                currentBytes = 1
            }
            result.append(segment)
            currentBytes += segmentBytes
            i += charCount
        }
        return result.toString()
    }

    fun escapeText(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                ';' -> sb.append("\\;")
                ',' -> sb.append("\\,")
                '\r' -> {}
                '\n' -> sb.append("\\n")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun unescapeText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    ',' -> sb.append(',')
                    ';' -> sb.append(';')
                    else -> {
                        sb.append('\\')
                        sb.append(next)
                    }
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun parseDateOrDateTime(
        raw: String,
        params: Map<String, String>,
        defaultZone: ZoneId
    ): Triple<LocalDate, Instant, Boolean>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val isDateOnly = params["VALUE"]?.equals("DATE", ignoreCase = true) == true || trimmed.length == 8
        return if (isDateOnly) {
            val date = runCatching { LocalDate.parse(trimmed.take(8), DATE_FORMATTER) }.getOrNull() ?: return null
            Triple(date, date.atStartOfDay(defaultZone).toInstant(), true)
        } else if (trimmed.endsWith("Z") && trimmed.length >= 16) {
            val instant = runCatching {
                LocalDateTime.parse(trimmed.substring(0, 15), LOCAL_DATE_TIME_FORMATTER).toInstant(ZoneOffset.UTC)
            }.getOrNull() ?: return null
            Triple(instant.atZone(defaultZone).toLocalDate(), instant, false)
        } else if (trimmed.length >= 15 && trimmed[8] == 'T') {
            val tz = params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
            val instant = runCatching {
                LocalDateTime.parse(trimmed.substring(0, 15), LOCAL_DATE_TIME_FORMATTER).atZone(tz).toInstant()
            }.getOrNull() ?: return null
            Triple(instant.atZone(defaultZone).toLocalDate(), instant, false)
        } else {
            null
        }
    }

    private fun parseIsoDuration(raw: String): Duration? {
        val cleaned = raw.trim().uppercase()
        if (!cleaned.startsWith("P")) return null
        // Handle P1W (weeks) which java.time.Duration doesn't accept directly
        val weekMatch = Regex("^P(\\d+)W$").matchEntire(cleaned)
        if (weekMatch != null) {
            val weeks = weekMatch.groupValues[1].toLongOrNull() ?: return null
            return Duration.ofDays(weeks * 7)
        }
        return runCatching { Duration.parse(cleaned) }.getOrNull()
    }

    internal fun parseContentLine(line: String): ContentLine? {
        var inQuotes = false
        var colonIndex = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') inQuotes = !inQuotes
            else if (ch == ':' && !inQuotes) {
                colonIndex = i
                break
            }
        }
        if (colonIndex <= 0) return null

        val header = line.substring(0, colonIndex)
        val value = line.substring(colonIndex + 1)

        val parts = mutableListOf<String>()
        val current = StringBuilder()
        inQuotes = false
        for (ch in header) {
            if (ch == '"') {
                inQuotes = !inQuotes
                current.append(ch)
            } else if (ch == ';' && !inQuotes) {
                parts.add(current.toString())
                current.setLength(0)
            } else {
                current.append(ch)
            }
        }
        parts.add(current.toString())

        val name = parts.first().trim()
        val params = mutableMapOf<String, String>()
        for (idx in 1 until parts.size) {
            val part = parts[idx]
            val eq = part.indexOf('=')
            if (eq > 0) {
                val pKey = part.substring(0, eq).trim().uppercase()
                val pVal = part.substring(eq + 1).trim().trim('"')
                params[pKey] = pVal
            }
        }
        return ContentLine(name = name, params = params, value = value, rawLine = line)
    }
}
