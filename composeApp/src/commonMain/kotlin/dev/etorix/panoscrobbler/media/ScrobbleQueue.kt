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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.parse_error
import pano_scrobbler.composeapp.generated.resources.scrobble_ignored
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds


class ScrobbleQueue(
    private val scope: CoroutineScope,
) {
    private data class TrackOccurrence(val hash: Int, val timestamp: Long)
    private data class QueuedScrobble(val occurrence: TrackOccurrence, val job: Job)

    // An editor holds only the playback occurrence it opened.
    private var lockedTrack: TrackOccurrence? = null

    private val tickEveryMs = 500L

    private val scrobbleTasks = mutableMapOf<Int, QueuedScrobble>()

    // ticker, only handles empty messages and messagePQ
    // required because uptimeMillis pauses / slows down in deep sleep

    private fun prune() {
        scrobbleTasks.entries.removeAll { (_, task) ->
            task.job.isCancelled || task.job.isCompleted
        }
    }

    fun shutdown() {
        scrobbleTasks.forEach { (_, task) ->
            task.job.cancel()
        }

        scrobbleTasks.clear()
    }

    fun has(hash: Int, timestamp: Long? = null) =
        scrobbleTasks[hash]?.let {
            !it.job.isCancelled && !it.job.isCompleted &&
                    (timestamp == null || it.occurrence.timestamp == timestamp)
        } == true

    fun has(trackInfo: PlayingTrackInfo) = has(trackInfo.hash, trackInfo.toScrobbleData(false).timestamp)

    fun lock(hash: Int, timestamp: Long) {
        lockedTrack = TrackOccurrence(hash, timestamp)
    }

    fun unlock(hash: Int, timestamp: Long) {
        if (lockedTrack == TrackOccurrence(hash, timestamp)) {
            lockedTrack = null
        }
    }

    fun completeEdit(hash: Int, timestamp: Long) {
        if (scrobbleTasks[hash]?.occurrence == TrackOccurrence(hash, timestamp)) {
            scrobbleTasks.remove(hash)?.job?.cancel()
        }
        unlock(hash, timestamp)
    }

    fun scrobble(
        trackInfo: PlayingTrackInfo,
        delay: Long,
        timestampOverride: Long? = null,
        reschedule: Boolean = false,
    ) {
        if (trackInfo.title.isEmpty() || has(trackInfo) && !reschedule) {
            return
        }

        val effectiveTimestampOverride = if (reschedule) {
            trackInfo.toScrobbleData(false).timestamp.takeIf { it > 0L } ?: timestampOverride
        } else {
            timestampOverride
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
        trackInfo.prepareForScrobbling(effectiveTimestampOverride)
        fun ScrobbleData.withTimestampOverride() =
            if (effectiveTimestampOverride != null) copy(timestamp = effectiveTimestampOverride) else this

        fun PlayingTrackNotifyEvent.TrackPlaying.withScrobbleData(
            scrobbleData: ScrobbleData,
            origScrobbleData: ScrobbleData? = null,
        ) = copy(
            scrobbleData = scrobbleData.withTimestampOverride(),
            origScrobbleData = (origScrobbleData ?: this.origScrobbleData).withTimestampOverride(),
        )

        val scrobbleData = trackInfo.toScrobbleData(useOriginals = false).withTimestampOverride()
        val origScrobbleData = trackInfo.toScrobbleData(useOriginals = true).withTimestampOverride()
        val occurrence = TrackOccurrence(hash, scrobbleData.timestamp)
        val duplicateWindowStartMillis = timestampOverride ?: trackInfo.timelineStartTime.takeIf { it > 0L }

        suspend fun nowPlayingAndSubmit(
            sd: ScrobbleData,
            fetchAdditionalMetadata: Boolean
        ) = coroutineScope {
            val nowPlayingSd = sd.withTimestampOverride()

            val submitNowPlaying =
                PlatformStuff.mainPrefs.data.map { it.submitNowPlaying }.first()

            // now playing for a new track or after that of the previously paused track has expired
            if (
                submitNowPlaying &&
                (previousNowPlayingExpired || wasScrobbledBeforePrepare)
            ) {
                val npResults =
                    withTimeoutOrNull((submitAtTime - PlatformStuff.monotonicTimeMs() - 5000).milliseconds) {
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

                val lastfmNpSucc = npResults?.any { (k, v) ->
                    k.userAccount.type == AccountType.LASTFM && v.isSuccess
                } == true
                if (lastfmNpSucc && trackInfo.artUrlState == PlayingTrackInfo.ArtUrlState.None)
                    trackInfo.setArtUrlState(PlayingTrackInfo.ArtUrlState.CanFetch)

                if (msid != null || PlatformStuff.isDesktop)
                    notifyPlayingTrackEvent(trackInfo.toTrackPlayingEvent().withScrobbleData(nowPlayingSd))


                if (npResults != null && npResults.values.any { !it.isSuccess }) {
                    notifyScrobbleError(
                        notiKey = trackInfo.notiKey,
                        scrobbleResults = npResults,
                        scrobbleData = nowPlayingSd,
                        hash = hash
                    )
                }
            }

            // tick every n milliseconds
            var wasLocked = false
            while (submitAtTime > PlatformStuff.monotonicTimeMs() || occurrence == lockedTrack) {
                if (occurrence == lockedTrack)
                    wasLocked = true

                delay(tickEveryMs.milliseconds)
            }

            if (wasLocked && trackInfo.hash == hash && !trackInfo.isPlaying)
                return@coroutineScope

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
            val submissionData = if (trackInfo.hash == hash) {
                sd.copy(extraTags = trackInfo.toScrobbleData(false).extraTags)
            } else {
                sd
            }

            if (duplicateSource == null) {
                // launch it in a separate scope, so that it does not get cancelled
                scope.launch(Dispatchers.IO) {
                    val scrobbleSd = (if (fetchAdditionalMetadata) {
                        val additionalMetadata = ScrobbleEverywhere.fetchMissingMetadata(scrobbleData)

                        ScrobbleEverywhere.preprocessMetadata(
                            additionalMetadata.scrobbleData ?: scrobbleData,
                            trackInfo.normalizedUrlHost
                        ).scrobbleData
                    } else {
                        sd
                    }).copy(extraTags = submissionData.extraTags).withTimestampOverride()

                    ScrobbleEverywhere.scrobble(scrobbleSd)
                }
            }

            trackInfo.scrobbled()
            val scrobbledEvent = trackInfo.toTrackPlayingEvent().withScrobbleData(
                submissionData,
                origScrobbleData.copy(extraTags = submissionData.extraTags),
            )

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

        prune()
        scrobbleTasks[trackInfo.hash]?.job?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            // some players put the previous song and then switch to the current song in like 150ms
            // potentially wasting an api call. sleep and throw cancellation exception in that case
            delay(Stuff.META_WAIT.milliseconds)

            if (trackInfo.scrobbledState in
                PlayingTrackInfo.ScrobbledState.PREPROCESSED..PlayingTrackInfo.ScrobbledState.NOW_PLAYING_SUBMITTED
            ) {
                nowPlayingAndSubmit(
                    trackInfo.toScrobbleData(false).withTimestampOverride(),
                    trackInfo.scrobbledState == PlayingTrackInfo.ScrobbledState.PREPROCESSED
                )
                return@launch
            }

            val additionalMeta = ScrobbleEverywhere.fetchMissingMetadata(scrobbleData)

            var preprocessResult = ScrobbleEverywhere.preprocessMetadata(
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
                    var artUrl = additionalMeta.artUrl
                    var shouldFetchAgain = additionalMeta.shouldFetchAgain

                    if (preprocessResult.canFetchAlbum) {
                        val additionalMetaAlbumGuess = ScrobbleEverywhere.guessAlbumFromCacheLastfm(
                            preprocessResult.scrobbleData,
                        )

                        preprocessResult = if (additionalMetaAlbumGuess.scrobbleData != null)
                            preprocessResult.copy(scrobbleData = additionalMetaAlbumGuess.scrobbleData)
                        else
                            preprocessResult

                        artUrl = artUrl ?: additionalMetaAlbumGuess.artUrl
                        shouldFetchAgain = shouldFetchAgain || additionalMetaAlbumGuess.shouldFetchAgain
                    }

                    trackInfo.putPreprocessedData(
                        preprocessResult.scrobbleData,
                        preprocessResult.userLoved,
                        !shouldFetchAgain
                    )

                    if (artUrl != null) {
                        trackInfo.setArtUrl(artUrl)
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
                        shouldFetchAgain
                    )
                }
            }
        }
        scrobbleTasks[hash] = QueuedScrobble(occurrence, job)
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
        if (scrobbleTasks[hash]?.occurrence?.let { it == lockedTrack } == true) {
            Logger.d { "${hash.toHexString()} locked" }
            return
        }

        if (scrobbleTasks.remove(hash)?.job?.cancel() != null) {
            Logger.d { "${hash.toHexString()} cancelled" }
        }
    }
}
