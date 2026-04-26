package com.arn.scrobble.utils

import com.arn.scrobble.VariantStuffInterface
import com.arn.scrobble.review.BaseReviewPrompter

class AndroidExtrasVariantStuff : VariantStuffInterface {
    override val reviewPrompter: BaseReviewPrompter = AndroidVariantStuffProps.reviewPrompter
    override val githubApiUrl: String? = null
    override val hasForegroundServiceSpecialUse =
        AndroidVariantStuffProps.hasForegroundServiceSpecialUse
}