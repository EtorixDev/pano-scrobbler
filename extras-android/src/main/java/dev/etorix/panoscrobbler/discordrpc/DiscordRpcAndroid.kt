package dev.etorix.panoscrobbler.discordrpc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import co.touchlab.kermit.Logger
import com.discord.socialsdk.rpc.IDiscordRpcCallback
import com.discord.socialsdk.rpc.IDiscordRpcConnection
import com.discord.socialsdk.rpc.IDiscordRpcService
import dev.etorix.panoscrobbler.discordrpc.DiscordRpcSession.Failure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

/*
 * needs
 *   <queries>
 *     <intent><action android:name="com.discord.socialsdk.rpc.IDiscordRpcService" /></intent>
 *     <package android:name="com.discord" />
 *   </queries>
 *
 * Connects lazily: only setActivity() (re)connects. clearActivity() never does.
 *
 * RESULTS
 * setActivity() suspends until Discord acknowledges the SET_ACTIVITY frame (matched by nonce) or
 * something fails, so callers get a real answer even though every Binder call runs on the worker.
 *
 * BACKOFF
 * Every failure closes a gate: until it reopens, setActivity() returns immediately without touching
 * Discord. This matters because on Android a bind can *start* Discord's process, and setActivity()
 * is called on every metadata / seek / settings change.
 *
 *   persistent: not installed, handshake rejected / auth-ish codes (likely logged out), rate limited
 *               1 min, 2, 4, 8, 16, then 30 min max
 *   transient:  never READY, no ack, abnormal close, binder death, bind failure, SET_ACTIVITY error
 *               10 s, 20, 40, 80 ... then 10 min max
 *
 * The delay doubles per *consecutive* failure. The gate fully resets on the first successful ack,
 * and on clearActivity(disconnect = true) (session over / feature disabled).
 * A connection that dies while idle after having been healthy is NOT a failure: we just reconnect
 * on the next setActivity().
 *
 * Instance is reusable after clearActivity(disconnect = true).
 */
class DiscordRpcAndroid(
    context: Context,
    private val applicationId: Long,
    private val json: Json,
) {
    private val appContext = context.applicationContext
    private val pid = 0 // The official SDK uses 0 on Android.
    private val session = DiscordRpcSession(
        createBinding = ::AndroidBinding,
        elapsedRealtime = SystemClock::elapsedRealtime,
    )

    private inner class AndroidBinding(private val events: DiscordRpcSession.Events) :
        DiscordRpcSession.Binding, ServiceConnection {
        // Accessed only by the transport worker.
        private var bound = false

        override fun bind(): Failure? {
            val intent = Intent(ACTION).setPackage(DISCORD_PACKAGE)
            if (appContext.packageManager.resolveService(intent, 0) == null)
                return Failure.NOT_INSTALLED
            bound = appContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
            return if (bound) null else Failure.BIND_FAILED
        }

        override fun unbind() {
            if (!bound) return
            bound = false
            appContext.unbindService(this)
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            events.connected {
                val callback = object : IDiscordRpcCallback.Stub() {
                    override fun onFrame(frame: String?) {
                        if (frame == null) return
                        Logger.withTag(TAG).d { "<- $frame" }
                        when (val decoded = decodeFrame(frame)) {
                            is RpcFrame.Ready -> events.ready()
                            is RpcFrame.ActivitySet -> events.acknowledge(decoded.nonce)
                            is RpcFrame.Error -> {
                                Logger.withTag(TAG).w { "RPC error ${decoded.code}: ${decoded.message}" }
                                events.fail(classifyError(decoded.code), decoded.nonce)
                            }
                            is RpcFrame.Unknown -> Unit
                        }
                    }

                    override fun onClose(code: Int, reason: String?) {
                        Logger.withTag(TAG).w { "closed: $code" }
                        events.fail(classifyClose(code))
                    }
                }
                IDiscordRpcService.Stub.asInterface(binder)
                    .connect(applicationId, RPC_VERSION, callback)
                    ?.let(::AndroidConnection)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = events.fail(Failure.SERVICE_DIED)
        override fun onBindingDied(name: ComponentName) = events.fail(Failure.SERVICE_DIED)
        override fun onNullBinding(name: ComponentName) = events.fail(Failure.BIND_FAILED)
    }

    private class AndroidConnection(private val connection: IDiscordRpcConnection) :
        DiscordRpcSession.Connection {
        override fun sendFrame(frame: String) {
            Logger.withTag(TAG).d { "-> $frame" }
            connection.sendFrame(frame)
        }

        override fun disconnect() = connection.disconnect()
    }

    // ---------------------------------------------------------------- public api

    /**
     * Suspends until Discord acknowledges this activity, it fails, or the 10-second deadline passes.
     * Cancelling the caller is safe. An in-flight frame may still be delivered.
     */
    suspend fun setActivity(
        name: String,
        state: String,
        details: String,
        largeText: String,
        startTimeMillis: Long,
        endTimeMillis: Long?,
        artUrl: String,
        detailsUrl: String,
        isPlaying: Boolean,
        statusLine: Int,
        buttonText: String,
        buttonUrl: String,
    ): Boolean {
        val assets = RpcAssets(
            large_image = artUrl.ifEmpty { assetsMap["graphic_eq"] },
            large_text = largeText,
            small_image = if (isPlaying) null else assetsMap["pause_circle"],
            small_text = if (isPlaying) null else "Paused",
        )

        val buttons = if (buttonText.isNotEmpty() && buttonUrl.isNotEmpty())
            listOf(
                RpcButton(buttonText, buttonUrl)
            )
        else
            null

        val activity = RpcActivity(
            type = ActivityType.LISTENING.value,
            name = name.ifEmpty { null },
            state = state.ifEmpty { null },
            details = details.ifEmpty { null },
            details_url = detailsUrl.ifEmpty { null },
            status_display_type = when (statusLine) {
                2 -> StatusDisplayType.STATE.value
                1 -> StatusDisplayType.DETAILS.value
                else -> StatusDisplayType.NAME.value
            },
            timestamps = RpcTimestamps(
                start = startTimeMillis,
                end = endTimeMillis,
            ),
            assets = assets,
            buttons = buttons,
        )

        val request = SetActivityRequest(pid, activity)
        val frame = json.encodeToString(request)
        return session.setActivity(request.nonce, frame)
    }

    /**
     * Never connects: if there's no live connection, there's nothing to clear.
     * [disconnect] = true also ends the session and reopens the backoff gate.
     */
    fun clearActivity(disconnect: Boolean) {
        val frame = json.encodeToString(ClearActivityRequest(pid))
        session.clearActivity(frame, disconnect)
    }

    // RPC close event codes:
    // https://discord.com/developers/docs/topics/opcodes-and-status-codes#rpc-rpc-close-event-codes
    // 1000 normal and 1006 abnormal fall through to CLOSED (transient).
    private fun classifyClose(code: Int) = when (code) {
        1003, // unsupported data
        4000, // invalid client id
        4001, // invalid origin
        4003, // token revoked
        4004, // invalid version
        4005, // invalid encoding
            -> Failure.REJECTED

        4002 -> Failure.RATE_LIMITED
        else -> Failure.CLOSED
    }

    // Codes in the data of an `evt: "ERROR"` frame. This is a different table from the close codes
    // (4000 here is "invalid payload", not "invalid client id"):
    // https://discord.com/developers/docs/topics/opcodes-and-status-codes#rpc-rpc-error-codes
    // The RPC docs say a client that isn't authenticated gets a 4006 on any command, so 4006 is the
    // prime suspect for "user is logged out".
    private fun classifyError(code: Int?) = when (code) {
        4002, // invalid command: resending the same frame can't help
        4006, // invalid permissions / not authenticated
        4007, // invalid client id
        4008, // invalid origin
        4009, // invalid token
        4010, // invalid user
        5000, // oauth2 error
            -> Failure.REJECTED

        // 1000 unknown, 4000 invalid payload, anything else: blame the request, not the connection
        else -> Failure.REQUEST_ERROR
    }

// ---------------------------------------------------------------- payload

    private enum class ActivityType(val value: Int) {
        PLAYING(0),
        LISTENING(2),
        WATCHING(3),
        COMPETING(5)
    }

    private enum class StatusDisplayType(val value: Int) {
        NAME(0),
        STATE(1),
        DETAILS(2)
    }

    @Serializable
    private data class RpcActivity(
        val type: Int? = null,
        val name: String? = null,
        val state: String? = null,
        val details: String? = null,
        val details_url: String? = null,
        val status_display_type: Int? = null,
        val timestamps: RpcTimestamps? = null,
        val assets: RpcAssets? = null,
        val buttons: List<RpcButton>? = null,
    )

    /* Milliseconds since the Unix epoch. */
    @Serializable
    private data class RpcTimestamps(
        val start: Long? = null,
        val end: Long? = null,
    )

    @Serializable
    private data class RpcAssets(
        val large_image: String? = null,
        val large_text: String? = null,
        val small_image: String? = null,
        val small_text: String? = null,
    )

    @Serializable
    private data class RpcButton(
        val label: String,
        val url: String,
    )

// ---------------------------------------------------------------- requests

    @Serializable
    private data class SetActivityRequest(val cmd: String, val args: Args, val nonce: String) {

        constructor(
            pid: Int,
            activity: RpcActivity,
        ) : this("SET_ACTIVITY", Args(pid, activity), UUID.randomUUID().toString())

        @Serializable
        data class Args(val pid: Int, val activity: RpcActivity)
    }

    /* Non-defaulted JsonElement so `"activity": null` is always encoded, even with explicitNulls = false. */
    @Serializable
    private data class ClearActivityRequest(val cmd: String, val args: Args, val nonce: String) {
        constructor(pid: Int) : this(
            "SET_ACTIVITY",
            Args(pid, JsonNull),
            UUID.randomUUID().toString()
        )

        @Serializable
        data class Args(val pid: Int, val activity: JsonElement)
    }

// ---------------------------------------------------------------- responses

    @Serializable
    private data class RpcEnvelope(
        val cmd: String? = null,
        val evt: String? = null,
        val nonce: String? = null,
        val data: JsonElement? = null,
    )

    @Serializable
    private data class ActivityResult(
        val type: Int? = null,
        val name: String? = null,
        val state: String? = null,
        val details: String? = null,
        val details_url: String? = null,
        val status_display_type: Int? = null,
        val application_id: String? = null,
        val platform: String? = null,
        val timestamps: RpcTimestamps? = null,
        val assets: RpcAssets? = null,
    )

    @Serializable
    private data class RpcError(val code: Int? = null, val message: String? = null)

    private sealed interface RpcFrame {
        object Ready : RpcFrame
        data class ActivitySet(val nonce: String?, val activity: ActivityResult?) : RpcFrame
        data class Error(val nonce: String?, val code: Int?, val message: String?) : RpcFrame
        data class Unknown(val raw: String) : RpcFrame
    }

    private fun decodeFrame(frame: String): RpcFrame = try {
        val env = json.decodeFromString<RpcEnvelope>(frame)
        val data = env.data?.takeIf { it !is JsonNull }
        when {
            env.evt == "READY" -> RpcFrame.Ready
            env.evt == "ERROR" -> {
                val err = data?.let { json.decodeFromJsonElement<RpcError>(it) }
                RpcFrame.Error(env.nonce, err?.code, err?.message)
            }

            env.cmd == "SET_ACTIVITY" -> RpcFrame.ActivitySet(
                env.nonce,
                data?.let { json.decodeFromJsonElement<ActivityResult>(it) },
            )

            else -> RpcFrame.Unknown(frame)
        }
    } catch (e: IllegalArgumentException) { // SerializationException extends this
        RpcFrame.Unknown(frame)
    }

    private companion object {
        const val TAG = "DiscordPresence"
        const val ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"
        const val DISCORD_PACKAGE = "com.discord"
        const val RPC_VERSION = "1"

        // https://discord.com/api/v9/oauth2/applications/$applicationId/assets
        // I don't know why strings fail on android
        val assetsMap = mapOf(
            "graphic_eq" to "1572115192618287144",
            "pause_circle" to "1572115192270160065",
        )
    }
}
