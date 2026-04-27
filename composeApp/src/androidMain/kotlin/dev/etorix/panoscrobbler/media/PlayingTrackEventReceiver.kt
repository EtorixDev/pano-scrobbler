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

    fun decode(intent: Intent): PlayingTrackNotifyEvent? {
        val eventStr = intent.getStringExtra(EXTRA_EVENT)

        if (eventStr == null) {
            return null
        }

        return runCatching {
            Stuff.myJson.decodeFromString<PlayingTrackNotifyEvent>(eventStr)
        }.getOrElse { error ->
            Logger.e(error) { "Failed to decode PlayingTrackNotifyEvent: $eventStr" }
            null
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
}