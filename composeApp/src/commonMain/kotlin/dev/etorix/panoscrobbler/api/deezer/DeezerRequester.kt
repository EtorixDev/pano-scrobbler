package dev.etorix.panoscrobbler.api.deezer

import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.api.Requesters.getResult
import io.ktor.client.request.parameter

class DeezerRequester {
    private val client get() = Requesters.genericKtorClient

    suspend fun searchTrack(
        artist: String,
        track: String,
        limit: Int
    ) =
        client.getResult<DeezerSearchResponse>("https://api.deezer.com/search") {
            parameter("q", "artist:\"$artist\" track:\"$track\"")
            parameter("order", "RANKING")
            parameter("limit", limit)
        }

    suspend fun lookupTrack(
        trackId: Long,
    ) =
        client.getResult<DeezerTrack>("https://api.deezer.com/track/$trackId")
}