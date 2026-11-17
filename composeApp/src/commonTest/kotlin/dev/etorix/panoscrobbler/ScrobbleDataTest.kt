package dev.etorix.panoscrobbler

import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.ImagesUrls
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.media.PlayingTrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScrobbleDataTest {

    @Test
    fun unknownPositionResumePreservesTheInferredTimestamp() {
        val trackInfo = PlayingTrackInfo("local-player", "local-session", null)
        trackInfo.putOriginals("Artist", "Track", "Album", "Artist", 1, 180_000, null, null, emptyMap())
        trackInfo.prepareForScrobbling(timestampOverride = 123_000)
        trackInfo.paused()
        trackInfo.setTimelineStartTime(-1)
        trackInfo.resumed()
        trackInfo.prepareForScrobbling()
        assertEquals(123_000L, trackInfo.toTrackPlayingEvent().scrobbleData.timestamp)
        assertEquals(123_000L, trackInfo.toTrackPlayingEvent().origScrobbleData.timestamp)
    }

    @Test
    fun latePlayerArtworkDoesNotReplaceAnEditedAlbumCover() {
        val trackInfo = PlayingTrackInfo("local-player", "local-session", null)
        trackInfo.putOriginals(
            "Artist", "Track", "Original Album", "Artist", 1, 180_000, null,
            "https://example.invalid/original.png", emptyMap(),
        )
        trackInfo.applySubmittedEdit(trackInfo.toScrobbleData(false).copy(album = "Edited Album"))
        trackInfo.setArtUrl("https://example.invalid/edited.png")
        trackInfo.updateAdditionalMetadata(emptyMap(), "https://example.invalid/original-late.png")
        assertEquals("Edited Album", trackInfo.toScrobbleData(false).album)
        assertEquals("https://example.invalid/edited.png", trackInfo.artUrl)

        trackInfo.resetMetadata()
        assertEquals("Original Album", trackInfo.toScrobbleData(false).album)
        assertEquals("https://example.invalid/original-late.png", trackInfo.artUrl)
    }

    @Test
    fun repeatPreservesTagsWithoutChangingQueuedMetadata() {
        val tags = mutableMapOf("org.musicbrainz.MUSICBRAINZ_TRACKID" to "recording-one")
        val trackInfo = PlayingTrackInfo("local-player", "local-session", null)

        fun loadTrack(title: String, extraTags: Map<String, String>) {
            trackInfo.putOriginals(
                artist = "Artist",
                title = title,
                album = "Album",
                albumArtist = "Album Artist",
                trackNumber = 1,
                durationMillis = 180_000L,
                normalizedUrlHost = null,
                artUrl = "https://example.invalid/cover.png",
                extraTags = extraTags,
            )
        }

        loadTrack("First Track", tags)
        val queued = trackInfo.toScrobbleData(false)
        tags.clear()
        trackInfo.resetMetadata()
        assertEquals(queued.extraTags, trackInfo.toScrobbleData(false).extraTags)
        assertEquals("recording-one", queued.extraTags["org.musicbrainz.MUSICBRAINZ_TRACKID"])
        assertEquals("https://example.invalid/cover.png", trackInfo.artUrl)

        loadTrack("Second Track", emptyMap())
        assertEquals(emptyMap(), trackInfo.toScrobbleData(false).extraTags)
        assertEquals("recording-one", queued.extraTags["org.musicbrainz.MUSICBRAINZ_TRACKID"])
    }

    @Test
    fun toTrackPreservesAlbumImageFromPreviousTrack() {
        val previousTrack = Track(
            name = "Old Title",
            artist = Artist("Artist"),
            album = Album(
                name = "Album",
                artist = Artist("Artist"),
                image = ImagesUrls(
                    medium = "https://lastfm.freetls.fastly.net/i/u/300x300/cover.webp",
                    large = "https://lastfm.freetls.fastly.net/i/u/600x600/cover.webp",
                )
            ),
            date = 123L,
            duration = 456L,
        )

        val editedTrack = ScrobbleData(
            artist = "Artist",
            track = "Clean Title",
            album = "Album",
            timestamp = 123L,
            albumArtist = null,
            duration = 456L,
            appId = null,
        ).toTrack(previousTrack)

        assertEquals("Clean Title", editedTrack.name)
        assertNotNull(editedTrack.album)
        assertEquals(previousTrack.album?.image, editedTrack.album.image)
    }
}
