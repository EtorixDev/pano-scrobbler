package dev.etorix.panoscrobbler.imageloader

import coil3.key.Keyer
import coil3.request.Options
import dev.etorix.panoscrobbler.ui.PackageName

class AppIconKeyer : Keyer<PackageName> {
    override fun key(data: PackageName, options: Options) = "package:" + data.packageName
}