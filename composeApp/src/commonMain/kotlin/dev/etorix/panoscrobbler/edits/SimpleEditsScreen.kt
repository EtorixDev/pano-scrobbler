package dev.etorix.panoscrobbler.edits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.etorix.panoscrobbler.db.SimpleEdit
import dev.etorix.panoscrobbler.icons.Album
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.icons.Mic
import dev.etorix.panoscrobbler.icons.MusicNote
import dev.etorix.panoscrobbler.icons.Stop
import dev.etorix.panoscrobbler.icons.automirrored.ArrowRight
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
import pano_scrobbler.composeapp.generated.resources.simple_edits
import pano_scrobbler.composeapp.generated.resources.stop

@Composable
fun SimpleEditsScreen(
    onNavigate: (PanoRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SimpleEditsVM = viewModel { SimpleEditsVM() },
) {
    val simpleEdits by viewModel.simpleEditsFiltered.collectAsStateWithLifecycle()
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
                },
                modifier = Modifier
                    .padding(panoContentPadding(bottom = false))
            )
        }

        EmptyTextWithImportButtonOnTv(
            visible = simpleEdits?.isEmpty() == true,
            text = stringResource(Res.string.simple_edits) + ": " + 0,
            onButtonClick = {
                onNavigate(PanoRoute.Import)
            }
        )

        PanoLazyColumn(
            contentPadding = panoContentPadding(mayHaveBottomFab = true),
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (simpleEdits == null) {
                val shimmerEdits = List(10) {
                    SimpleEdit(
                        _id = it.toLong(),
                        hasOrigAlbumArtist = true,
                        track = "",
                        artist = "",
                        album = "",
                        albumArtist = "",
                    )
                }
                items(
                    shimmerEdits,
                ) { edit ->
                    SimpleEditItem(
                        edit,
                        forShimmer = true,
                        onEdit = {},
                        onDelete = {},
                        modifier = Modifier.shimmerWindowBounds().animateItem()
                    )
                }
            } else {
                items(
                    simpleEdits!!,
                    key = { it._id }
                ) { edit ->
                    SimpleEditItem(
                        edit,
                        onEdit = {
                            onNavigate(PanoRoute.SimpleEditsAdd(it))
                        },
                        onDelete = { viewModel.delete(it) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SimpleEditItem(
    edit: SimpleEdit,
    onEdit: (SimpleEdit?) -> Unit,
    onDelete: (SimpleEdit) -> Unit,
    modifier: Modifier = Modifier,
    forShimmer: Boolean = false,
) {
    val wildcardStr = "*"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.medium)
                .clickable(enabled = !forShimmer) { onEdit(edit) }
                .padding(8.dp)
                .backgroundForShimmer(forShimmer)
        ) {
            if (!edit.continueMatching)
                Icon(
                    imageVector = Icons.Stop,
                    contentDescription = stringResource(Res.string.stop),
                )

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                TextWithIcon(
                    text = edit.origTrack.takeIf { edit.hasOrigTrack } ?: wildcardStr,
                    icon = Icons.MusicNote,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                TextWithIcon(
                    text = edit.origArtist.takeIf { edit.hasOrigArtist } ?: wildcardStr,
                    icon = Icons.Mic,
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                )
                TextWithIcon(
                    text = edit.origAlbum.takeIf { edit.hasOrigAlbum } ?: wildcardStr,
                    icon = Icons.Album,
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                )
                TextWithIcon(
                    text = edit.origAlbumArtist.takeIf { edit.hasOrigAlbumArtist } ?: wildcardStr,
                    icon = PanoIcons.AlbumArtist,
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = edit.track ?: wildcardStr,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = edit.artist ?: wildcardStr,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = edit.album ?: wildcardStr,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = edit.albumArtist ?: wildcardStr,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        EditsDeleteMenu(
            onDelete = { onDelete(edit) },
            enabled = !forShimmer
        )

    }
}