package dev.etorix.panoscrobbler.api.steelseries

import dev.etorix.panoscrobbler.api.AdditionalMetadataResult
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData

expect object SteelSeriesReceiverServer {
    suspend fun getAdditionalData(scrobbleData: ScrobbleData): AdditionalMetadataResult
}