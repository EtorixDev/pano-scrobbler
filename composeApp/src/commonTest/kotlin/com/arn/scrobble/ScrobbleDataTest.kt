package com.arn.scrobble

import com.arn.scrobble.api.lastfm.Album
import com.arn.scrobble.api.lastfm.Artist
import com.arn.scrobble.api.lastfm.ImageSize
import com.arn.scrobble.api.lastfm.LastFmImage
import com.arn.scrobble.api.lastfm.ScrobbleData
import com.arn.scrobble.api.lastfm.Track
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
                image = listOf(
                    LastFmImage(
                        size = ImageSize.extralarge.name,
                        url = "https://lastfm.freetls.fastly.net/i/u/300x300/cover.jpg"
                    )
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