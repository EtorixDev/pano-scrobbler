package dev.etorix.panoscrobbler

import dev.etorix.panoscrobbler.review.BaseReviewPrompter


interface VariantStuffInterface {
    val reviewPrompter: BaseReviewPrompter
    val githubApiUrl: String?
    val hasForegroundServiceSpecialUse: Boolean
}