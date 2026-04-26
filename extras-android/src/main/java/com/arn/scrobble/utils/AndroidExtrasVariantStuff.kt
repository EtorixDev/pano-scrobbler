package com.arn.scrobble.utils

import android.content.Context
import com.arn.scrobble.VariantStuffInterface
import com.arn.scrobble.billing.BaseBillingRepository
import com.arn.scrobble.billing.BillingRepository
import com.arn.scrobble.review.BaseReviewPrompter
import com.arn.scrobble.review.ReviewPrompter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class AndroidExtrasVariantStuff(
    scope: CoroutineScope,
    receipt: Flow<Pair<String?, String?>>,
    lastLicenseCheckTimeFile: () -> File,
    lastReviewPromptTimeFile: () -> File,
    setReceipt: suspend (String?, String?) -> Unit,

    httpPost: suspend (url: String, body: String) -> String,
    deviceIdentifier: () -> String,
    openInBrowser: (url: String) -> Unit,
    context: Context
) : VariantStuffInterface {
    override val billingRepository: BaseBillingRepository = BillingRepository(
        scope,
        receipt,
        flow {
            val t = withContext(Dispatchers.IO) {
                lastLicenseCheckTimeFile()
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.toLongOrNull()
            } ?: -1L

            emit(t)
        },
        { time ->
            withContext(Dispatchers.IO) {
                lastLicenseCheckTimeFile()
                    .writeText(time.toString())
            }
        },
        setReceipt,
        httpPost,
        deviceIdentifier,
        openInBrowser,
        context
    )
    override val reviewPrompter: BaseReviewPrompter = ReviewPrompter(
        lastCheckTime = flow {
            val t = withContext(Dispatchers.IO) {
                lastReviewPromptTimeFile()
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.toLongOrNull()
            } ?: -1L

            emit(t)
        },
        setLastCheckTime = { time ->
            withContext(Dispatchers.IO) {
                lastReviewPromptTimeFile()
                    .writeText(time.toString())
            }
        },
    )
    override val githubApiUrl: String? = null
}