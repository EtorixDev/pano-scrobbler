package dev.etorix.panoscrobbler.imageloader

import coil3.ComponentRegistry
import coil3.ImageLoader


actual fun ImageLoader.Builder.additionalOptions(): ImageLoader.Builder {
    return this
}

actual fun ComponentRegistry.Builder.additionalComponents(): ComponentRegistry.Builder {
    return this
}