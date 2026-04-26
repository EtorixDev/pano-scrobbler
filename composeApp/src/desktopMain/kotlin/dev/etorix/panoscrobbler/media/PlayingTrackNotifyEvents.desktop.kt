package dev.etorix.panoscrobbler.media

import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.discordrpc.DiscordRpc
import dev.etorix.panoscrobbler.utils.PanoNotifications
import dev.etorix.panoscrobbler.utils.PlatformStuff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

actual fun notifyPlayingTrackEvent(event: PlayingTrackNotifyEvent) {
    if (globalTrackEventFlow.subscriptionCount.value > 0) {
        globalTrackEventFlow.tryEmit(event)
    }

    if (event is PlayingTrackNotifyEvent.TrackCancelled)
        DiscordRpc.clearDiscordActivity(event.hash)
}


actual fun getNowPlayingFromMainProcess(): Pair<ScrobbleData, Int>? {
    val playingEvent = PanoNotifications.playingTrackTrayInfo.value.values
        .filterIsInstance<PlayingTrackNotifyEvent.TrackPlaying>()
        .firstOrNull { it.nowPlaying }
    return playingEvent?.let {
        it.origScrobbleData to it.hash
    }
}

actual fun shouldFetchNpArtUrl(): Flow<Boolean> {
    return combine(
        DiscordRpc.wasSuccessFul,
        PlatformStuff.mainPrefs.data.map {
            it.discordRpc.enabled &&
                    it.discordRpc.albumArt &&
                    it.discordRpc.albumArtFromNowPlaying
        }) { wasSuccessful, settingsEnabled ->
        wasSuccessful == true && settingsEnabled
    }
}
