package com.arn.scrobble.review

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.days

open class BaseReviewPrompter(
    private val lastCheckTime: Flow<Long>,
    protected val setLastCheckTime: suspend (Long) -> Unit,
) {
    open suspend fun showIfNeeded(
        activity: Any?,
    ): Boolean {
        val lastReviewPromptTime = lastCheckTime.first()
        if (lastReviewPromptTime <= 0) {
            setLastCheckTime(System.currentTimeMillis())
            return false
        }

        val shouldShowPrompt =
            System.currentTimeMillis() - lastReviewPromptTime >= 30.days.inWholeMilliseconds

        return shouldShowPrompt
    }
}