package dev.etorix.panoscrobbler.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun QrCodeCanvas(
    url: String,
    modifier: Modifier = Modifier,
)