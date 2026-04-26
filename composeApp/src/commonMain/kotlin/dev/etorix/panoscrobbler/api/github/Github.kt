package dev.etorix.panoscrobbler.api.github

import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.api.Requesters.getResult

object Github {

    suspend fun getLatestRelease(
        apiUrl: String,
    ) = Requesters.genericKtorClient.getResult<GithubReleases>(apiUrl)
}
