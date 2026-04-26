package dev.etorix.panoscrobbler.utils

import dev.etorix.panoscrobbler.BuildKonfig
import dev.etorix.panoscrobbler.VariantStuffInterface

private val desktopVariantStuff = DesktopExtrasVariantStuff()

actual val VariantStuff: VariantStuffInterface =
    if (BuildKonfig.UPDATES_AVAILABLE)
        desktopVariantStuff
    else
        object : VariantStuffInterface by desktopVariantStuff {
            override val githubApiUrl: String? = null
        }