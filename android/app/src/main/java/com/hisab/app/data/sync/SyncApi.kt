package com.hisab.app.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the server said about one pushed event. Codes are language-neutral (D011). */
data class PushResult(
    val eventId: String,
    val status: String,
    val code: String?,
) {
    val isAccepted: Boolean get() = status == STATUS_APPLIED || status == STATUS_ALREADY_APPLIED

    companion object {
        const val STATUS_APPLIED = "applied"
        const val STATUS_ALREADY_APPLIED = "already-applied"
        const val STATUS_CONFLICT = "conflict"
        const val STATUS_REJECTED = "rejected"
    }
}

/** One change the server has that this phone may not. */
data class PulledChange(
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: JSONObject,
)

data class PullResult(
    val changes: List<PulledChange>,
    val cursor: Long,
)

/**
 * The little that this app needs from the server. An interface so the sync
 * engine can be tested without a network, and so a different transport could
 * replace it later.
 */
interface SyncApi {
    suspend fun login(
        email: String,
        password: String,
    ): String

    suspend fun push(
        token: String,
        events: List<SyncOutboxEntity>,
    ): List<PushResult>

    suspend fun pull(
        token: String,
        afterCursor: Long,
    ): PullResult
}

class SyncException(
    message: String,
) : IOException(message)

/**
 * Talks to the server over plain HTTP with `HttpURLConnection`, which Android
 * ships — no HTTP library (D006). Every call is short and runs off the main
 * thread.
 *
 * Cleartext HTTP is allowed in debug builds only (see src/debug), because a
 * development server on a laptop has no certificate. A real deployment must
 * be HTTPS — see docs/SECURITY.md.
 */
class HttpSyncApi(
    private val baseUrl: String,
) : SyncApi {
    override suspend fun login(
        email: String,
        password: String,
    ): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("email", email).put("password", password)
            val response = request("POST", "/auth/login", body.toString(), token = null)
            JSONObject(response).getString("token")
        }

    override suspend fun push(
        token: String,
        events: List<SyncOutboxEntity>,
    ): List<PushResult> =
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            events.forEach { event ->
                array.put(
                    JSONObject()
                        .put("eventId", event.eventId)
                        .put("entityType", event.entityType)
                        .put("entityId", event.entityId)
                        .put("operation", event.operation)
                        .put("payload", JSONObject(event.payload))
                        .put("baseRevision", event.baseRevision ?: JSONObject.NULL)
                        .put("clientTimestamp", event.clientTimestamp.toString()),
                )
            }

            val response = request("POST", "/sync/push", JSONObject().put("events", array).toString(), token)
            val results = JSONObject(response).getJSONArray("results")
            (0 until results.length()).map { index ->
                val result = results.getJSONObject(index)
                PushResult(
                    eventId = result.getString("eventId"),
                    status = result.getString("status"),
                    code = if (result.isNull("code")) null else result.optString("code"),
                )
            }
        }

    override suspend fun pull(
        token: String,
        afterCursor: Long,
    ): PullResult =
        withContext(Dispatchers.IO) {
            val response = request("GET", "/sync/changes?after=$afterCursor", body = null, token = token)
            val json = JSONObject(response)
            val events = json.getJSONArray("events")
            PullResult(
                changes =
                    (0 until events.length()).map { index ->
                        val event = events.getJSONObject(index)
                        PulledChange(
                            entityType = event.getString("entityType"),
                            entityId = event.getString("entityId"),
                            operation = event.getString("operation"),
                            payload = event.getJSONObject("payload"),
                        )
                    },
                cursor = json.getLong("cursor"),
            )
        }

    private fun request(
        method: String,
        path: String,
        body: String?,
        token: String?,
    ): String {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val error =
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                throw SyncException("$method $path failed with HTTP $code: $error")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
    }
}
