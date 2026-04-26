package dev.etorix.panoscrobbler.edits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.etorix.panoscrobbler.db.BlockPlayerAction
import dev.etorix.panoscrobbler.db.BlockedMetadata
import dev.etorix.panoscrobbler.icons.Album
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.icons.Mic
import dev.etorix.panoscrobbler.icons.MusicNote
import dev.etorix.panoscrobbler.icons.SkipNext
import dev.etorix.panoscrobbler.icons.automirrored.VolumeOff
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.panoicons.AlbumArtist
import dev.etorix.panoscrobbler.panoicons.PanoIcons
import dev.etorix.panoscrobbler.ui.EmptyTextWithImportButtonOnTv
import dev.etorix.panoscrobbler.ui.PanoLazyColumn
import dev.etorix.panoscrobbler.ui.SearchField
import dev.etorix.panoscrobbler.ui.TextWithIcon
import dev.etorix.panoscrobbler.ui.backgroundForShimmer
import dev.etorix.panoscrobbler.ui.panoContentPadding
import dev.etorix.panoscrobbler.ui.shimmerWindowBounds
import dev.etorix.panoscrobbler.utils.Stuff
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.mute
import pano_scrobbler.composeapp.generated.resources.pref_blocked_metadata
import pano_scrobbler.composeapp.generated.resources.skip

@Composable
fun BlockedMetadatasScreen(
    onNavigate: (PanoRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BlockedMetadataVM = viewModel { BlockedMetadataVM() },
) {
    val blockedMetadatas by viewModel.blockedMetadataFiltered.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    var searchTerm by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(searchTerm) {
        viewModel.setFilter(searchTerm)
    }

    Column(modifier = modifier) {
        if (count > Stuff.MIN_ITEMS_TO_SHOW_SEARCH) {
            SearchField(
                searchTerm = searchTerm,
                onSearchTermChange = {
                    searchTerm = it
                    viewModel.setFilter(it)
                },
                modifier = Modifier.padding(panoContentPadding(bottom = false))
            )
        }

        EmptyTextWithImportButtonOnTv(
            visible = blockedMetadatas?.isEmpty() == true,
            text = stringResource(Res.string.pref_blocked_metadata) + ": " + 0,
            onButtonClick = {
                onNavigate(PanoRoute.Import)
            }
        )

        PanoLazyColumn(
            contentPadding = panoContentPadding(mayHaveBottomFab = true),
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (blockedMetadatas == null) {
                val shimmerEdits = List(10) {
                    BlockedMetadata(
                        _id = it.toLong(),
                        track = " ",
                        artist = "",
                        album = "",
                        albumArtist = "",
                    )
                }
                items(shimmerEdits) {
                    BlockedMetadataItem(
                        it,
                        forShimmer = true,
                        onEdit = {},
                        onDelete = {},
                        modifier = Modifier.shimmerWindowBounds().animateItem()
                    )
                }
            } else {
                items(
                    blockedMetadatas!!,
                    key = { it._id }
                ) {
                    BlockedMetadataItem(
                        it,
                        onEdit = {
                            onNavigate(PanoRoute.Modal.BlockedMetadataAdd(it))
                        },
                        onDelete = {
                            viewModel.delete(it)
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }

    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BlockedMetadataItem(
    blockedMetadata: BlockedMetadata,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    forShimmer: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 56.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(enabled = !forShimmer, onClick = onEdit)
                .padding(8.dp)
        ) {
            TextWithIcon(
                text = blockedMetadata.track.ifEmpty { "*" },
                icon = Icons.MusicNote,
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier
                    .fillMaxWidth()
                    .backgroundForShimmer(forShimmer)
            )

            TextWithIcon(
                text = blockedMetadata.artist.ifEmpty { "*" },
                icon = Icons.Mic,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
            )

            TextWithIcon(
                text = blockedMetadata.album.ifEmpty { "*" },
                icon = Icons.Album,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
            )

            TextWithIcon(
                text = blockedMetadata.albumArtist.ifEmpty { "*" },
                icon = PanoIcons.AlbumArtist,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
            )
        }

        if (blockedMetadata.blockPlayerAction == BlockPlayerAction.skip) {
            Icon(
                imageVector = Icons.SkipNext,
                contentDescription = stringResource(Res.string.skip),
                tint = MaterialTheme.colorScheme.secondary,
            )
        } else if (blockedMetadata.blockPlayerAction == BlockPlayerAction.mute) {
            Icon(
                imageVector = Icons.AutoMirrored.VolumeOff,
                contentDescription = stringResource(Res.string.mute),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }

        EditsDeleteMenu(
            onDelete = onDelete,
            enabled = !forShimmer
        )
    }
}