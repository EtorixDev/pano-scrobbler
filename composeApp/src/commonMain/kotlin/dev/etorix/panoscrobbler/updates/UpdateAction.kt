package dev.etorix.panoscrobbler.updates

import dev.etorix.panoscrobbler.api.github.GithubReleases
import kotlinx.serialization.Serializable

@Serializable
data class UpdateAction(val urlOrFilePath: String, val version: String, val changelog: String)

expect fun runUpdateAction(
    updateAction: UpdateAction,
)

expect suspend fun doAfterUpdateCheck(
    releases: GithubReleases,
): UpdateAction?