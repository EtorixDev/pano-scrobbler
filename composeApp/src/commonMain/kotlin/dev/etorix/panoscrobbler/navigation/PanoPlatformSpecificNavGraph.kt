package dev.etorix.panoscrobbler.navigation

import androidx.navigation3.runtime.EntryProviderScope

expect fun EntryProviderScope<PanoRoute>.panoPlatformSpecificNavGraph(
    onSetTitle: (PanoRoute, String) -> Unit,
    navigate: (PanoRoute) -> Unit,
    goBack: () -> Unit,
)