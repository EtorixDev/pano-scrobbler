package dev.etorix.panoscrobbler.api.steelseries

import dev.etorix.panoscrobbler.api.AdditionalMetadataResult
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData

actual object SteelSeriesReceiverServer {
    actual suspend fun getAdditionalData(scrobbleData: ScrobbleData): AdditionalMetadataResult =
        AdditionalMetadataResult.Empty
}