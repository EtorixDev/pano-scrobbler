package dev.etorix.panoscrobbler.updates

import dev.etorix.panoscrobbler.api.github.GithubReleases
import dev.etorix.panoscrobbler.utils.PlatformStuff


actual suspend fun doAfterUpdateCheck(releases: GithubReleases): UpdateAction? {
    val downloadUrl = releases.getDownloadUrl("android")
    if (downloadUrl.isEmpty()) {
        return null
    }

    return UpdateAction(
        urlOrFilePath = releases.html_url,
        version = releases.versionName,
        changelog = releases.body,
    )
}

actual fun runUpdateAction(updateAction: UpdateAction) {
    PlatformStuff.openInBrowser(updateAction.urlOrFilePath)
}
