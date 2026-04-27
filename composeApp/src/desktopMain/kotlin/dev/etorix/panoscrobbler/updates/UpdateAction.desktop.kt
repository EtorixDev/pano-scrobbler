package dev.etorix.panoscrobbler.updates

import co.touchlab.kermit.Logger
import dev.etorix.panoscrobbler.api.github.GithubReleases
import dev.etorix.panoscrobbler.utils.DesktopStuff

actual suspend fun doAfterUpdateCheck(releases: GithubReleases): UpdateAction? {
    val updateFile = AutoUpdater.update(releases) ?: return null

    return UpdateAction(
        urlOrFilePath = updateFile.absolutePath,
        version = releases.versionName,
        changelog = releases.body,
    )
}

actual fun runUpdateAction(updateAction: UpdateAction) {
    try {
        if (DesktopStuff.os == DesktopStuff.Os.Windows) {
            // there doesn't seem to be a way to pass arguments to the installer when launching with explorer.exe
            ProcessBuilder("explorer.exe", updateAction.urlOrFilePath)
                .start()
        } else if (DesktopStuff.os == DesktopStuff.Os.Linux) {
            val appDir = System.getenv("APPDIR")
            val relauncher = "$appDir/usr/bin/relaunch.sh"
            ProcessBuilder(relauncher, updateAction.urlOrFilePath)
                .inheritIO()
                .start()
        }
    } catch (e: Exception) {
        Logger.e(e) { "Failed to relaunch after update" }
    }

    DesktopStuff.exit()
}