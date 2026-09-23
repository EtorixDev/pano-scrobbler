package dev.etorix.panoscrobbler.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.etorix.panoscrobbler.api.UserCached
import dev.etorix.panoscrobbler.api.lastfm.LastfmPeriod
import dev.etorix.panoscrobbler.charts.ChartsOverviewScreen
import dev.etorix.panoscrobbler.friends.FriendsScreen
import dev.etorix.panoscrobbler.navigation.PanoRoute
import dev.etorix.panoscrobbler.navigation.PanoTab
import dev.etorix.panoscrobbler.recents.ScrobblesScreen
import dev.etorix.panoscrobbler.ui.PanoPullToRefreshStateForTab
import dev.etorix.panoscrobbler.utils.PlatformStuff
import dev.etorix.panoscrobbler.utils.Stuff
import kotlinx.coroutines.launch

@Composable
fun HomePagerScreen(
    user: UserCached,
    tabIdx: Int,
    digestTimePeriod: LastfmPeriod?,
    onSetTabIdx: (Int) -> Unit,
    onSetTitle: (String) -> Unit,
    tabsList: List<PanoTab>,
    onNavigate: (PanoRoute) -> Unit,
    pullToRefreshState: PullToRefreshState,
    onSetRefreshing: (PanoTab, PanoPullToRefreshStateForTab) -> Unit,
    selectSubTabId: (Int) -> Unit,
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    var scrobblesTitle by rememberSaveable { mutableStateOf("") }
    var followingTitle by rememberSaveable { mutableStateOf("") }
    var chartsTitle by rememberSaveable { mutableStateOf("") }
    var lastTabIdxRef by remember { mutableIntStateOf(tabIdx) }

    LaunchedEffect(tabIdx, scrobblesTitle, followingTitle, chartsTitle) {
        lastTabIdxRef = tabIdx

        val title = when (tabsList.getOrNull(tabIdx)) {
            PanoTab.Scrobbles, PanoTab.ScrobblesNoSubtabs -> scrobblesTitle
            PanoTab.Following -> followingTitle
            PanoTab.Charts -> chartsTitle
            else -> ""
        }
        onSetTitle(title)
    }


    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            // this captures the parameter tabIdx when the effect started, so use lastTabIdxRef state capture
            if (user.isSelf) {
                val tabIdx = lastTabIdxRef.coerceIn(tabsList.indices)
                Stuff.appScope.launch {
                    PlatformStuff.mainPrefs.updateData {
                        it.copy(lastHomePagerTab = tabIdx)
                    }
                }
            }
        }
    }

    PanoPager(
        selectedPage = tabIdx,
        onSelectPage = onSetTabIdx,
        totalPages = tabsList.size,
        modifier = modifier,
    ) { page ->
        when (val tab = tabsList.getOrNull(page)) {
            PanoTab.Scrobbles, PanoTab.ScrobblesNoSubtabs -> ScrobblesScreen(
                user = user,
                pullToRefreshState = pullToRefreshState,
                onSetRefreshing = { onSetRefreshing(tab, it) },
                onNavigate = onNavigate,
                editDataFlow = mainViewModel.editScrobbleUtils.editDataFlow,
                scrobblerStateFlow = mainViewModel.scrobblerStateFlow,
                updateScrobblerState = { mainViewModel.updateScrobblerServiceState(false) },
                onTitleChange = {
                    scrobblesTitle = it
                },
                selectSubTabId = selectSubTabId,
                modifier = Modifier.fillMaxSize()
            )

            PanoTab.Following -> FriendsScreen(
                user = user,
                pullToRefreshState = pullToRefreshState,
                onSetRefreshing = { onSetRefreshing(tab, it) },
                onNavigate = onNavigate,
                onTitleChange = {
                    followingTitle = it
                },
                modifier = Modifier.fillMaxSize()
            )

            PanoTab.Charts -> ChartsOverviewScreen(
                user = user,
                digestTimePeriod = digestTimePeriod,
                onNavigate = onNavigate,
                modifier = Modifier.fillMaxSize()
            )

            else -> {
            }
        }
    }
}