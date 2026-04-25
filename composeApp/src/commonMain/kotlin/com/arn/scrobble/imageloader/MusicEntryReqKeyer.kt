package com.arn.scrobble.imageloader

import coil3.key.Keyer
import coil3.request.Options
import com.arn.scrobble.api.lastfm.Album
import com.arn.scrobble.api.lastfm.Artist
import com.arn.scrobble.api.lastfm.Track

class MusicEntryReqKeyer : Keyer<MusicEntryImageReq> {
    override fun key(data: MusicEntryImageReq, options: Options) = genKey(data)

    companion object {
        fun genKey(data: MusicEntryImageReq): String {
            val prefix =
                "MusicEntryReqKeyer|accountType=${data.accountType}"
            return when (data.musicEntry) {
                is Artist -> prefix + "|artist=" + data.musicEntry.name
                is Album -> prefix + "|artist=" + data.musicEntry.artist!!.name + "|album=" + data.musicEntry.name
                is Track -> {
                    val album = data.musicEntry.album
                    if (album != null) {
                        prefix + "|artist=" + (album.artist?.name ?: data.musicEntry.artist.name) +
                                "|album=" + album.name
                    } else {
                        prefix + "|artist=" + data.musicEntry.artist.name + "|track=" + data.musicEntry.name
                    }
                }
            }
        }
    }
}