package dev.etorix.panoscrobbler.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.MusicEntry
import dev.etorix.panoscrobbler.api.spotify.AlbumItem
import dev.etorix.panoscrobbler.api.spotify.ArtistItem
import dev.etorix.panoscrobbler.api.spotify.SpotifyMusicItem
import dev.etorix.panoscrobbler.api.spotify.SpotifySearchResponse
import dev.etorix.panoscrobbler.api.spotify.SpotifySearchType
import dev.etorix.panoscrobbler.db.CustomSpotifyMapping
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.utils.PlatformFile
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageSearchVM : ViewModel() {
    private val _searchTerm =
        MutableSharedFlow<Pair<String, Int>>(replay = 1, extraBufferCapacity = 1)
    private val _searchResults = MutableStateFlow<SpotifySearchResponse?>(null)
    val searchResultsWithImages = _searchResults.map {
        it ?: return@map null

        (it.artists?.items ?: it.albums?.items ?: emptyList())
            .filter { item ->
                (item is AlbumItem && !item.images.isNullOrEmpty()) ||
                        (item is ArtistItem && !item.images.isNullOrEmpty())
            }
    }
    private val _searchError = MutableStateFlow<Throwable?>(null)
    val searchError = _searchError.asStateFlow()
    private val _hasRedirect = MutableStateFlow(false)
    private val _existingMappings = MutableStateFlow<List<CustomSpotifyMapping>>(emptyList())
    val existingMappings = _existingMappings.asStateFlow()
    private var searchType: Int = -1
    private var musicEntry: MusicEntry? = null
    private var originalMusicEntry: MusicEntry? = null

    init {
        viewModelScope.launch {
            val country = PlatformStuff.mainPrefs.data.map { it.spotifyCountryP }.first()

            _searchTerm
                .distinctUntilChanged()
                .debounce(500)
                .collectLatest { (term, searchType) ->
                    val results = when (searchType) {
                        Stuff.TYPE_ALBUMS ->
                            Requesters.spotifyRequester.search(
                                term,
                                SpotifySearchType.album,
                                market = country,
                                limit = LIMIT
                            )

                        Stuff.TYPE_ARTISTS ->
                            Requesters.spotifyRequester.search(
                                term,
                                SpotifySearchType.artist,
                                market = country,
                                limit = LIMIT
                            )

                        else -> throw IllegalArgumentException("Invalid search type: $searchType")
                    }
                    results.onSuccess {
                        _searchResults.value = it
                        _searchError.value = null
                    }.onFailure { e ->
                        _searchResults.value = null
                        _searchError.value = e
                    }
                }
        }
    }

    fun search(term: String) {
        _searchTerm.tryEmit(term to searchType)
    }

    fun setMusicEntries(
        musicEntry: MusicEntry,
        originalMusicEntry: MusicEntry?,
    ) {
        fun hasRedirect(): Boolean {
            return (musicEntry is Artist && originalMusicEntry is Artist && musicEntry.name != originalMusicEntry.name) ||
                    (musicEntry is Album && originalMusicEntry is Album &&
                            (musicEntry.name != originalMusicEntry.name ||
                                    musicEntry.artist!!.name != originalMusicEntry.artist!!.name))

        }

        this.musicEntry = musicEntry
        this.originalMusicEntry = originalMusicEntry

        _hasRedirect.value = hasRedirect()
        searchType = if (musicEntry is Album) Stuff.TYPE_ALBUMS else Stuff.TYPE_ARTISTS

        viewModelScope.launch {

            val customSpotifyMapping = withContext(Dispatchers.IO) {
                when (musicEntry) {
                    is Album -> PanoDb.db.getCustomSpotifyMappingsDao()
                        .searchAlbum(musicEntry.artist!!.name, musicEntry.name)

                    is Artist -> PanoDb.db.getCustomSpotifyMappingsDao()
                        .searchArtist(musicEntry.name)

                    else -> null
                }
            }

            val customSpotifyMappingOrig = if (_hasRedirect.value)
                withContext(Dispatchers.IO) {
                    when (originalMusicEntry) {
                        is Album -> PanoDb.db.getCustomSpotifyMappingsDao()
                            .searchAlbum(originalMusicEntry.artist!!.name, musicEntry.name)

                        is Artist -> PanoDb.db.getCustomSpotifyMappingsDao()
                            .searchArtist(originalMusicEntry.name)

                        else -> null
                    }
                }
            else
                null

            _existingMappings.value = listOfNotNull(customSpotifyMapping, customSpotifyMappingOrig)
        }
    }

    fun deleteExistingMappings() {
        if (existingMappings.value.isNotEmpty()) {
            existingMappings.value.forEach {
                it.fileUri?.let { PlatformFile(it).releasePersistableUriPermission(readWrite = false) }

                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        PanoDb.db.getCustomSpotifyMappingsDao().delete(it)
                    }
                }
            }
        }
    }

    private fun createCustomMapping(
        musicEntry: MusicEntry,
        spotifyItem: SpotifyMusicItem?,
        fileUri: String?,
    ): CustomSpotifyMapping {
        return when (musicEntry) {
            is Album -> CustomSpotifyMapping(
                artist = musicEntry.artist!!.name,
                album = musicEntry.name,
                spotifyId = spotifyItem?.id,
                fileUri = fileUri
            )

            is Artist -> CustomSpotifyMapping(
                artist = musicEntry.name,
                spotifyId = spotifyItem?.id,
                fileUri = fileUri
            )

            else -> throw IllegalArgumentException("Invalid item type: $spotifyItem")
        }
    }

    fun insertCustomMappings(
        spotifyItem: SpotifyMusicItem?,
        fileUri: String?,
    ) {
        val musicEntry = musicEntry ?: return // has to be initialized

        val mappings = mutableListOf<CustomSpotifyMapping>()
        mappings += createCustomMapping(musicEntry, spotifyItem, fileUri)

        // revoke uri permission for existing mappings
        existingMappings.value.forEach {
            it.fileUri?.let { PlatformFile(it).releasePersistableUriPermission(false) }
        }

        // create another mapping for the redirected artist/album
        if (_hasRedirect.value)
            mappings += createCustomMapping(originalMusicEntry!!, spotifyItem, fileUri)

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                PanoDb.db.getCustomSpotifyMappingsDao()
                    .insert(mappings)
            }
        }
    }


    fun setImage(platformFile: PlatformFile) {
        try {
            platformFile.takePersistableUriPermission(readWrite = false)

            insertCustomMappings(null, platformFile.uri)
        } catch (e: SecurityException) {
            viewModelScope.launch {
                Stuff.globalExceptionFlow.emit(e)
            }
        }
    }

    companion object {
        private const val LIMIT = 40
    }
}
