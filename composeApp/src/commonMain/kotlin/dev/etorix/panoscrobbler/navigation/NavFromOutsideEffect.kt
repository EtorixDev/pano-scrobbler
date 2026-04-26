package dev.etorix.panoscrobbler.navigation

import androidx.compose.runtime.Composable

@Composable
expect fun NavFromOutsideEffect(
    onNavigate: (PanoRoute) -> Unit,
    isAndroidDialogActivity: Boolean,
)