package dev.etorix.panoscrobbler.utils

import dev.etorix.panoscrobbler.VariantStuffInterface

class AndroidExtrasVariantStuff : VariantStuffInterface {
    override val githubApiUrl: String =
        "https://api.github.com/repos/EtorixDev/pano-scrobbler/releases/latest"
}