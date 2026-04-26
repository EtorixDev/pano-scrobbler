package com.arn.scrobble.review

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ReviewPrompter(
    lastCheckTime: Flow<Long>,
    setLastCheckTime: suspend (Long) -> Unit,
) : BaseReviewPrompter(lastCheckTime, setLastCheckTime) {
    override suspend fun showIfNeeded(
        activity: Any?,
    ): Boolean {
        if (activity !is Activity) return false

        val show = super.showIfNeeded(activity)

        if (show) {
            val manager = ReviewManagerFactory.create(activity)

            coroutineScope {
                manager.requestReviewFlow().addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        launch {
                            setLastCheckTime(System.currentTimeMillis())
                        }
                        manager.launchReviewFlow(activity, task.result)
                    } else {
                        task.exception?.printStackTrace()
                    }
                }
            }
        }

        return show
    }
}
