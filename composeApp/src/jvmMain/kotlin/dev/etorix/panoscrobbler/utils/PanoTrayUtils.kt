package dev.etorix.panoscrobbler.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object PanoTrayUtils {
    enum class TrayIconState {
        Idle,
        Scrobbling,
        Error,
    }

    data class TrayData(
        val iconType: TrayIconState,
        val tooltip: String,
        val menuItemIds: List<String>,
        val menuItemTexts: List<String>,
    )

    private val _onTrayMenuItemClicked =
        MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    val onTrayMenuItemClicked = _onTrayMenuItemClicked.asSharedFlow()

    fun onTrayMenuItemClickedFn(id: String) {
        _onTrayMenuItemClicked.tryEmit(id)
    }

    enum class ItemId {
        TrackName,
        ArtistName,
        AlbumName,
        Separator,
        Love,
        Edit,
        Cancel,
        Block,
        Copy,
        Error,
        Open,
        Settings,
        Update,
        DiscordRpcDisabled,
        Exit;

        fun withSuffix(suffix: String): String {
            return "${this.name}:$suffix"
        }
    }

}
