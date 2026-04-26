package dev.etorix.panoscrobbler.utils

import dev.etorix.panoscrobbler.BuildKonfig
import dev.etorix.panoscrobbler.VariantStuffInterface

private val androidVariantStuff = AndroidExtrasVariantStuff()

actual val VariantStuff: VariantStuffInterface =
	if (BuildKonfig.UPDATES_AVAILABLE)
		androidVariantStuff
	else
		object : VariantStuffInterface by androidVariantStuff {
			override val githubApiUrl: String? = null
		}