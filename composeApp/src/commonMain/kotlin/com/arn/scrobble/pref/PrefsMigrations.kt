package com.arn.scrobble.pref

import androidx.datastore.core.DataMigration
import com.arn.scrobble.BuildKonfig


class MainPrefsMigration6 : DataMigration<MainPrefs> {
    private val version = 6

    override suspend fun shouldMigrate(currentData: MainPrefs) =
        currentData.version < version

    override suspend fun migrate(currentData: MainPrefs): MainPrefs {
        val migrated = if (currentData.changelogSeenHashcode == null) // first install
            currentData.copy(
                version = version,
            )
        // keep spotifyApi = true for existing installations, but show prompt
        else
            currentData.copy(
                spotifyApi = BuildKonfig.SPOTIFY_API_AVAILABLE,
                version = version,
            )

        return migrated.constrainedForBuild()
    }

    override suspend fun cleanUp() {

    }
}