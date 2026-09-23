package dev.etorix.panoscrobbler.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf

val LocalModalShownTracker = compositionLocalOf { mutableIntStateOf(0) }
