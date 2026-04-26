package dev.etorix.panoscrobbler.charts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.Album
import dev.etorix.panoscrobbler.api.lastfm.Artist
import dev.etorix.panoscrobbler.api.lastfm.MusicEntry
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.icons.Album
import dev.etorix.panoscrobbler.icons.ArrowDropDown
import dev.etorix.panoscrobbler.icons.Favorite
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.icons.Mic
import dev.etorix.panoscrobbler.icons.MusicNote
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.navigation.jsonSerializableSaver
import dev.etorix.panoscrobbler.ui.ErrorText
import dev.etorix.panoscrobbler.ui.MusicEntryListItem
import dev.etorix.panoscrobbler.ui.getMusicEntryPlaceholderItem
import dev.etorix.panoscrobbler.ui.shimmerWindowBounds
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue
import dev.etorix.panoscrobbler.utils.redactedMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.album
import pano_scrobbler.composeapp.generated.resources.artist
import pano_scrobbler.composeapp.generated.resources.item_options
import pano_scrobbler.composeapp.generated.resources.loved
import pano_scrobbler.composeapp.generated.resources.track


@Composable
fun RandomScreen(
    user: UserCached,
    onNavigate: (PanoRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RandomVM = viewModel { RandomVM(user.name) },
    chartsPeriodViewModel: ChartsPeriodVM = viewModel { ChartsPeriodVM(user) },
) {
    val musicEntry by viewModel.musicEntry.collectAsStateWithLifecycle()
    val hasLoaded by viewModel.hasLoaded.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val type by PlatformStuff.mainPrefs.data
        .collectAsStateWithInitialValue { it.randomType }
    var timePeriod by rememberSaveable(saver = jsonSerializableSaver<TimePeriod?>()) {
        mutableStateOf(null)
    }

    val isTimePeriodContinuous = timePeriod?.lastfmPeriod != null

    fun load(type: Int, refresh: Boolean = false) {
        if (type != -1 && timePeriod != null) {
            viewModel.setRandomInput(
                RandomLoaderInput(
                    timePeriod = timePeriod!!,
                    type = type
                ),
                refresh
            )
        }
    }

    fun onEntryClick(musicEntry: MusicEntry) {
        onNavigate(
            PanoRoute.Modal.MusicEntryInfo(
                user = user,
                artist = musicEntry as? Artist,
                album = musicEntry as? Album,
                track = musicEntry as? Track
            )
        )
    }


    // first load
    LaunchedEffect(user) {
        PlatformStuff.mainPrefs.data.map { it.randomType }
            .combine(chartsPeriodViewModel.selectedPeriod) { type, selectedPeriod ->
                type to selectedPeriod
            }
            .take(1)
            .collect { (type, selectedPeriod) ->
                load(type)
            }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {

        AnimatedVisibility(
            visible = type != Stuff.TYPE_LOVES,
            modifier = Modifier.fillMaxWidth()
        ) {
            TimePeriodSelector(
                user = user,
                viewModel = chartsPeriodViewModel,
                onSelected = { curr, prev, _ ->
                    timePeriod = curr
                    load(type)
                },
                showRefreshButton = false,
            )
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f)
        ) {
            val isLandscape = (maxWidth * 0.7f) > maxHeight

            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    if (isLandscape) Modifier
                        .heightIn(max = 400.dp)
                    else Modifier.widthIn(max = 400.dp)
            ) {
                val musicEntryOrPlaceholder =
                    musicEntry.takeIf { hasLoaded } ?: getMusicEntryPlaceholderItem(
                        if (type == -1 || type == Stuff.TYPE_LOVES) Stuff.TYPE_TRACKS else type
                    )

                if (hasLoaded) {
                    ErrorText(
                        errorText = error?.redactedMessage,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }

                if (error == null) {
                    MusicEntryListItem(
                        entry = musicEntryOrPlaceholder,
                        forShimmer = !hasLoaded,
                        fetchAlbumImageIfMissing = !isTimePeriodContinuous,
                        onEntryClick = {
                            musicEntry?.let { onEntryClick(it) }
                        },
                        fixedImageHeight = false,
                        isColumn = !isLandscape,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (!hasLoaded) Modifier.shimmerWindowBounds()
                                else Modifier
                            )
                    )
                }
            }
        }
        RandomTypeSelector(
            type = type,
            onSameClick = {
                load(type, true)
            },
            onMenuItemClick = { newType ->
                load(newType)
            },
        )

    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RandomTypeSelector(
    type: Int,
    onSameClick: () -> Unit,
    onMenuItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typeSelectorIsShown by remember { mutableStateOf(false) }

    fun getIconForType(type: Int) = when (type) {
        Stuff.TYPE_ARTISTS -> Icons.Mic
        Stuff.TYPE_ALBUMS -> Icons.Album
        Stuff.TYPE_TRACKS -> Icons.MusicNote
        Stuff.TYPE_LOVES -> Icons.Favorite
        else -> error("Unknown type $type")
    }

    @Composable
    fun getTextForType(type: Int) = when (type) {
        Stuff.TYPE_ARTISTS -> stringResource(Res.string.artist)
        Stuff.TYPE_ALBUMS -> stringResource(Res.string.album)
        Stuff.TYPE_TRACKS -> stringResource(Res.string.track)
        Stuff.TYPE_LOVES -> stringResource(Res.string.loved)
        else -> error("Unknown type $type")
    }

    Box(
        modifier = modifier
    ) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.OutlinedLeadingButton(
                    onClick = onSameClick
                ) {
                    Icon(
                        getIconForType(type),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )

                    Text(text = getTextForType(type))
                }
            },
            trailingButton = {
                SplitButtonDefaults.OutlinedTrailingButton(
                    onCheckedChange = {
                        typeSelectorIsShown = it
                    },
                    checked = typeSelectorIsShown
                ) {
                    Icon(
                        Icons.ArrowDropDown,
                        contentDescription = stringResource(Res.string.item_options)
                    )
                }
                Icon(
                    Icons.ArrowDropDown,
                    contentDescription = stringResource(Res.string.item_options)
                )
            },
        )

        DropdownMenu(
            expanded = typeSelectorIsShown,
            onDismissRequest = { typeSelectorIsShown = false }
        ) {
            arrayOf(
                Stuff.TYPE_ARTISTS,
                Stuff.TYPE_ALBUMS,
                Stuff.TYPE_TRACKS,
                Stuff.TYPE_LOVES
            ).forEach { thisType ->
                DropdownMenuItem(
                    enabled = thisType != type,
                    onClick = {
                        typeSelectorIsShown = false
                        onMenuItemClick(thisType)
                    },
                    leadingIcon = {
                        Icon(
                            getIconForType(thisType),
                            contentDescription = getTextForType(thisType)
                        )
                    },
                    text = {
                        Text(getTextForType(thisType))
                    }
                )
            }
        }
    }
}