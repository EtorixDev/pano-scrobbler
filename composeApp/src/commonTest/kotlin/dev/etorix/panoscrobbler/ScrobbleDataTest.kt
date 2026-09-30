package dev.etorix.panoscrobbler

import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.ImagesUrls
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.api.lastfm.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScrobbleDataTest {

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
