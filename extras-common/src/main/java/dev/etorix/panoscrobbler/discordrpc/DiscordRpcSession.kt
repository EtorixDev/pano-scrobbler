package dev.etorix.panoscrobbler.discordrpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Serializes blocking RPC calls without making caller deadlines depend on that worker.
 * State changes hold [lock] briefly. Transport calls always run outside it.
 *
 * A stalled call keeps the one worker occupied. Its session is invalidated at the deadline,
 * and retries are refused until its binding and any late connection have been cleaned up.
 */
class DiscordRpcSession(
    private val createBinding: (Events) -> Binding,
    private val elapsedRealtime: () -> Long,
    private val worker: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DiscordRpc").apply { isDaemon = true }
    },
    private val responseTimeoutMs: Long = 10_000,
) {
    enum class Failure(val persistent: Boolean, val dropsConnection: Boolean = true) {
        NOT_INSTALLED(true),
        REJECTED(true),
        RATE_LIMITED(true),
        BIND_FAILED(false),
        SERVICE_DIED(false),
        CLOSED(false),
        TIMEOUT(false),
        NO_ACK(false, false),
        REQUEST_ERROR(false, false),
        INTERNAL(false),
    }

    interface Binding {
        fun bind(): Failure?
        fun unbind()
    }

    interface Connection {
        fun sendFrame(frame: String)
        fun disconnect()
    }

    interface Events {
        fun connected(connect: () -> Connection?)
        fun ready()
        fun acknowledge(nonce: String?)
        fun fail(kind: Failure, nonce: String? = null)
    }

    private val lock = Any()
    private var current: Session? = null
    private var retired: Session? = null
    private var workerRunning = false
    private var pending: Request? = null
    private var consecutiveFailures = 0
    private var retryAtElapsedMs = 0L

    private class Request(val nonce: String, val deadline: Long) {
        val done = CompletableDeferred<Boolean>()
    }

    private class Frame(val value: String, val nonce: String?)

    private inner class Session : Events {
        val binding = createBinding(this)
        var bindStarted = false
        var connect: (() -> Connection?)? = null
        var connectStarted = false
        var connection: Connection? = null
        var ready = false
        var outgoing: Frame? = null
        var clearBeforeDisconnect: String? = null

        override fun connected(connect: () -> Connection?) = synchronized(lock) {
            if (current !== this || connectStarted || this.connect != null) return@synchronized
            this.connect = connect
            wakeWorker()
        }

        override fun ready() = synchronized(lock) {
            if (current !== this) return@synchronized
            ready = true
            wakeWorker()
        }

        override fun acknowledge(nonce: String?) = synchronized(lock) {
            if (current !== this) return@synchronized
            val request = pending ?: return@synchronized
            if (nonce == null || nonce != request.nonce) return@synchronized
            if (elapsedRealtime() >= request.deadline) {
                timeout()
                return@synchronized
            }
            resetBackoff()
            settle(true)
        }

        override fun fail(kind: Failure, nonce: String?) = synchronized(lock) {
            if (current !== this || (nonce != null && nonce != pending?.nonce)) return@synchronized
            recordFailure(kind)
        }
    }

    suspend fun setActivity(nonce: String, frame: String): Boolean {
        val request = synchronized(lock) {
            if (elapsedRealtime() < retryAtElapsedMs || retired != null) return false
            val session = current ?: Session().also { current = it }
            val previous = pending
            Request(nonce, elapsedRealtime() + responseTimeoutMs).also {
                pending = it
                session.outgoing = Frame(frame, nonce)
                wakeWorker()
                previous?.done?.complete(false)
            }
        }
        try {
            val result = withTimeoutOrNull(responseTimeoutMs) { request.done.await() }
            if (result != null) return result
            synchronized(lock) {
                if (pending === request) timeout()
            }
            return false
        } finally {
            synchronized(lock) {
                // Cancellation or supersession must not leave an activity waiting for READY.
                if (pending === request) settle(false)
            }
        }
    }

    /** Invalidates pending activity immediately. Clearing never creates a binding. */
    fun clearActivity(frame: String, disconnect: Boolean) = synchronized(lock) {
        val session = current
        if (disconnect) {
            resetBackoff()
            if (session != null) {
                session.clearBeforeDisconnect = frame.takeIf { session.ready }
                retire()
            }
        } else if (session != null) {
            session.outgoing = if (session.ready) Frame(frame, null) else null
            wakeWorker()
        }
        settle(false)
    }

    // All helpers below require lock, except drainWorker and the actions it executes.
    private fun settle(success: Boolean) {
        val request = pending ?: return
        if (current?.outgoing?.nonce == request.nonce) current?.outgoing = null
        pending = null
        request.done.complete(success)
    }

    private fun timeout() {
        val kind = if (current?.ready == true) Failure.NO_ACK else Failure.TIMEOUT
        // A completed send with no ack can retain its connection. An in-flight Binder call cannot.
        recordFailure(kind, dropConnection = workerRunning || kind.dropsConnection)
    }

    private fun recordFailure(kind: Failure, dropConnection: Boolean = kind.dropsConnection) {
        val idleDrop = pending == null && current?.ready == true &&
                (kind == Failure.CLOSED || kind == Failure.SERVICE_DIED)
        if (!idleDrop) {
            consecutiveFailures++
            val base = if (kind.persistent) 60_000L else 10_000L
            val maximum = if (kind.persistent) 1_800_000L else 600_000L
            val delay = minOf(base shl (consecutiveFailures - 1).coerceIn(0, 16), maximum)
            retryAtElapsedMs = elapsedRealtime() + delay
        }
        if (dropConnection) retire()
        settle(false)
    }

    private fun resetBackoff() {
        consecutiveFailures = 0
        retryAtElapsedMs = 0L
    }

    private fun retire() {
        val session = current ?: return
        session.outgoing = null
        current = null // Late READY, nonce replies and service callbacks are now stale.
        retired = session
        wakeWorker()
    }

    private fun wakeWorker() {
        if (workerRunning) return
        workerRunning = true
        worker.execute(::drainWorker)
    }

    private fun drainWorker() {
        while (true) {
            val action = synchronized(lock) {
                nextAction() ?: run {
                    workerRunning = false
                    return
                }
            }
            action()
        }
    }

    private fun nextAction(): (() -> Unit)? {
        retired?.let { session ->
            return {
                // Release the Android binding before an optional remote cleanup can stall.
                runCatching { session.binding.unbind() }
                session.connection?.let { connection ->
                    session.clearBeforeDisconnect?.let { runCatching { connection.sendFrame(it) } }
                    runCatching { connection.disconnect() }
                }
                synchronized(lock) { retired = null }
            }
        }
        val session = current ?: return null
        if (!session.bindStarted) {
            session.bindStarted = true
            return action@{
                if (!synchronized(lock) { current === session }) return@action
                val failure = try {
                    session.binding.bind()
                } catch (_: Exception) {
                    Failure.BIND_FAILED
                }
                failure?.let { session.fail(it) }
            }
        }
        val connect = session.connect
        if (connect != null && !session.connectStarted) {
            session.connectStarted = true
            session.connect = null
            return action@{
                if (!synchronized(lock) { current === session }) return@action
                try {
                    val connection = connect()
                    synchronized(lock) {
                        // A late connection belongs to the retired session and is cleaned up next.
                        session.connection = connection
                        if (connection == null && current === session) recordFailure(Failure.REJECTED)
                    }
                } catch (_: Exception) {
                    session.fail(Failure.SERVICE_DIED)
                }
            }
        }
        val connection = session.connection ?: return null
        if (!session.ready) return null
        val frame = session.outgoing ?: return null
        session.outgoing = null
        return {
            // Clear/cancel/timeout may have invalidated this frame since it was selected.
            val send = synchronized(lock) {
                if (current !== session) false
                else if (frame.nonce == null) true
                else if (pending?.nonce != frame.nonce) false
                else if (elapsedRealtime() >= pending!!.deadline) {
                    timeout()
                    false
                } else true
            }
            if (send) {
                try {
                    connection.sendFrame(frame.value)
                } catch (_: Exception) {
                    session.fail(Failure.SERVICE_DIED)
                }
            }
        }
    }
}
