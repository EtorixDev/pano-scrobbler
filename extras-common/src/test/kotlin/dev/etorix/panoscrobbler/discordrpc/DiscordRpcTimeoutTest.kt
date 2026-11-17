package dev.etorix.panoscrobbler.discordrpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordRpcTimeoutTest {
    @Test
    fun stalledConnectHasBoundedDeadlineRetriesAndLateCleanup() = runBlocking {
        Fixture().use { fixture ->
            fixture.stallConnect = true
            val result = async { fixture.rpc.setActivity("old", "old frame") }
            fixture.await { fixture.sessions.firstOrNull()?.connectEntered?.isCompleted == true }
            val old = fixture.sessions.single()
            assertFalse(withTimeout(2_000) { result.await() })
            assertEquals(1L, old.connectRelease.count)

            fixture.now.set(60_000)
            withTimeout(2_000) {
                repeat(100) {
                    assertFalse(fixture.rpc.setActivity("retry-$it", "retry"))
                    fixture.rpc.clearActivity("clear", disconnect = true)
                }
            }
            assertEquals(1, fixture.sessions.size)
            assertEquals(1L, fixture.worker.taskCount)
            assertEquals(0, fixture.worker.queue.size)
            assertEquals(1, fixture.worker.largestPoolSize)

            old.events.ready()
            old.events.acknowledge("old")
            old.connectRelease.countDown()
            fixture.idle()
            assertEquals(listOf("unbind", "disconnect"), old.cleanup.toList())
            assertTrue(old.sent.isEmpty())

            fixture.stallConnect = false
            val next = async { fixture.rpc.setActivity("next", "next frame") }
            fixture.await { fixture.sessions.size == 2 && fixture.sessions.last().sent.size == 1 }
            val current = fixture.sessions.last()
            old.events.ready()
            old.events.acknowledge("next")
            old.events.fail(DiscordRpcSession.Failure.REJECTED)
            old.events.connected { error("A retired service callback must not reconnect") }
            assertFalse(next.isCompleted)
            current.events.acknowledge("next")
            assertTrue(next.await())
            assertEquals(listOf("next frame"), current.sent.toList())
        }
    }

    @Test
    fun stalledSendTimesOutWithoutAcceptingItsLateAcknowledgment() = runBlocking {
        Fixture().use { fixture ->
            fixture.stallSend = true
            val result = async { fixture.rpc.setActivity("old", "old frame") }
            fixture.await { fixture.sessions.firstOrNull()?.sendEntered?.isCompleted == true }
            val old = fixture.sessions.single()
            assertFalse(withTimeout(2_000) { result.await() })
            old.events.acknowledge("old")
            old.sendRelease.countDown()
            fixture.idle()
            assertEquals(listOf("unbind", "disconnect"), old.cleanup.toList())
            assertFalse(fixture.rpc.setActivity("gated", "gated"))
            assertEquals(1, fixture.sessions.size)

            fixture.now.set(10_000)
            fixture.stallSend = false
            val next = async { fixture.rpc.setActivity("next", "next frame") }
            fixture.await { fixture.sessions.size == 2 && fixture.sessions.last().sent.isNotEmpty() }
            fixture.sessions.last().events.acknowledge("next")
            assertTrue(next.await())
        }
    }

    @Test
    fun stopDuringConnectOrSendCompletesCallerAndDefersOnlyCleanup() = runBlocking {
        for (connecting in listOf(true, false)) {
            Fixture(timeout = 10_000).use { fixture ->
                fixture.stallConnect = connecting
                fixture.stallSend = !connecting
                val result = async { fixture.rpc.setActivity("old", "old frame") }
                fixture.await {
                    fixture.sessions.firstOrNull()?.let {
                        if (connecting) it.connectEntered.isCompleted else it.sendEntered.isCompleted
                    } == true
                }
                val old = fixture.sessions.single()
                fixture.rpc.clearActivity("clear", disconnect = true)
                assertFalse(withTimeout(1_000) { result.await() })
                old.events.acknowledge("old")
                repeat(100) { assertFalse(fixture.rpc.setActivity("retry", "retry")) }
                assertEquals(0, fixture.worker.queue.size)
                assertEquals(1L, fixture.worker.taskCount)

                old.connectRelease.countDown()
                old.sendRelease.countDown()
                fixture.idle()
                assertEquals(listOf("unbind", "disconnect"), old.cleanup.toList())
                assertEquals(if (connecting) emptyList() else listOf("old frame", "clear"), old.sent.toList())
                fixture.stallConnect = false
                fixture.stallSend = false
                val next = async { fixture.rpc.setActivity("next", "next frame") }
                fixture.await { fixture.sessions.size == 2 && fixture.sessions.last().sent.isNotEmpty() }
                fixture.sessions.last().events.acknowledge("next")
                assertTrue(next.await())
            }
        }
    }

    @Test
    fun stalledDisconnectReleasesBindingAndPreventsRetryAccumulation() = runBlocking {
        Fixture(timeout = 10_000).use { fixture ->
            fixture.stallDisconnect = true
            val result = async { fixture.rpc.setActivity("first", "first frame") }
            fixture.await { fixture.sessions.firstOrNull()?.sent?.isNotEmpty() == true }
            val old = fixture.sessions.single()
            old.events.acknowledge("first")
            assertTrue(result.await())
            fixture.rpc.clearActivity("clear", disconnect = true)
            fixture.await { old.cleanup.size == 2 }
            val scheduled = fixture.worker.taskCount
            assertEquals(listOf("unbind", "disconnect"), old.cleanup.toList())
            assertEquals(1L, old.disconnectRelease.count)
            repeat(100) {
                assertFalse(fixture.rpc.setActivity("retry", "retry"))
                fixture.rpc.clearActivity("clear", disconnect = true)
            }
            assertEquals(1, fixture.sessions.size)
            assertEquals(scheduled, fixture.worker.taskCount)
            assertEquals(0, fixture.worker.queue.size)
            old.disconnectRelease.countDown()
            fixture.idle()
            fixture.stallDisconnect = false
            val next = async { fixture.rpc.setActivity("next", "next frame") }
            fixture.await { fixture.sessions.size == 2 && fixture.sessions.last().sent.isNotEmpty() }
            fixture.sessions.last().events.acknowledge("next")
            assertTrue(next.await())
        }
    }

    @Test
    fun healthyIdleDisconnectReconnectsWithoutFailureBackoff() = runBlocking {
        Fixture(timeout = 10_000).use { fixture ->
            val first = async { fixture.rpc.setActivity("first", "first frame") }
            fixture.await { fixture.sessions.firstOrNull()?.sent?.isNotEmpty() == true }
            val old = fixture.sessions.single()
            old.events.acknowledge("first")
            assertTrue(first.await())
            old.events.fail(DiscordRpcSession.Failure.SERVICE_DIED)
            fixture.idle()
            val next = async { fixture.rpc.setActivity("next", "next frame") }
            fixture.await { fixture.sessions.size == 2 && fixture.sessions.last().sent.isNotEmpty() }
            fixture.sessions.last().events.acknowledge("next")
            assertTrue(next.await())
        }
    }

    @Test
    fun missingAckRetainsConnectionAndOnlyMatchingAckResetsBackoff() = runBlocking {
        Fixture().use { fixture ->
            assertFalse(fixture.rpc.setActivity("first", "first frame"))
            fixture.idle()
            val session = fixture.sessions.single()
            assertTrue(session.cleanup.isEmpty())
            session.events.acknowledge("first")
            session.events.acknowledge(null)
            session.events.fail(DiscordRpcSession.Failure.REJECTED, "first")
            fixture.now.set(9_999)
            assertFalse(fixture.rpc.setActivity("too soon", "too soon"))
            assertEquals(listOf("first frame"), session.sent.toList())

            fixture.now.set(10_000)
            val second = async { fixture.rpc.setActivity("second", "second frame") }
            fixture.await { session.sent.size == 2 }
            session.events.acknowledge("first")
            assertFalse(withTimeout(2_000) { second.await() })
            fixture.idle()
            fixture.now.set(29_999)
            assertFalse(fixture.rpc.setActivity("too soon", "too soon"))
            assertEquals(2, session.sent.size)

            fixture.now.set(30_000)
            val third = async { fixture.rpc.setActivity("third", "third frame") }
            fixture.await { session.sent.size == 3 }
            session.events.acknowledge("third")
            assertTrue(third.await())
            assertFalse(fixture.rpc.setActivity("fourth", "fourth frame"))
            fixture.idle()
            fixture.now.set(39_999)
            assertFalse(fixture.rpc.setActivity("too soon", "too soon"))
            fixture.now.set(40_000)
            val fifth = async { fixture.rpc.setActivity("fifth", "fifth frame") }
            fixture.await { session.sent.size == 5 }
            session.events.acknowledge("fifth")
            assertTrue(fifth.await())
            assertEquals(1, fixture.sessions.size)
            assertTrue(session.cleanup.isEmpty())
        }
    }

    @Test
    fun cancellationAndClearBeforeReadyDoNotReplayActivity() = runBlocking {
        for (cancel in listOf(true, false)) {
            Fixture(timeout = 10_000).use { fixture ->
                fixture.readyOnConnect = false
                val result = async { fixture.rpc.setActivity("old", "old frame") }
                fixture.await { fixture.sessions.firstOrNull()?.connectEntered?.isCompleted == true }
                fixture.idle()
                val session = fixture.sessions.single()
                if (cancel) result.cancelAndJoin()
                else {
                    fixture.rpc.clearActivity("clear", disconnect = false)
                    assertFalse(withTimeout(1_000) { result.await() })
                }
                session.events.ready()
                fixture.idle()
                assertTrue(session.sent.isEmpty())
                val next = async { fixture.rpc.setActivity("next", "next frame") }
                fixture.await { session.sent.isNotEmpty() }
                session.events.acknowledge("next")
                assertTrue(next.await())
            }
        }
    }

    @Test
    fun supersededRequestSettlesAndOnlyCurrentNonceSucceeds() = runBlocking {
        Fixture(timeout = 10_000).use { fixture ->
            fixture.readyOnConnect = false
            val first = async { fixture.rpc.setActivity("first", "first frame") }
            fixture.await { fixture.sessions.firstOrNull()?.connectEntered?.isCompleted == true }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.rpc.setActivity("second", "second frame")
            }
            assertFalse(withTimeout(1_000) { first.await() })
            val session = fixture.sessions.single()
            session.events.ready()
            fixture.await { session.sent.isNotEmpty() }
            assertEquals(listOf("second frame"), session.sent.toList())
            session.events.acknowledge("first")
            session.events.fail(DiscordRpcSession.Failure.REJECTED, "first")
            assertFalse(second.isCompleted)
            session.events.acknowledge("second")
            assertTrue(second.await())
        }
    }

    @Test
    fun ackPastDeadlineCannotSucceedBeforeTimeoutCoroutineRuns() = runBlocking {
        Fixture(timeout = 10_000).use { fixture ->
            val result = async { fixture.rpc.setActivity("late", "late frame") }
            fixture.await { fixture.sessions.firstOrNull()?.sent?.isNotEmpty() == true }
            fixture.idle()
            fixture.now.set(10_000)
            fixture.sessions.single().events.acknowledge("late")
            assertFalse(withTimeout(1_000) { result.await() })
            assertFalse(fixture.rpc.setActivity("gated", "gated"))
        }
    }

    @Test
    fun persistentFailuresBackOffToCapAndStopResetsGate() = runBlocking {
        Fixture().use { fixture ->
            fixture.bindFailure = DiscordRpcSession.Failure.NOT_INSTALLED
            var now = 0L
            for ((index, delay) in listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L, 1_800_000L).withIndex()) {
                fixture.now.set(now)
                assertFalse(fixture.rpc.setActivity("failed-$index", "failed"))
                fixture.idle()
                assertEquals(index + 1, fixture.sessions.size)
                fixture.now.set(now + delay - 1)
                assertFalse(fixture.rpc.setActivity("gated", "gated"))
                assertEquals(index + 1, fixture.sessions.size)
                now += delay
            }
            fixture.rpc.clearActivity("clear", disconnect = true)
            fixture.bindFailure = null
            val result = async { fixture.rpc.setActivity("recovered", "recovered frame") }
            fixture.await { fixture.sessions.last().sent.isNotEmpty() }
            fixture.sessions.last().events.acknowledge("recovered")
            assertTrue(result.await())
        }
    }

    private class Fixture(timeout: Long = 100) : AutoCloseable {
        val now = AtomicLong()
        val sessions = CopyOnWriteArrayList<FakeSession>()
        var stallConnect = false
        var stallSend = false
        var stallDisconnect = false
        var readyOnConnect = true
        var bindFailure: DiscordRpcSession.Failure? = null
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val rpc = DiscordRpcSession(
            createBinding = { events ->
                FakeSession(events, stallConnect, stallSend, stallDisconnect, readyOnConnect, bindFailure)
                    .also { sessions.add(it) }
            },
            elapsedRealtime = now::get,
            worker = worker,
            responseTimeoutMs = timeout,
        )

        suspend fun await(condition: () -> Boolean) = withTimeout(2_000) {
            while (!condition()) delay(1)
        }

        suspend fun idle() = await { worker.activeCount == 0 && worker.queue.isEmpty() }

        override fun close() {
            sessions.forEach {
                it.connectRelease.countDown()
                it.sendRelease.countDown()
                it.disconnectRelease.countDown()
            }
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private class FakeSession(
        val events: DiscordRpcSession.Events,
        stallConnect: Boolean,
        stallSend: Boolean,
        stallDisconnect: Boolean,
        private val readyOnConnect: Boolean,
        private val bindFailure: DiscordRpcSession.Failure?,
    ) : DiscordRpcSession.Binding, DiscordRpcSession.Connection {
        val connectEntered = CompletableDeferred<Unit>()
        val sendEntered = CompletableDeferred<Unit>()
        val connectRelease = CountDownLatch(if (stallConnect) 1 else 0)
        val sendRelease = CountDownLatch(if (stallSend) 1 else 0)
        val disconnectRelease = CountDownLatch(if (stallDisconnect) 1 else 0)
        val sent = CopyOnWriteArrayList<String>()
        val cleanup = CopyOnWriteArrayList<String>()

        override fun bind(): DiscordRpcSession.Failure? {
            if (bindFailure != null) return bindFailure
            events.connected {
                connectEntered.complete(Unit)
                connectRelease.await()
                if (readyOnConnect) events.ready()
                this
            }
            return null
        }

        override fun unbind() {
            cleanup.add("unbind")
        }

        override fun sendFrame(frame: String) {
            sent.add(frame)
            sendEntered.complete(Unit)
            if (frame != "clear") sendRelease.await()
        }

        override fun disconnect() {
            cleanup.add("disconnect")
            disconnectRelease.await()
        }
    }
}
