package dev.etorix.panoscrobbler.imageloader

import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.lastfm.MusicEntry

data class MusicEntryImageReq(
    val musicEntry: MusicEntry,
    val accountType: AccountType?,
    val isHeroImage: Boolean = false,
    val fetchAlbumInfoIfMissing: Boolean = false,
)