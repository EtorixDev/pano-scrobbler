package com.arn.scrobble.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MainProcessTrackPlayingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val event = PlayingTrackEventIntentCodec.decode(intent) as? PlayingTrackNotifyEvent.TrackPlaying
            ?: return

        if (globalTrackEventFlow.subscriptionCount.value > 0) {
            globalTrackEventFlow.tryEmit(event)
        }
    }

    companion object {
        fun createIntent(
            context: Context,
            event: PlayingTrackNotifyEvent.TrackPlaying,
        ): Intent =
            PlayingTrackEventIntentCodec.createIntent(
                context,
                MainProcessTrackPlayingReceiver::class.java,
                event
            )
    }
}