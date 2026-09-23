package dev.etorix.panoscrobbler.navigation

import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import dev.etorix.panoscrobbler.onboarding.FixItDialog
import dev.etorix.panoscrobbler.ui.navModal

actual fun EntryProviderScope<PanoRoute>.panoPlatformSpecificNavGraph(
    onSetTitle: (PanoRoute, String) -> Unit,
    navigate: (PanoRoute) -> Unit,
    goBack: () -> Unit,
) {
    modalEntry<PanoRoute.Modal.FixIt> { route ->

        FixItDialog(
            killedReason = route.killedReason,
            onNavigate = navigate,
            modifier = Modifier.navModal(),
        )
    }
}