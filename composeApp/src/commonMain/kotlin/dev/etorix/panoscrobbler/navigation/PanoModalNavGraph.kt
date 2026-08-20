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
import androidx.navigation3.runtime.result.LocalResultEventBus
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.DrawerData
import dev.etorix.panoscrobbler.charts.ChartsLegendDialog
import dev.etorix.panoscrobbler.charts.CollageGeneratorDialog
import dev.etorix.panoscrobbler.charts.DateDialog
import dev.etorix.panoscrobbler.charts.DateRangeDialog
import dev.etorix.panoscrobbler.charts.HiddenTagsDialog
import dev.etorix.panoscrobbler.charts.TimeDialog
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

fun EntryProviderScope<PanoRoute>.panoModalNavGraph(
    navigate: (PanoRoute) -> Unit,
    goBack: () -> Unit,
    mainViewModel: MainViewModel,
) {
    modalEntry<PanoRoute.Modal.NavPopup> { route ->
        val user by if (route.otherUser != null)
            remember { mutableStateOf(route.otherUser) }
        else
            PlatformStuff.mainPrefs.data
                .collectAsStateWithInitialValue { it.currentAccount?.user }

        val currentUser = user

        NavPopupDialog(
            user = currentUser ?: return@modalEntry,
            initialDrawerData = mainViewModel.drawerDataMap.getOrElse(currentUser) { DrawerData(0) },
            drawSnowfall = mainViewModel.isItChristmas,
            onSetDrawerData = {
                mainViewModel.drawerDataMap[currentUser] = it
            },
            onNavigate = navigate,
            modifier = Modifier.fillMaxWidth()
        )
    }

    modalEntry<PanoRoute.Modal.Changelog> { route ->
        ChangelogDialog(
            text = route.text,
            modifier = Modifier.navModal()
        )
    }

    modalEntry<PanoRoute.Modal.ChartsLegend> {
        ChartsLegendDialog(
            modifier = Modifier.navModal()
        )
    }

    modalEntry<PanoRoute.Modal.UpdateAvailable> { route ->
        UpdateAvailableDialog(
            updateAction = route.updateAction,
            modifier = Modifier.navModal()
        )
    }

    modalEntry<PanoRoute.Modal.HiddenTags> {
        HiddenTagsDialog(
            modifier = Modifier.navModal()
        )
    }

    modalEntry<PanoRoute.Modal.CollageGenerator> { route ->
        CollageGeneratorDialog(
            collageType = route.collageType,
            timePeriod = route.timePeriod,
            user = route.user,
            modifier = Modifier.navModal()
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
            modifier = Modifier.navModal(padding = false)
        )
    }

    modalEntry<PanoRoute.Modal.TagInfo> { route ->
        val scrollState = rememberScrollState()
        TagInfoDialog(
            tag = route.tag,
            scrollState = scrollState,
            modifier = Modifier.navModal()
        )
    }

    modalEntry<PanoRoute.Modal.ShowLink> { route ->
        ShowLinkDialog(
            url = route.url,
            modifier = Modifier.navModal(),
        )
    }

    modalEntry<PanoRoute.Modal.MediaSearchPref> {
        MediaSearchPrefDialog(
            modifier = Modifier.navModal(),
        )
    }

    modalEntry<PanoRoute.Modal.ProxyPref> {
        ProxyPrefDialog(
            modifier = Modifier.navModal(),
        )
    }

    modalEntry<PanoRoute.Modal.BlockedMetadataAdd> { route ->
        BlockedMetadataAddDialog(
            blockedMetadata = route.blockedMetadata,
            ignoredArtist = route.ignoredArtist,
            hash = route.hash,
            onDismiss = goBack,
            modifier = Modifier.navModal()
        )
    }

    modalEntry<PanoRoute.Modal.EditScrobble> { route ->
        SimpleEditsAddScreen(
            simpleEdit = SimpleEdit(
                track = route.scrobbleData.track,
                artist = route.scrobbleData.artist,
                album = route.scrobbleData.album.orEmpty(),
                albumArtist = route.scrobbleData.albumArtist.orEmpty(),
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
            msid = route.msid,
            hash = route.hash,
            key = route.key,
            onDone = {
                goBack()
            },
            onReauthenticate = {
                goBack()
                navigate(LoginDestinations.route(AccountType.LASTFM))
            },
            // this viewmodel should be scoped to the main viewmodel store owner
            viewModel = mainViewModel,
            modifier = Modifier.navModal()
        )
    }

    modalEntry<PanoRoute.Modal.TimePicker> { route ->
        val resultBus = LocalResultEventBus.current

        TimeDialog(
            h = route.initialHour,
            m = route.initialMinute,
            onTimeSelected = {
                resultBus.sendResult(it)
                goBack()
            },
            modifier = Modifier
                .fillMaxWidth()
        )
    }

    modalEntry<PanoRoute.Modal.DateRangePicker> { route ->
        val resultBus = LocalResultEventBus.current

        DateRangeDialog(
            selectedDateRange = route.selectedDateRange,
            allowedRange = route.allowedRange,
            onDateRangeSelected = {
                resultBus.sendResult(it)
                goBack()
            },
            modifier = Modifier
                .fillMaxWidth()
        )
    }

    modalEntry<PanoRoute.Modal.DatePicker> { route ->
        val resultBus = LocalResultEventBus.current

        DateDialog(
            selectedDate = route.selectedDate,
            allowedRange = route.allowedRange,
            weeksOnly = route.weeksOnly,
            onDateSelected = {
                resultBus.sendResult(it)
                goBack()
            },
            modifier = Modifier
                .fillMaxWidth()
        )
    }
}


@Composable
fun Modifier.navModal(
    padding: Boolean = true,
    scrollState: ScrollState = rememberScrollState()
) = fillMaxWidth()
    .then(
        if (padding)
            Modifier.padding(horizontal = 24.dp)
        else
            Modifier
    )
    .padding(bottom = verticalOverscanPadding())
    .verticalScroll(scrollState)

inline fun <reified K : PanoRoute.Modal> EntryProviderScope<PanoRoute>.modalEntry(
    noinline content: @Composable (K) -> Unit,
) {
    entry<K>(
        metadata = BottomSheetSceneStrategy.bottomSheet(),
        content = content
    )
}
