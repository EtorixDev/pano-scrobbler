package com.arn.scrobble.utils

import com.arn.scrobble.VariantStuffInterface
import com.arn.scrobble.review.BaseReviewPrompter

class DesktopExtrasVariantStuff : VariantStuffInterface {
    override val reviewPrompter: BaseReviewPrompter = BaseReviewPrompter()
    override val githubApiUrl: String =
        "https://api.github.com/repos/EtorixDev/pano-scrobbler/releases/latest"
    override val hasForegroundServiceSpecialUse = false
}