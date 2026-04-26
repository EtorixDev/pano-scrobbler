package com.arn.scrobble.utils

import com.arn.scrobble.BuildKonfig
import com.arn.scrobble.VariantStuffInterface

private val desktopVariantStuff = DesktopExtrasVariantStuff()

actual val VariantStuff: VariantStuffInterface =
    if (BuildKonfig.UPDATES_AVAILABLE)
        desktopVariantStuff
    else
        object : VariantStuffInterface by desktopVariantStuff {
            override val githubApiUrl: String? = null
        }