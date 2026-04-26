package com.arn.scrobble.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arn.scrobble.utils.Stuff

class PlayingTrackEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val eventStr = intent.getStringExtra(EXTRA_EVENT) ?: return

        val event = Stuff.myJson.decodeFromString<PlayingTrackNotifyEvent>(eventStr)
        notifyPlayingTrackEvent(event)
    }

    companion object {
        private const val EXTRA_EVENT = "event"

        fun createIntent(context: Context, event: PlayingTrackNotifyEvent): Intent =
            Intent(context, PlayingTrackEventReceiver::class.java)
                .putExtra(
                    EXTRA_EVENT,
                    Stuff.myJson.encodeToString(event)
                )
    }
}