package dev.etorix.panoscrobbler.work

import dev.etorix.panoscrobbler.utils.DesktopStuff
import dev.etorix.panoscrobbler.utils.VariantStuff
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