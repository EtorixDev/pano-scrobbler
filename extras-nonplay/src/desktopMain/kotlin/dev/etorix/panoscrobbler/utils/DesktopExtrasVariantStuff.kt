package dev.etorix.panoscrobbler.utils

import dev.etorix.panoscrobbler.VariantStuffInterface
import dev.etorix.panoscrobbler.review.BaseReviewPrompter

class DesktopExtrasVariantStuff : VariantStuffInterface {
    override val reviewPrompter: BaseReviewPrompter = BaseReviewPrompter()
    override val githubApiUrl: String =
        "https://api.github.com/repos/EtorixDev/pano-scrobbler/releases/latest"
    override val hasForegroundServiceSpecialUse = false
}