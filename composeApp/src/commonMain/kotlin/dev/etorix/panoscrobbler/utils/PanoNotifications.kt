package dev.etorix.panoscrobbler.utils

import dev.etorix.panoscrobbler.api.lastfm.LastfmPeriod
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.media.PlayingTrackNotifyEvent
import dev.etorix.panoscrobbler.updates.UpdateAction
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun MutableStateFlow<Map<String, PlayingTrackNotifyEvent.PlayingTrackState>>.putPlayingTrack(
    event: PlayingTrackNotifyEvent.TrackPlaying,
) {
    update { states ->
        if (event.hasMediaSession || event.nowPlaying) states + (event.notiKey to event)
        else states - event.notiKey
    }
}

expect object PanoNotifications {
    val forcePersistentNoti: Boolean
    val playingTrackStates: StateFlow<Map<String, PlayingTrackNotifyEvent.PlayingTrackState>>
    fun updatePlayingTrackState(event: PlayingTrackNotifyEvent.TrackPlaying)
    fun clearPlayingTrackStates()
    suspend fun notifyScrobble(event: PlayingTrackNotifyEvent.TrackPlaying)

    suspend fun notifyError(event: PlayingTrackNotifyEvent.Error)

    suspend fun notifyAppDetected(appId: String, appLabel: String)

    suspend fun notifyUnscrobbled(notiKey: String, scrobbleData: ScrobbleData, hash: Int)

    suspend fun notifyDigest(lastfmPeriod: LastfmPeriod, title: String, text: String)

    suspend fun notifyUpdater(updateAction: UpdateAction)

    fun repostFgNotiIfNeeded()

    fun isNotiChannelEnabled(channelId: String): Boolean

    fun removeNotificationByKey(key: String)
}
