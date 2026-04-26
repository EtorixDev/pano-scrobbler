package dev.etorix.panoscrobbler.media

expect class PlatformMediaMetadata

expect fun transformMediaMetadata(
    trackInfo: PlayingTrackInfo,
    metadata: PlatformMediaMetadata,
): Pair<MetadataInfo, Boolean>