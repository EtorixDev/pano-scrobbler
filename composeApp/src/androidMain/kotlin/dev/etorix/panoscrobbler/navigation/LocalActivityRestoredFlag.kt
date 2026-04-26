package dev.etorix.panoscrobbler.navigation

import androidx.compose.runtime.compositionLocalOf

val LocalActivityRestoredFlag = compositionLocalOf<Boolean> {
    error("No ActivityRestoredFlag provided")
}
