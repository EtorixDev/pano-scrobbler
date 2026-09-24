package dev.etorix.panoscrobbler

import com.sun.net.httpserver.HttpServer
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Scrobblables
import dev.etorix.panoscrobbler.api.ScrobbleEverywhere
import dev.etorix.panoscrobbler.api.UserAccountSerializable
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.db.SimpleEdit
import dev.etorix.panoscrobbler.db.SimpleEditsDao.Companion.insertReplaceLowerCase
import dev.etorix.panoscrobbler.media.CommonPlaybackState
import dev.etorix.panoscrobbler.media.MediaListener
import dev.etorix.panoscrobbler.media.MetadataInfo
import dev.etorix.panoscrobbler.media.PlaybackInfo
import dev.etorix.panoscrobbler.media.PlayingTrackInfo
import dev.etorix.panoscrobbler.media.PlayingTrackNotifyEvent
import dev.etorix.panoscrobbler.media.ScrobbleQueue
import dev.etorix.panoscrobbler.media.globalTrackEventFlow
import dev.etorix.panoscrobbler.media.listenForPlayingTrackEvents
import dev.etorix.panoscrobbler.pref.MainPrefs
import dev.etorix.panoscrobbler.utils.DesktopStuff
import dev.etorix.panoscrobbler.utils.PlatformStuff
import java.io.File
import java.net.InetSocketAddress
import java.net.URLClassLoader
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.system.exitProcess

class MediaListenerPromotionTest {
    @Test
    fun submittedSessionIsNotPromotedAgain() {
        // DesktopStuff, PanoDb, preferences, and event flows are process-wide singletons.
        val fixture = Files.createTempDirectory("pano-media-listener-promotion-")
        val home = Files.createDirectories(fixture.resolve("home"))
        val tmp = Files.createDirectories(fixture.resolve("tmp"))
        val log = fixture.resolve("scenario.log")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java,
            "-Djava.awt.headless=true",
            "-Duser.home=$home",
            "-Djava.io.tmpdir=$tmp",
            "-cp",
            testClasspath(),
            MediaListenerPromotionScenario::class.java.name,
            fixture.resolve("data").toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile())
        process.environment()["XDG_CONFIG_HOME"] = fixture.resolve("config").toString()
        process.environment()["XDG_CACHE_HOME"] = fixture.resolve("cache").toString()
        process.environment()["XDG_DATA_HOME"] = fixture.resolve("share").toString()

        println("ISOLATED_PROMOTION_FIXTURE=$fixture")
        val child = process.start()
        val finished = child.waitFor(110, TimeUnit.SECONDS)
        if (!finished) {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
        assertTrue(finished, "Promotion scenario timed out. Fixture: $fixture\n${Files.readString(log)}")
        assertEquals(0, child.exitValue(), "Promotion scenario failed. Fixture: $fixture\n${Files.readString(log)}")
    }

    private fun testClasspath(): String {
        // Gradle's java.class.path may contain only gradle-worker.jar.
        val classLoaderEntries = generateSequence(MediaListenerPromotionTest::class.java.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { loader -> loader.getURLs().asSequence() }
            .filter { it.protocol == "file" }
            .map { Path.of(it.toURI()).toString() }
            .toList()
        return (classLoaderEntries + System.getProperty("java.class.path").split(File.pathSeparator))
            .distinct()
            .joinToString(File.pathSeparator)
    }
}

/** Runs only from [MediaListenerPromotionTest]'s isolated child JVM. */
object MediaListenerPromotionScenario {
    private data class Request(val method: String, val artist: String?, val track: String?, val album: String?)

    private val requests = CopyOnWriteArrayList<Request>()
    private val events = CopyOnWriteArrayList<PlayingTrackNotifyEvent.TrackPlaying>()
    private val errors = CopyOnWriteArrayList<Throwable>()

    private class Listener(
        scope: CoroutineScope,
        queue: ScrobbleQueue,
        override val notifyTimelineUpdates: Boolean,
    ) : MediaListener(scope, queue) {
        override fun isMediaPlaying() = sessionTrackers.values.any { it.trackInfo.isPlaying }
        override fun shouldScrobble(rawAppId: String) = true
        override fun mute(hash: Int) {}
        override fun unmute(clearMutedHash: Boolean) {}

        fun add(key: String): SessionTracker {
            val tracker = object : SessionTracker(key, createTrackInfo(key, key)) {
                override fun love() {}
                override fun unlove() {}
                override fun skip() {}
                override fun stop() = pause()
                override fun onBeforeScrobble() {}
            }
            sessionTrackers[key] = tracker
            return tracker
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            runBlocking { runScenario(args) }
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
        exitProcess(0)
    }

    private suspend fun runScenario(args: Array<String>) {
        val data = Path.of(args.single())
        DesktopStuff.parseCmdlineArgs(arrayOf("--data-dir", data.toString(), "--no-update-check"))
        assertEquals(data.toFile().canonicalFile, DesktopStuff.appDataRoot.canonicalFile)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val values = exchange.requestBody.bufferedReader().use { body ->
                body.readText().split('&').associate { part ->
                    val fields = part.split('=', limit = 2)
                    URLDecoder.decode(fields[0], "UTF-8") to
                        URLDecoder.decode(fields.getOrElse(1) { "" }, "UTF-8")
                }
            }
            val method = values["method"].orEmpty()
            requests += Request(method, values["artist"], values["track"], values["album"])
            val response = when (method) {
                "track.updateNowPlaying" -> """{"nowplaying":{"artist":{"corrected":"0","#text":"YOASOBI"},"track":{"corrected":"0","#text":"ok"},"albumArtist":{"corrected":"0","#text":""},"album":{"corrected":"0","#text":""},"ignoredMessage":{"code":"0","#text":""}}}"""
                "track.scrobble" -> """{"scrobbles":{"@attr":{"accepted":1,"ignored":0}}}"""
                else -> """{"error":3,"message":"Unexpected isolated test request"}"""
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val account = UserAccountSerializable(
                AccountType.GNUFM,
                UserCached("Local Test", "", "", "", 0),
                "dummy-local-token",
                "http://127.0.0.1:${server.address.port}/",
            )
            PlatformStuff.mainPrefs.updateData {
                MainPrefs(
                    scrobbleAccounts = listOf(account),
                    currentAccountType = AccountType.GNUFM,
                    delaySecs = 30,
                    delayPercent = 30,
                    minDurationSecs = 10,
                    useTrackProgress = false,
                    submitNowPlaying = true,
                    fetchAlbum = false,
                    regexPresetsApps = emptyMap(),
                    regexPresets = emptySet(),
                    deezerApi = false,
                    tidalSteelSeriesApi = false,
                )
            }
            PlatformStuff.mainPrefs.data.first()
            awaitCondition("local service configured") {
                Scrobblables.all.singleOrNull()?.userAccount == account
            }
            val edits = PanoDb.db.getSimpleEditsDao()
            edits.insertReplaceLowerCase(
                SimpleEdit(
                    origArtist = "YOASOBI", origTrack = "アイドル", hasOrigAlbum = false,
                    track = "Idol", artist = "YOASOBI", continueMatching = false,
                )
            )
            edits.insertReplaceLowerCase(
                SimpleEdit(
                    origArtist = "YOASOBI", origTrack = "Idol", hasOrigAlbum = false,
                    track = "Idol (English Version)", artist = "YOASOBI", continueMatching = false,
                )
            )
            assertEquals(2, edits.allFlow().first().size)
            assertTrue(PanoDb.db.getRegexEditsDao().enabledFlow().first().isEmpty())
            assertEquals("Idol", preprocess("アイドル"))
            assertEquals("Idol (English Version)", preprocess("Idol"))

            submittedSongNotPromoted("unknown-position", knownPosition = false, pauseGap = 0)
            submittedSongNotPromoted("known-position", knownPosition = true, pauseGap = 0)
            submittedSongNotPromoted("shifted-timeline", knownPosition = true, pauseGap = 4_000)
            normalResumeAndReplay()
            unfinishedCandidateIsPromotedAndSubmittedOwnerBlocksClaimant()

            assertTrue(errors.isEmpty(), "Async errors: $errors")
            assertTrue(requests.all { it.method in setOf("track.updateNowPlaying", "track.scrobble") })
            assertEquals(0, PanoDb.db.getPendingScrobblesDao().count())
            println("ALL_PROMOTION_ASSERTIONS_PASSED")
        } finally {
            server.stop(0)
        }
    }

    private suspend fun preprocess(title: String) = ScrobbleEverywhere.preprocessMetadata(
        ScrobbleData(
            track = title, artist = "YOASOBI", album = "Original Album",
            timestamp = 1, albumArtist = null, duration = null, appId = null,
        ),
        null,
    ).scrobbleData.track

    private fun metadata(label: String, japanese: Boolean = true) = MetadataInfo(
        title = if (japanese) "アイドル" else "Other Track",
        artist = if (japanese) "YOASOBI" else "Other Artist",
        album = label,
        albumArtist = "Album Artist",
        trackNumber = 1,
        duration = 40_000,
        artUrl = null,
        normalizedUrlHost = null,
    )

    private fun MediaListener.SessionTracker.play(position: Long) = playbackStateChanged(
        PlaybackInfo(CommonPlaybackState.Playing, position, true), false,
    )

    private fun MediaListener.SessionTracker.pauseEvent(position: Long) = playbackStateChanged(
        PlaybackInfo(CommonPlaybackState.Paused, position, true), false,
    )

    private suspend fun awaitCondition(label: String, predicate: () -> Boolean) {
        withTimeout(20_000) {
            while (!predicate()) {
                assertTrue(errors.isEmpty(), "Async errors while awaiting $label: $errors")
                delay(25)
            }
        }
    }

    private fun titleRequests(label: String, method: String) = requests.filter {
        it.album == label && it.artist == "YOASOBI" && it.method == method
    }.map { it.track }

    private fun observe(scope: CoroutineScope, queue: ScrobbleQueue, listener: Listener) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { listenForPlayingTrackEvents(queue, listener) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            globalTrackEventFlow.collect { event ->
                if (event is PlayingTrackNotifyEvent.TrackPlaying) events += event
                if (event is PlayingTrackNotifyEvent.Error) {
                    errors += AssertionError("Unexpected application event: $event")
                }
            }
        }
    }

    private suspend fun submittedSongNotPromoted(label: String, knownPosition: Boolean, pauseGap: Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            errors += error
        })
        val queue = ScrobbleQueue(scope)
        val listener = Listener(scope, queue, notifyTimelineUpdates = knownPosition)
        observe(scope, queue, listener)
        try {
            delay(150)
            val song = listener.add("$label-song")
            song.metadataChanged(metadata(label), false)
            val started = System.currentTimeMillis()
            song.play(if (knownPosition) 2_000 else -1)
            awaitCondition("first submission for $label") {
                song.trackInfo.scrobbledState == PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED &&
                    !queue.has(song.trackInfo.hash) && titleRequests(label, "track.scrobble").size == 1
            }
            assertEquals("Idol", song.trackInfo.title)
            assertEquals("アイドル", song.trackInfo.origTitle)
            song.metadataChanged(metadata(label), false)
            assertEquals("Idol", song.trackInfo.title)
            val nowPlayingBefore = titleRequests(label, "track.updateNowPlaying")
            val scrobblesBefore = titleRequests(label, "track.scrobble")
            assertEquals(listOf("Idol"), nowPlayingBefore)
            assertEquals(listOf("Idol"), scrobblesBefore)

            val positionAtPause = System.currentTimeMillis() - started + 2_000
            song.pauseEvent(if (knownPosition) positionAtPause else -1)
            val other = listener.add("$label-other")
            other.metadataChanged(metadata("$label-other", japanese = false), false)
            other.play(-1)
            awaitCondition("other owner queued for $label") { queue.has(other.trackInfo.hash) }
            if (pauseGap > 0) delay(pauseGap)
            val resumePosition = if (knownPosition) {
                if (pauseGap > 0) positionAtPause else System.currentTimeMillis() - started + 2_000
            } else {
                -1
            }
            song.play(resumePosition)
            assertTrue(song.trackInfo.isPlaying)
            assertEquals(PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED, song.trackInfo.scrobbledState)
            assertFalse(queue.has(song.trackInfo.hash), "A submitted resume must stay out of the queue")

            other.pauseEvent(-1)
            assertFalse(queue.has(song.trackInfo.hash), "Releasing another owner must not requeue the submitted song")
            delay(1_500) // Longer than ScrobbleQueue's metadata wait on the old promotion path.
            assertEquals(PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED, song.trackInfo.scrobbledState)
            assertEquals("Idol", song.trackInfo.title)
            assertEquals("アイドル", song.trackInfo.origTitle)
            assertEquals(nowPlayingBefore, titleRequests(label, "track.updateNowPlaying"))
            assertEquals(scrobblesBefore, titleRequests(label, "track.scrobble"))
            assertFalse(events.any {
                it.notiKey == "$label-song" && it.scrobbleData.track == "Idol (English Version)"
            })
            assertTrue(System.currentTimeMillis() - started < 40_000 + pauseGap)
        } finally {
            queue.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun normalResumeAndReplay() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            errors += error
        })
        val queue = ScrobbleQueue(scope)
        val listener = Listener(scope, queue, notifyTimelineUpdates = true)
        observe(scope, queue, listener)
        val label = "controls"
        try {
            delay(150)
            val song = listener.add("$label-song")
            song.metadataChanged(metadata(label), false)
            song.play(-1)
            awaitCondition("initial preprocessing") { song.trackInfo.title == "Idol" }
            song.pauseEvent(-1)
            assertFalse(queue.has(song.trackInfo.hash))

            val holder = listener.add("early-resume-holder")
            holder.metadataChanged(metadata("early-resume-holder", japanese = false), false)
            holder.play(-1)
            awaitCondition("early resume holder queued") { queue.has(holder.trackInfo.hash) }
            song.play(-1)
            assertEquals("Idol", song.trackInfo.title)
            assertFalse(queue.has(song.trackInfo.hash), "An unfinished claimant must wait for the holder")
            holder.pauseEvent(-1)
            assertTrue(queue.has(song.trackInfo.hash), "Releasing the holder must promote the preprocessed song")
            awaitCondition("submission after early resume") {
                !queue.has(song.trackInfo.hash) && titleRequests(label, "track.scrobble").size == 1
            }
            assertEquals(listOf("Idol"), titleRequests(label, "track.scrobble"))
            song.play(-1) // Repeated Playing event must not restart processing.
            song.pauseEvent(-1)
            song.play(-1) // A submitted single-session resume must stay out of the queue.
            assertFalse(queue.has(song.trackInfo.hash))
            assertEquals("Idol", song.trackInfo.title)
            song.play(0) // A same-track replay must restart from the original title.
            assertEquals("アイドル", song.trackInfo.title)
            awaitCondition("replay from originals") { song.trackInfo.title == "Idol" }
            assertFalse(titleRequests(label, "track.updateNowPlaying").contains("Idol (English Version)"))
            assertFalse(titleRequests(label, "track.scrobble").contains("Idol (English Version)"))
        } finally {
            queue.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun unfinishedCandidateIsPromotedAndSubmittedOwnerBlocksClaimant() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            errors += error
        })
        val queue = ScrobbleQueue(scope)
        val listener = Listener(scope, queue, notifyTimelineUpdates = false)
        observe(scope, queue, listener)
        val label = "fresh-candidate"
        try {
            delay(150)
            val holder = listener.add("fresh-holder")
            holder.metadataChanged(metadata("fresh-holder", japanese = false), false)
            holder.play(-1)
            awaitCondition("fresh holder queued") { queue.has(holder.trackInfo.hash) }

            val candidate = listener.add(label)
            candidate.metadataChanged(metadata(label), false)
            candidate.play(-1)
            assertFalse(queue.has(candidate.trackInfo.hash), "A competing claimant must wait for its owner")
            holder.pauseEvent(-1)
            awaitCondition("unfinished candidate promoted") { queue.has(candidate.trackInfo.hash) }
            awaitCondition("promoted candidate submitted") {
                candidate.trackInfo.scrobbledState == PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED &&
                    !queue.has(candidate.trackInfo.hash) && titleRequests(label, "track.scrobble").size == 1
            }
            assertEquals("Idol", candidate.trackInfo.title)
            assertEquals(listOf("Idol"), titleRequests(label, "track.updateNowPlaying"))

            val claimant = listener.add("blocked-claimant")
            claimant.metadataChanged(metadata("blocked-claimant", japanese = false), false)
            claimant.play(-1)
            assertFalse(queue.has(claimant.trackInfo.hash))
            delay(8_600) // Exercise the real eight-second contention grace period.
            assertTrue(candidate.trackInfo.isPlaying)
            assertEquals(PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED, candidate.trackInfo.scrobbledState)
            assertFalse(queue.has(claimant.trackInfo.hash))
            assertTrue(requests.none { it.album == "blocked-claimant" })
        } finally {
            queue.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
