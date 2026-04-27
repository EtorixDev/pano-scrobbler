package dev.etorix.panoscrobbler.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.DrawerData
import dev.etorix.panoscrobbler.charts.ChartsLegendDialog
import dev.etorix.panoscrobbler.charts.CollageGeneratorDialog
import dev.etorix.panoscrobbler.charts.HiddenTagsDialog
import dev.etorix.panoscrobbler.db.SimpleEdit
import dev.etorix.panoscrobbler.edits.BlockedMetadataAddDialog
import dev.etorix.panoscrobbler.edits.SimpleEditsAddScreen
import dev.etorix.panoscrobbler.info.MusicEntryInfoDialog
import dev.etorix.panoscrobbler.info.TagInfoDialog
import dev.etorix.panoscrobbler.main.MainViewModel
import dev.etorix.panoscrobbler.onboarding.LoginDestinations
import dev.etorix.panoscrobbler.onboarding.ShowLinkDialog
import dev.etorix.panoscrobbler.pref.MediaSearchPrefDialog
import dev.etorix.panoscrobbler.pref.ProxyPrefDialog
import dev.etorix.panoscrobbler.ui.verticalOverscanPadding
import dev.etorix.panoscrobbler.updates.ChangelogDialog
import dev.etorix.panoscrobbler.updates.UpdateAvailableDialog
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue
import kotlinx.coroutines.flow.map

fun EntryProviderScope<PanoRoute>.panoModalNavGraph(
    navigate: (PanoRoute) -> Unit,
    goBack: () -> Unit,
    onSetDrawerData: (DrawerData) -> Unit,
    mainViewModel: MainViewModel,
) {
    modalEntry<PanoRoute.Modal.NavPopup> { route ->
        val user by if (route.otherUser != null)
            remember { mutableStateOf(route.otherUser) }
        else
            PlatformStuff.mainPrefs.data
                .collectAsStateWithInitialValue { it.currentAccount?.user }

        NavPopupDialog(
            user = user ?: return@modalEntry,
            initialDrawerData = route.initialDrawerData,
            drawSnowfall = mainViewModel.isItChristmas,
            onSetDrawerData = onSetDrawerData,
            onNavigate = navigate,
            modifier = modalModifier()
        )
    }

    modalEntry<PanoRoute.Modal.Changelog> { route ->
        ChangelogDialog(
            text = route.text,
            modifier = modalModifier()
        )
    }

    modalEntry<PanoRoute.Modal.ChartsLegend> {
        ChartsLegendDialog(
            modifier = modalModifier()
        )
    }

    modalEntry<PanoRoute.Modal.UpdateAvailable> { route ->
        UpdateAvailableDialog(
            updateAction = route.updateAction,
            modifier = modalModifier()
        )
    }

    modalEntry<PanoRoute.Modal.HiddenTags> {
        HiddenTagsDialog(
            modifier = modalModifier()
        )
    }

    modalEntry<PanoRoute.Modal.CollageGenerator> { route ->
        CollageGeneratorDialog(
            collageType = route.collageType,
            timePeriod = route.timePeriod,
            user = route.user,
            modifier = modalModifier()
        )
    }

    modalEntry<PanoRoute.Modal.MusicEntryInfo> { route ->
        val scrollState = rememberScrollState()
        MusicEntryInfoDialog(
            musicEntry = route.artist ?: route.album ?: route.track!!,
            appId = route.appId,
            user = route.user,
            onNavigate = navigate,
            scrollState = scrollState,
            modifier = modalModifier(padding = false)
        )
    }

    modalEntry<PanoRoute.Modal.TagInfo> { route ->
        val scrollState = rememberScrollState()
        TagInfoDialog(
            tag = route.tag,
            scrollState = scrollState,
            modifier = modalModifier()
        )
    }

    modalEntry<PanoRoute.Modal.ShowLink> { route ->
        ShowLinkDialog(
            url = route.url,
            modifier = modalModifier(),
        )
    }

    modalEntry<PanoRoute.Modal.MediaSearchPref> {
        MediaSearchPrefDialog(
            modifier = modalModifier(),
        )
    }

    modalEntry<PanoRoute.Modal.ProxyPref> {
        ProxyPrefDialog(
            modifier = modalModifier(),
        )
    }

    modalEntry<PanoRoute.Modal.BlockedMetadataAdd> { route ->
        BlockedMetadataAddDialog(
            blockedMetadata = route.blockedMetadata,
            ignoredArtist = route.ignoredArtist,
            hash = route.hash,
            onDismiss = goBack,
            modifier = modalModifier()
        )
    }

    modalEntry<PanoRoute.Modal.EditScrobble> { route ->
        SimpleEditsAddScreen(
            simpleEdit = SimpleEdit(
                track = route.origScrobbleData.track,
                artist = route.origScrobbleData.artist,
                album = route.origScrobbleData.album.orEmpty(),
                albumArtist = route.origScrobbleData.albumArtist.orEmpty(),
                origTrack = route.origScrobbleData.track,
                origArtist = route.origScrobbleData.artist,
                origAlbum = route.origScrobbleData.album.orEmpty(),
                origAlbumArtist = route.origScrobbleData.albumArtist.orEmpty(),
                hasOrigTrack = true,
                hasOrigArtist = true,
                hasOrigAlbum = true,
                hasOrigAlbumArtist = false,
            ),
            origScrobbleData = route.origScrobbleData,
            originalTrack = route.origTrack,
            msid = route.msid,
            hash = route.hash,
            key = route.key,
            onDone = goBack,
            onReauthenticate = {
                goBack()
                navigate(LoginDestinations.route(AccountType.LASTFM))
            },
            // this viewmodel should be scoped to the main viewmodel store owner
            viewModel = mainViewModel,
            modifier = modalModifier()
        )
    }
}


@Composable
fun modalModifier(
    padding: Boolean = true,
    scrollState: ScrollState = rememberScrollState()
): Modifier {
    return Modifier
        .fillMaxWidth()
        .then(
            if (padding)
                Modifier.padding(horizontal = 24.dp)
            else
                Modifier
        )
        .padding(bottom = verticalOverscanPadding())
        .verticalScroll(scrollState)
}

inline fun <reified K : PanoRoute.Modal> EntryProviderScope<PanoRoute>.modalEntry(
    noinline content: @Composable (K) -> Unit,
) {
    entry<K>(
        metadata = BottomSheetSceneStrategy.bottomSheet(),
        content = content
    )
}