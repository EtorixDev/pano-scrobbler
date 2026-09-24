package dev.etorix.panoscrobbler.fork

import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.ImageSize
import dev.etorix.panoscrobbler.api.lastfm.LastFmImage
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.api.lastfm.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ForkEditDataTest {
    private val previousTrack = Track(
        name = "Original Title",
        artist = Artist("Original Artist"),
        album = Album(
            name = "Original Album",
            artist = Artist("Original Album Artist"),
            url = "https://example.test/albums/original",
            image = listOf(LastFmImage(ImageSize.extralarge.name, "https://example.test/cover.jpg")),
            listeners = 42,
        ),
        url = "https://example.test/tracks/original",
        date = 100,
        duration = 40_000,
        userloved = true,
    )

    private fun editedData(album: String?) = ScrobbleData(
        artist = "Edited Artist",
        track = "Edited Title",
        album = album,
        timestamp = 200,
        albumArtist = "Edited Album Artist",
        duration = 50_000,
        appId = "test.player",
    )

    @Test
    fun sameAlbumEditRetainsEnrichedMetadata() {
        val edited = editedData("Original Album").toTrack(previousTrack)
        val album = assertNotNull(edited.album)

        assertEquals("Edited Title", edited.name)
        assertEquals("Edited Artist", edited.artist.name)
        assertEquals("Edited Album Artist", album.artist?.name)
        assertEquals(previousTrack.album?.url, album.url)
        assertEquals(previousTrack.album?.listeners, album.listeners)
        assertEquals(previousTrack.url, edited.url)
        assertEquals(previousTrack.userloved, edited.userloved)
        assertEquals(200L, edited.date)
        assertEquals(50_000L, edited.duration)
        assertEquals("test.player", edited.appId)
    }

    @Test
    fun changedAlbumDoesNotInheritOldAlbumArtworkOrMetadata() {
        val album = assertNotNull(editedData("Replacement Album").toTrack(previousTrack).album)

        assertEquals("Replacement Album", album.name)
        assertEquals("Edited Album Artist", album.artist?.name)
        assertNull(album.image)
        assertNull(album.url)
        assertNull(album.listeners)
    }

    @Test
    fun removedAlbumDoesNotInheritOldAlbumArtwork() {
        for (albumName in listOf<String?>(null, "")) {
            assertNull(editedData(albumName).toTrack(previousTrack).album, "albumName=$albumName")
        }
    }
}
