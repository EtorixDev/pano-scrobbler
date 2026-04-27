package dev.etorix.panoscrobbler.work

import dev.etorix.panoscrobbler.BuildKonfig
import dev.etorix.panoscrobbler.api.github.Github
import dev.etorix.panoscrobbler.api.github.GithubReleases
import dev.etorix.panoscrobbler.updates.doAfterUpdateCheck
import dev.etorix.panoscrobbler.utils.PanoNotifications
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.utils.VariantStuff
import dev.etorix.panoscrobbler.utils.redactedMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.done
import pano_scrobbler.composeapp.generated.resources.downloading
import pano_scrobbler.composeapp.generated.resources.loading
import pano_scrobbler.composeapp.generated.resources.no_updates
import pano_scrobbler.composeapp.generated.resources.update_available

class UpdaterWorker(
    override val setProgress: suspend (CommonWorkProgress) -> Unit
) : CommonWorker {
    override suspend fun doWork(): CommonWorkerResult {
        val url = VariantStuff.githubApiUrl
        val mainPrefs = PlatformStuff.mainPrefs
        if (url == null) {
            return CommonWorkerResult.Failure("Update check disabled")
        }

        if (Stuff.globalUpdateAction.value != null) {
            setProgress(
                CommonWorkProgress(
                    getString(
                        Res.string.update_available,
                        Stuff.globalUpdateAction.value!!.version
                    ),
                    0.1f
                )
            )

            delay(1000)

            return CommonWorkerResult.Failure("Update available, but not installed yet")
        }

        setProgress(
            CommonWorkProgress(
                getString(Res.string.loading),
                0.1f
            )
        )

        val latestRelease: Result<GithubReleases> = Github.getLatestRelease(url)

        latestRelease.onSuccess { release ->
            mainPrefs.updateData { it.copy(lastUpdateCheckTime = System.currentTimeMillis()) }

            if (release.versionCode > BuildKonfig.VER_CODE || MOCK) {
                val updatingMessage = if (PlatformStuff.isDesktop)
                    getString(Res.string.downloading)
                else
                    getString(Res.string.update_available, release.versionName)

                setProgress(CommonWorkProgress(updatingMessage, 0.2f))

                val updateAction = doAfterUpdateCheck(release)

                if (updateAction != null) {
                    PanoNotifications.notifyUpdater(updateAction)
                    Stuff.globalUpdateAction.value = updateAction
                }

                setProgress(CommonWorkProgress(getString(Res.string.done), 1f))
            } else {
                setProgress(
                    CommonWorkProgress(
                        getString(Res.string.no_updates),
                        1f
                    )
                )
            }
        }.onFailure { e ->
            setProgress(CommonWorkProgress(e.redactedMessage, 1f))
        }

        delay(1000)

        // do not schedule another update if already updated, but not restarted yet
        if (Stuff.globalUpdateAction.value == null && mainPrefs.data.first().autoUpdates)
            UpdaterWork.schedule(false)

        return if (latestRelease.isSuccess)
            CommonWorkerResult.Success
        else
            CommonWorkerResult.Failure(
                latestRelease.exceptionOrNull()?.redactedMessage ?: "Unknown error"
            )
    }

    companion object {
        const val NAME = "updater"
        private val MOCK = BuildKonfig.DEBUG && false
    }
}