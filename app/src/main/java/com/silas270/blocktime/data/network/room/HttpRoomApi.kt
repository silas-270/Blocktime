package com.silas270.blocktime.data.network.room

import com.google.gson.JsonParseException
import com.silas270.blocktime.data.model.OutcomeClaim
import com.silas270.blocktime.data.model.ParticipantSnapshot
import com.silas270.blocktime.data.model.RoomDefinition
import com.silas270.blocktime.data.model.RoomState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The body of `POST /rooms`. */
data class CreateRoomBodyDto(
    val definition: RoomDefinitionDto? = null,
    val snapshot: ParticipantSnapshotDto? = null,
)

/** The body of `PUT /rooms/{code}/participants/{userCode}`. */
data class PutSnapshotBodyDto(
    val snapshot: ParticipantSnapshotDto? = null,
    val claim: ClaimDto? = null,
)

/** What the server says with a refusal: `{"error": "RaceLocked"}`. */
data class ErrorDto(val error: String? = null)

/**
 * The [RoomApi] of a build with a `ROOM_SERVER_URL`: the five routes of docs/shared-challenges.md
 * "Protocol" over HTTP, against the server in `backend/`.
 *
 * Every request carries the pilot as `X-Pilot` and their secret as `Authorization: Bearer`, set
 * by [bindCallerIdentity] before the first call; a write without them is [RoomResult.Unauthorized]
 * without asking the server. Statuses map back to [RoomResult] the way `backend/src/api.rs`
 * documents: 404 [RoomResult.NotFound], 409 by its `error`, 401 [RoomResult.Unauthorized], and
 * everything else that is not a success (429, a 5xx, an unreadable body, an I/O failure) is
 * [RoomResult.Unreachable], so nothing changes locally.
 */
internal class HttpRoomApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) : RoomApi {

    private val baseUrl = baseUrl.trimEnd('/')

    @Volatile
    private var caller: Pair<String, String>? = null

    override val isConfigured: Boolean = true

    fun bindCaller(userCode: String, secret: String) {
        caller = userCode to secret
    }

    override suspend fun ping(): Boolean =
        execute(request("/health").get()) { it.isSuccessful } ?: false

    override suspend fun createRoom(definition: RoomDefinition, self: ParticipantSnapshot): RoomResult<RoomState> =
        call(authorised("/rooms")?.post(json(CreateRoomBodyDto(definition.toDto(), self.toDto()))))

    override suspend fun getRoom(code: String): RoomResult<RoomState> =
        call(request("/rooms/$code").get())

    override suspend fun putSnapshot(code: String, self: ParticipantSnapshot, claim: OutcomeClaim?): RoomResult<RoomState> =
        call(
            authorised("/rooms/$code/participants/${self.userCode}")
                ?.put(json(PutSnapshotBodyDto(self.toDto(), claim?.toDto())))
        )

    override suspend fun leaveRoom(code: String): RoomResult<Unit> {
        val userCode = caller?.first ?: return RoomResult.Unauthorized
        val builder = authorised("/rooms/$code/participants/$userCode")?.delete()
            ?: return RoomResult.Unauthorized
        return execute(builder) { response -> response.failure() ?: RoomResult.Ok(Unit) } ?: RoomResult.Unreachable
    }

    /** A request to [path], with the caller's headers when there is one; a read needs none. */
    private fun request(path: String): Request.Builder {
        val builder = Request.Builder().url(baseUrl + path)
        caller?.let { (code, secret) -> builder.header("X-Pilot", code).header("Authorization", "Bearer $secret") }
        return builder
    }

    /** A write to [path]: null while nobody is bound, since the server would refuse it anyway. */
    private fun authorised(path: String): Request.Builder? = if (caller == null) null else request(path)

    /** A call answered by a room state. */
    private suspend fun call(builder: Request.Builder?): RoomResult<RoomState> {
        if (builder == null) return RoomResult.Unauthorized
        return execute(builder) { response ->
            response.failure() ?: response.room()?.let { RoomResult.Ok(it) } ?: RoomResult.Unreachable
        } ?: RoomResult.Unreachable
    }

    /** Runs the request off the main thread; null on an I/O failure. */
    private suspend fun <T> execute(builder: Request.Builder, read: (Response) -> T): T? =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(builder.build()).execute().use(read)
            } catch (e: IOException) {
                null
            }
        }

    private fun Response.failure(): RoomResult<Nothing>? = when {
        isSuccessful -> null
        code == 404 -> RoomResult.NotFound
        code == 401 -> RoomResult.Unauthorized
        code == 409 -> when (parse(ErrorDto::class.java)?.error) {
            "RoomClosed" -> RoomResult.RoomClosed
            "RaceLocked" -> RoomResult.RaceLocked
            "RoomFull" -> RoomResult.RoomFull
            else -> RoomResult.Unreachable
        }
        else -> RoomResult.Unreachable
    }

    /** Null when the body is not a room this app version can map, which reads as no answer. */
    private fun Response.room(): RoomState? = parse(RoomStateDto::class.java)?.toDomain()

    private fun <T> Response.parse(type: Class<T>): T? = try {
        body?.string()?.let { RoomJson.gson.fromJson(it, type) }
    } catch (e: JsonParseException) {
        null
    } catch (e: IllegalStateException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun json(body: Any): RequestBody = RoomJson.gson.toJson(body).toRequestBody(JSON)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Short enough that a dead server reads as unreachable before the pilot gives up. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
