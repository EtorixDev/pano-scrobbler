package dev.etorix.panoscrobbler.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.MusicEntry
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.icons.Album
import dev.etorix.panoscrobbler.icons.Favorite
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.icons.Mic
import dev.etorix.panoscrobbler.icons.MusicNote
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.ui.EmptyText
import dev.etorix.panoscrobbler.ui.MusicEntryListItem
import dev.etorix.panoscrobbler.ui.PanoLazyColumn
import dev.etorix.panoscrobbler.ui.SearchField
import dev.etorix.panoscrobbler.ui.expandableSublist
import dev.etorix.panoscrobbler.ui.getMusicEntryPlaceholderItem
import dev.etorix.panoscrobbler.ui.panoContentPadding
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.albums
import pano_scrobbler.composeapp.generated.resources.artists
import pano_scrobbler.composeapp.generated.resources.external_metadata
import pano_scrobbler.composeapp.generated.resources.is_turned_off
import pano_scrobbler.composeapp.generated.resources.lastfm
import pano_scrobbler.composeapp.generated.resources.loved
import pano_scrobbler.composeapp.generated.resources.not_found
import pano_scrobbler.composeapp.generated.resources.search
import pano_scrobbler.composeapp.generated.resources.tracks

@Composable
fun SearchScreen(
    onNavigate: (PanoRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchVM = viewModel { SearchVM() },
) {
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle(null)
    val hasLoaded by viewModel.hasLoaded.collectAsStateWithLifecycle()

    var searchTerm by rememberSaveable { mutableStateOf("") }

    var artistsExpanded by rememberSaveable { mutableStateOf(false) }
    var albumsExpanded by rememberSaveable { mutableStateOf(false) }
    var tracksExpanded by rememberSaveable { mutableStateOf(false) }
    var lovedExpanded by rememberSaveable { mutableStateOf(false) }

    val artistsText = stringResource(Res.string.artists)
    val albumsText = stringResource(Res.string.albums)
    val tracksText = stringResource(Res.string.tracks)
    val lovedText = stringResource(Res.string.loved)
    val currentAccount by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.currentAccount
    }
    val useLastfm by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.lastfmApiAlways || it.currentAccountType == AccountType.LASTFM
    }

    val focusRequester = remember { FocusRequester() }

    fun onItemClick(item: MusicEntry) {
        currentAccount?.user?.let { userSelf ->
            onNavigate(
                PanoRoute.Modal.MusicEntryInfo(
                    track = item as? Track,
                    album = item as? Album,
                    artist = item as? Artist,
                    user = userSelf,
                )
            )
        }
    }

    LaunchedEffect(searchTerm) {
        viewModel.search(searchTerm)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        SearchField(
            searchTerm = if (useLastfm) searchTerm else "",
            label = if (useLastfm)
                stringResource(Res.string.search)
            else
                stringResource(
                    Res.string.is_turned_off,
                    stringResource(Res.string.lastfm),
                    stringResource(Res.string.external_metadata),
                ),
            enabled = useLastfm,
            onSearchTermChange = { searchTerm = it },
            modifier = Modifier
                .padding(panoContentPadding(bottom = false))
                .focusRequester(focusRequester)

        )

        EmptyText(
            text = stringResource(Res.string.not_found),
            visible = hasLoaded && searchResults?.isEmpty == true,
        )

        PanoLazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            if (hasLoaded) {
                expandableSublist(
                    headerText = artistsText,
                    headerIcon = Icons.Mic,
                    items = searchResults?.artists ?: emptyList(),
                    expanded = artistsExpanded,
                    onToggle = { artistsExpanded = it },
                    onItemClick = ::onItemClick,
                )

                expandableSublist(
                    headerText = albumsText,
                    headerIcon = Icons.Album,
                    items = searchResults?.albums ?: emptyList(),
                    expanded = albumsExpanded,
                    onToggle = { albumsExpanded = it },
                    onItemClick = ::onItemClick,
                )

                expandableSublist(
                    headerText = tracksText,
                    headerIcon = Icons.MusicNote,
                    items = searchResults?.tracks ?: emptyList(),
                    expanded = tracksExpanded,
                    onToggle = { tracksExpanded = it },
                    onItemClick = ::onItemClick,
                    fetchAlbumImageIfMissing = true,
                )

                expandableSublist(
                    headerText = lovedText,
                    headerIcon = Icons.Favorite,
                    items = searchResults?.lovedTracks ?: emptyList(),
                    expanded = lovedExpanded,
                    onToggle = { lovedExpanded = it },
                    onItemClick = ::onItemClick,
                    fetchAlbumImageIfMissing = true,
                )

            } else if (searchResults != null) {
                items(10) {
                    MusicEntryListItem(
                        getMusicEntryPlaceholderItem(Stuff.TYPE_TRACKS),
                        forShimmer = true,
                        onEntryClick = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}