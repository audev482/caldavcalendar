package dev.vcal.calendar.protocol

import dev.vcal.calendar.domain.CalendarCollection
import dev.vcal.calendar.domain.CalendarServerConfig
import dev.vcal.calendar.domain.VEventItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

interface CalDavEventService {
    suspend fun discoverCalendars(config: CalendarServerConfig): List<CalendarCollection>
    suspend fun fetchEvents(config: CalendarServerConfig, calendar: CalendarCollection): List<VEventItem>
    suspend fun createEvent(config: CalendarServerConfig, calendar: CalendarCollection, item: VEventItem): VEventItem
    suspend fun updateEvent(config: CalendarServerConfig, item: VEventItem): VEventItem
    suspend fun deleteEvent(config: CalendarServerConfig, item: VEventItem)
}

class CalDavEventException(
    val httpCode: Int?,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class CalDavEventClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) : CalDavEventService {

    private data class DavHttpResponse(
        val code: Int,
        val body: String,
        val finalUrl: HttpUrl,
        val etag: String?,
        val location: String?
    )

    override suspend fun discoverCalendars(config: CalendarServerConfig): List<CalendarCollection> =
        withContext(Dispatchers.IO) {
            if (config.directCollectionUrl.isNotBlank()) {
                val directUrl = normalizeUrl(config.directCollectionUrl)
                    ?: throw CalDavEventException(null, "Invalid direct collection URL")
                val resp = executeDav("PROPFIND", directUrl, config, CalDavEventXml.buildCollectionsRequest(), "0")
                ensureSuccess(resp, "Could not access calendar collection")
                val parsed = CalDavEventXml.parseCollections(resp.body).firstOrNull()
                val resolvedUrl = ensureTrailingSlash(resp.finalUrl.toString())
                val name = parsed?.displayName
                    ?: resolvedUrl.trimEnd('/').substringAfterLast('/').ifBlank { "Calendar" }
                return@withContext listOf(
                    CalendarCollection(
                        url = resolvedUrl,
                        displayName = name,
                        colorHex = parsed?.colorHex ?: CalendarCollection.DEFAULT_COLOR_HEX,
                        ctag = parsed?.ctag,
                        order = parsed?.order ?: 0,
                        readOnly = parsed?.readOnly ?: false
                    )
                )
            }

            val baseUrl = normalizeUrl(config.serverUrl)
                ?: throw CalDavEventException(null, "Please enter a valid server URL (e.g. https://cloud.example.com)")

            val candidates = buildDiscoveryCandidates(baseUrl)
            var principalUrl: HttpUrl? = null
            var lastError: CalDavEventException? = null

            for (candidate in candidates) {
                val resp = try {
                    executeDav("PROPFIND", candidate, config, CalDavEventXml.buildCurrentUserPrincipalRequest(), "0")
                } catch (e: CalDavEventException) {
                    lastError = e
                    continue
                }

                if (resp.code == 401 || resp.code == 403) {
                    throw CalDavEventException(
                        resp.code,
                        "Authentication failed (${resp.code}). Check your username and App Password."
                    )
                }

                if (resp.code in 200..299) {
                    val directCal = CalDavEventXml.parseCollections(resp.body)
                        .firstOrNull { it.isCalendar && it.supportsVEvent }
                    if (directCal != null && candidate == baseUrl) {
                        val resolved = ensureTrailingSlash(resp.finalUrl.toString())
                        return@withContext listOf(
                            CalendarCollection(
                                url = resolved,
                                displayName = directCal.displayName,
                                colorHex = directCal.colorHex ?: CalendarCollection.DEFAULT_COLOR_HEX,
                                ctag = directCal.ctag,
                                order = directCal.order,
                                readOnly = directCal.readOnly
                            )
                        )
                    }

                    val principalHref = CalDavEventXml.parseCurrentUserPrincipal(resp.body)
                    if (!principalHref.isNullOrBlank()) {
                        principalUrl = resp.finalUrl.resolve(principalHref)
                        if (principalUrl != null) break
                    }
                }
            }

            if (principalUrl == null) {
                throw lastError ?: CalDavEventException(
                    null,
                    "Could not discover CalDAV endpoint at ${baseUrl.host}. Verify the URL or enter a direct collection URL."
                )
            }

            val homeResp = executeDav("PROPFIND", principalUrl, config, CalDavEventXml.buildCalendarHomeSetRequest(), "0")
            ensureSuccess(homeResp, "Failed to query CalDAV principal")
            val homeHref = CalDavEventXml.parseCalendarHomeSet(homeResp.body)
                ?: throw CalDavEventException(null, "Server did not return a calendar-home-set for this account")
            val homeUrl = homeResp.finalUrl.resolve(homeHref)
                ?: throw CalDavEventException(null, "Invalid calendar-home-set URL: $homeHref")

            val listResp = executeDav("PROPFIND", homeUrl, config, CalDavEventXml.buildCollectionsRequest(), "1")
            ensureSuccess(listResp, "Failed to list CalDAV calendars")

            CalDavEventXml.parseCollections(listResp.body)
                .filter { it.isCalendar && it.supportsVEvent }
                .mapNotNull { col ->
                    val resolved = listResp.finalUrl.resolve(col.href) ?: return@mapNotNull null
                    CalendarCollection(
                        url = ensureTrailingSlash(resolved.toString()),
                        displayName = col.displayName,
                        colorHex = col.colorHex ?: CalendarCollection.DEFAULT_COLOR_HEX,
                        ctag = col.ctag,
                        order = col.order,
                        readOnly = col.readOnly
                    )
                }
                .sortedWith(compareBy<CalendarCollection> { it.order }.thenBy { it.displayName.lowercase() })
        }

    override suspend fun fetchEvents(
        config: CalendarServerConfig,
        calendar: CalendarCollection
    ): List<VEventItem> = withContext(Dispatchers.IO) {
        val calUrl = calendar.url.toHttpUrlOrNull()
            ?: throw CalDavEventException(null, "Invalid calendar URL: ${calendar.url}")
        val resp = executeDav("REPORT", calUrl, config, CalDavEventXml.buildVEventQueryRequest(), "1")
        ensureSuccess(resp, "Failed to fetch events from ${calendar.displayName}")

        CalDavEventXml.parseCalendarObjects(resp.body).mapNotNull { obj ->
            val data = obj.calendarData ?: return@mapNotNull null
            val resolvedHref = resp.finalUrl.resolve(obj.href)?.toString() ?: obj.href
            ICalendarEventCodec.parseVEvent(
                ics = data,
                href = resolvedHref,
                etag = obj.etag,
                calendarUrl = calendar.url
            )
        }
    }

    override suspend fun createEvent(
        config: CalendarServerConfig,
        calendar: CalendarCollection,
        item: VEventItem
    ): VEventItem = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val safeFileName = item.uid.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".ics"
        val targetUrlStr = ensureTrailingSlash(calendar.url) + safeFileName
        val targetUrl = targetUrlStr.toHttpUrlOrNull()
            ?: throw CalDavEventException(null, "Invalid target event URL: $targetUrlStr")

        val icsPayload = ICalendarEventCodec.serializeNewVEvent(item, now)
        val resp = executePutOrDelete("PUT", targetUrl, config, icsPayload, ifNoneMatch = "*")
        ensureSuccess(resp, "Failed to create event on server")

        item.copy(
            href = resp.finalUrl.toString(),
            etag = resp.etag,
            calendarUrl = calendar.url,
            createdAt = item.createdAt ?: now,
            lastModifiedAt = now,
            rawIcs = icsPayload
        )
    }

    override suspend fun updateEvent(
        config: CalendarServerConfig,
        item: VEventItem
    ): VEventItem = withContext(Dispatchers.IO) {
        val targetUrl = item.href.toHttpUrlOrNull()
            ?: throw CalDavEventException(null, "Invalid event URL: ${item.href}")
        val now = Instant.now()
        val icsPayload = ICalendarEventCodec.updateRawIcs(item, now)
        val resp = executePutOrDelete("PUT", targetUrl, config, icsPayload, ifMatch = item.etag)
        if (resp.code == 412) {
            throw CalDavEventException(412, "Event changed on server; refresh to sync latest version.")
        }
        ensureSuccess(resp, "Failed to update event on server")

        item.copy(
            etag = resp.etag ?: item.etag,
            lastModifiedAt = now,
            rawIcs = icsPayload
        )
    }

    override suspend fun deleteEvent(config: CalendarServerConfig, item: VEventItem) {
        withContext(Dispatchers.IO) {
            val targetUrl = item.href.toHttpUrlOrNull()
                ?: throw CalDavEventException(null, "Invalid event URL: ${item.href}")
            val resp = executePutOrDelete("DELETE", targetUrl, config, null, ifMatch = item.etag)
            if (resp.code == 404) return@withContext
            ensureSuccess(resp, "Failed to delete event on server")
        }
    }

    private fun executeDav(
        method: String,
        url: HttpUrl,
        config: CalendarServerConfig,
        xmlBody: String,
        depth: String
    ): DavHttpResponse {
        var currentUrl = url
        for (hop in 0 until MAX_REDIRECTS) {
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .method(method, xmlBody.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", depth)
                .header("Accept", "application/xml, text/xml")
                .header("User-Agent", USER_AGENT)
            applyAuthIfSameHost(reqBuilder, url, currentUrl, config)

            val resp = executeOnce(reqBuilder.build())
            if (resp.code in REDIRECT_CODES && !resp.location.isNullOrBlank()) {
                val nextUrl = currentUrl.resolve(resp.location) ?: return resp
                currentUrl = nextUrl
                continue
            }
            return resp
        }
        throw CalDavEventException(null, "Too many redirects from ${url.host}")
    }

    private fun executePutOrDelete(
        method: String,
        url: HttpUrl,
        config: CalendarServerConfig,
        icsBody: String?,
        ifMatch: String? = null,
        ifNoneMatch: String? = null
    ): DavHttpResponse {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (method == "PUT") {
            reqBuilder.put((icsBody ?: "").toRequestBody(ICS_MEDIA_TYPE))
        } else {
            reqBuilder.delete()
        }
        if (!ifMatch.isNullOrBlank()) reqBuilder.header("If-Match", ifMatch)
        if (!ifNoneMatch.isNullOrBlank()) reqBuilder.header("If-None-Match", ifNoneMatch)
        applyAuthIfSameHost(reqBuilder, url, url, config)
        return executeOnce(reqBuilder.build())
    }

    private fun executeOnce(request: Request): DavHttpResponse {
        try {
            httpClient.newCall(request).execute().use { response ->
                return DavHttpResponse(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                    finalUrl = response.request.url,
                    etag = response.header("ETag"),
                    location = response.header("Location")
                )
            }
        } catch (e: IOException) {
            throw CalDavEventException(null, "Network error: ${e.message ?: "Unable to reach server"}", e)
        }
    }

    private fun applyAuthIfSameHost(
        builder: Request.Builder,
        originalUrl: HttpUrl,
        currentUrl: HttpUrl,
        config: CalendarServerConfig
    ) {
        if (config.username.isBlank() && config.password.isBlank()) return
        if (originalUrl.host.equals(currentUrl.host, ignoreCase = true)) {
            builder.header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
        }
    }

    private fun ensureSuccess(resp: DavHttpResponse, fallbackMessage: String) {
        if (resp.code in 200..299) return
        val detail = when (resp.code) {
            401 -> "Authentication failed (401). Check your username and App Password."
            403 -> "Permission denied (403). This calendar may be read-only."
            404 -> "CalDAV resource not found (404)."
            412 -> "Conflict (412): Event changed on server."
            else -> "$fallbackMessage (HTTP ${resp.code})"
        }
        throw CalDavEventException(resp.code, detail)
    }

    private fun buildDiscoveryCandidates(baseUrl: HttpUrl): List<HttpUrl> {
        val candidates = linkedSetOf<HttpUrl>()
        candidates.add(baseUrl)
        val origin = baseUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        origin.resolve("/.well-known/caldav")?.let { candidates.add(it) }
        val basePathWithSlash = ensureTrailingSlash(baseUrl.toString())
        basePathWithSlash.toHttpUrlOrNull()?.resolve(".well-known/caldav")?.let { candidates.add(it) }
        basePathWithSlash.toHttpUrlOrNull()?.resolve("remote.php/dav/")?.let { candidates.add(it) }
        origin.resolve("/remote.php/dav/")?.let { candidates.add(it) }
        return candidates.toList()
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "VCal-Googlebook/1.0"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val ICS_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun normalizeUrl(raw: String): HttpUrl? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) trimmed else "https://$trimmed"
            return withScheme.toHttpUrlOrNull()
        }

        fun ensureTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
