package dev.etorix.panoscrobbler.utils

import co.touchlab.kermit.Logger
import dev.etorix.panoscrobbler.PanoNativeComponents
import dev.etorix.panoscrobbler.api.lastfm.LastfmPeriod
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.media.PlayingTrackNotifyEvent
import dev.etorix.panoscrobbler.updates.UpdateAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.getString
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.new_player
import pano_scrobbler.composeapp.generated.resources.new_player_prompt_desktop
import pano_scrobbler.composeapp.generated.resources.update_downloaded

actual object PanoNotifications {
    actual val forcePersistentNoti = false

    private val _playingTrackStates =
        MutableStateFlow<Map<String, PlayingTrackNotifyEvent.PlayingTrackState>>(emptyMap())
    actual val playingTrackStates = _playingTrackStates.asStateFlow()
    actual fun updatePlayingTrackState(event: PlayingTrackNotifyEvent.TrackPlaying) {
        _playingTrackStates.putPlayingTrack(event)
    }

    actual fun clearPlayingTrackStates() {
        _playingTrackStates.value = emptyMap()
    }

    actual suspend fun notifyScrobble(event: PlayingTrackNotifyEvent.TrackPlaying) {
        updatePlayingTrackState(event)
    }

    actual suspend fun notifyError(event: PlayingTrackNotifyEvent.Error) {
        _playingTrackStates.update { it + (event.notiKey to event) }
    }

    actual suspend fun notifyAppDetected(appId: String, appLabel: String) {
        PanoNativeComponents.notify(
            getString(Res.string.new_player, appLabel.ifEmpty { appId }),
            getString(Res.string.new_player_prompt_desktop)
        )
    }

    actual suspend fun notifyUnscrobbled(notiKey: String, scrobbleData: ScrobbleData, hash: Int) {
        removeNotificationByKey(notiKey)
    }

    actual suspend fun notifyDigest(lastfmPeriod: LastfmPeriod, title: String, text: String) {
        if (text.isEmpty()) {
            return
        }
        PanoNativeComponents.notify(
            title,
            text
        )

        Logger.i { "notifyDigest: $lastfmPeriod, $text" }
    }

    actual suspend fun notifyUpdater(
        updateAction: UpdateAction
    ) {
        PanoNativeComponents.notify(
            getString(Res.string.update_downloaded),
            updateAction.version
        )
    }

    actual fun repostFgNotiIfNeeded() {}

    actual fun isNotiChannelEnabled(channelId: String): Boolean {
        // todo implement
        return false
    }


    actual fun removeNotificationByKey(key: String) {
        _playingTrackStates.update { it - key }
    }
}
