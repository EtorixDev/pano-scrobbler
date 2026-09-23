package dev.etorix.panoscrobbler.media

expect class PlatformPlaybackInfo

expect fun transformPlaybackState(
    trackInfo: PlayingTrackInfo,
    playbackInfo: PlatformPlaybackInfo,
    scrobbleSpotifyRemote: Boolean,
): Pair<PlaybackInfo, Boolean>
