package dev.etorix.panoscrobbler.recents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.Track
import dev.etorix.panoscrobbler.charts.DatePickerModal
import dev.etorix.panoscrobbler.charts.TimePeriodType
import dev.etorix.panoscrobbler.charts.TimePeriodsGenerator
import dev.etorix.panoscrobbler.charts.getPeriodTypeIcon
import dev.etorix.panoscrobbler.charts.getPeriodTypePluralRes
import dev.etorix.panoscrobbler.db.PendingScrobble
import dev.etorix.panoscrobbler.edits.EditScrobbleUtils
import dev.etorix.panoscrobbler.icons.ArrowDropDown
import dev.etorix.panoscrobbler.icons.Casino
import dev.etorix.panoscrobbler.icons.Favorite
import dev.etorix.panoscrobbler.icons.History
import dev.etorix.panoscrobbler.icons.HourglassEmpty
import dev.etorix.panoscrobbler.icons.Icons
import dev.etorix.panoscrobbler.icons.Refresh
import dev.etorix.panoscrobbler.main.PanoPullToRefresh
import dev.etorix.panoscrobbler.main.ScrobblerState
import dev.etorix.panoscrobbler.media.PlayingTrackNotifyEvent
import dev.etorix.panoscrobbler.media.globalTrackEventFlow
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.ui.DismissableNotice
import dev.etorix.panoscrobbler.ui.EmptyText
import dev.etorix.panoscrobbler.ui.PanoLazyColumn
import dev.etorix.panoscrobbler.ui.PanoPullToRefreshStateForTab
import dev.etorix.panoscrobbler.ui.combineImageVectors
import dev.etorix.panoscrobbler.utils.PanoTimeFormatter
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import dev.etorix.panoscrobbler.utils.Stuff.collectAsStateWithInitialValue
import dev.etorix.panoscrobbler.utils.Stuff.format
import dev.etorix.panoscrobbler.utils.Stuff.timeToLocal
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.also_available_on
import pano_scrobbler.composeapp.generated.resources.charts_custom
import pano_scrobbler.composeapp.generated.resources.desktop
import pano_scrobbler.composeapp.generated.resources.loved
import pano_scrobbler.composeapp.generated.resources.no_scrobbles
import pano_scrobbler.composeapp.generated.resources.not_running
import pano_scrobbler.composeapp.generated.resources.pending_scrobbles
import pano_scrobbler.composeapp.generated.resources.random_text
import pano_scrobbler.composeapp.generated.resources.recents
import pano_scrobbler.composeapp.generated.resources.reload
import pano_scrobbler.composeapp.generated.resources.scrobbler_off
import pano_scrobbler.composeapp.generated.resources.scrobbles
import pano_scrobbler.composeapp.generated.resources.time_jump
import kotlin.math.abs

private enum class ScrobblesType {
    RECENTS,
    LOVED,
    TIME_JUMP,
}

private val AUTO_REFRESH_RETRY_DELAYS_MS = listOf(3_000L, 6_000L, 6_000L)
private const val SCROBBLE_REFRESH_MATCH_WINDOW_MS = 2_000L

@Composable
fun ScrobblesScreen(
    user: UserCached,
    showChips: Boolean,
    pullToRefreshState: PullToRefreshState,
    onSetRefreshing: (PanoPullToRefreshStateForTab) -> Unit,
    pullToRefreshTriggered: () -> Flow<Unit>,
    onNavigate: (PanoRoute) -> Unit,
    onTitleChange: (String) -> Unit,
    editDataFlow: Flow<EditScrobbleUtils.EditData>,
    scrobblerStateFlow: StateFlow<ScrobblerState>,
    updateScrobblerState: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScrobblesVM = viewModel(key = user.key<ScrobblesVM>()) { ScrobblesVM(user, null) },
) {
    val listState = rememberLazyListState()
    var selectedType by rememberSaveable { mutableStateOf(ScrobblesType.RECENTS) }
    var timeJumpMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    val currentTracks by rememberUpdatedState(tracks)
    val currentViewModel by rememberUpdatedState(viewModel)
    val pendingScrobblesWithCount by
    if (user.isSelf)
        viewModel.pendingScrobblesWithCount.collectAsStateWithLifecycle()
    else
        remember { mutableStateOf(emptyList<PendingScrobble>() to 0) }
    val (pendingScrobbles, pendingScrobblesCount) = pendingScrobblesWithCount
    val total by viewModel.total.collectAsStateWithLifecycle()
    val pkgMap by viewModel.pkgMap.collectAsStateWithLifecycle()
    val scrobblerState by scrobblerStateFlow.collectAsStateWithLifecycle()
    val showScrobbleSources by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.showScrobbleSources
    }
    val submitNowPlaying by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue {
        it.submitNowPlaying
    }
    val currentSubmitNowPlaying by rememberUpdatedState(submitNowPlaying)
    val accountType by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.currentAccountType }
    val otherPlatformsLearnt by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.desktopAppLearnt }
    var pendingScrobblesExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var topTrackKeyBeforeRefresh by remember { mutableStateOf<String?>(null) }
    var shouldAutoScrollToUpdatedTopTrack by remember { mutableStateOf(false) }
    val pendingScrobblesHeader =
        stringResource(Res.string.pending_scrobbles) + ": " + pendingScrobblesCount
    val canLove = accountType != AccountType.PLEROMA
    val density = LocalDensity.current
    val listViewportHeight = remember {
        derivedStateOf {
            with(density) {
                listState.layoutInfo.viewportSize.height.toDp()
            }
        }
    }
    val animateListItemContentSize = remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount > listState.layoutInfo.visibleItemsInfo.size
        }
    }
    val scope = rememberCoroutineScope()

    val canEditOrDelete by remember(selectedType, accountType) {
        mutableStateOf(
            !PlatformStuff.isTv &&
                    selectedType != ScrobblesType.LOVED &&
                    accountType !in arrayOf(AccountType.FILE, AccountType.PLEROMA)
        )
    }


    fun onTrackClick(track: Track, appId: String?) {
        onNavigate(PanoRoute.Modal.MusicEntryInfo(user = user, track = track, appId = appId))
    }

    fun currentTopTrackItem() =
        currentTracks.itemSnapshotList.items.firstOrNull() as? TrackWrapper.TrackItem

    fun topTrackListIndex(): Int {
        var index = 0

        if (user.isSelf) {
            when (scrobblerState) {
                ScrobblerState.Disabled,
                ScrobblerState.NLSDisabled,
                is ScrobblerState.Killed,
                    -> index += 1

                ScrobblerState.Unknown,
                ScrobblerState.Running,
                    -> {
                    if (!otherPlatformsLearnt && canEditOrDelete &&
                        !PlatformStuff.isTv && !PlatformStuff.isDesktop
                    ) {
                        index += 1
                    }
                }
            }
        }

        if (selectedType == ScrobblesType.RECENTS && user.isSelf && pendingScrobbles.isNotEmpty()) {
            index += 1 + pendingScrobbles.size + 1
        }

        return index
    }

    LaunchedEffect(user, selectedType, timeJumpMillis, total) {
        when (selectedType) {
            ScrobblesType.LOVED -> {
                viewModel.setScrobblesInput(
                    ScrobblesInput(
                        showScrobbleSources = showScrobbleSources,
                        loadLoved = true,
                    )
                )

                onTitleChange(
                    getString(Res.string.loved) +
                            if (total != null)
                                ": " + total!!.format()
                            else
                                ""
                )
                expandedKey = null
            }

            ScrobblesType.TIME_JUMP -> {
                if (timeJumpMillis != null)
                    viewModel.setScrobblesInput(
                        ScrobblesInput(
                            showScrobbleSources = showScrobbleSources,
                            timeJumpMillis = timeJumpMillis
                        )
                    )

                onTitleChange(getString(Res.string.time_jump))
                expandedKey = null
            }

            ScrobblesType.RECENTS -> {
                viewModel.setScrobblesInput(
                    ScrobblesInput(showScrobbleSources = showScrobbleSources)
                )

                onTitleChange(
                    getString(Res.string.scrobbles) +
                            if (total != null)
                                ": " + total!!.format()
                            else
                                ""
                )
            }
        }
    }

    LaunchedEffect(user) {
        if (user.isSelf && selectedType == ScrobblesType.RECENTS) {
            currentTracks.refresh()
        }
    }

    LaunchedEffect(expandedKey) {
        if (expandedKey != null) {
            val expandedItem = listState.layoutInfo.visibleItemsInfo.find {
                it.key == expandedKey
            }

            listState.requestScrollToItem(expandedItem?.index ?: 0)
        }
    }

    LaunchedEffect(
        tracks.loadState.refresh,
        selectedType,
        scrobblerState,
        pendingScrobbles,
        otherPlatformsLearnt,
        canEditOrDelete,
    ) {
        if (selectedType != ScrobblesType.RECENTS) {
            topTrackKeyBeforeRefresh = null
            shouldAutoScrollToUpdatedTopTrack = false
            return@LaunchedEffect
        }

        when (tracks.loadState.refresh) {
            is LoadState.Loading -> {
                val topTrackKey = currentTopTrackItem()?.key
                val firstTrackIndex = topTrackListIndex()
                val isNearTopOfTrackList =
                    listState.firstVisibleItemIndex <= firstTrackIndex &&
                            listState.firstVisibleItemScrollOffset <= 32

                if (topTrackKey != null && isNearTopOfTrackList) {
                    topTrackKeyBeforeRefresh = topTrackKey
                    shouldAutoScrollToUpdatedTopTrack = true
                } else {
                    topTrackKeyBeforeRefresh = null
                    shouldAutoScrollToUpdatedTopTrack = false
                }
            }

            is LoadState.NotLoading -> {
                if (shouldAutoScrollToUpdatedTopTrack) {
                    val newTopTrackKey = currentTopTrackItem()?.key

                    if (topTrackKeyBeforeRefresh != null &&
                        newTopTrackKey != null &&
                        newTopTrackKey != topTrackKeyBeforeRefresh
                    ) {
                        listState.animateScrollToItem(topTrackListIndex())
                    }
                }

                topTrackKeyBeforeRefresh = null
                shouldAutoScrollToUpdatedTopTrack = false
            }

            is LoadState.Error -> {
            }
        }
    }

    if (user.isSelf) {
        LifecycleStartEffect(Unit) {
            currentViewModel.setForeground(true)
            onStopOrDispose {
                currentViewModel.setForeground(false)
            }
        }

        LaunchedEffect(pendingScrobblesExpanded) {
            currentViewModel.setPendingScrobblesExpanded(pendingScrobblesExpanded)
        }
    }

    LifecycleResumeEffect(tracks.loadState) {
        onSetRefreshing(
            if (tracks.loadState.refresh is LoadState.Loading) {
                PanoPullToRefreshStateForTab.Refreshing
            } else {
                PanoPullToRefreshStateForTab.NotRefreshing
            }
        )

        onPauseOrDispose {
            onSetRefreshing(PanoPullToRefreshStateForTab.Disabled)
        }
    }

    LaunchedEffect(Unit) {
        pullToRefreshTriggered().collect {
            if (currentTracks.loadState.refresh is LoadState.NotLoading) {
                currentTracks.refresh()
            }
        }
    }

    LaunchedEffect(user.isSelf, selectedType) {
        if (!user.isSelf || selectedType != ScrobblesType.RECENTS) {
            return@LaunchedEffect
        }

        fun nullableStringMatches(value: String?, candidates: Set<String?>): Boolean {
            return candidates.any { candidate ->
                value.orEmpty().equals(candidate.orEmpty(), ignoreCase = true)
            }
        }

        fun trackIdentityMatchesEvent(
            track: Track,
            event: PlayingTrackNotifyEvent.TrackPlaying,
        ): Boolean {
            val candidateArtists = setOf(
                event.scrobbleData.artist,
                event.origScrobbleData.artist,
            )
            val candidateTracks = setOf(
                event.scrobbleData.track,
                event.origScrobbleData.track,
            )
            val candidateAlbums = setOf(
                event.scrobbleData.album,
                event.origScrobbleData.album,
            )

            return nullableStringMatches(track.artist.name, candidateArtists) &&
                    nullableStringMatches(track.name, candidateTracks) &&
                    nullableStringMatches(track.album?.name, candidateAlbums)
        }

        fun topTrackMatchesEvent(event: PlayingTrackNotifyEvent.TrackPlaying): Boolean {
            val topTrack = currentTopTrackItem()?.track
            if (topTrack == null) return false

            return topTrack.isNowPlaying == event.nowPlaying &&
                    trackIdentityMatchesEvent(topTrack, event)
        }

        fun scrobbleEntryMatchesEvent(event: PlayingTrackNotifyEvent.TrackPlaying): Boolean {
            val timestamp = event.scrobbleData.timestamp
            val match = currentTracks.itemSnapshotList.items
                .filterIsInstance<TrackWrapper.TrackItem>()
                .firstOrNull { item ->
                    val track = item.track
                    val date = track.date ?: return@firstOrNull false
                    !track.isNowPlaying &&
                            abs(date - timestamp) <= SCROBBLE_REFRESH_MATCH_WINDOW_MS &&
                            trackIdentityMatchesEvent(track, event)
                }

            return match != null
        }

        fun topTrackUpdatedSince(topTrackKeyBeforeRefresh: String?): Boolean {
            val currentTopTrackKey = currentTopTrackItem()?.key

            return topTrackKeyBeforeRefresh != null &&
                    currentTopTrackKey != null &&
                    currentTopTrackKey != topTrackKeyBeforeRefresh
        }

        suspend fun awaitRefreshCompletion() {
            snapshotFlow { currentTracks.loadState.refresh }
                .dropWhile { it !is LoadState.Loading }
                .first { it !is LoadState.Loading }
        }

        var lastRefreshEventKey: String? = null
        var refreshJob: Job? = null

        globalTrackEventFlow
            .filterIsInstance<PlayingTrackNotifyEvent.TrackPlaying>()
            .collect {
                val refreshEventTimestamp =
                    if (it.nowPlaying) it.timelineStartTime else it.scrobbleData.timestamp
                val refreshEventKey =
                    "${it.hash}\n$refreshEventTimestamp\n${it.nowPlaying}"

                if (it.nowPlaying && !it.preprocessed) {
                    return@collect
                }

                if (it.nowPlaying && !currentSubmitNowPlaying) {
                    return@collect
                }

                if (lastRefreshEventKey == refreshEventKey) {
                    return@collect
                }

                lastRefreshEventKey = refreshEventKey

                refreshJob?.cancel()
                refreshJob = launch {
                    val topTrackKeyBeforeRefresh = currentTopTrackItem()?.key

                    if (!it.nowPlaying) {
                        AUTO_REFRESH_RETRY_DELAYS_MS.forEach { delayMs ->
                            if (scrobbleEntryMatchesEvent(it)) {
                                return@launch
                            }

                            delay(delayMs)

                            if (scrobbleEntryMatchesEvent(it)) {
                                return@launch
                            }

                            if (currentTracks.loadState.refresh is LoadState.NotLoading &&
                                !currentTracks.loadState.hasError
                            ) {
                                currentTracks.refresh()
                                awaitRefreshCompletion()

                                if (scrobbleEntryMatchesEvent(it)) {
                                    return@launch
                                }
                            }
                        }

                        return@launch
                    }

                    var completedRefresh = false
                    AUTO_REFRESH_RETRY_DELAYS_MS.forEach { delayMs ->
                        val topUpdated = topTrackUpdatedSince(topTrackKeyBeforeRefresh)
                        val topMatches = topTrackMatchesEvent(it)
                        if (topUpdated || (completedRefresh && topMatches)) {
                            return@launch
                        }

                        delay(delayMs)

                        val topUpdatedAfterDelay = topTrackUpdatedSince(topTrackKeyBeforeRefresh)
                        val topMatchesAfterDelay = topTrackMatchesEvent(it)
                        if (topUpdatedAfterDelay || (completedRefresh && topMatchesAfterDelay)) {
                            return@launch
                        }

                        if (currentTracks.loadState.refresh is LoadState.NotLoading &&
                            !currentTracks.loadState.hasError
                        ) {
                            currentTracks.refresh()
                            awaitRefreshCompletion()
                            completedRefresh = true

                            if (topTrackMatchesEvent(it) || topTrackUpdatedSince(topTrackKeyBeforeRefresh)) {
                                return@launch
                            }
                        }
                    }
                }
            }
    }

    OnEditEffect(
        viewModel,
        editDataFlow,
        onEdited = {
            if (currentTracks.loadState.refresh is LoadState.NotLoading &&
                !currentTracks.loadState.hasError
            ) {
                currentTracks.refresh()
            }
        }
    )

    PanoPullToRefresh(
        isRefreshing = tracks.loadState.refresh is LoadState.Loading,
        state = pullToRefreshState,
    ) {
        EmptyText(
            visible = tracks.loadState.refresh is LoadState.NotLoading &&
                    tracks.itemCount == 0 &&
                    pendingScrobbles.isEmpty(),
            text = stringResource(Res.string.no_scrobbles)
        )

        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top)
        ) {
            if (showChips) {
                ScrobblesTypeSelector(
                    selectedType = selectedType,
                    timeJumpMillis = timeJumpMillis,
                    registeredTime = user.registeredTime,
                    onTypeSelected = { type, timeJumpMillisp ->
                        when (type) {
                            ScrobblesType.RECENTS,
                            ScrobblesType.LOVED,
                                -> {
                                selectedType = type
                                timeJumpMillis = null
                            }

                            ScrobblesType.TIME_JUMP -> {
                                timeJumpMillis = timeJumpMillisp
                                selectedType = type
                            }
                        }

                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    onRefresh = {
                        if (tracks.loadState.refresh is LoadState.NotLoading) {
                            tracks.refresh()
                        }
                    },
                    onNavigateToRandom = {
                        onNavigate(PanoRoute.Random(user))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            PanoLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {

                if (user.isSelf) {
                    when (val scrobblerState = scrobblerState) {
                        ScrobblerState.Disabled, ScrobblerState.NLSDisabled -> {
                            item("notice") {
                                val innerScope = rememberCoroutineScope()
                                DismissableNotice(
                                    title = stringResource(Res.string.scrobbler_off),
                                    onClick = {
                                        updateScrobblerState()

                                        innerScope.launch {
                                            delay(500)
                                            if (scrobblerState == ScrobblerState.Disabled)
                                                onNavigate(PanoRoute.Prefs)
                                            else
                                                onNavigate(PanoRoute.Onboarding)

                                        }
                                    },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }

                        is ScrobblerState.Killed -> {
                            item("notice") {
                                DismissableNotice(
                                    title = stringResource(Res.string.not_running),
                                    onClick = { onNavigate(PanoRoute.Modal.FixIt(scrobblerState.reason)) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }

                        ScrobblerState.Unknown,
                        ScrobblerState.Running -> {
                            // todo remove canEditOrDelete
                            if (!otherPlatformsLearnt && canEditOrDelete && !PlatformStuff.isTv && !PlatformStuff.isDesktop) {
                                item("notice") {
                                    DismissableNotice(
                                        title = stringResource(
                                            Res.string.also_available_on,
                                            stringResource(Res.string.desktop)
                                        ),
                                        onClick = {
                                            onNavigate(PanoRoute.Modal.ShowLink(Stuff.HOMEPAGE_URL))
                                        },
                                        onDismiss = {
                                            scope.launch {
                                                PlatformStuff.mainPrefs.updateData {
                                                    it.copy(desktopAppLearnt = true)
                                                }
                                            }
                                        },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                }

                if (selectedType == ScrobblesType.RECENTS && user.isSelf) {
                    pendingScrobblesListItems(
                        headerText = pendingScrobblesHeader,
                        headerIcon = Icons.HourglassEmpty,
                        items = pendingScrobbles,
                        expanded = if (pendingScrobblesCount <= viewModel.pendingScrobblesPreviewCount)
                            null
                        else
                            pendingScrobblesExpanded,
                        onToggle = {
                            pendingScrobblesExpanded = it
                        },
                        showScrobbleSources = showScrobbleSources,
                        onItemClick = {
                            onTrackClick(it as Track, null)
                        },
                        viewModel = viewModel,
                    )

                    if (pendingScrobbles.isNotEmpty()) {
                        item("pending_divider") {
                            HorizontalDivider(
                                modifier = Modifier.animateItem().padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                scrobblesListItems(
                    tracks = tracks,
                    user = user,
                    pkgMap = pkgMap,
                    fetchAlbumImageIfMissing = selectedType == ScrobblesType.LOVED,
                    showScrobbleSources = showScrobbleSources,
                    canLove = canLove,
                    canEdit = canEditOrDelete,
                    canDelete = canEditOrDelete,
                    canHate = accountType == AccountType.LISTENBRAINZ,
                    expandedKey = { expandedKey },
                    onExpand = { expandedKey = it },
                    onNavigate = onNavigate,
                    animateListItemContentSize = animateListItemContentSize,
                    maxHeight = listViewportHeight,
                    viewModel = viewModel,
                )

                scrobblesPlaceholdersAndErrors(tracks = tracks)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ScrobblesTypeSelectorButton(
    type: ScrobblesType?,
    checked: Boolean,
    text: String,
    imageVector: ImageVector,
    onTypeSelected: (ScrobblesType?) -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        enableUserInput = !checked,
    ) {
        OutlinedToggleButton(
            checked = checked,
            onCheckedChange = {
                if (it)
                    onTypeSelected(type)
            },
            shapes = when {
                isFirst -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                isLast -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            },
        ) {
            if (checked) {
                Icon(imageVector, contentDescription = text)
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(text = text, maxLines = 1)
            } else {
                Icon(
                    imageVector,
                    contentDescription = text
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScrobblesTypeSelector(
    selectedType: ScrobblesType,
    timeJumpMillis: Long?,
    registeredTime: Long,
    onTypeSelected: (ScrobblesType, Long?) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToRandom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var timeJumpMenuShown by remember { mutableStateOf(false) }
    var datePickerShown by rememberSaveable { mutableStateOf(false) }
    val firstDayOfWeek by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.firstDayOfWeek }

    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(
            ButtonGroupDefaults.ConnectedSpaceBetween,
            Alignment.CenterHorizontally
        ),
    ) {
        if (PlatformStuff.isDesktop || PlatformStuff.isTv) {
            ScrobblesTypeSelectorButton(
                type = null,
                checked = false,
                text = stringResource(Res.string.reload),
                imageVector = Icons.Refresh,
                onTypeSelected = {
                    onRefresh()
                },
                isFirst = true
            )
        }

        ScrobblesTypeSelectorButton(
            type = ScrobblesType.RECENTS,
            checked = selectedType == ScrobblesType.RECENTS,
            text = stringResource(Res.string.recents),
            imageVector = Icons.History,
            onTypeSelected = {
                if (it != null)
                    onTypeSelected(it, null)
            },
            isFirst = !(PlatformStuff.isDesktop || PlatformStuff.isTv)
        )
        ScrobblesTypeSelectorButton(
            type = ScrobblesType.LOVED,
            checked = selectedType == ScrobblesType.LOVED,
            text = stringResource(Res.string.loved),
            imageVector = Icons.Favorite,
            onTypeSelected = {
                if (it != null)
                    onTypeSelected(it, null)
            }
        )
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(stringResource(Res.string.time_jump)) } },
            state = rememberTooltipState(),
            enableUserInput = selectedType != ScrobblesType.TIME_JUMP,
        ) {
            OutlinedToggleButton(
                checked = selectedType == ScrobblesType.TIME_JUMP || timeJumpMenuShown,
                onCheckedChange = {
                    if ((it && selectedType != ScrobblesType.TIME_JUMP) ||
                        (!it && selectedType == ScrobblesType.TIME_JUMP)
                    )
                        timeJumpMenuShown = true
                },
                shapes = ButtonGroupDefaults.connectedMiddleButtonShapes()
            ) {
                val painter = combineImageVectors(
                    getPeriodTypeIcon(TimePeriodType.CUSTOM),
                    Icons.ArrowDropDown
                )

                if (selectedType == ScrobblesType.TIME_JUMP) {
                    Icon(painter, contentDescription = stringResource(Res.string.time_jump))

                    Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                    Text(
                        if (timeJumpMillis == null)
                            stringResource(Res.string.time_jump)
                        else
                            PanoTimeFormatter.relative(timeJumpMillis, null),
                        maxLines = 1
                    )
                } else {
                    Icon(painter, contentDescription = stringResource(Res.string.time_jump))
                }
            }

            DropdownMenu(
                expanded = timeJumpMenuShown,
                onDismissRequest = { timeJumpMenuShown = false },
            ) {
                val timeJumpEntries = remember(registeredTime, timeJumpMillis) {
                    TimePeriodsGenerator(
                        registeredTime,
                        timeJumpMillis ?: System.currentTimeMillis(),
                        firstDayOfWeek
                    ).recentsTimeJumps
                }

                timeJumpEntries.forEach {
                    DropdownMenuItem(
                        onClick = {
                            onTypeSelected(ScrobblesType.TIME_JUMP, it.timeMillis)
                            timeJumpMenuShown = false
                        },
                        leadingIcon = {
                            Icon(getPeriodTypeIcon(it.type), contentDescription = null)
                        },
                        text = {
                            val name = pluralStringResource(
                                getPeriodTypePluralRes(it.type),
                                1,
                                (if (it.addsTime) "+1" else "-1")
                            )
                            Text(text = name)
                        }
                    )
                }
                DropdownMenuItem(
                    onClick = {
                        datePickerShown = true
                        timeJumpMenuShown = false
                    },
                    leadingIcon = {
                        Icon(getPeriodTypeIcon(TimePeriodType.CUSTOM), contentDescription = null)
                    },
                    text = {
                        Text(text = stringResource(Res.string.charts_custom))
                    }
                )
            }
        }

        ScrobblesTypeSelectorButton(
            type = null,
            checked = false,
            text = stringResource(Res.string.random_text),
            imageVector = Icons.Casino,
            onTypeSelected = {
                onNavigateToRandom()
            },
            isLast = true
        )
    }

    if (datePickerShown) {
        DatePickerModal(
            selectedDate = timeJumpMillis,
            allowedRange = Pair(registeredTime, System.currentTimeMillis()),
            onDateSelected = {
                onTypeSelected(
                    ScrobblesType.TIME_JUMP,
                    it?.timeToLocal()?.plus((24 * 60 * 60 - 1) * 1000)
                )
            },
            onDismiss = { datePickerShown = false },
        )
    }
}
