package dev.etorix.panoscrobbler.edits

import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.api.Scrobblable
import dev.etorix.panoscrobbler.api.Scrobblables
import dev.etorix.panoscrobbler.api.ScrobbleEverywhere
import dev.etorix.panoscrobbler.api.lastfm.ApiException
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.LastFm
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleIgnoredException
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.api.listenbrainz.ListenBrainz
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.db.PendingListenBrainzMutation
import dev.etorix.panoscrobbler.db.ScrobbleSource
import dev.etorix.panoscrobbler.db.SimpleEdit
import dev.etorix.panoscrobbler.db.SimpleEditsDao.Companion.insertReplaceLowerCase
import dev.etorix.panoscrobbler.db.SimpleEditsDao.Companion.performEdit
import dev.etorix.panoscrobbler.media.PlayingTrackNotifyEvent
import dev.etorix.panoscrobbler.media.notifyPlayingTrackEvent
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.utils.redactedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.rank_change_no_change
import pano_scrobbler.composeapp.generated.resources.required_fields_empty
import kotlin.math.abs

class EditScrobbleUtils(private val viewModelScope: CoroutineScope) {
    data class EditData(
        val id: Long,
        val key: String,
        val track: Track,
    )

    private data class ServiceTrackResolution(
        val track: Track?,
        val alreadyTarget: Boolean = false,
    )

    private class EditScrobbleException(
        failures: List<Pair<Scrobblable, Throwable>>
    ) : Exception(
        failures.joinToString("\n") { (scrobblable, throwable) ->
            "${scrobblable.userAccount.type}: ${throwable.redactedMessage}"
        },
        failures.firstOrNull()?.second
    )

    private val _result = MutableSharedFlow<Pair<ScrobbleData?, Result<Unit>>>()
    val result = _result.asSharedFlow()

    private val _updatedAlbum = MutableSharedFlow<Pair<ScrobbleData, String>>()
    val updatedAlbum = _updatedAlbum.asSharedFlow()

    private val _updatedAlbumArtist = MutableSharedFlow<Pair<ScrobbleData, String>>()
    val updatedAlbumArtist = _updatedAlbumArtist.asSharedFlow()

    private var editDataId = 0L
    private val _editData = MutableSharedFlow<EditData>(replay = 1, extraBufferCapacity = 1)
    val editDataFlow = _editData.asSharedFlow()

    fun doEdit(
        simpleEdit: SimpleEdit,
        origScrobbleData: ScrobbleData?,
        origTrack: Track?,
        msid: String?,
        hash: Int?,
        key: String?,
        save: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (origScrobbleData != null) {
                val newScrobbleData = simpleEdit.performEdit(origScrobbleData)

                val r = scrobbleAndDelete(
                    origScrobbleData,
                    newScrobbleData,
                    msid,
                    isNowPlaying = hash != null
                )
                    .onSuccess { editedSd ->
                        if (save)
                            PanoDb.db.getSimpleEditsDao().insertReplaceLowerCase(simpleEdit)

                        if (hash != null) { // from notification
                            notifyPlayingTrackEvent(
                                PlayingTrackNotifyEvent.TrackScrobbleLocked(
                                    hash = hash,
                                    state = PlayingTrackNotifyEvent.TrackScrobbleLocked.LockState.SCROBBLED
                                ),
                            )
                        }

                        if (key != null) { // from scrobble history
                            _editData.emit(
                                EditData(
                                    id = ++editDataId,
                                    key = key,
                                    track = editedSd.toTrack(origTrack),
                                )
                            )
                        }
                    }
                    .recoverCatching {
                        if (it is ScrobbleIgnoredException) {
                            if (System.currentTimeMillis() - it.scrobbleTime >= Stuff.LASTFM_MAX_PAST_SCROBBLE && save) {
                                PanoDb.db.getSimpleEditsDao().insertReplaceLowerCase(simpleEdit)
                                throw ApiException(
                                    -1,
                                    "Scrobble too old, edit saved only for future scrobbles"
                                )
                            }
                        }
                        throw it
                    }
                _result.emit(origScrobbleData to r.map { })
            } else if (save) {
                PanoDb.db.getSimpleEditsDao().insertReplaceLowerCase(simpleEdit)
                _result.emit(origScrobbleData to Result.success(Unit))
            }
        }
    }

    private suspend fun scrobbleAndDelete(
        _origScrobbleData: ScrobbleData,
        _newScrobbleData: ScrobbleData,
        msid: String?,
        isNowPlaying: Boolean
    ): Result<ScrobbleData> {
        val newScrobbleData = _newScrobbleData.trimmed()
        val origScrobbleData = _origScrobbleData.trimmed()

        val track = newScrobbleData.track
        val origTrack = origScrobbleData.track
        var album = newScrobbleData.album
        val origAlbum = origScrobbleData.album
        var albumArtist = newScrobbleData.albumArtist
        val origAlbumArtist = origScrobbleData.albumArtist
        val artist = newScrobbleData.artist
        val origArtist = origScrobbleData.artist
        val timeMillis = origScrobbleData.timestamp

        if (track.isEmpty() || artist.isEmpty()) {
            return Result.failure(IllegalArgumentException(getString(Res.string.required_fields_empty)))
        }

        val fetchAlbum = PlatformStuff.mainPrefs.data.map { it.fetchAlbum }.first()
        val fetchAlbumAndAlbumArtist =
            album.isNullOrEmpty() && origAlbum.isNullOrEmpty() && fetchAlbum
        val rescrobbleRequired = !isNowPlaying && (fetchAlbumAndAlbumArtist ||
                (track.equals(origTrack, ignoreCase = true) &&
                        artist.equals(origArtist, ignoreCase = true)
                        && (album != origAlbum || !albumArtist.isNullOrEmpty())))
        var scrobbleData = ScrobbleData(
            artist = artist,
            track = track,
            timestamp = if (timeMillis > 0) timeMillis else System.currentTimeMillis(),
            album = album,
            albumArtist = albumArtist,
            duration = origScrobbleData.duration,
            appId = origScrobbleData.appId,
        )
        val origTrackObj = Track(
            origTrack,
            null,
            Artist(origArtist),
            date = timeMillis,
            msid = msid
        )

        if (track == origTrack &&
            artist == origArtist && album == origAlbum && albumArtist == origAlbumArtist &&
            !(album.isNullOrEmpty() && fetchAlbum)
        ) {
            return Result.failure(IllegalArgumentException(getString(Res.string.rank_change_no_change)))
        }

        if (fetchAlbumAndAlbumArtist) {
            val newTrack = Track(track, null, Artist(artist))

            val fetchedTrack = Requesters.lastfmUnauthedRequester
                .getTrackInfo(newTrack)
                .getOrNull()

            if (album.isNullOrEmpty() && fetchedTrack?.album != null) {
                album = fetchedTrack.album.name
                scrobbleData = scrobbleData.copy(album = album)
                _updatedAlbum.emit(_origScrobbleData to album)
            }
            if (albumArtist.isNullOrEmpty() && fetchedTrack?.album?.artist != null) {
                albumArtist = fetchedTrack.album.artist.name
                scrobbleData = scrobbleData.copy(albumArtist = albumArtist)
                _updatedAlbumArtist.emit(_origScrobbleData to albumArtist)
            }
        }

        if (isNowPlaying) {
            ScrobbleEverywhere.scrobble(scrobbleData)
            ScrobbleEverywhere.nowPlaying(scrobbleData)
            return Result.success(scrobbleData)
        }

        val syncEditsAcrossServices =
            PlatformStuff.mainPrefs.data.map { it.syncEditsAcrossServices }.first()
        val editCandidates =
            if (syncEditsAcrossServices)
                Scrobblables.all
            else
                listOfNotNull(Scrobblables.current)
        val editTargets = editCandidates.filter { it.supportsHistoricalEdit() }

        if (editTargets.isNotEmpty()) {
            if (editTargets.any { it.userAccount.type == AccountType.LASTFM }) {
                runCatching { LastFm.LastfmUnscrobbler.ensureCanEditScrobbles() }
                    .onFailure { return Result.failure(it) }
            }

            val failures = editTargets.mapNotNull { scrobblable ->
                editScrobbleForService(
                    scrobblable = scrobblable,
                    scrobbleData = scrobbleData,
                    origScrobbleData = origScrobbleData,
                    origTrackObj = origTrackObj,
                    rescrobbleRequired = rescrobbleRequired,
                ).exceptionOrNull()?.let { scrobblable to it }
            }

            if (failures.isNotEmpty()) {
                return Result.failure(
                    if (failures.size == 1) {
                        failures.first().second
                    } else {
                        EditScrobbleException(failures)
                    }
                )
            }
        }

        // track player
        scrobbleData.appId?.let {
            val scrobbleSource =
                ScrobbleSource(timeMillis = scrobbleData.timestamp, pkg = it)
            PanoDb.db.getScrobbleSourcesDao().insert(scrobbleSource)
        }

        return Result.success(scrobbleData)
    }

    private fun Scrobblable.supportsHistoricalEdit(): Boolean {
        return userAccount.type in arrayOf(
            AccountType.LASTFM,
            AccountType.LIBREFM,
            AccountType.GNUFM,
            AccountType.LISTENBRAINZ,
            AccountType.CUSTOM_LISTENBRAINZ,
        )
    }

    private suspend fun editScrobbleForService(
        scrobblable: Scrobblable,
        scrobbleData: ScrobbleData,
        origScrobbleData: ScrobbleData,
        origTrackObj: Track,
        rescrobbleRequired: Boolean,
    ): Result<Unit> = runCatching {
        val resolution = scrobblable.resolveOriginalTrackForService(
            origScrobbleData = origScrobbleData,
            scrobbleData = scrobbleData,
            fallbackTrack = origTrackObj,
        )
        if (resolution.alreadyTarget)
            return@runCatching

        val originalTrackForService = resolution.track
        if (originalTrackForService == null) {
            if (scrobblable is ListenBrainz) {
                PendingListenBrainzMutation.unresolvedEdit(
                    userAccount = scrobblable.userAccount,
                    originalScrobbleData = origScrobbleData,
                    replacementScrobbleData = scrobbleData,
                )?.let {
                    PanoDb.db.getPendingListenBrainzMutationsDao().insertBounded(it)
                }
            }
            return@runCatching
        }

        scrobblable.scrobble(scrobbleData).getOrThrow()
            .takeIf { it.ignored }
            ?.let { throw ScrobbleIgnoredException(origScrobbleData.timestamp) }

        // The user might submit the edit after it has been scrobbled, so delete anyways.
        val deleteResult = scrobblable.delete(originalTrackForService)
        if (deleteResult.exceptionOrNull() is LastFm.CookiesInvalidatedException) {
            throw deleteResult.exceptionOrNull()!!
        }
        deleteResult.getOrThrow()

        if (scrobblable is ListenBrainz) {
            PendingListenBrainzMutation.edit(
                userAccount = scrobblable.userAccount,
                originalTrack = originalTrackForService,
                replacementScrobbleData = scrobbleData,
            )?.let {
                PanoDb.db.getPendingListenBrainzMutationsDao().insertBounded(it)
            }
        }

        if (rescrobbleRequired) {
            scrobblable.scrobble(scrobbleData).getOrThrow()
                .takeIf { it.ignored }
                ?.let { throw ScrobbleIgnoredException(origScrobbleData.timestamp) }
        }
    }

    private suspend fun Scrobblable.resolveOriginalTrackForService(
        origScrobbleData: ScrobbleData,
        scrobbleData: ScrobbleData,
        fallbackTrack: Track,
    ): ServiceTrackResolution {
        return when {
            this is ListenBrainz -> ServiceTrackResolution(
                track = findOriginalListenBrainzTrack(origScrobbleData, fallbackTrack),
            )

            userAccount.type in arrayOf(
                AccountType.LASTFM,
                AccountType.LIBREFM,
                AccountType.GNUFM,
            ) -> resolveTimestampedTrackForService(
                origScrobbleData = origScrobbleData,
                scrobbleData = scrobbleData,
                fallbackTrack = fallbackTrack,
            )

            else -> ServiceTrackResolution(fallbackTrack)
        }
    }

    private suspend fun Scrobblable.resolveTimestampedTrackForService(
        origScrobbleData: ScrobbleData,
        scrobbleData: ScrobbleData,
        fallbackTrack: Track,
    ): ServiceTrackResolution {
        val timestamp = origScrobbleData.timestamp
        if (timestamp <= 0)
            return ServiceTrackResolution(fallbackTrack)

        val lookupWindowMs = 5 * 60 * 1000L
        val pageResult = getRecents(
            page = 1,
            cached = false,
            from = timestamp - lookupWindowMs,
            to = timestamp + lookupWindowMs,
            includeNowPlaying = false,
            limit = 25,
        ).getOrNull() ?: return ServiceTrackResolution(fallbackTrack)

        val candidates = pageResult.entries
            .filter { !it.isNowPlaying && it.date != null }
            .filter { abs(it.date!! - timestamp) <= 2_000L }

        fun List<Track>.closest() = minByOrNull { abs(it.date!! - timestamp) }

        val exactOriginal = candidates
            .filter { it.matchesExact(origScrobbleData) }
            .closest()
        val exactTarget = candidates
            .filter { it.matchesExact(scrobbleData) }
            .closest()
        if (exactOriginal == null && exactTarget != null) {
            return ServiceTrackResolution(
                track = exactTarget,
                alreadyTarget = true,
            )
        }

        val resolvedTrack = exactOriginal
            ?: candidates
                .filter { it.matchesLenient(origScrobbleData) }
                .closest()
            ?: candidates.closest()
            ?: fallbackTrack

        return ServiceTrackResolution(resolvedTrack)
    }

    private suspend fun ListenBrainz.findOriginalListenBrainzTrack(
        origScrobbleData: ScrobbleData,
        fallbackTrack: Track,
    ): Track? {
        val timestamp = origScrobbleData.timestamp
        if (timestamp <= 0)
            return fallbackTrack.takeIf { it.msid != null }

        val lookupWindowMs = 5 * 60 * 1000L
        val pageResult = getRecents(
            page = 1,
            cached = false,
            from = timestamp - lookupWindowMs,
            to = timestamp + lookupWindowMs,
            includeNowPlaying = false,
            limit = 25,
        ).getOrNull() ?: return fallbackTrack.takeIf { it.msid != null }

        val candidates = pageResult.entries
            .filter { !it.isNowPlaying && it.date != null && it.msid != null }

        return candidates
            .filter { it.matchesLenient(origScrobbleData) }
            .minByOrNull { abs(it.date!! - timestamp) }
            ?: fallbackTrack.takeIf { it.msid != null }
            ?: candidates
                .filter { abs(it.date!! - timestamp) <= 2_000L }
                .minByOrNull { abs(it.date!! - timestamp) }
    }

    private fun Track.matchesLenient(scrobbleData: ScrobbleData): Boolean {
        fun String?.metadataEquals(other: String?): Boolean {
            return orEmpty().trim().equals(other.orEmpty().trim(), ignoreCase = true)
        }

        val albumName = album?.name
        val albumMatches = albumName.isNullOrBlank() ||
                scrobbleData.album.isNullOrBlank() ||
                albumName.metadataEquals(scrobbleData.album)

        return name.metadataEquals(scrobbleData.track) &&
                artist.name.metadataEquals(scrobbleData.artist) &&
                albumMatches
    }

    private fun Track.matchesExact(scrobbleData: ScrobbleData): Boolean {
        fun String?.metadataEquals(other: String?): Boolean {
            return orEmpty().trim() == other.orEmpty().trim()
        }

        val albumName = album?.name
        val albumMatches = albumName.isNullOrBlank() ||
                scrobbleData.album.isNullOrBlank() ||
                albumName.metadataEquals(scrobbleData.album)

        return name.metadataEquals(scrobbleData.track) &&
                artist.name.metadataEquals(scrobbleData.artist) &&
                albumMatches
    }

    fun deleteSimpleEdit(simpleEdit: SimpleEdit) {
        viewModelScope.launch(Dispatchers.IO) {
            PanoDb.db.getSimpleEditsDao().delete(simpleEdit)
            _result.emit(null to Result.success(Unit))
        }
    }
}
