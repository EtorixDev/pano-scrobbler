package dev.etorix.panoscrobbler.discordrpc

import dev.etorix.panoscrobbler.PanoNativeComponents

actual object DiscordRpcPlatform {
    actual suspend fun update(activity: DiscordActivity): Boolean =
        when (activity) {
            is DiscordActivity.Activity -> {
                val now = System.currentTimeMillis()
                val startTimeMillis = if (activity.isPlaying || activity.durationMillis == null)
                    activity.startTimeMillis
                else
                    now - activity.durationMillis
                val startTimeSecs = startTimeMillis / 1000
                val endTimeSecs =
                    if (activity.durationMillis != null && activity.durationMillis > 0) {
                        if (activity.isPlaying)
                            startTimeSecs + activity.durationMillis / 1000
                        else
                            now / 1000
                    } else {
                        0
                    }

                PanoNativeComponents.updateDiscordActivity(
                    clientId = activity.discordClientId,
                    name = activity.name,
                    state = activity.state,
                    details = activity.details,
                    largeText = activity.largeText,
                    startTime = startTimeSecs,
                    endTime = endTimeSecs,
                    artUrl = activity.artUrl,
                    detailsUrl = activity.detailsUrl,
                    isPlaying = activity.isPlaying,
                    statusLine = activity.statusLine,
                    buttonText = activity.buttonText,
                    buttonUrl = activity.buttonUrl,
                )
            }
            DiscordActivity.Clear -> PanoNativeComponents.clearDiscordActivity(false)
            DiscordActivity.Stop -> PanoNativeComponents.clearDiscordActivity(true)
        }
}
