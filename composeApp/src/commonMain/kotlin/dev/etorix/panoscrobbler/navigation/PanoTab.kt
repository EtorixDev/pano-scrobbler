package dev.etorix.panoscrobbler.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import dev.etorix.panoscrobbler.icons.Album
import dev.etorix.panoscrobbler.icons.BarChart4Bars
import dev.etorix.panoscrobbler.icons.Group
import dev.etorix.panoscrobbler.icons.History
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.icons.Mic
import dev.etorix.panoscrobbler.icons.MusicNote
import dev.etorix.panoscrobbler.icons.Person
import org.jetbrains.compose.resources.StringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.albums
import pano_scrobbler.composeapp.generated.resources.artists
import pano_scrobbler.composeapp.generated.resources.charts
import pano_scrobbler.composeapp.generated.resources.following
import pano_scrobbler.composeapp.generated.resources.pref_user_label
import pano_scrobbler.composeapp.generated.resources.scrobbles
import pano_scrobbler.composeapp.generated.resources.tracks

sealed class PanoTab(
    val titleRes: StringResource,
    val icon: ImageVector,
) {
    data class Scrobbles(val showChips: Boolean = true) :
        PanoTab(titleRes = Res.string.scrobbles, icon = Icons.History)

    data object Following : PanoTab(titleRes = Res.string.following, icon = Icons.Group)
    data object Charts : PanoTab(titleRes = Res.string.charts, icon = Icons.BarChart4Bars)
    data object Profile : PanoTab(titleRes = Res.string.pref_user_label, icon = Icons.Person)
    data object TopArtists : PanoTab(titleRes = Res.string.artists, icon = Icons.Mic)
    data object TopAlbums : PanoTab(titleRes = Res.string.albums, icon = Icons.Album)
    data object TopTracks : PanoTab(titleRes = Res.string.tracks, icon = Icons.MusicNote)
}