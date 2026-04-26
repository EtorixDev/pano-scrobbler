package com.arn.scrobble.utils

import com.arn.scrobble.VariantStuffInterface
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

private val lastLicenseCheckTimeFile
    get() =
        AndroidStuff.applicationContext.noBackupFilesDir
            .resolve("last_license_check_time.txt")
private val lastReviewPromptTimeFile
    get() =
        AndroidStuff.applicationContext.noBackupFilesDir
            .resolve("last_review_prompt_time.txt")
actual val VariantStuff: VariantStuffInterface = AndroidExtrasVariantStuff(
    scope = Stuff.appScope,
    lastLicenseCheckTimeFile = { lastLicenseCheckTimeFile },
    lastReviewPromptTimeFile = { lastReviewPromptTimeFile },
    receipt = flow { emitAll(Stuff.receiptFlow) },
    setReceipt = Stuff::setReceipt,
    httpPost = Stuff::httpPost,
    deviceIdentifier = PlatformStuff::getDeviceIdentifier,
    openInBrowser = PlatformStuff::openInBrowser,
    context = AndroidStuff.applicationContext
)