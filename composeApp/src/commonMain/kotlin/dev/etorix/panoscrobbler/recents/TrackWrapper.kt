package dev.etorix.panoscrobbler.recents

import dev.etorix.panoscrobbler.api.lastfm.Track

sealed interface TrackWrapper {
    val key: String

    data class TrackItem(val track: Track, override val key: String) : TrackWrapper
    data class SeparatorItem(val millis: Long, override val key: String) : TrackWrapper
}
