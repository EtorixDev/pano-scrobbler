package dev.etorix.panoscrobbler.ui

import kotlinx.serialization.Serializable

@Serializable
data class SerializableWindowState(
    val width: Float,
    val height: Float,
    val isMaximized: Boolean,
)