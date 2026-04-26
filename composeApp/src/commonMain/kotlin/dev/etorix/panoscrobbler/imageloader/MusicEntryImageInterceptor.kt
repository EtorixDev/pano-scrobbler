package dev.etorix.panoscrobbler.imageloader

import androidx.collection.LruCache
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ImageResult
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.api.lastfm.webp300
import dev.etorix.panoscrobbler.api.spotify.SpotifySearchType
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.utils.PlatformStuff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MusicEntryImageInterceptor : Interceptor {

    private val delayMs = 400L
    private val musicEntryCache by lazy { LruCache<String, FetchedImageUrls>(500) }
    private val semaphore = Semaphore(1)
    private val customSpotifyMappingsDao by lazy { PanoDb.db.getCustomSpotifyMappingsDao() }
    private val spotifyArtistSearchApproximate by lazy { PlatformStuff.mainPrefs.data.map { it.spotifyArtistSearchApproximate } }
    private val useSpotify by lazy { PlatformStuff.mainPrefs.data.map { it.spotifyApi } }


    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {

        suspend fun customAlbumArtMapping(
            artistName: String,
            albumName: String
        ): FetchedImageUrls? {
            val customMapping = customSpotifyMappingsDao.searchAlbum(artistName, albumName)

            return if (customMapping != null && customMapping.fileUri != null)
                FetchedImageUrls(customMapping.fileUri)
            else if (customMapping != null && useSpotify.first() && customMapping.spotifyId != null) {
                semaphore.withPermit {
                    delay(delayMs)
                    Requesters.spotifyRequester.album(
                        customMapping.spotifyId
                    ).getOrNull()?.let {
                        FetchedImageUrls(it.mediumImageUrl, it.largeImageUrl)
                    } ?: FetchedImageUrls(null)
                }
            } else {
                null
            }
        }

        val musicEntryImageReq =
            chain.request.data as? MusicEntryImageReq ?: return chain.proceed()
        val entry = musicEntryImageReq.musicEntry
        val key = MusicEntryReqKeyer.genKey(musicEntryImageReq)
        val cachedOptional = musicEntryCache[key]

        var fetchedImageUrls = cachedOptional

        if (cachedOptional == null) {
            withContext(Dispatchers.IO) {
                fetchedImageUrls = when (entry) {
                    is Artist -> {
                        semaphore.withPermit {
                            delay(delayMs)

                            val customMapping = customSpotifyMappingsDao.searchArtist(entry.name)
                            val imageUrls = if (customMapping != null) {
                                if (customMapping.spotifyId == null) {
                                    FetchedImageUrls(customMapping.fileUri)
                                } else if (useSpotify.first()) {
                                    Requesters.spotifyRequester.artist(
                                        customMapping.spotifyId
                                    ).getOrNull()?.let {
                                        FetchedImageUrls(it.mediumImageUrl, it.largeImageUrl)
                                    }
                                } else {
                                    FetchedImageUrls(null)
                                }
                            } else if (useSpotify.first()) {
                                val spotifyArtists = Requesters.spotifyRequester.search(
                                    entry.name,
                                    SpotifySearchType.artist,
                                    market = PlatformStuff.mainPrefs.data.map { it.spotifyCountryP }
                                        .first(),
                                    limit = 3
                                ).getOrNull()
                                    ?.artists
                                    ?.items

                                val caseInsensitiveMatch = spotifyArtists?.find {
                                    it.name.equals(entry.name, ignoreCase = true) &&
                                            !it.images.isNullOrEmpty()
                                }

                                val approximateMatch = spotifyArtists?.firstOrNull()

                                val artistItem = if (spotifyArtistSearchApproximate.first())
                                    caseInsensitiveMatch ?: approximateMatch
                                else
                                    caseInsensitiveMatch

                                artistItem?.let {
                                    FetchedImageUrls(it.mediumImageUrl, it.largeImageUrl)
                                }
                            } else {
                                null
                            }
                            imageUrls
                        }
                    }

                    is Album,
                    is Track -> {
                        val album = when (entry) {
                            is Album -> entry
                            is Track -> entry.album
                        }

                        val artist = when (entry) {
                            is Track -> entry.album?.artist ?: entry.artist
                            is Album -> entry.artist
                        }
                        var fetchedAlbumImageUrls = FetchedImageUrls(null)

                        val customMappingUrls = if (album != null && artist != null) {
                            customAlbumArtMapping(artist.name, album.name)
                        } else {
                            null
                        }

                        if (customMappingUrls != null) {
                            fetchedAlbumImageUrls = customMappingUrls
                        } else {
                            val needFetch = album?.webp300 == null ||
                                    album.webp300?.contains(StarMapper.STAR_PATTERN) == true

                            if (!needFetch) {
                                fetchedAlbumImageUrls = FetchedImageUrls(album.webp300)
                            } else if (musicEntryImageReq.fetchAlbumInfoIfMissing &&
                                musicEntryImageReq.accountType == AccountType.LASTFM
                            ) {
                                val dao = PanoDb.db.getSeenEntitiesDao()
                                val seenAlbum = when (entry) {
                                    is Album -> {
                                        dao.getAlbumWithFetchedArt(
                                            entry.artist!!.name,
                                            entry.name
                                        )
                                    }

                                    is Track -> {
                                        val bestAlbums = dao.getBestAlbumsForTrack(
                                            entry.artist.name,
                                            entry.name
                                        )

                                        val bestAlbumWithArt =
                                            bestAlbums.firstOrNull { it.artUpdatedAt != null }

                                        bestAlbumWithArt ?: bestAlbums.firstOrNull()
                                    }
                                }

                                fetchedAlbumImageUrls = FetchedImageUrls(seenAlbum?.artUrl)

                                // if the image from cache was still a placeholder, don't do a lookup

                                when (entry) {
                                    is Album -> {
                                        if (seenAlbum == null) {
                                            semaphore.withPermit {
                                                delay(delayMs)
                                                Requesters.lastfmUnauthedRequester.getAlbumInfo(
                                                    entry
                                                ).onSuccess {
                                                    fetchedAlbumImageUrls =
                                                        FetchedImageUrls(it.webp300)
                                                }
                                            }
                                        }
                                    }

                                    is Track -> {
                                        if (seenAlbum == null) {
                                            val t = dao.getTrack(
                                                entry.artist.name,
                                                entry.name
                                            )
                                            if (t?.trackInfoFetchedAt == null) {
                                                semaphore.withPermit {
                                                    delay(delayMs)
                                                    Requesters.lastfmUnauthedRequester.getTrackInfo(
                                                        entry
                                                    ).onSuccess {
                                                        fetchedAlbumImageUrls =
                                                            FetchedImageUrls(it.album?.webp300)
                                                    }
                                                }
                                            }
                                        } else {
                                            val customMapping = customAlbumArtMapping(
                                                seenAlbum.artist,
                                                seenAlbum.album
                                            )

                                            if (customMapping != null) {
                                                fetchedAlbumImageUrls = customMapping
                                            } else {
                                                if (seenAlbum.artUpdatedAt == null) {
                                                    semaphore.withPermit {
                                                        delay(delayMs)
                                                        Requesters.lastfmUnauthedRequester.getAlbumInfo(
                                                            Album(
                                                                seenAlbum.album,
                                                                Artist(entry.artist.name)
                                                            )
                                                        ).onSuccess {
                                                            fetchedAlbumImageUrls =
                                                                FetchedImageUrls(it.webp300)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        fetchedAlbumImageUrls
                    }
                }
                musicEntryCache.put(key, fetchedImageUrls ?: FetchedImageUrls(null))
            }
        }

        val fetchedImageUrl = if (musicEntryImageReq.isHeroImage) {
            fetchedImageUrls?.largeImage ?: fetchedImageUrls?.mediumImage
        } else {
            fetchedImageUrls?.mediumImage
        }

        val request = chain.request.newBuilder()
            .data(fetchedImageUrl ?: "")
            .listener(
                onError = { req, err ->
                    // cache the errors too, to avoid repeated failed fetches
                    // e.g. coil3.network.HttpException: HTTP 404
                    val httpException = err.throwable as? HttpException
                    if (httpException != null && httpException.response.code >= 400)
                        musicEntryCache.put(key, FetchedImageUrls(null))
                }
            )
            .build()

        return chain.withRequest(request).proceed()
    }

    fun clearCacheForEntry(req: MusicEntryImageReq) {
        val key = MusicEntryReqKeyer.genKey(req)
        musicEntryCache.remove(key)

        if (req.musicEntry is Album) {
            // remove all tracks of the album

            musicEntryCache.snapshot().keys
                .filter { it.startsWith(key) }
                .forEach { musicEntryCache.remove(it) }
        }
    }

    private data class FetchedImageUrls(val mediumImage: String?, val largeImage: String?) {
        constructor(webp300: String?) : this(
            webp300,
            webp300
                ?.takeIf { it.startsWith("https://lastfm.freetls.fastly.net") }
                ?.replace("300x300", "600x600")
        )
    }
}