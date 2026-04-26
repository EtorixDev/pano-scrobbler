package dev.etorix.panoscrobbler.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.etorix.panoscrobbler.api.Requesters
import dev.etorix.panoscrobbler.api.lastfm.SearchResults
import dev.etorix.panoscrobbler.ui.generateKey
import dev.etorix.panoscrobbler.utils.Stuff
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


class SearchVM : ViewModel() {
    private val _searchTerm = MutableSharedFlow<String>()
    private val _searchResults = MutableStateFlow<SearchResults?>(null)
    val searchResults = _searchResults.asSharedFlow()
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded = _hasLoaded.asStateFlow()

    init {
        viewModelScope.launch {
            _searchTerm
                .distinctUntilChanged()
                .debounce(500)
                .collectLatest { term ->
                    if (term.length < 3)
                        return@collectLatest

                    _hasLoaded.value = false
                    Requesters.lastfmUnauthedRequester
                        .search(term)
                        .map {
                            it.copy(
                                tracks = it.tracks.distinctBy { it.generateKey() },
                                albums = it.albums.distinctBy { it.generateKey() },
                                artists = it.artists.distinctBy { it.generateKey() },
                            )
                        }
                        .onSuccess {
                            _searchResults.emit(it)
                        }.onFailure { e ->
                            Stuff.globalExceptionFlow.emit(e)
                        }
                    _hasLoaded.value = true
                }
        }
    }

    fun search(term: String) {
        viewModelScope.launch {
            _searchTerm.emit(term)
        }
    }
}