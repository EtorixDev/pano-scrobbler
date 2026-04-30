package dev.etorix.panoscrobbler.media

import co.touchlab.kermit.Logger
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Scrobblable
import dev.etorix.panoscrobbler.api.ScrobbleEverywhere
import dev.etorix.panoscrobbler.api.ScrobbleResult
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.db.BlockedMetadata
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.utils.PanoNotifications
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.utils.redactedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.parse_error
import pano_scrobbler.composeapp.generated.resources.scrobble_ignored
import kotlin.math.min


class ScrobbleQueue(
    private val scope: CoroutineScope,
) {
    class NetworkRequestNeededException(cause: Throwable? = null) :
        IllegalStateException("Network request needed", cause)

    // delays scrobbling this hash until it becomes null again
    private var lockedHash: Int? = null

    private val tickEveryMs = 500L

    private val scrobbleTasks = mutableMapOf<Int, Job>()

    private val fetchAdditionalMetadataTimestamps = ArrayDeque<Long>()
    private val fetchAdditionalMetadataMutex = Mutex()

    // ticker, only handles empty messages and messagePQ
    // required because uptimeMillis pauses / slows down in deep sleep

    private fun prune() {
        scrobbleTasks.entries.removeAll { (hash, job) ->
            job.isCancelled || job.isCompleted
        }
    }

    fun shutdown() {
        scrobbleTasks.forEach { (_, job) ->
            job.cancel()
        }

        scrobbleTasks.clear()
    }

    fun has(hash: Int) =
        scrobbleTasks[hash]?.let { !(it.isCancelled || it.isCompleted) } == true

    fun setLockedHash(hash: Int?) {
        lockedHash = hash
    }

    private suspend fun canFetchAdditionalMetadata() {
        // was still getting java.util.NoSuchElementException: ArrayDeque is empty, so use lock
        return fetchAdditionalMetadataMutex.withLock {
            val now = System.currentTimeMillis()
            // Remove timestamps older than n seconds
            while (now - (fetchAdditionalMetadataTimestamps.firstOrNull() ?: now) > 1 * 60_000) {
                fetchAdditionalMetadataTimestamps.removeFirstOrNull()
            }
            val can = fetchAdditionalMetadataTimestamps.size < 2
            fetchAdditionalMetadataTimestamps.addLast(System.currentTimeMillis())

            if (!can)
                throw NetworkRequestNeededException()
        }
    }

    fun scrobble(
        trackInfo: PlayingTrackInfo,
        appIsAllowListed: Boolean,
        delay: Long,
        timestampOverride: Long? = null,
    ) {
        if (trackInfo.title.isEmpty() || has(trackInfo.hash)) {
            return
        }

        val submitAtTime = PlatformStuff.monotonicTimeMs() + delay
        val hash = trackInfo.hash
        val wasScrobbledBeforePrepare =
            trackInfo.scrobbledState >= PlayingTrackInfo.ScrobbledState.SCROBBLE_SUBMITTED
        val prevPlayStartTime =
            if (trackInfo.scrobbledState > PlayingTrackInfo.ScrobbledState.PREPROCESSED)
                trackInfo.playStartTime
            else
                null
        val previousNowPlayingExpired = prevPlayStartTime == null ||
                (System.currentTimeMillis() - prevPlayStartTime) > min(
            trackInfo.durationMillis * 3 / 4,
            4 * 60 * 1000L
        )
        val shouldEmitNowPlayingEvent =
            previousNowPlayingExpired || wasScrobbledBeforePrepare
        trackInfo.prepareForScrobbling()
        fun ScrobbleData.withTimestampOverride() =
            if (timestampOverride != null) copy(timestamp = timestampOverride) else this

        fun PlayingTrackNotifyEvent.TrackPlaying.withScrobbleData(
            scrobbleData: ScrobbleData,
            origScrobbleData: ScrobbleData? = null,
        ) = copy(
            scrobbleData = scrobbleData.withTimestampOverride(),
            origScrobbleData = (origScrobbleData ?: this.origScrobbleData).withTimestampOverride(),
        )

        val scrobbleData = trackInfo.toScrobbleData(useOriginals = false).withTimestampOverride()
        val origScrobbleData = trackInfo.toScrobbleData(useOriginals = true).withTimestampOverride()
        val duplicateWindowStartMillis = timestampOverride ?: trackInfo.timelineStartTime.takeIf { it > 0L }

        suspend fun nowPlayingAndSubmit(
            sd: ScrobbleData,
            fetchAdditionalMetadata: Boolean
        ) = coroutineScope {
            val nowPlayingSd = sd.withTimestampOverride()

            val submitNowPlaying =
                PlatformStuff.mainPrefs.data.map { it.submitNowPlaying }.first()

            // now playing for a new track or after that of the previously paused track has expired
            var lastfmNpSucc = false
            if (
                submitNowPlaying &&
                (previousNowPlayingExpired || wasScrobbledBeforePrepare)
            ) {
                val npResults =
                    withTimeoutOrNull(submitAtTime - PlatformStuff.monotonicTimeMs() - 5000) {
                        ScrobbleEverywhere.nowPlaying(nowPlayingSd)
                    }

                // listenbrainz msid for now playing
                val msid = npResults?.firstNotNullOfOrNull { (k, v) ->
                    if (k.userAccount.type == AccountType.LISTENBRAINZ && v.isSuccess)
                        v.getOrThrow().msid
                    else
                        null
                }

                trackInfo.nowPlayingSubmitted(msid)

                if (msid != null)
                    notifyPlayingTrackEvent(trackInfo.toTrackPlayingEvent().withScrobbleData(nowPlayingSd))


                if (npResults != null && npResults.values.any { !it.isSuccess }) {
                    notifyScrobbleError(
                        notiKey = trackInfo.notiKey,
                        scrobbleResults = npResults,
                        scrobbleData = nowPlayingSd,
                        hash = hash
                    )
                }

                lastfmNpSucc = npResults?.any { (k, v) ->
                    k.userAccount.type == AccountType.LASTFM && v.isSuccess
                } == true
            }

            // discord rpc album art
            val npArtFetchJob =
                if (PlatformStuff.isDesktop &&
                    PlatformStuff.mainPrefs.data.map { it.discordRpc.enabled }.first() &&
                    lastfmNpSucc &&
                    trackInfo.artUrl == null
                ) {
                    launch(Dispatchers.IO) {
                        if (shouldFetchNpArtUrl().firstOrNull { it } == true) {
                            val additionalMetadata = ScrobbleEverywhere.fetchAdditionalMetadata(
                                scrobbleData,
                                ::canFetchAdditionalMetadata,
                                true,
                            )

                            if (additionalMetadata.artUrl != null) {
                                Logger.d { "fetched artUrl for now playing: ${additionalMetadata.artUrl}" }
                                trackInfo.setArtUrl(additionalMetadata.artUrl)
                                notifyPlayingTrackEvent(trackInfo.toTrackPlayingEvent())
                            }
                        }
                    }
                } else
                    null

            // tick every n milliseconds
            while (submitAtTime > PlatformStuff.monotonicTimeMs() || hash == lockedHash) {
                delay(tickEveryMs)
            }

            npArtFetchJob?.cancel()

            val duplicateProbeData = sd.withTimestampOverride()
            val exactDuplicateSource = duplicateProbeData.appId?.let { appId ->
                duplicateWindowStartMillis?.let { windowStart ->
                    PanoDb.db.getScrobbleSourcesDao().findForPackageBetween(
                        pkg = appId,
                        earliest = windowStart - Stuff.SCROBBLE_SOURCE_THRESHOLD,
                        latest = System.currentTimeMillis(),
                    )
                }
            }
            val sameTrackDuplicateSource = if (exactDuplicateSource == null) {
                duplicateProbeData.appId?.let { appId ->
                    duplicateWindowStartMillis?.let { windowStart ->
                        val windowMillis =
                            if (trackInfo.durationMillis > 0L) {
                                (trackInfo.durationMillis * 3 / 4)
                                    .coerceAtLeast(Stuff.SCROBBLE_SOURCE_THRESHOLD)
                            } else {
                                4 * 60 * 1000L
                            }
                        PanoDb.db.getScrobbleSourcesDao().findSameTrackForPackageBetween(
                            pkg = appId,
                            artist = duplicateProbeData.artist,
                            track = duplicateProbeData.track,
                            album = duplicateProbeData.album,
                            time = windowStart,
                            earliest = windowStart - windowMillis,
                            latest = windowStart + windowMillis,
                        )
                    }
                }
            } else {
                null
            }
            val duplicateSource = exactDuplicateSource ?: sameTrackDuplicateSource
            if (duplicateSource != null) {
                trackInfo.scrobbled()
                return@coroutineScope
            }

            // launch it in a separate scope, so that it does not get cancelled
            scope.launch(Dispatchers.IO) {
                val scrobbleSd = (if (fetchAdditionalMetadata) {
                    val additionalMetadata = ScrobbleEverywhere.fetchAdditionalMetadata(
                        scrobbleData,
                        { }
                    )

                    ScrobbleEverywhere.preprocessMetadata(
                        additionalMetadata.scrobbleData ?: scrobbleData,
                        trackInfo.normalizedUrlHost
                    ).scrobbleData
                } else {
                    sd
                }).withTimestampOverride()

                ScrobbleEverywhere.scrobble(scrobbleSd)
            }

            trackInfo.scrobbled()
            val scrobbledEvent = trackInfo.toTrackPlayingEvent().withScrobbleData(sd, origScrobbleData)

            notifyPlayingTrackEvent(
                scrobbledEvent
            )
        }

        if (shouldEmitNowPlayingEvent) {
            notifyPlayingTrackEvent(
                trackInfo.toTrackPlayingEvent().copy(
                    scrobbleData = scrobbleData,
                    nowPlaying = true,
                )
            )
        }

        if (!appIsAllowListed) {
            scope.launch {
                PanoNotifications.notifyAppDetected(
                    trackInfo.appId,
                    PlatformStuff.loadApplicationLabel(trackInfo.appId)
                )
            }
        }

        prune()
        scrobbleTasks[trackInfo.hash]?.cancel()
        scrobbleTasks[trackInfo.hash] = scope.launch(Dispatchers.IO) {
            // some players put the previous song and then switch to the current song in like 150ms
            // potentially wasting an api call. sleep and throw cancellation exception in that case
            delay(Stuff.META_WAIT)

            if (trackInfo.scrobbledState in
                PlayingTrackInfo.ScrobbledState.PREPROCESSED..PlayingTrackInfo.ScrobbledState.NOW_PLAYING_SUBMITTED
            ) {
                nowPlayingAndSubmit(
                    trackInfo.toScrobbleData(false).withTimestampOverride(),
                    trackInfo.scrobbledState == PlayingTrackInfo.ScrobbledState.PREPROCESSED
                )
                return@launch
            }

            val additionalMeta = ScrobbleEverywhere.fetchAdditionalMetadata(
                scrobbleData,
                ::canFetchAdditionalMetadata
            )

            val preprocessResult = ScrobbleEverywhere.preprocessMetadata(
                additionalMeta.scrobbleData ?: scrobbleData,
                trackInfo.normalizedUrlHost
            )

            when {
                preprocessResult.blockPlayerAction != null -> {
                    notifyPlayingTrackEvent(
                        PlayingTrackNotifyEvent.TrackCancelled(
                            hash = hash,
                            showUnscrobbledNotification = false,
                            blockedMetadata = BlockedMetadata(blockPlayerAction = preprocessResult.blockPlayerAction),
                        )
                    )
                }

                preprocessResult.titleParseFailed -> {
                    notifyPlayingTrackEvent(
                        PlayingTrackNotifyEvent.Error(
                            notiKey = trackInfo.notiKey,
                            hash = hash,
                            scrobbleError = ScrobbleError(
                                getString(Res.string.parse_error),
                                null,
                                trackInfo.appId,
                                canFixMetadata = true
                            ),
                            scrobbleData = scrobbleData.copy(albumArtist = ""),
                            msid = null,
                        )
                    )
                }

                else -> {
                    trackInfo.putPreprocessedData(
                        preprocessResult.scrobbleData,
                        preprocessResult.userLoved,
                        !additionalMeta.shouldFetchAgain
                    )

                    if (additionalMeta.artUrl != null) {
                        trackInfo.setArtUrl(additionalMeta.artUrl)
                    }

                    val preprocessedEvent =
                        trackInfo.toTrackPlayingEvent().withScrobbleData(
                            preprocessResult.scrobbleData,
                            additionalMeta.scrobbleData ?: origScrobbleData,
                        ).copy(nowPlaying = true)
                    notifyPlayingTrackEvent(
                        preprocessedEvent
                    )

                    nowPlayingAndSubmit(
                        preprocessResult.scrobbleData.withTimestampOverride(),
                        additionalMeta.shouldFetchAgain
                    )
                }
            }
        }
    }


    private suspend fun notifyScrobbleError(
        notiKey: String,
        scrobbleResults: Map<Scrobblable, Result<ScrobbleResult>>,
        scrobbleData: ScrobbleData,
        hash: Int
    ) {
        val failedTextLines = mutableListOf<String>()
        var ignored = false
        scrobbleResults.forEach { (scrobblable, result) ->
            if (result.isFailure) {
                val exception = scrobbleResults[scrobblable]?.exceptionOrNull()

                val errMsg = exception?.redactedMessage
                failedTextLines += scrobblable.userAccount.type.name + ": $errMsg"
            } else if (result.isSuccess && result.getOrThrow().ignored) {
                failedTextLines += scrobblable.userAccount.type.name + ": " +
                        getString(Res.string.scrobble_ignored)
                ignored = true
            }
        }

        if (failedTextLines.isNotEmpty()) {
            val failedText = failedTextLines.joinToString("\n")
            Logger.w { "failedText= $failedText" }
            if (ignored && scrobbleData.appId != null) {
                notifyPlayingTrackEvent(
                    PlayingTrackNotifyEvent.Error(
                        notiKey = notiKey,
                        hash = hash,
                        scrobbleData = scrobbleData,
                        scrobbleError = ScrobbleError(
                            failedText,
                            null,
                            scrobbleData.appId,
                            canFixMetadata = true,
                        ),
                        msid = null,
                    )
                )
            }
        }
    }

    fun remove(hash: Int) {
        if (hash == lockedHash) {
            Logger.d { "${hash.toHexString()} locked" }
            return
        }

        if (scrobbleTasks.remove(hash)?.cancel() != null) {
            Logger.d { "${hash.toHexString()} cancelled" }
        }
    }
}
