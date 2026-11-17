package dev.etorix.panoscrobbler.media

import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.utils.MetadataUtils
import dev.etorix.panoscrobbler.utils.PlatformStuff
import kotlinx.serialization.Serializable
import java.util.Objects
import kotlin.math.abs

class PlayingTrackInfo(
    val appId: String, // normalized app id
    val notiKey: String,
    cachedTrackInfo: PlayingTrackInfo?,
    val hasMediaSession: Boolean = true,
) {
    enum class ScrobbledState {
        NONE,
        PREPARED,
        PREPROCESSED,
        ADDITIONAL_METADATA_FETCHED,
        NOW_PLAYING_SUBMITTED,
        SCROBBLE_SUBMITTED,
        CANCELLED,
    }

    @JvmInline
    @Serializable
    value class ArtUrlState(private val value: String?) {
        val url: String? get() = value.takeUnless { canFetch }
        val canFetch: Boolean get() = value == "can_fetch"

        companion object {
            val None = ArtUrlState(null)
            val CanFetch = ArtUrlState("can_fetch")
        }
    }

    var title: String = ""
        private set
    var origTitle: String = ""
        private set

    var album: String = ""
        private set

    var origAlbum: String = ""
        private set

    var artist: String = ""
        private set
    var origArtist: String = ""
        private set

    var albumArtist: String = ""
        private set
    var origAlbumArtist: String = ""
        private set

    var trackNumber: Int = 0
        private set

    var artUrlState: ArtUrlState = ArtUrlState.None
        private set
    val artUrl: String? get() = artUrlState.url
    private var originalArtworkBeforeEdit: ArtUrlState? = null

    var normalizedUrlHost: String? = null
        private set

    private var msid: String? = null

    var durationMillis: Long = 0
        private set

    private var extraTags: Map<String, String> = emptyMap()

    var hash: Int = 0
        private set

    var isPlaying: Boolean = false
        private set
    private var isBuffering: Boolean = false

    var userPlayCount: Int = 0
        private set

    var userLoved: Boolean = false
        private set

    // cached values
    var timelineStartTime: Long = cachedTrackInfo?.timelineStartTime ?: 0L
        private set
    private var playbackPositionMillis: Long = cachedTrackInfo?.playbackPositionMillis ?: -1L
    // Retained through unknown samples only while playback is continuous.
    var lastKnownPlaybackPositionMillis: Long = -1L
        private set
    var playStartTime: Long = cachedTrackInfo?.playStartTime ?: 0L
        private set
    private var scrobbleTimestamp: Long? = cachedTrackInfo?.scrobbleTimestamp

    // baseline for timePlayed; unlike playStartTime, advances on every resume,
    // so pause gaps don't count as played time
    private var segmentStartTime: Long = cachedTrackInfo?.segmentStartTime ?: 0L
    var scrobbledState: ScrobbledState = cachedTrackInfo?.scrobbledState ?: ScrobbledState.NONE
        private set
    var timePlayed: Long = cachedTrackInfo?.timePlayed ?: 0L
        private set
    var lastScrobbleHash: Int = cachedTrackInfo?.lastScrobbleHash ?: 0
        private set

    val sessionStartTime = PlatformStuff.monotonicTimeMs()

    fun putOriginals(
        artist: String,
        title: String,
        album: String,
        albumArtist: String,
        trackNumber: Int,
        durationMillis: Long,
        normalizedUrlHost: String?,
        artUrl: String?,
        extraTags: Map<String, String>,
        setPlayStartTime: Boolean = true,
    ) {
        origArtist = artist
        this.artist = artist
        origTitle = title
        this.title = title
        origAlbum = album
        this.album = album
        origAlbumArtist = albumArtist
        this.albumArtist = albumArtist
        this.trackNumber = trackNumber

        this.durationMillis = durationMillis
        hash = Objects.hash(albumArtist, artist, album, title, appId, notiKey)
        this.normalizedUrlHost = normalizedUrlHost

        this.artUrlState = ArtUrlState(artUrl)
        originalArtworkBeforeEdit = null

        this.extraTags = extraTags.toMap()

        scrobbledState = ScrobbledState.NONE
        scrobbleTimestamp = null
        lastKnownPlaybackPositionMillis = -1L
        msid = null
        if (setPlayStartTime) {
            playStartTime = if (isPlaying) System.currentTimeMillis() else 0L
            segmentStartTime = if (isBuffering) 0L else playStartTime
        }
    }

    fun setArtUrl(artUrl: String?) {
        artUrlState = ArtUrlState(artUrl)
    }

    fun setArtUrlState(state: ArtUrlState) {
        artUrlState = state
    }

    fun updateDuration(durationMillis: Long) {
        this.durationMillis = durationMillis
    }

    fun updateAdditionalMetadata(extraTags: Map<String, String>, artUrl: String?): Boolean {
        val updatedTags = this.extraTags + extraTags
        val tagsChanged = updatedTags != this.extraTags
        if (originalArtworkBeforeEdit != null && !artUrl.isNullOrEmpty()) {
            originalArtworkBeforeEdit = ArtUrlState(artUrl)
        }
        val artChanged = originalArtworkBeforeEdit == null && !artUrl.isNullOrEmpty() && artUrl != this.artUrl
        this.extraTags = updatedTags
        if (artChanged) {
            setArtUrl(artUrl)
        }
        return tagsChanged || artChanged
    }

    fun resetPlaybackProgress() {
        timelineStartTime = 0L
        playbackPositionMillis = -1L
        lastKnownPlaybackPositionMillis = -1L
        timePlayed = 0L
    }

    // this is only done for desktop
    fun setTimelineStartTime(seekPosition: Long): Boolean {
        if (seekPosition == -1L) {
            playbackPositionMillis = -1L
            if ((isBuffering || !isPlaying) && timelineStartTime != 0L) {
                timelineStartTime = 0L
                return true
            }
            return false
        }

        playbackPositionMillis = seekPosition
        lastKnownPlaybackPositionMillis = seekPosition

        if (durationMillis <= 0) {
            if (timelineStartTime != 0L) {
                timelineStartTime = 0L
                return true
            }
            return false
        }

        val startTime = System.currentTimeMillis() - seekPosition

        // only change if delta is significant
        if (abs(timelineStartTime - startTime) > 1000) {
            timelineStartTime = startTime
            return true
        }

        return false
    }

    fun prepareForScrobbling(timestampOverride: Long? = null) {
        lastScrobbleHash = hash

        if (scrobbledState < ScrobbledState.PREPROCESSED) {
            artist = MetadataUtils.sanitizeArtist(origArtist)
            album = MetadataUtils.sanitizeAlbum(origAlbum)
            albumArtist = MetadataUtils.sanitizeAlbumArtist(origAlbumArtist)
            userPlayCount = 0
            userLoved = false
            scrobbledState = ScrobbledState.PREPARED
        }

        isPlaying = true

        if (playStartTime <= 0L)
            playStartTime = System.currentTimeMillis()
        if (segmentStartTime <= 0L && !isBuffering)
            segmentStartTime = System.currentTimeMillis()
        scrobbleTimestamp = timestampOverride ?: scrobbleTimestamp ?: playStartTime
    }

    fun updateUserProps(
        userPlayCount: Int = this.userPlayCount,
        userLoved: Boolean = this.userLoved,
    ) {
        this.userPlayCount = userPlayCount
        this.userLoved = userLoved
    }

    fun resetTimePlayed() {
        timePlayed = 0L
        playStartTime = System.currentTimeMillis()
        segmentStartTime = if (isBuffering) 0L else playStartTime
    }

    fun resetMetadata() {
        putOriginals(
            artist = origArtist,
            title = origTitle,
            album = origAlbum,
            albumArtist = origAlbumArtist,
            trackNumber = trackNumber,
            durationMillis = durationMillis,
            normalizedUrlHost = normalizedUrlHost,
            artUrl = (originalArtworkBeforeEdit ?: artUrlState).url,
            extraTags = extraTags,
        )
    }

    fun addTimePlayed() {
        if (segmentStartTime > 0L)
            timePlayed += System.currentTimeMillis() - segmentStartTime
        segmentStartTime = 0L
    }

    fun elapsedPlaybackMillis(now: Long = System.currentTimeMillis()): Long = timePlayed +
            if (isPlaying && !isBuffering && segmentStartTime > 0L) (now - segmentStartTime).coerceAtLeast(0L) else 0L

    fun effectiveProgressMillis(now: Long = System.currentTimeMillis()): Long {
        return when {
            playbackPositionMillis >= 0L -> playbackPositionMillis
            timelineStartTime > 0L && isPlaying && !isBuffering -> (now - timelineStartTime).coerceAtLeast(0L)
            else -> timePlayed
        }
    }

    fun paused() {
        isPlaying = false
        segmentStartTime = 0L
        lastKnownPlaybackPositionMillis = -1L
    }

    fun buffering() {
        addTimePlayed()
        isBuffering = true
        lastKnownPlaybackPositionMillis = -1L
    }

    fun resumed() {
        isPlaying = true
        isBuffering = false
        if (playStartTime <= 0L)
            playStartTime = System.currentTimeMillis()
        if (segmentStartTime <= 0L)
            segmentStartTime = System.currentTimeMillis()
    }

    fun toScrobbleData(useOriginals: Boolean) = ScrobbleData(
        track = if (useOriginals) origTitle else title,
        artist = if (useOriginals) origArtist else artist,
        album = (if (useOriginals) origAlbum else album).ifEmpty { null },
        albumArtist = (if (useOriginals) origAlbumArtist else albumArtist).ifEmpty { null },
        timestamp = scrobbleTimestamp ?: playStartTime,
        duration = durationMillis.takeIf { it > 0 },
        appId = appId,
        extraTags = extraTags,
    )

    fun toTrackPlayingEvent() = PlayingTrackNotifyEvent.TrackPlaying(
        notiKey = notiKey,
        scrobbleData = toScrobbleData(false),
        origScrobbleData = toScrobbleData(true),
        msid = msid,
        hash = hash,
        nowPlaying = scrobbledState < ScrobbledState.SCROBBLE_SUBMITTED,
        userLoved = userLoved,
        userPlayCount = userPlayCount,
        artUrlState = artUrlState,
        timelineStartTime = timelineStartTime,
        preprocessed = scrobbledState >= ScrobbledState.PREPROCESSED,
        hasMediaSession = hasMediaSession,
    )

    fun applySubmittedEdit(scrobbleData: ScrobbleData) {
        if (album != scrobbleData.album.orEmpty()) {
            if (originalArtworkBeforeEdit == null) originalArtworkBeforeEdit = artUrlState
            artUrlState = ArtUrlState.None
        }
        title = scrobbleData.track
        artist = scrobbleData.artist
        album = scrobbleData.album.orEmpty()
        albumArtist = scrobbleData.albumArtist.orEmpty()
        scrobbleTimestamp = scrobbleData.timestamp
        extraTags = scrobbleData.extraTags.toMap()
        msid = null
        scrobbled()
    }

    fun putPreprocessedData(
        sd: ScrobbleData,
        loved: Boolean,
        additionalMetadataFetched: Boolean
    ) {
        title = sd.track
        album = sd.album.orEmpty()
        artist = sd.artist
        albumArtist = sd.albumArtist.orEmpty()
        userLoved = loved

        scrobbledState = if (additionalMetadataFetched)
            ScrobbledState.ADDITIONAL_METADATA_FETCHED
        else
            ScrobbledState.PREPROCESSED
    }

    fun nowPlayingSubmitted(msid: String?) {
        scrobbledState = ScrobbledState.NOW_PLAYING_SUBMITTED
        this.msid = msid
    }

    fun scrobbled() {
        scrobbledState = ScrobbledState.SCROBBLE_SUBMITTED
    }

    fun cancelled() {
        scrobbledState = ScrobbledState.CANCELLED
    }

    override fun toString(): String {
        return "PlayingTrackInfo(appId='$appId', notiKey='$notiKey', title='$title', origTitle='$origTitle', album='$album', origAlbum='$origAlbum', artist='$artist', origArtist='$origArtist', albumArtist='$albumArtist', origAlbumArtist='$origAlbumArtist', trackNumber=$trackNumber, artUrl=$artUrl, normalizedUrlHost=$normalizedUrlHost, msid=$msid, durationMillis=$durationMillis, hash=${hash.toHexString()}, isPlaying=$isPlaying, userPlayCount=$userPlayCount, userLoved=$userLoved, timelineStartTime=$timelineStartTime, playStartTime=$playStartTime, scrobbledState=$scrobbledState, timePlayed=$timePlayed, lastScrobbleHash=${lastScrobbleHash.toHexString()})"
    }
}
