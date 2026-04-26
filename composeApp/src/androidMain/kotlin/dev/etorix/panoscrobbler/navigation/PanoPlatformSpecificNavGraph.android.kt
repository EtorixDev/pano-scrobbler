package dev.etorix.panoscrobbler.navigation

import androidx.navigation3.runtime.EntryProviderScope
import dev.etorix.panoscrobbler.onboarding.FixItDialog

actual fun EntryProviderScope<PanoRoute>.panoPlatformSpecificNavGraph(
    onSetTitle: (PanoRoute, String) -> Unit,
    navigate: (PanoRoute) -> Unit,
    goBack: () -> Unit,
) {
    modalEntry<PanoRoute.Modal.FixIt> { route ->

        FixItDialog(
            killedReason = route.killedReason,
            onNavigate = navigate,
            modifier = modalModifier(),
        )
    }
}