package dev.etorix.panoscrobbler.navigation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.result.LocalResultEventBus
import dev.etorix.panoscrobbler.api.AccountType
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
import dev.etorix.panoscrobbler.ui.navModal
import dev.etorix.panoscrobbler.ui.verticalOverscanPadding
import dev.etorix.panoscrobbler.updates.ChangelogDialog
import dev.etorix.panoscrobbler.updates.UpdateAvailableDialog
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.edit

fun EntryProviderScope<PanoRoute>.panoModalNavGraph(
    onSetTitle: (PanoRoute, String) -> Unit,
    navigate: (PanoRoute) -> Unit,
    goBack: () -> Unit,
    onExpandModal: (PanoRoute.Modal.CanExpand) -> Unit,
    mainViewModel: MainViewModel,
) {
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
            onExpand = { onExpandModal(route) },
            modifier = Modifier.navModal(scrollState, expanded = route.isExpanded, sides = false)
        )
    }

    modalEntry<PanoRoute.Modal.TagInfo> { route ->
        if (route.isExpanded)
            onSetTitle(route, route.tag.name)

        val scrollState = rememberScrollState()
        TagInfoDialog(
            tag = route.tag,
            isExpanded = route.isExpanded,
            onExpand = { onExpandModal(route) },
            scrollState = scrollState,
            modifier = Modifier.navModal(scrollState, expanded = route.isExpanded)
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = verticalOverscanPadding())
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
        if (route.isExpanded)
            onSetTitle(route, stringResource(Res.string.edit))

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
            isExpanded = route.isExpanded,
            onExpand = { onExpandModal(route) },
            // this viewmodel should be scoped to the main viewmodel store owner
            viewModel = mainViewModel,
            modifier = Modifier.navModal(expanded = route.isExpanded)
        )
    }

    entry<PanoRoute.Modal.TimePicker>(
        metadata = BottomSheetSceneStrategy.bottomSheetNoGestures()
    ) { route ->
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

    entry<PanoRoute.Modal.DateRangePicker>(
        metadata = BottomSheetSceneStrategy.bottomSheetNoGestures()
    ) { route ->
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

    entry<PanoRoute.Modal.DatePicker>(
        metadata = BottomSheetSceneStrategy.bottomSheetNoGestures()
    ) { route ->
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

inline fun <reified K : PanoRoute.Modal> EntryProviderScope<PanoRoute>.modalEntry(
    noinline content: @Composable (K) -> Unit,
) {
    entry<K>(
        metadata = { route ->
            if (route.isModal()) {
                BottomSheetSceneStrategy.bottomSheet()
            } else {
                emptyMap()
            }
        },
        clazzContentKey = { route ->
            if (route is PanoRoute.Modal.CanExpand) {
                route.copyExpanded().toString()
            } else {
                route.toString()
            }
        },
        content = content
    )
}
