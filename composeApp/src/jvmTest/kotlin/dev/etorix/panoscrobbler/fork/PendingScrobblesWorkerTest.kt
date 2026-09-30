package dev.etorix.panoscrobbler.fork

import com.sun.net.httpserver.HttpServer
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Scrobblables
import dev.etorix.panoscrobbler.api.ScrobbleEvent
import dev.etorix.panoscrobbler.api.UserAccountSerializable
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.db.PendingScrobble
import dev.etorix.panoscrobbler.pref.MainPrefs
import dev.etorix.panoscrobbler.utils.DesktopStuff
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.work.CommonWorkerResult
import dev.etorix.panoscrobbler.work.PendingScrobblesWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.system.exitProcess

class PendingScrobblesWorkerTest {
    @Test
    fun disabledServiceRetainsQueuedScrobblesAndAllowsLoves() {
        runIsolatedUiScenario("pending-scrobbles", PendingScrobblesWorkerScenario::class.java)
    }
}

/** Runs only in [PendingScrobblesWorkerTest]'s isolated child JVM. */
object PendingScrobblesWorkerScenario {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            runBlocking { runScenario(Path.of(args.single())) }
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
        exitProcess(0)
    }

    private suspend fun runScenario(data: Path) {
        DesktopStuff.parseCmdlineArgs(arrayOf("--data-dir", data.toString(), "--no-update-check"))
        assertEquals(data.toFile().canonicalFile, DesktopStuff.appDataRoot.canonicalFile)

        val output = Files.createFile(data.resolveSibling("submissions.jsonl"))
        val account = UserAccountSerializable(
            type = AccountType.FILE,
            user = UserCached("Local Test", "", "", "", 0),
            authKey = "jsonl",
            apiRoot = output.toUri().toString(),
            canScrobble = false,
        )
        PlatformStuff.mainPrefs.updateData {
            MainPrefs(
                scrobbleAccounts = listOf(account),
                currentAccountType = AccountType.FILE,
            )
        }
        awaitAccounts(setOf(account))

        val dao = PanoDb.db.getPendingScrobblesDao()
        val scrobble = ScrobbleData(
            artist = "Artist",
            track = "Queued Track",
            timestamp = System.currentTimeMillis() - 60_000,
            album = "Album",
            albumArtist = "Album Artist",
            duration = 180_000,
            appId = null,
        )
        val loved = scrobble.copy(track = "Loved Track")
        val queued = PendingScrobble(
            scrobbleData = scrobble,
            event = ScrobbleEvent.scrobble,
            services = setOf(AccountType.FILE),
            lastFailedTimestamp = 1_704_000_000_000,
            lastFailedReason = "Connection refused",
            canForceRetry = true,
        )
        dao.insertIgnore(queued)
        dao.insert(loved, ScrobbleEvent.love, listOf(AccountType.FILE), emptyList())
        val original = dao.allScrobblesForSubmission(10).single()
        assertEquals(queued.copy(_id = original._id), original)

        assertEquals(CommonWorkerResult.Success, PendingScrobblesWorker {}.doWork())
        val retained = dao.allScrobblesForSubmission(10).single()
        assertEquals(original, retained)
        assertTrue(dao.allLovesForSubmission(10).isEmpty())
        assertEquals(listOf("love" to "Loved Track"), submissions(output))

        val enabled = account.copy(canScrobble = true)
        PlatformStuff.mainPrefs.updateData { it.copy(scrobbleAccounts = listOf(enabled)) }
        awaitAccounts(setOf(enabled))

        assertEquals(CommonWorkerResult.Success, PendingScrobblesWorker {}.doWork())
        assertTrue(dao.allScrobblesForSubmission(10).isEmpty())
        assertEquals(
            listOf("love" to "Loved Track", "scrobble" to "Queued Track"),
            submissions(output),
        )

        val disabledRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            disabledRequests.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val disabled = UserAccountSerializable(
                type = AccountType.GNUFM,
                user = UserCached("Disabled Local Test", "", "", "", 0),
                authKey = "dummy-local-token",
                apiRoot = "http://127.0.0.1:${server.address.port}/",
                canScrobble = false,
            )
            PlatformStuff.mainPrefs.updateData {
                it.copy(scrobbleAccounts = listOf(enabled, disabled))
            }
            awaitAccounts(setOf(enabled, disabled))

            val mixed = queued.copy(
                scrobbleData = scrobble.copy(track = "Mixed Track"),
                services = setOf(AccountType.FILE, AccountType.GNUFM),
            )
            dao.insertIgnore(mixed)
            val mixedOriginal = dao.allScrobblesForSubmission(10).single()

            assertEquals(CommonWorkerResult.Success, PendingScrobblesWorker {}.doWork())
            assertEquals(
                mixedOriginal.copy(services = setOf(AccountType.GNUFM)),
                dao.allScrobblesForSubmission(10).single(),
            )
            assertFalse(Scrobblables.all.single { it.userAccount.type == AccountType.GNUFM }.userAccount.canScrobble)
            assertEquals(0, disabledRequests.get())
            assertEquals(
                listOf(
                    "love" to "Loved Track",
                    "scrobble" to "Queued Track",
                    "scrobble" to "Mixed Track",
                ),
                submissions(output),
            )

            dao.insert(scrobble, ScrobbleEvent.scrobble, listOf(AccountType.FILE), emptyList())
            PlatformStuff.mainPrefs.updateData { it.copy(scrobbleAccounts = emptyList()) }
            awaitAccounts(emptySet())

            assertEquals(CommonWorkerResult.Success, PendingScrobblesWorker {}.doWork())
            assertEquals(0, dao.count())
            assertEquals(3, submissions(output).size)
            assertEquals(0, disabledRequests.get())
        } finally {
            server.stop(0)
        }
    }

    private suspend fun awaitAccounts(expected: Set<UserAccountSerializable>) {
        PlatformStuff.mainPrefs.data.first()
        withTimeout(5_000) {
            while (Scrobblables.all.map { it.userAccount }.toSet() != expected) {
                delay(20)
            }
        }
    }

    private fun submissions(path: Path): List<Pair<String, String>> {
        return Files.readAllLines(path).map { line ->
            val row: JsonObject = Json.parseToJsonElement(line).jsonObject
            row.getValue("event").jsonPrimitive.content to row.getValue("track").jsonPrimitive.content
        }
    }
}
