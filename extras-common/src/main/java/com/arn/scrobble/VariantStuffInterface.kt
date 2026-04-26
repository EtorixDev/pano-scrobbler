package com.arn.scrobble

import com.arn.scrobble.review.BaseReviewPrompter


interface VariantStuffInterface {
    val reviewPrompter: BaseReviewPrompter
    val githubApiUrl: String?
    val hasForegroundServiceSpecialUse: Boolean
}