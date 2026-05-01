package dev.etorix.panoscrobbler.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.etorix.panoscrobbler.api.UserAccountSerializable
import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.utils.Stuff

@Entity(
    tableName = PendingListenBrainzMutationsDao.tableName,
    indices = [
        Index(value = ["apiRoot", "username"]),
        Index(value = ["expiresAtMillis"]),
        Index(
            value = ["apiRoot", "username", "listenedAtMillis", "recordingMsid"],
            unique = true
        ),
    ]
)
data class PendingListenBrainzMutation(
    @PrimaryKey(autoGenerate = true)
    val _id: Long = 0,
    val apiRoot: String,
    val username: String,
    val listenedAtMillis: Long,
    val recordingMsid: String,
    val kind: PendingListenBrainzMutationKind,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long = createdAtMillis + MAX_RETENTION_MS,
    val replacementArtist: String? = null,
    val replacementTrack: String? = null,
    val replacementAlbum: String? = null,
    val replacementAlbumArtist: String? = null,
    val replacementDuration: Long? = null,
    val originalArtist: String? = null,
    val originalTrack: String? = null,
    val originalAlbum: String? = null,
) {
    val identityKey get() = identityKey(listenedAtMillis, recordingMsid)

    fun toReplacementTrack(previousTrack: Track): Track {
        val artistName = replacementArtist ?: previousTrack.artist.name
        val trackName = replacementTrack ?: previousTrack.name
        val albumName = replacementAlbum ?: previousTrack.album?.name
        val albumArtistName = replacementAlbumArtist ?: previousTrack.album?.artist?.name ?: artistName
        val artist = Artist(artistName)
        val albumArtist = Artist(albumArtistName)
        val album = albumName?.let {
            previousTrack.album
                ?.takeIf { previousAlbum -> previousAlbum.name == it }
                ?.copy(artist = albumArtist)
                ?: Album(it, albumArtist)
        }

        return previousTrack.copy(
            name = trackName,
            artist = artist,
            album = album,
            duration = replacementDuration ?: previousTrack.duration,
            date = listenedAtMillis,
        )
    }

    fun matchesUnresolvedOriginal(track: Track, maxDistanceMs: Long = 2_000L): Boolean {
        if (kind != PendingListenBrainzMutationKind.UNRESOLVED_EDIT)
            return false
        val trackDate = track.date ?: return false
        if (kotlin.math.abs(trackDate - listenedAtMillis) > maxDistanceMs)
            return false

        fun String?.metadataEquals(other: String?): Boolean {
            return orEmpty().trim().equals(other.orEmpty().trim(), ignoreCase = true)
        }

        val albumName = track.album?.name
        val albumMatches = originalAlbum.isNullOrBlank() ||
                albumName.isNullOrBlank() ||
                originalAlbum.metadataEquals(albumName)

        return originalTrack.metadataEquals(track.name) &&
                originalArtist.metadataEquals(track.artist.name) &&
                albumMatches
    }

    companion object {
        const val MAX_PER_ACCOUNT = 200
        const val MAX_RETENTION_MS = 24 * 60 * 60 * 1000L

        fun accountApiRoot(userAccount: UserAccountSerializable) =
            userAccount.apiRoot ?: Stuff.LISTENBRAINZ_API_ROOT

        fun identityKey(listenedAtMillis: Long, recordingMsid: String) =
            "$listenedAtMillis\n$recordingMsid"

        fun identityKey(track: Track): String? {
            val listenedAtMillis = track.date ?: return null
            val recordingMsid = track.msid ?: return null
            return identityKey(listenedAtMillis, recordingMsid)
        }

        fun delete(
            userAccount: UserAccountSerializable,
            track: Track,
        ): PendingListenBrainzMutation? {
            val listenedAtMillis = track.date ?: return null
            val recordingMsid = track.msid ?: return null

            return PendingListenBrainzMutation(
                apiRoot = accountApiRoot(userAccount),
                username = userAccount.user.name,
                listenedAtMillis = listenedAtMillis,
                recordingMsid = recordingMsid,
                kind = PendingListenBrainzMutationKind.DELETE,
            )
        }

        fun edit(
            userAccount: UserAccountSerializable,
            originalTrack: Track,
            replacementScrobbleData: ScrobbleData,
        ): PendingListenBrainzMutation? {
            val deleteMutation = delete(userAccount, originalTrack) ?: return null

            return deleteMutation.copy(
                kind = PendingListenBrainzMutationKind.EDIT,
                replacementArtist = replacementScrobbleData.artist,
                replacementTrack = replacementScrobbleData.track,
                replacementAlbum = replacementScrobbleData.album,
                replacementAlbumArtist = replacementScrobbleData.albumArtist,
                replacementDuration = replacementScrobbleData.duration,
                originalArtist = originalTrack.artist.name,
                originalTrack = originalTrack.name,
                originalAlbum = originalTrack.album?.name,
            )
        }

        fun unresolvedEdit(
            userAccount: UserAccountSerializable,
            originalScrobbleData: ScrobbleData,
            replacementScrobbleData: ScrobbleData,
        ): PendingListenBrainzMutation? {
            val listenedAtMillis = originalScrobbleData.timestamp.takeIf { it > 0 } ?: return null

            return PendingListenBrainzMutation(
                apiRoot = accountApiRoot(userAccount),
                username = userAccount.user.name,
                listenedAtMillis = listenedAtMillis,
                recordingMsid = "",
                kind = PendingListenBrainzMutationKind.UNRESOLVED_EDIT,
                replacementArtist = replacementScrobbleData.artist,
                replacementTrack = replacementScrobbleData.track,
                replacementAlbum = replacementScrobbleData.album,
                replacementAlbumArtist = replacementScrobbleData.albumArtist,
                replacementDuration = replacementScrobbleData.duration,
                originalArtist = originalScrobbleData.artist,
                originalTrack = originalScrobbleData.track,
                originalAlbum = originalScrobbleData.album,
            )
        }
    }
}

enum class PendingListenBrainzMutationKind {
    DELETE,
    EDIT,
    UNRESOLVED_EDIT,
}
