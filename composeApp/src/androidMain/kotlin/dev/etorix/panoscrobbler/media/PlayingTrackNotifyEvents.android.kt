package dev.etorix.panoscrobbler.media

import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.core.net.toUri
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData
import dev.etorix.panoscrobbler.automation.Automation
import dev.etorix.panoscrobbler.utils.AndroidStuff
import dev.etorix.panoscrobbler.utils.Stuff
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch


actual fun notifyPlayingTrackEvent(event: PlayingTrackNotifyEvent) {
    val context = AndroidStuff.applicationContext

    if (!AndroidStuff.isMainProcess) {
        if (globalTrackEventFlow.subscriptionCount.value > 0) {
            globalTrackEventFlow.tryEmit(event)
        }

        if (event is PlayingTrackNotifyEvent.TrackPlaying) {
            context.sendBroadcast(MainProcessTrackPlayingReceiver.createIntent(context, event))
        }
    } else {
        if (globalTrackEventFlow.subscriptionCount.value > 0) {
            globalTrackEventFlow.tryEmit(event)
        }

        val intent = PlayingTrackEventReceiver.createIntent(context, event)
        context.sendBroadcast(intent)
    }
}

actual fun getNowPlayingFromMainProcess(): Pair<ScrobbleData, Int>? {
    if (!AndroidStuff.isMainProcess)
        return null

    val cr = AndroidStuff.applicationContext.contentResolver ?: return null
    val cancellationSignal = CancellationSignal()

    // wait for 500ms max to prevent ANR
    val cancelJob = Stuff.appScope.launch {
        delay(500)
        cancellationSignal.cancel()
    }

    val cursor = try {
        cr.query(
            "content://${Automation.PREFIX}/${Automation.ANDROID_NOW_PLAYING}".toUri(),
            null,
            null,
            null,
            null,
            cancellationSignal
        ).also {
            cancelJob.cancel()
        }
    } catch (e: OperationCanceledException) {
        null
    } ?: return null

    while (cursor.moveToNext()) {
        val sdColIdx =
            cursor.getColumnIndex(PlayingTrackNotifyEvent.TrackPlaying::origScrobbleData.name)
        val hashColIdx = cursor.getColumnIndex(PlayingTrackNotifyEvent.TrackPlaying::hash.name)

        if (sdColIdx != -1 && hashColIdx != -1) {
            val sd = cursor.getString(sdColIdx)
            val hash = cursor.getString(hashColIdx)
            if (sd != null && hash != null) {
                cursor.close()
                return Stuff.myJson.decodeFromString<ScrobbleData>(sd) to hash.toInt()
            }
        }
    }

    cursor.close()
    return null
}

actual fun shouldFetchNpArtUrl(): Flow<Boolean> {
    return emptyFlow()
}