package dev.etorix.panoscrobbler.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import dev.etorix.panoscrobbler.utils.Stuff

class PlayingTrackEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val event = PlayingTrackEventIntentCodec.decode(intent) ?: return

        notifyPlayingTrackEvent(event)
    }

    companion object {
        fun createIntent(context: Context, event: PlayingTrackNotifyEvent): Intent =
            PlayingTrackEventIntentCodec.createIntent(
                context,
                PlayingTrackEventReceiver::class.java,
                event
            )
    }
}

internal object PlayingTrackEventIntentCodec {
    private const val EXTRA_EVENT = "event"
    private const val EXTRA_EVENT_TYPE = "event_type"

    fun decode(intent: Intent): PlayingTrackNotifyEvent? {
        val eventStr = intent.getStringExtra(EXTRA_EVENT)
        val eventType = intent.getStringExtra(EXTRA_EVENT_TYPE)

        if (eventStr == null || eventType == null) {
            return null
        }

        return when (eventType) {
            PlayingTrackNotifyEvent.TrackPlaying::class.simpleName -> {
                Stuff.myJson.decodeFromString<PlayingTrackNotifyEvent.TrackPlaying>(eventStr)
            }

            PlayingTrackNotifyEvent.TrackCancelled::class.simpleName -> {
                Stuff.myJson.decodeFromString<PlayingTrackNotifyEvent.TrackCancelled>(eventStr)
            }

            PlayingTrackNotifyEvent.TrackLovedUnloved::class.simpleName -> {
                Stuff.myJson.decodeFromString<PlayingTrackNotifyEvent.TrackLovedUnloved>(eventStr)
            }

            PlayingTrackNotifyEvent.AppAllowedBlocked::class.simpleName -> {
                Stuff.myJson.decodeFromString<PlayingTrackNotifyEvent.AppAllowedBlocked>(eventStr)
            }

            PlayingTrackNotifyEvent.TrackScrobbleLocked::class.simpleName -> {
                Stuff.myJson.decodeFromString<PlayingTrackNotifyEvent.TrackScrobbleLocked>(eventStr)
            }

            else -> {
                Logger.e {
                    "Unknown PlayingTrackNotifyEvent type: $eventType, eventStr: $eventStr"
                }
                null
            }
        }
    }

    fun createIntent(
        context: Context,
        receiverClass: Class<out BroadcastReceiver>,
        event: PlayingTrackNotifyEvent,
    ): Intent =
        Intent(context, receiverClass)
            .putExtra(
                EXTRA_EVENT,
                Stuff.myJson.encodeToString(event)
            )
            .putExtra(
                EXTRA_EVENT_TYPE,
                event::class.simpleName
            )
}