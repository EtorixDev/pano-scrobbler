package dev.etorix.panoscrobbler.recents

data class ScrobblesLoaderPage(
    val page: Int,
    val lastScrobbleTimestamp: Long?,
)
