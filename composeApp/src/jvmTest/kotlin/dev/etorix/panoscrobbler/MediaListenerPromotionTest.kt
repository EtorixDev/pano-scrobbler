package dev.etorix.panoscrobbler

import com.sun.net.httpserver.HttpServer
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Scrobblables
import dev.etorix.panoscrobbler.api.ScrobbleEverywhere
import dev.etorix.panoscrobbler.api.UserAccountSerializable
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.api.listenbrainz.ListenBrainz
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.db.SimpleEdit
import dev.etorix.panoscrobbler.db.SimpleEditsDao.Companion.insertReplaceLowerCase
import dev.etorix.panoscrobbler.discordrpc.DiscordRpc
import dev.etorix.panoscrobbler.edits.EditScrobbleUtils
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
import dev.etorix.panoscrobbler.utils.PanoNotifications
import java.io.File
import java.net.InetSocketAddress
import java.net.URLClassLoader
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val finished = child.waitFor(180, TimeUnit.SECONDS)
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
    private data class Request(val method: String, val artist: String?, val track: String?, val album: String?, val timestamp: Long?)

    private val requests = CopyOnWriteArrayList<Request>()
    private val listens = CopyOnWriteArrayList<JsonObject>()
    private val historicalEdits = CopyOnWriteArrayList<Pair<String, String>>()
    private val events = CopyOnWriteArrayList<PlayingTrackNotifyEvent.TrackPlaying>()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private data class DelayedEditResponse(
        val entered: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1),
    )
    private val delayedEditResponses = java.util.concurrent.ConcurrentHashMap<String, DelayedEditResponse>()
    private var completionBarrierNumber = 0

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
        for (slot in listOf("slot2", "slot3")) {
            server.createContext("/$slot/") { exchange ->
                val response = if (exchange.requestMethod == "GET") {
                    """{"payload":{"count":0,"listens":[],"latest_listen_ts":null,"oldest_listen_ts":null}}"""
                } else {
                    historicalEdits += slot to exchange.requestURI.path.substringAfterLast('/')
                    exchange.requestBody.use { it.readBytes() }
                    """{"status":"ok"}"""
                }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }
        server.createContext("/listenbrainz/1/submit-listens") { exchange ->
            listens += Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
            val response = """{"status":"ok"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/") { exchange ->
            val values = exchange.requestBody.bufferedReader().use { body ->
                body.readText().split('&').associate { part ->
                    val fields = part.split('=', limit = 2)
                    URLDecoder.decode(fields[0], "UTF-8") to
                        URLDecoder.decode(fields.getOrElse(1) { "" }, "UTF-8")
                }
            }
            val method = values["method"].orEmpty()
            requests += Request(method, values["artist"], values["track"], values["album"], values["timestamp"]?.toLongOrNull())
            val delayedResponse = values["album"]?.let { delayedEditResponses[it] }
            if (method == "track.scrobble" && delayedResponse != null) {
                delayedResponse.entered.countDown()
                if (!delayedResponse.release.await(20, TimeUnit.SECONDS)) {
                    errors += AssertionError("The delayed edit response was not released")
                }
            }
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
            val listenBrainzAccount = account.copy(
                type = AccountType.CUSTOM_LISTENBRAINZ,
                apiRoot = "http://127.0.0.1:${server.address.port}/listenbrainz/",
            )
            PlatformStuff.mainPrefs.updateData {
                MainPrefs(
                    scrobbleAccounts = listOf(account, listenBrainzAccount),
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
                Scrobblables.all.map { it.userAccount }.toSet() == setOf(account, listenBrainzAccount)
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
            notificationRecognitionExpiresAfterSubmission()
            lateMetadataAndImmediateEditKeepCanonicalData()
            for (useTrackProgress in listOf(false, true)) {
                for (change in PendingEditPlaybackChange.entries) {
                    delayedEditKeepsPlaybackOwnership(change, useTrackProgress)
                }
            }
            cancelledEditUsesUpdatedDurationDeadline()
            completedEditSurvivesPlaybackGap(buffering = true)
            completedEditSurvivesPlaybackGap(buffering = false)
            customListenBrainzSlotsAreEdited(server.address.port)

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

    private fun listenMetadata(label: String, type: String): List<JsonObject> = listens
        .filter { it.getValue("listen_type").jsonPrimitive.content == type }
        .flatMap { it.getValue("payload").jsonArray }
        .map { it.jsonObject.getValue("track_metadata").jsonObject }
        .filter { it["release_name"]?.jsonPrimitive?.content == label }

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
            awaitCondition("initial now playing for $label") {
                titleRequests(label, "track.updateNowPlaying").size == 1 &&
                    listenMetadata(label, "playing_now").size == 1 &&
                    song.trackInfo.scrobbledState == PlayingTrackInfo.ScrobbledState.NOW_PLAYING_SUBMITTED
            }
            val firstArtistMbid = "12345678-1234-1234-1234-1234567890ab"
            val secondArtistMbid = "abcdefab-abcd-abcd-abcd-abcdefabcdef"
            val lateTags = mapOf(
                ListenBrainz.MUSICBRAINZ_TRACKID to firstArtistMbid,
                ListenBrainz.MUSICBRAINZ_ARTISTID to "$firstArtistMbid / $secondArtistMbid / $firstArtistMbid",
            )
            val beforeMetadataUpdate = song.trackInfo.scrobbledState
            val lateArtUrl = "https://example.invalid/$label.png"
            song.metadataChanged(metadata(label).copy(extraTags = lateTags, artUrl = lateArtUrl), false)
            assertEquals(beforeMetadataUpdate, song.trackInfo.scrobbledState)
            assertEquals(lateTags, song.trackInfo.toScrobbleData(false).extraTags)
            assertEquals(lateArtUrl, song.trackInfo.artUrl)
            awaitCondition("first submission for $label") {
                song.trackInfo.scrobbledState == PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED &&
                    !queue.has(song.trackInfo.hash) && titleRequests(label, "track.scrobble").size == 1 &&
                    listenMetadata(label, "single").size == 1
            }
            val additionalInfo = listenMetadata(label, "single").single().getValue("additional_info").jsonObject
            assertEquals(firstArtistMbid, additionalInfo.getValue("recording_mbid").jsonPrimitive.content)
            assertEquals(
                listOf(firstArtistMbid, secondArtistMbid),
                additionalInfo.getValue("artist_mbids").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(additionalInfo.getValue("media_player").jsonPrimitive.content.isNotBlank())
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
            val resumedPresence = PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                    as PlayingTrackNotifyEvent.TrackPlaying
            assertFalse(resumedPresence.nowPlaying)
            assertEquals("Idol", resumedPresence.scrobbleData.track)
            if (knownPosition) {
                song.play(resumePosition + 10_000)
                val soughtPresence = PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                        as PlayingTrackNotifyEvent.TrackPlaying
                assertEquals(song.trackInfo.timelineStartTime, soughtPresence.timelineStartTime)
                assertFalse(queue.has(song.trackInfo.hash))
            }

            other.pauseEvent(-1)
            assertFalse(queue.has(song.trackInfo.hash), "Releasing another owner must not requeue the submitted song")
            delay(1_500) // Longer than ScrobbleQueue's metadata wait on the old promotion path.
            assertEquals(PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED, song.trackInfo.scrobbledState)
            assertEquals("Idol", song.trackInfo.title)
            assertEquals("アイドル", song.trackInfo.origTitle)
            assertEquals(nowPlayingBefore, titleRequests(label, "track.updateNowPlaying"))
            assertEquals(scrobblesBefore, titleRequests(label, "track.scrobble"))
            assertEquals(1, listenMetadata(label, "playing_now").size)
            assertEquals(1, listenMetadata(label, "single").size)
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
            song.play(10_000) // Establish progress before seeking back to the start.
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

    private suspend fun notificationRecognitionExpiresAfterSubmission() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            errors += error
        })
        val queue = ScrobbleQueue(scope)
        val listener = Listener(scope, queue, notifyTimelineUpdates = true)
        observe(scope, queue, listener)
        try {
            PanoNotifications.clearPlayingTrackStates()
            fun newRecognition() = listener.createTrackInfo(
                "recognizer", "recognizer|notification", hasMediaSession = false,
            ).apply {
                putOriginals(
                    artist = "Artist", title = "Recognized Track", album = "recognition", albumArtist = "Artist",
                    trackNumber = 1, durationMillis = 120_000, normalizedUrlHost = null, artUrl = null,
                    extraTags = emptyMap(), setPlayStartTime = false,
                )
            }
            val sampleTimestamp = System.currentTimeMillis() - 10_000
            val recognition = newRecognition()
            queue.scrobble(recognition, delay = 0, timestampOverride = sampleTimestamp)
            awaitCondition("notification recognition submitted and cleared") {
                recognition.scrobbledState == PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED &&
                        requests.any { it.album == "recognition" && it.method == "track.scrobble" } &&
                        recognition.notiKey !in PanoNotifications.playingTrackStates.value
            }
            val duplicate = newRecognition()
            queue.scrobble(duplicate, delay = 2_000, timestampOverride = sampleTimestamp)
            awaitCondition("duplicate recognition presence before suppression") {
                (PanoNotifications.playingTrackStates.value[duplicate.notiKey]
                        as? PlayingTrackNotifyEvent.TrackPlaying)?.preprocessed == true
            }
            awaitCondition("duplicate recognition completed and cleared") {
                duplicate.scrobbledState == PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED &&
                        !queue.has(duplicate.hash) && duplicate.notiKey !in PanoNotifications.playingTrackStates.value
            }
            assertEquals(1, requests.count { it.album == "recognition" && it.method == "track.scrobble" })

            val song = listener.add("music-after-recognition")
            song.metadataChanged(metadata("music-after-recognition", japanese = false), false)
            song.play(-1)
            awaitCondition("music presence after duplicate recognition") {
                DiscordRpc.selectPlayingTrack(PanoNotifications.playingTrackStates.value)?.notiKey == song.trackInfo.notiKey
            }
        } finally {
            queue.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun lateMetadataAndImmediateEditKeepCanonicalData() {
        PlatformStuff.mainPrefs.updateData {
            it.copy(useTrackProgress = true, delaySecs = 60, delayPercent = 100, submitNowPlaying = false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            errors += error
        })
        val queue = ScrobbleQueue(scope)
        val listener = Listener(scope, queue, notifyTimelineUpdates = true)
        observe(scope, queue, listener)
        try {
            delay(150)
            val song = listener.add("immediate-edit-song")
            val initial = metadata("immediate-edit").copy(title = "Original Title", duration = 120_000)
            song.metadataChanged(initial, false)
            song.play(10_000)
            awaitCondition("canonical initial event") {
                (PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                        as? PlayingTrackNotifyEvent.TrackPlaying)?.preprocessed == true
            }
            val initialEvent = PanoNotifications.playingTrackStates.value.getValue(song.trackInfo.notiKey)
                    as PlayingTrackNotifyEvent.TrackPlaying
            val timestamp = initialEvent.scrobbleData.timestamp
            assertTrue(song.trackInfo.playStartTime - timestamp >= 9_000)
            val tags = mapOf(ListenBrainz.MUSICBRAINZ_TRACKID to "12345678-1234-1234-1234-1234567890ab")
            song.metadataChanged(initial.copy(extraTags = tags, artUrl = "https://example.invalid/late.png"), false)
            val refreshed = PanoNotifications.playingTrackStates.value.getValue(song.trackInfo.notiKey)
                    as PlayingTrackNotifyEvent.TrackPlaying
            assertEquals(timestamp, refreshed.scrobbleData.timestamp)
            assertEquals(timestamp, refreshed.origScrobbleData.timestamp)

            queue.lock(song.trackInfo.hash, refreshed.scrobbleData.timestamp)
            val editor = EditScrobbleUtils(scope)
            val result = scope.async(start = CoroutineStart.UNDISPATCHED) { editor.result.first() }
            editor.doEdit(
                simpleEdit = SimpleEdit(track = "Edited Title"),
                origScrobbleData = refreshed.scrobbleData,
                origTrack = null,
                msid = null,
                hash = song.trackInfo.hash,
                key = null,
                save = false,
            )
            withTimeout(10_000) { result.await().second.getOrThrow() }
            awaitCondition("edited snapshot returned to tracker") {
                song.trackInfo.title == "Edited Title" &&
                        song.trackInfo.scrobbledState == PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED
            }
            assertFalse(queue.has(song.trackInfo.hash), "Completing an edit must cancel even a locked queued submission")
            val submitted = requests.single { it.album == "immediate-edit" && it.method == "track.scrobble" }
            assertEquals("Edited Title", submitted.track)
            assertEquals(timestamp / 1000, submitted.timestamp)

            song.pauseEvent(10_000)
            song.play(10_000)
            song.play(20_000)
            val resumed = PanoNotifications.playingTrackStates.value.getValue(song.trackInfo.notiKey)
                    as PlayingTrackNotifyEvent.TrackPlaying
            assertEquals("Edited Title", resumed.scrobbleData.track)
            assertEquals("Original Title", resumed.origScrobbleData.track)
            assertEquals(timestamp, resumed.scrobbleData.timestamp)
            assertEquals(tags, resumed.scrobbleData.extraTags)
            assertFalse(resumed.nowPlaying)
            assertFalse(queue.has(song.trackInfo.hash))
            delay(750)
            assertEquals(1, requests.count { it.album == "immediate-edit" && it.method == "track.scrobble" })

            song.play(-1)
            assertEquals("Edited Title", song.trackInfo.title)
            assertFalse(queue.has(song.trackInfo.hash))
            song.play(0)
            awaitCondition("repeat restores original metadata") { song.trackInfo.title == "Original Title" }
            assertTrue(queue.has(song.trackInfo))
            assertTrue(song.trackInfo.toScrobbleData(false).timestamp > timestamp + 5_000)
            assertEquals(tags, song.trackInfo.toScrobbleData(false).extraTags)
        } finally {
            queue.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private enum class PendingEditPlaybackChange {
        DIFFERENT_TRACK, DURATION_CORRECTION, EARLY_POSITION_UPDATE, BUFFERING, REPEAT,
    }

    private suspend fun awaitEventCollector() {
        val markerApp = "test.event-barrier.${++completionBarrierNumber}"
        globalTrackEventFlow.emit(PlayingTrackNotifyEvent.AppAllowedBlocked(markerApp, allowed = true))
        withTimeout(10_000) {
            PlatformStuff.mainPrefs.data.first { markerApp in it.allowedPackages }
        }
    }

    private suspend fun delayedEditKeepsPlaybackOwnership(
        change: PendingEditPlaybackChange,
        useTrackProgress: Boolean,
    ) {
        val durationCorrection = change == PendingEditPlaybackChange.DURATION_CORRECTION
        val buffering = change == PendingEditPlaybackChange.BUFFERING
        val samePlayback = durationCorrection || change == PendingEditPlaybackChange.EARLY_POSITION_UPDATE || buffering
        PlatformStuff.mainPrefs.updateData {
            it.copy(useTrackProgress = useTrackProgress, delaySecs = if (samePlayback) 30 else 60,
                delayPercent = 100, minDurationSecs = 10, submitNowPlaying = false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            errors += error
        })
        val queue = ScrobbleQueue(scope)
        val listener = Listener(scope, queue, notifyTimelineUpdates = true)
        observe(scope, queue, listener)
        val label = "delayed-edit-${change.name}-$useTrackProgress"
        val delayedResponse = DelayedEditResponse()
        delayedEditResponses[label] = delayedResponse
        try {
            delay(150)
            val song = listener.add(label)
            val initial = metadata(label).copy(title = "Original Title", duration = if (buffering) 5_000 else 120_000)
            if (buffering) {
                song.playbackStateChanged(PlaybackInfo(CommonPlaybackState.Waiting, 0, true), false)
            }
            song.metadataChanged(initial, false)
            if (buffering) assertFalse(queue.has(song.trackInfo.hash), "Initial buffering must wait for playback")
            val initialPosition = when {
                buffering -> 0L
                change == PendingEditPlaybackChange.EARLY_POSITION_UPDATE -> -1L
                useTrackProgress || change == PendingEditPlaybackChange.REPEAT -> 30_000L
                else -> -1L
            }
            song.play(initialPosition)
            awaitCondition("first playback ready for a delayed edit") {
                (PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                        as? PlayingTrackNotifyEvent.TrackPlaying)?.preprocessed == true
            }
            val original = song.trackInfo.toScrobbleData(false)
            val originalHash = song.trackInfo.hash
            queue.lock(originalHash, original.timestamp)
            val editor = EditScrobbleUtils(scope)
            val result = scope.async(start = CoroutineStart.UNDISPATCHED) { editor.result.first() }
            editor.doEdit(
                simpleEdit = SimpleEdit(track = "Edit Of Earlier Playback"),
                origScrobbleData = original, origTrack = null, msid = null,
                hash = originalHash, key = null, save = false,
            )
            awaitCondition("edit request awaiting its response") { delayedResponse.entered.count == 0L }

            when (change) {
                PendingEditPlaybackChange.DIFFERENT_TRACK -> {
                    song.metadataChanged(initial.copy(title = "Intervening Track"), false)
                    song.metadataChanged(initial, false)
                }
                PendingEditPlaybackChange.DURATION_CORRECTION -> {
                    song.metadataChanged(initial.copy(duration = 125_000), false)
                }
                PendingEditPlaybackChange.EARLY_POSITION_UPDATE -> {
                    delay(3_200)
                    song.play(1_000)
                }
                PendingEditPlaybackChange.BUFFERING -> {
                    song.playbackStateChanged(PlaybackInfo(CommonPlaybackState.Waiting, 0, true), false)
                    delay(5_500)
                    song.play(1_000)
                }
                PendingEditPlaybackChange.REPEAT -> {
                    song.play(-1)
                    song.play(0)
                }
            }
            val expectedDuration = if (durationCorrection) 125_000L else initial.duration
            if (samePlayback) {
                assertEquals(original.timestamp, song.trackInfo.toScrobbleData(false).timestamp)
                assertEquals(expectedDuration, song.trackInfo.durationMillis)
                assertTrue(queue.has(song.trackInfo))
            } else {
                awaitCondition("second playback queued independently of the editor") {
                    val event = PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                            as? PlayingTrackNotifyEvent.TrackPlaying
                    event?.preprocessed == true && event.hash == originalHash &&
                            event.scrobbleData.timestamp > original.timestamp && queue.has(song.trackInfo)
                }
            }
            val current = song.trackInfo.toScrobbleData(false)
            delayedResponse.release.countDown()
            withTimeout(10_000) { result.await().second.getOrThrow() }

            // This barrier acknowledges completion without republishing the playing state.
            awaitEventCollector()
            if (samePlayback) {
                assertEquals("Edit Of Earlier Playback", song.trackInfo.title)
                assertEquals(original.timestamp, song.trackInfo.toScrobbleData(false).timestamp)
                assertEquals(expectedDuration, song.trackInfo.durationMillis)
                assertFalse(queue.has(song.trackInfo.hash))
                if (change == PendingEditPlaybackChange.EARLY_POSITION_UPDATE) {
                    for (position in listOf(1_000L, 0L, 1_000L)) song.play(position)
                    assertEquals("Edit Of Earlier Playback", song.trackInfo.title)
                    assertEquals(original.timestamp, song.trackInfo.toScrobbleData(false).timestamp)
                    assertFalse(queue.has(song.trackInfo.hash), "Early position updates must not reopen a completed edit")
                }
            } else {
                assertEquals(current, song.trackInfo.toScrobbleData(false))
                assertTrue(queue.has(song.trackInfo), "An earlier edit must not cancel this playback's queue task")
                assertTrue(song.trackInfo.scrobbledState < PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED)
                val presence = PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                        as? PlayingTrackNotifyEvent.TrackPlaying
                assertEquals(current, presence?.scrobbleData)
            }
            assertEquals(1, requests.count { it.album == label && it.method == "track.scrobble" })
        } finally {
            delayedResponse.release.countDown()
            delayedEditResponses.remove(label)
            queue.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun cancelledEditUsesUpdatedDurationDeadline() {
        PlatformStuff.mainPrefs.updateData {
            it.copy(useTrackProgress = false, delaySecs = 60, delayPercent = 30,
                minDurationSecs = 10, submitNowPlaying = false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            errors += error
        })
        val queue = ScrobbleQueue(scope)
        val listener = Listener(scope, queue, notifyTimelineUpdates = true)
        observe(scope, queue, listener)
        val label = "cancelled-duration-edit"
        try {
            delay(150)
            val song = listener.add(label)
            val initial = metadata(label).copy(title = "Duration Original", duration = 35_000)
            song.metadataChanged(initial, false)
            val started = System.currentTimeMillis()
            song.play(-1)
            awaitCondition("duration editor ready") {
                (PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                        as? PlayingTrackNotifyEvent.TrackPlaying)?.preprocessed == true
            }
            val original = song.trackInfo.toScrobbleData(false)
            val hash = song.trackInfo.hash
            globalTrackEventFlow.emit(PlayingTrackNotifyEvent.TrackScrobbleLocked(
                hash, PlayingTrackNotifyEvent.TrackScrobbleLocked.LockState.LOCKED, original.timestamp,
            ))
            awaitEventCollector()
            song.metadataChanged(initial.copy(duration = 50_000), false)
            assertEquals(original.timestamp, song.trackInfo.toScrobbleData(false).timestamp)
            delay((started + 11_000 - System.currentTimeMillis()).coerceAtLeast(0L))
            globalTrackEventFlow.emit(PlayingTrackNotifyEvent.TrackScrobbleLocked(
                hash, PlayingTrackNotifyEvent.TrackScrobbleLocked.LockState.UNLOCKED, original.timestamp,
            ))
            awaitEventCollector()
            delay(1_000)
            assertTrue(queue.has(song.trackInfo), "Cancelling the editor must retain the corrected deadline")
            assertTrue(requests.none { it.album == label && it.method == "track.scrobble" })
            awaitCondition("corrected duration submitted") {
                requests.count { it.album == label && it.method == "track.scrobble" } == 1 &&
                        listenMetadata(label, "single").size == 1 && !queue.has(hash)
            }
            assertTrue(System.currentTimeMillis() - started >= 14_500)
            val additionalInfo = listenMetadata(label, "single").single().getValue("additional_info").jsonObject
            assertEquals("50000", additionalInfo.getValue("duration_ms").jsonPrimitive.content)
        } finally {
            queue.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun completedEditSurvivesPlaybackGap(buffering: Boolean) {
        PlatformStuff.mainPrefs.updateData {
            it.copy(useTrackProgress = buffering, delaySecs = 30, delayPercent = 95,
                minDurationSecs = 10, submitNowPlaying = false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            errors += error
        })
        val queue = ScrobbleQueue(scope)
        val listener = Listener(scope, queue, notifyTimelineUpdates = true)
        observe(scope, queue, listener)
        val label = "completed-edit-gap-$buffering"
        try {
            delay(150)
            val song = listener.add(label)
            song.metadataChanged(metadata(label).copy(title = "Original Title", duration = 5_000), false)
            song.play(if (buffering) 0 else -1)
            awaitCondition("gap editor ready") {
                (PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                        as? PlayingTrackNotifyEvent.TrackPlaying)?.preprocessed == true
            }
            val original = song.trackInfo.toScrobbleData(false)
            queue.lock(song.trackInfo.hash, original.timestamp)
            val editor = EditScrobbleUtils(scope)
            val result = scope.async(start = CoroutineStart.UNDISPATCHED) { editor.result.first() }
            editor.doEdit(
                simpleEdit = SimpleEdit(track = "Completed Edit"),
                origScrobbleData = original, origTrack = null, msid = null,
                hash = song.trackInfo.hash, key = null, save = false,
            )
            withTimeout(10_000) { result.await().second.getOrThrow() }
            awaitEventCollector()
            val edited = song.trackInfo.toScrobbleData(false)
            assertEquals("Completed Edit", edited.track)
            song.play(2_000)
            if (buffering) {
                song.playbackStateChanged(PlaybackInfo(CommonPlaybackState.Waiting, 0, true), false)
            } else {
                song.pauseEvent(-1)
            }
            val playedBeforeGap = song.trackInfo.elapsedPlaybackMillis()
            delay(5_500)
            assertEquals(playedBeforeGap, song.trackInfo.elapsedPlaybackMillis())
            song.play(-1)
            assertEquals(0L, song.trackInfo.timelineStartTime, "Unknown-position resume must discard a stale timeline")
            song.play(1_000)
            awaitEventCollector()
            assertEquals(edited, song.trackInfo.toScrobbleData(false))
            assertEquals(PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED, song.trackInfo.scrobbledState)
            assertFalse(queue.has(song.trackInfo.hash))
            val presence = PanoNotifications.playingTrackStates.value[song.trackInfo.notiKey]
                    as? PlayingTrackNotifyEvent.TrackPlaying
            assertEquals(edited, presence?.scrobbleData)
            assertEquals(1, requests.count { it.album == label && it.method == "track.scrobble" })
        } finally {
            queue.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun customListenBrainzSlotsAreEdited(port: Int) {
        val accounts = listOf(AccountType.CUSTOM_LISTENBRAINZ_2, AccountType.CUSTOM_LISTENBRAINZ_3)
            .mapIndexed { index, type ->
                UserAccountSerializable(type, UserCached("Local Test", "", "", "", 0), "local-token",
                    "http://127.0.0.1:$port/slot${index + 2}/")
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val editor = EditScrobbleUtils(scope)
        try {
            for ((index, sync) in listOf(0 to false, 1 to false, 0 to true)) {
                PlatformStuff.mainPrefs.updateData {
                    it.copy(scrobbleAccounts = accounts, currentAccountType = accounts[index].type,
                        syncEditsAcrossServices = sync, fetchAlbum = false)
                }
                awaitCondition("custom slot selection") {
                    Scrobblables.all.map { it.userAccount }.toSet() == accounts.toSet() &&
                            Scrobblables.current?.userAccount?.type == accounts[index].type
                }
                historicalEdits.clear()
                val result = scope.async(start = CoroutineStart.UNDISPATCHED) { editor.result.first() }
                editor.doEdit(
                    simpleEdit = SimpleEdit(track = "Edited Historical Title"),
                    origScrobbleData = ScrobbleData("Artist", "Original Historical Title", "Album",
                        System.currentTimeMillis() - 60_000, albumArtist = null, duration = 120_000, appId = null),
                    origTrack = null,
                    msid = "12345678-1234-1234-1234-1234567890ab",
                    hash = null,
                    key = "historical",
                    save = false,
                )
                withTimeout(10_000) { result.await().second.getOrThrow() }
                val slots = if (sync) listOf("slot2", "slot3") else listOf("slot${index + 2}")
                assertEquals(slots.toSet(), historicalEdits.map { it.first }.toSet())
                assertEquals(slots.size * 2, historicalEdits.size)
                for (slot in slots) {
                    assertEquals(
                        listOf("submit-listens", "delete-listen"),
                        historicalEdits.filter { it.first == slot }.map { it.second },
                    )
                }
            }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
