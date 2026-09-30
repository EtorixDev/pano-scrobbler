package dev.etorix.panoscrobbler

import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.ImagesUrls
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.api.lastfm.User
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastFmImageSerializerTest {
    @Test
    fun legacyArrayUsesLargestAvailableImage() {
        val album = Json.decodeFromString<Album>(
            """
            {
              "name": "Album",
              "image": [
                {"size": "large", "#text": "https://lastfm-img.freetls.fastly.net/i/u/174s/fallback.png"},
                {"size": "extralarge", "#text": ""},
                {"size": "extralarge", "#text": "https://lastfm-img.freetls.fastly.net/i/u/300x300/cover.jpeg"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            ImagesUrls(
                "https://lastfm-img.freetls.fastly.net/i/u/300x300/cover.webp",
                "https://lastfm-img.freetls.fastly.net/i/u/600x600/cover.webp",
            ),
            album.image,
        )
    }

    @Test
    fun emptyOrMissingImagesDecodeWithoutAnImage() {
        for (imageValue in listOf("null", "\"\"", "[]")) {
            val album = Json.decodeFromString<Album>("{\"name\":\"Album\",\"image\":$imageValue}")
            assertNull(album.image, "image=$imageValue")
        }
        assertNull(Json.decodeFromString<Album>("{\"name\":\"Album\"}").image)
    }

    @Test
    fun legacyCustomHostKeepsItsImageUrl() {
        val album = Json.decodeFromString<Album>(
            """{"name":"Album","image":[{"size":"extralarge","#text":"https://example.test/300x300/cover.jpg"}]}"""
        )

        assertEquals(
            ImagesUrls("https://example.test/300x300/cover.jpg", "https://example.test/300x300/cover.jpg"),
            album.image,
        )
    }

    @Test
    fun normalizedImagesRoundTripWithoutRewritingCustomSizes() {
        val original = Album(
            name = "Album",
            image = ImagesUrls(
                "https://coverartarchive.org/release/example/front-250",
                "https://coverartarchive.org/release/example/front-500",
            ),
        )

        val restored = Json.decodeFromString<Album>(Json.encodeToString(original))

        assertEquals(original.image, restored.image)
    }

    @Test
    fun recentTrackImageIsCopiedToItsAlbum() {
        val track = Json.decodeFromString<Track>(
            """
            {
              "name": "Track",
              "artist": {"name": "Artist"},
              "album": {"name": "Album"},
              "image": [{"size":"large","#text":"https://lastfm.freetls.fastly.net/i/u/300x300/cover.png"}]
            }
            """.trimIndent()
        ).copyImageToAlbum()

        assertEquals(
            ImagesUrls(
                "https://lastfm.freetls.fastly.net/i/u/300x300/cover.webp",
                "https://lastfm.freetls.fastly.net/i/u/600x600/cover.webp",
            ),
            track.album?.image,
        )
    }

    @Test
    fun userImageAcceptsAnEmptyStringFromTheApi() {
        val user = Json.decodeFromString<User>(
            """{"name":"user","url":"https://example.test/user","image":""}"""
        )

        assertNull(user.image)
    }
}
