package dev.etorix.panoscrobbler.api.lastfm

import androidx.annotation.Keep
import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import dev.etorix.panoscrobbler.db.StringMapTypeConverter
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class ScrobbleData(
    val artist: String,
    val track: String,
    val album: String?,
    val timestamp: Long,
    val trackNumber: Int? = null,
    val albumArtist: String?,
    val duration: Long?,
    val appId: String?,

    @ColumnInfo(defaultValue = "")
    @field:ColumnTypeConverters(StringMapTypeConverter::class)
    val extraTags: Map<String, String> = emptyMap(),
) {
    fun safeDuration() = duration?.takeIf { it in (30_000..3600_000) }

    fun toTrack(previousTrack: Track? = null): Track {
        val trackArtist = Artist(artist)
        val albumArtist = Artist(albumArtist.orEmpty().ifEmpty { artist })
        val trackAlbum = album?.ifEmpty { null }
            ?.let { albumName ->
                previousTrack?.album
                    ?.takeIf { it.name == albumName }
                    ?.copy(name = albumName, artist = albumArtist)
                    ?: Album(albumName, albumArtist)
            }

        return previousTrack?.copy(
            name = track,
            artist = trackArtist,
            album = trackAlbum,
            date = timestamp,
            duration = duration,
            appId = appId,
        ) ?: Track(
            name = track,
            artist = trackArtist,
            date = timestamp,
            album = trackAlbum,
            duration = duration,
            appId = appId,
        )
    }

    fun trimmed() = copy(
        artist = artist.trim(),
        track = track.trim(),
        album = album?.trim()?.ifEmpty { null },
        albumArtist = albumArtist?.trim()?.ifEmpty { null },
    )
}