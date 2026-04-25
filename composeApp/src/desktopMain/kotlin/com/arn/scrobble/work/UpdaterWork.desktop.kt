package com.arn.scrobble.work

import com.arn.scrobble.utils.DesktopStuff
import com.arn.scrobble.utils.VariantStuff
import kotlin.time.Duration.Companion.hours

actual object UpdaterWork : CommonWorkImpl(UpdaterWorker.NAME) {

    actual fun schedule(force: Boolean) {
        if (DesktopStuff.noUpdateCheck || VariantStuff.githubApiUrl == null) return

        if (force)
            DesktopWorkManager.cancelWork(uniqueName)

        DesktopWorkManager.scheduleWork(
            uniqueName,
            if (force)
                0
            else
                12.hours.inWholeMilliseconds,
            { UpdaterWorker(it) },
        )
    }
}