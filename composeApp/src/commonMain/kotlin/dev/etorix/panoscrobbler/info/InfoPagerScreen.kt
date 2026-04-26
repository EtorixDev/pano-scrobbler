package dev.etorix.panoscrobbler.info

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.main.PanoPager
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.navigation.PanoTab
import dev.etorix.panoscrobbler.ui.EntriesGridOrList
import dev.etorix.panoscrobbler.ui.getMusicEntryPlaceholderItem
import dev.etorix.panoscrobbler.utils.Stuff
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.not_found


@Composable
fun InfoPagerScreen(
    artist: Artist,
    user: UserCached,
    appId: String?,
    tabsList: List<PanoTab>,
    tabIdx: Int,
    onSetTabIdx: (Int) -> Unit,
    onNavigate: (PanoRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistMiscVM = viewModel { ArtistMiscVM(artist) },
) {
    val artistTopTracks = viewModel.topTracks.collectAsLazyPagingItems()
    val artistTopAlbums = viewModel.topAlbums.collectAsLazyPagingItems()
    val similarArtists = viewModel.similarArtists.collectAsLazyPagingItems()

    PanoPager(
        selectedPage = tabIdx,
        onSelectPage = onSetTabIdx,
        totalPages = tabsList.count(),
        modifier = modifier,
    ) { page ->

        val type by remember(page) {
            mutableIntStateOf(
                when (page) {
                    0 -> Stuff.TYPE_ARTISTS
                    1 -> Stuff.TYPE_ALBUMS
                    2 -> Stuff.TYPE_TRACKS
                    else -> throw IllegalArgumentException("Unknown page $tabIdx")
                }
            )
        }

        EntriesGridOrList(
            entries = when (type) {
                Stuff.TYPE_TRACKS -> artistTopTracks
                Stuff.TYPE_ALBUMS -> artistTopAlbums
                Stuff.TYPE_ARTISTS -> similarArtists
                else -> throw IllegalArgumentException("Unknown type $type")
            },
            fetchAlbumImageIfMissing = type == Stuff.TYPE_TRACKS,
            showArtists = type == Stuff.TYPE_ARTISTS,
            emptyStringRes = Res.string.not_found,
            placeholderItem = remember(type) {
                getMusicEntryPlaceholderItem(type, showScrobbleCount = type != Stuff.TYPE_ARTISTS)
            },
            onItemClick = {
                onNavigate(
                    PanoRoute.Modal.MusicEntryInfo(
                        track = it as? Track,
                        artist = it as? Artist,
                        album = it as? Album,
                        appId = appId,
                        user = user
                    )
                )
            },
        )
    }
}