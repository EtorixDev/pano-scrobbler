package dev.etorix.panoscrobbler.db

import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData

data class RegexResults(
    val matches: Set<RegexEdit>,
    val scrobbleData: ScrobbleData?,
    val blockPlayerAction: BlockPlayerAction?,
)
