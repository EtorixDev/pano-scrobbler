package dev.etorix.panoscrobbler.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import dev.etorix.panoscrobbler.charts.TimePeriodType
import dev.etorix.panoscrobbler.charts.getPeriodTypeIcon
import dev.etorix.panoscrobbler.icons.Album
import dev.etorix.panoscrobbler.icons.BarChart4Bars
import dev.etorix.panoscrobbler.icons.Casino
import dev.etorix.panoscrobbler.icons.Favorite
import dev.etorix.panoscrobbler.icons.Group
import dev.etorix.panoscrobbler.icons.History
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.icons.Mic
import dev.etorix.panoscrobbler.icons.MusicNote
import dev.etorix.panoscrobbler.icons.Refresh
import dev.etorix.panoscrobbler.utils.PlatformStuff
import org.jetbrains.compose.resources.StringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.albums
import pano_scrobbler.composeapp.generated.resources.artists
import pano_scrobbler.composeapp.generated.resources.charts
import pano_scrobbler.composeapp.generated.resources.following
import pano_scrobbler.composeapp.generated.resources.loved
import pano_scrobbler.composeapp.generated.resources.random_text
import pano_scrobbler.composeapp.generated.resources.recents
import pano_scrobbler.composeapp.generated.resources.reload
import pano_scrobbler.composeapp.generated.resources.scrobbles
import pano_scrobbler.composeapp.generated.resources.time_jump
import pano_scrobbler.composeapp.generated.resources.tracks

sealed class PanoTab(
    val titleRes: StringResource,
    val icon: ImageVector,
) {
    data class Subtab(
        val id: Int,
        val icon: ImageVector,
        val titleRes: StringResource,
        val isDropdown: Boolean = false
    )

    sealed interface HasSubtabs {
        val subTabs: List<Subtab>
    }

    data object Scrobbles : PanoTab(titleRes = Res.string.scrobbles, icon = Icons.History),
        HasSubtabs {
        enum class ScrobblesType {
            REFRESH,
            RECENTS,
            LOVED,
            TIME_JUMP,
            RANDOM,
        }

        override val subTabs = listOfNotNull(
            if (PlatformStuff.isDesktop || PlatformStuff.isTv)
                Subtab(ScrobblesType.REFRESH.ordinal, Icons.Refresh, Res.string.reload)
            else
                null,
            Subtab(ScrobblesType.RECENTS.ordinal, Icons.History, Res.string.recents),
            Subtab(ScrobblesType.LOVED.ordinal, Icons.Favorite, Res.string.loved),
            Subtab(
                ScrobblesType.TIME_JUMP.ordinal,
                getPeriodTypeIcon(TimePeriodType.CUSTOM),
                Res.string.time_jump
            ),
            Subtab(ScrobblesType.RANDOM.ordinal, Icons.Casino, Res.string.random_text)
        )
    }

    data object ScrobblesNoSubtabs : PanoTab(titleRes = Res.string.scrobbles, icon = Icons.History)
    data object Following : PanoTab(titleRes = Res.string.following, icon = Icons.Group)
    data object Charts : PanoTab(titleRes = Res.string.charts, icon = Icons.BarChart4Bars),
        PanoRoute.HasTimePeriods

    data object TopArtists : PanoTab(titleRes = Res.string.artists, icon = Icons.Mic)
    data object TopAlbums : PanoTab(titleRes = Res.string.albums, icon = Icons.Album)
    data object TopTracks : PanoTab(titleRes = Res.string.tracks, icon = Icons.MusicNote)
}
