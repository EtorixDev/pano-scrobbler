package dev.etorix.panoscrobbler.info

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.ui.EntriesGridOrList
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.not_found


@Composable
fun SimilarTracksScreen(
    track: Track,
    user: UserCached,
    appId: String?,
    onNavigate: (PanoRoute.Modal) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SimilarTracksVM = viewModel { SimilarTracksVM(track) },
) {
    val similarTracks = viewModel.similarTracks.collectAsLazyPagingItems()

    EntriesGridOrList(
        entries = similarTracks,
        fetchAlbumImageIfMissing = true,
        showArtists = true,
        emptyStringRes = Res.string.not_found,
        placeholderItem = remember {
            Track(
                name = "Track",
                artist = Artist(
                    name = "Artist",
                ),
                playcount = 10,
                album = null,
            )
        },
        onItemClick = {
            onNavigate(
                PanoRoute.Modal.MusicEntryInfo(
                    track = it as Track,
                    appId = appId,
                    user = user
                )
            )
        },
        modifier = modifier
    )

}