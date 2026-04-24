package com.arn.scrobble.billing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow


abstract class BaseBillingRepository {
    protected abstract val scope: CoroutineScope
    protected abstract val receipt: Flow<Pair<String?, String?>>
    protected val checkEveryDays = 30
    protected val productId = "pscrobbler_pro"
    abstract val formattedPrice: Flow<String?>
    protected val _licenseError = MutableSharedFlow<LicenseError>()
    val licenseError = _licenseError.asSharedFlow()
    abstract val purchaseMethods: List<PurchaseMethod>
    abstract val needsActivationCode: Boolean
    val licenseState = MutableStateFlow(LicenseState.VALID)

    abstract fun initBillingClient()

    abstract fun startDataSourceConnections()
    abstract fun endDataSourceConnections()
    abstract suspend fun queryPurchasesAsync()
    abstract suspend fun checkAndStoreLicense(receipt: String)
    protected abstract fun verifyPurchase(data: String, signature: String?): Boolean
    abstract fun launchBillingFlow(purchaseMethod: PurchaseMethod, activity: Any?)
}