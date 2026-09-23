package dev.etorix.panoscrobbler.recents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.result.ResultEffect
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.Track
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
import dev.etorix.panoscrobbler.icons.Today
import dev.etorix.panoscrobbler.main.PanoPullToRefresh
import dev.etorix.panoscrobbler.main.ScrobblerState
import dev.etorix.panoscrobbler.media.PlayingTrackNotifyEvent
import dev.etorix.panoscrobbler.media.globalTrackEventFlow
import dev.etorix.panoscrobbler.navigation.DatePickerResult
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.navigation.PanoTab
import dev.etorix.panoscrobbler.navigation.PanoTab.Scrobbles.ScrobblesType
import dev.etorix.panoscrobbler.navigation.PullToRefreshResult
import dev.etorix.panoscrobbler.navigation.SubTabClickedResult
import dev.etorix.panoscrobbler.ui.DismissableNotice
import dev.etorix.panoscrobbler.ui.PanoDropdownMenu
import dev.etorix.panoscrobbler.ui.PanoLazyColumn
import dev.etorix.panoscrobbler.ui.PanoPullToRefreshStateForTab
import dev.etorix.panoscrobbler.ui.emptyText
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
import pano_scrobbler.composeapp.generated.resources.recents
import pano_scrobbler.composeapp.generated.resources.scrobbler_off
import pano_scrobbler.composeapp.generated.resources.scrobbles
import pano_scrobbler.composeapp.generated.resources.time_jump
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds


private val AUTO_REFRESH_RETRY_DELAYS_MS = listOf(3_000L, 6_000L, 6_000L)
private const val SCROBBLE_REFRESH_MATCH_WINDOW_MS = 2_000L

@Composable
fun ScrobblesScreen(
    user: UserCached,
    pullToRefreshState: PullToRefreshState,
    onSetRefreshing: (PanoPullToRefreshStateForTab) -> Unit,
    onNavigate: (PanoRoute) -> Unit,
    onTitleChange: (String) -> Unit,
    editDataFlow: Flow<EditScrobbleUtils.EditData>,
    scrobblerStateFlow: StateFlow<ScrobblerState>,
    updateScrobblerState: () -> Unit,
    selectSubTabId: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScrobblesVM = viewModel(key = user.key<ScrobblesVM>()) { ScrobblesVM(user, null) },
) {
    val listState = rememberLazyListState()
    var selectedType by rememberSaveable { mutableStateOf(PanoTab.Scrobbles.ScrobblesType.RECENTS) }
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
    val pendingScrobbleLastErrored by if (user.isSelf)
        viewModel.pendingScrobbleLastErrored.collectAsStateWithLifecycle()
    else
        remember { mutableStateOf(null) }
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
    var scrollToTopOnLoad by rememberSaveable { mutableStateOf(true) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lastHandledExpandedKey by rememberSaveable { mutableStateOf(expandedKey) }
    val autoExpandNowPlaying by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.autoExpandNowPlaying }
    var timeJumpMenuShown by rememberSaveable { mutableStateOf(false) }
    var topTrackKeyBeforeRefresh by remember { mutableStateOf<String?>(null) }
    var shouldAutoScrollToUpdatedTopTrack by remember { mutableStateOf(false) }
    val pendingScrobblesHeader =
        stringResource(Res.string.pending_scrobbles) + ": " + pendingScrobblesCount
    val canLove = accountType != AccountType.PLEROMA

    val scope = rememberCoroutineScope()

    val canEditOrDelete by remember(selectedType, accountType) {
        mutableStateOf(
            !PlatformStuff.isTv &&
                    selectedType != PanoTab.Scrobbles.ScrobblesType.LOVED &&
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

    LaunchedEffect(user, selectedType, timeJumpMillis, total, timeJumpMenuShown) {
        if (timeJumpMenuShown) {
            onTitleChange(getString(Res.string.time_jump))
            selectSubTabId(ScrobblesType.TIME_JUMP.ordinal)
            return@LaunchedEffect
        }

        when (selectedType) {
            PanoTab.Scrobbles.ScrobblesType.LOVED -> {
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
                selectSubTabId(selectedType.ordinal)
            }

            PanoTab.Scrobbles.ScrobblesType.TIME_JUMP -> {
                val timeJumpMillis = timeJumpMillis

                if (timeJumpMillis != null) {
                    viewModel.setScrobblesInput(
                        ScrobblesInput(
                            showScrobbleSources = showScrobbleSources,
                            timeJumpMillis = timeJumpMillis
                        )
                    )

                    onTitleChange(PanoTimeFormatter.day(timeJumpMillis))
                } else
                    onTitleChange(getString(Res.string.time_jump))
                expandedKey = null
                selectSubTabId(selectedType.ordinal)
            }

            PanoTab.Scrobbles.ScrobblesType.RECENTS -> {
                viewModel.setScrobblesInput(
                    ScrobblesInput(showScrobbleSources = showScrobbleSources)
                )

                if (total != null)
                    onTitleChange(getString(Res.string.scrobbles) + ": " + total!!.format())
                else
                    onTitleChange(getString(Res.string.recents))
                selectSubTabId(selectedType.ordinal)
            }

            else -> {}
        }
    }

    LaunchedEffect(user) {
        if (user.isSelf && selectedType == ScrobblesType.RECENTS) {
            currentTracks.refresh()
        }
    }

    LaunchedEffect(expandedKey) {
        if (expandedKey != lastHandledExpandedKey) {
            lastHandledExpandedKey = expandedKey
            if (expandedKey != null) {
                val expandedItem = listState.layoutInfo.visibleItemsInfo.find {
                    it.key == expandedKey
                }

                listState.animateScrollToItem(expandedItem?.index ?: 0)
            }
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

        // expand now playing
        if (tracks.loadState.refresh is LoadState.NotLoading && autoExpandNowPlaying && tracks.itemCount > 0 &&
            (tracks.peek(0) as? TrackWrapper.TrackItem)?.track?.isNowPlaying == true
        ) {
            val newKey = tracks.peek(0)?.key

            val newExpandedItemIsVisible = listState.layoutInfo.visibleItemsInfo.find {
                it.key == newKey
            } != null

            val isAlmostAtTop = listState.firstVisibleItemIndex < 5

            if (isAlmostAtTop || newExpandedItemIsVisible)
                expandedKey = newKey
        }

        if (tracks.loadState.isIdle && scrollToTopOnLoad) {
            scrollToTopOnLoad = false
            scope.launch {
                listState.animateScrollToItem(0)
            }
        }

        onPauseOrDispose {
            onSetRefreshing(PanoPullToRefreshStateForTab.Disabled)
        }
    }

    ResultEffect<PullToRefreshResult> {
        if (it.tab == PanoTab.Scrobbles || it.tab == PanoTab.ScrobblesNoSubtabs) {
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

    ResultEffect<SubTabClickedResult> { res ->
        when (res.id) {
            PanoTab.Scrobbles.ScrobblesType.REFRESH.ordinal -> {
                if (tracks.loadState.refresh is LoadState.NotLoading) {
                    tracks.refresh()
                    scrollToTopOnLoad = true
                }
            }

            PanoTab.Scrobbles.ScrobblesType.RECENTS.ordinal -> {
                val reselected = selectedType == ScrobblesType.RECENTS && timeJumpMillis == null
                selectedType = PanoTab.Scrobbles.ScrobblesType.RECENTS
                timeJumpMillis = null
                if (reselected && tracks.loadState.isIdle) {
                    scrollToTopOnLoad = false
                    scope.launch { listState.animateScrollToItem(0) }
                } else {
                    scrollToTopOnLoad = true
                }
            }

            PanoTab.Scrobbles.ScrobblesType.TIME_JUMP.ordinal -> {
                timeJumpMenuShown = true
            }

            PanoTab.Scrobbles.ScrobblesType.LOVED.ordinal -> {
                val reselected = selectedType == ScrobblesType.LOVED
                selectedType = PanoTab.Scrobbles.ScrobblesType.LOVED
                timeJumpMillis = null
                if (reselected && tracks.loadState.isIdle) {
                    scrollToTopOnLoad = false
                    scope.launch { listState.animateScrollToItem(0) }
                } else {
                    scrollToTopOnLoad = true
                }
            }

            PanoTab.Scrobbles.ScrobblesType.RANDOM.ordinal -> {
                onNavigate(PanoRoute.Random(user))
            }
        }
    }

    ResultEffect<DatePickerResult> { res ->
        selectedType = PanoTab.Scrobbles.ScrobblesType.TIME_JUMP
        timeJumpMillis = res.timeUtc.timeToLocal().plus((24 * 60 * 60 - 1) * 1000)
        scrollToTopOnLoad = true
    }

    PanoPullToRefresh(
        isRefreshing = tracks.loadState.refresh is LoadState.Loading,
        state = pullToRefreshState,
    ) {
        if (timeJumpMenuShown) {
            Box(
                modifier = Modifier
                    .align { size, space, direction ->
                        val factor = if (direction == LayoutDirection.Ltr) 1 else (5 - 1)
                        val x = space.width * factor / 5
                        val y = 0
                        IntOffset(x, y)
                    }
            ) {
                TimeJumpMenu(
                    timeJumpMillis = timeJumpMillis,
                    registeredTime = user.registeredTime,
                    onNavigate = onNavigate,
                    onDismiss = { timeJumpMenuShown = false },
                    onTimeJumpSelected = {
                        selectedType = if (it == null)
                            ScrobblesType.RECENTS
                        else
                            ScrobblesType.TIME_JUMP
                        timeJumpMillis = it
                        scrollToTopOnLoad = true
                    },
                )
            }
        }

        BoxWithConstraints(modifier = modifier) {
            val isLandscape = maxWidth * 0.7f > maxHeight
            val listMaxHeight = maxHeight

            PanoLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                if (tracks.loadState.refresh is LoadState.NotLoading &&
                    tracks.itemCount == 0 &&
                    pendingScrobbles.isEmpty()
                )
                    emptyText { stringResource(Res.string.no_scrobbles) }

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
                                            delay(500.milliseconds)
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
                                    title = stringResource(Res.string.not_running) + ": " +
                                            scrobblerState.reason?.shortText().orEmpty(),
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

                if (selectedType == PanoTab.Scrobbles.ScrobblesType.RECENTS && user.isSelf) {
                    pendingScrobblesListItems(
                        headerText = pendingScrobblesHeader,
                        headerIcon = Icons.HourglassEmpty,
                        items = pendingScrobbles,
                        lastErrored = pendingScrobbleLastErrored,
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
                                modifier = Modifier
                                    .animateItem()
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                scrobblesListItems(
                    tracks = tracks,
                    user = user,
                    pkgMap = pkgMap,
                    fetchAlbumImageIfMissing = selectedType == PanoTab.Scrobbles.ScrobblesType.LOVED,
                    showScrobbleSources = showScrobbleSources,
                    canLove = canLove,
                    canEdit = canEditOrDelete,
                    canDelete = canEditOrDelete,
                    canHate = accountType == AccountType.LISTENBRAINZ,
                    expandedKey = { expandedKey },
                    onExpand = { key, isNowPlaying ->
                        if (isNowPlaying) {
                            val updatedAutoExpandNowPlaying = key != null
                            if (updatedAutoExpandNowPlaying != autoExpandNowPlaying) {
                                scope.launch {
                                    PlatformStuff.mainPrefs.updateData {
                                        it.copy(
                                            autoExpandNowPlaying = updatedAutoExpandNowPlaying
                                        )
                                    }
                                }
                            }
                        }

                        expandedKey = key
                    },
                    onNavigate = onNavigate,
                    isLandscape = { isLandscape },
                    maxHeight = { listMaxHeight },
                    viewModel = viewModel,
                )

                scrobblesPlaceholdersAndErrors(tracks = tracks)
            }
        }
    }
}

@Composable
private fun TimeJumpMenu(
    timeJumpMillis: Long?,
    registeredTime: Long,
    onDismiss: () -> Unit,
    onTimeJumpSelected: (Long?) -> Unit,
    onNavigate: (PanoRoute) -> Unit,
) {
    val firstDayOfWeek by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.firstDayOfWeek }

    val timeJumpEntries = remember(registeredTime, timeJumpMillis) {
        TimePeriodsGenerator(
            registeredTime,
            timeJumpMillis ?: System.currentTimeMillis(),
            firstDayOfWeek
        ).recentsTimeJumps
    }

    PanoDropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
    ) {
        item(
            enabled = timeJumpMillis != null,
            onClick = {
                onTimeJumpSelected(null)
                onDismiss()
            },
            leadingIcon = {
                Icon(Icons.Today, contentDescription = null)
            },
            text = {
                Text(text = stringResource(Res.string.recents))
            }
        )

        val theirDropdownMenuItemDefaultMinWidth = 112.dp
        val myMinWidth = (theirDropdownMenuItemDefaultMinWidth / 2) - DividerDefaults.Thickness

        timeJumpEntries.forEach {
            custom {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(IntrinsicSize.Min)
                ) {
                    DropdownMenuItem(
                        enabled = it.minus1 != null,
                        shape = MenuDefaults.middleItemShape,
                        onClick = {
                            onTimeJumpSelected(it.minus1)
                            onDismiss()
                        },
                        text = {
                            Text(
                                text = pluralStringResource(
                                    getPeriodTypePluralRes(it.type),
                                    -1,
                                    "-1"
                                )
                            )
                        },
                        modifier = Modifier
                            .requiredWidthIn(min = myMinWidth)
                            .weight(1f)
                    )

                    VerticalDivider(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                    )

                    DropdownMenuItem(
                        enabled = it.plus1 != null,
                        shape = MenuDefaults.middleItemShape,
                        onClick = {
                            onTimeJumpSelected(it.plus1)
                            onDismiss()
                        },
                        text = {
                            Text(
                                text = pluralStringResource(
                                    getPeriodTypePluralRes(it.type),
                                    1,
                                    "+1"
                                )
                            )
                        },
                        modifier = Modifier
                            .requiredWidthIn(min = myMinWidth)
                            .weight(1f)
                    )
                }
            }
//            SplitButtonLayout(
//                leadingButton = {
//                    SplitButtonDefaults.OutlinedLeadingButton(
//                        enabled = it.minus1 != null,
//                        onClick = {
//                            onTimeJumpSelected(it.minus1)
//                            onDismiss()
//                        },
//                    ) {
//                        Text(
//                            text = pluralStringResource(
//                                getPeriodTypePluralRes(it.type),
//                                -1,
//                                "-1"
//                            )
//                        )
//                    }
//                },
//                trailingButton = {
//                    SplitButtonDefaults.OutlinedTrailingButton(
//                        enabled = it.plus1 != null,
//                        onCheckedChange = { _ ->
//                            onTimeJumpSelected(it.plus1)
//                            onDismiss()
//                        },
//                        checked = false,
//                    ) {
//                        Text(
//                            text = pluralStringResource(
//                                getPeriodTypePluralRes(it.type),
//                                1,
//                                "+1"
//                            )
//                        )
//                    }
//                },
//                modifier = Modifier
//                    .padding(horizontal = 8.dp)
//                    .align(Alignment.CenterHorizontally)
//            )
        }

        item(
            onClick = {
                val route = PanoRoute.Modal.DatePicker(
                    selectedDate = timeJumpMillis,
                    allowedRange = registeredTime to System.currentTimeMillis(),
                    weeksOnly = false,
                )
                onNavigate(route)
                onDismiss()
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
