package dev.etorix.panoscrobbler.utils

import dev.etorix.panoscrobbler.api.lastfm.LastfmPeriod
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.media.PlayingTrackNotifyEvent
import dev.etorix.panoscrobbler.updates.UpdateAction

expect object PanoNotifications {
    val forcePersistentNoti: Boolean
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