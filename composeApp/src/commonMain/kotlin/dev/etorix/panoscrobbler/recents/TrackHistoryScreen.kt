package dev.etorix.panoscrobbler.recents

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.edits.EditScrobbleUtils
import dev.etorix.panoscrobbler.icons.Cake
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.ui.PanoLazyColumn
import dev.etorix.panoscrobbler.ui.myTransparentCheckableItemColors
import dev.etorix.panoscrobbler.utils.PanoTimeFormatter
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue
import dev.etorix.panoscrobbler.utils.Stuff.format
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.first_scrobbled_on
import pano_scrobbler.composeapp.generated.resources.num_scrobbles_noti
import pano_scrobbler.composeapp.generated.resources.time_just_now

@Composable
fun TrackHistoryScreen(
    user: UserCached,
    track: Track,
    onSetTitle: (String) -> Unit,
    onNavigate: (PanoRoute) -> Unit,
    editDataFlow: Flow<EditScrobbleUtils.EditData>,
    modifier: Modifier = Modifier,
    viewModel: ScrobblesVM = viewModel { ScrobblesVM(user, track) },
) {
    val listState = rememberLazyListState()
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    val firstScrobbleTime by viewModel.firstScrobbleTime.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val deletedTracksCount by viewModel.deletedTracksCount.collectAsStateWithLifecycle()
    val pkgMap by viewModel.pkgMap.collectAsStateWithLifecycle()
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val showScrobbleSources by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.showScrobbleSources }

    LaunchedEffect(total, deletedTracksCount) {
        val count = ((total ?: 0) - deletedTracksCount)
            .coerceAtLeast(0)

        val title = if (user.isSelf)
            getPluralString(Res.plurals.num_scrobbles_noti, count, count.format())
        else
            "${user.name}: ${count.format()}"
        onSetTitle(title)
    }

    LaunchedEffect(Unit) {
        viewModel.setScrobblesInput(
            ScrobblesInput(showScrobbleSources = showScrobbleSources)
        )
    }

    OnEditEffect(
        viewModel,
        editDataFlow
    )

    BoxWithConstraints(modifier = modifier) {
        val isLandscape = maxWidth * 0.7f > maxHeight
        val listMaxHeight = maxHeight

        PanoLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            if (firstScrobbleTime != null) {
                item("first_scrobble_time") {
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Cake,
                                contentDescription = null,
                            )
                        },
                        colors = ListItemDefaults.myTransparentCheckableItemColors(),
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(
                                Res.string.first_scrobbled_on,
                                PanoTimeFormatter.relative(
                                    firstScrobbleTime!!,
                                    stringResource(Res.string.time_just_now)
                                )
                            ),
                        )
                    }
                }
            }

            scrobblesListItems(
                tracks = tracks,
                user = user,
                pkgMap = pkgMap,
                fetchAlbumImageIfMissing = false,
                showScrobbleSources = showScrobbleSources,
                canEdit = true,
                canDelete = true,
                canLove = false,
                canHate = false,
                expandedKey = { expandedKey },
                onExpand = { key, _ ->
                    expandedKey = key
                },
                onNavigate = onNavigate,
                isLandscape = { isLandscape },
                maxHeight = { listMaxHeight },
                viewModel = viewModel,
            )

            scrobblesPlaceholdersAndErrors(tracks = tracks)
        }
    }
}
