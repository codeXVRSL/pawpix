package com.pawpixel.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.pawpixel.core.Ownership
import com.pawpixel.core.Pro
import com.pawpixel.core.StoreEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * PawPixel Pro through Google Play Billing (Play Billing Library 8): a one-time product,
 * [Pro.PRODUCT_ID]. Pending purchases are on, so owners can pay with cash at 7-Eleven or ECPay:
 * Play then says PENDING, and the purchase turns PURCHASED (through [onPurchases]) once they've paid.
 *
 * Every PURCHASED Pro is acknowledged (Play refunds purchases that aren't, after 3 days); one that
 * couldn't be (offline) is acknowledged at the next check. Refunds show up as Pro missing from
 * [owned], which the app asks at every start and resume.
 */
class PlayStore(private val context: Context, private val activity: () -> Activity?) : Store {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connecting = Mutex()
    @Volatile private var listener: ((StoreEvent) -> Unit)? = null
    /** The purchase sheet that's open, waiting for Play's answer. */
    @Volatile private var inFlight: CompletableDeferred<Pair<BillingResult, List<Purchase>>>? = null

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases -> onPurchases(result, purchases.orEmpty()) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    override fun listen(onEvent: (StoreEvent) -> Unit) { listener = onEvent }

    override suspend fun price(): String = details().oneTimePurchaseOfferDetails?.formattedPrice
        ?: throw StoreException(StoreProblem.NOT_SET_UP)

    override suspend fun buy(): BuyResult {
        val product = try { details() } catch (e: StoreException) { return BuyResult.Failed(e.problem) }
        val act = activity() ?: return BuyResult.Failed(StoreProblem.ERROR)
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        val answer = CompletableDeferred<Pair<BillingResult, List<Purchase>>>()
        inFlight = answer
        val launched = withContext(Dispatchers.Main) { client.launchBillingFlow(act, params) }
        if (launched.responseCode != BillingResponseCode.OK) {
            inFlight = null
            return resultOf(launched, emptyList())
        }
        val (result, purchases) = answer.await()
        return resultOf(result, purchases)
    }

    override suspend fun owned(sync: Boolean): Ownership {
        connect()
        val (result, purchases) = suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { r, list ->
                if (cont.isActive) cont.resume(r to list)
            }
        }
        if (result.responseCode != BillingResponseCode.OK) throw StoreException(problemOf(result))
        return ownershipOf(purchases)
    }

    // ---- Play's answers ----

    private fun onPurchases(result: BillingResult, purchases: List<Purchase>) {
        val waiting = inFlight
        if (waiting != null && !waiting.isCompleted) {
            inFlight = null
            waiting.complete(result to purchases)
            return
        }
        // Outside a purchase sheet: a cash payment that just came through, while PawPixel is open.
        if (result.responseCode == BillingResponseCode.OK && purchases.any { Pro.PRODUCT_ID in it.products }) {
            scope.launch { listener?.invoke(StoreEvent.Purchase(ownershipOf(purchases))) }
        }
    }

    private suspend fun resultOf(result: BillingResult, purchases: List<Purchase>): BuyResult = when (result.responseCode) {
        BillingResponseCode.OK -> BuyResult.Done(ownershipOf(purchases))
        BillingResponseCode.USER_CANCELED -> BuyResult.Cancelled
        // Bought before (on another phone, or the answer got lost): that's Pro, restored.
        BillingResponseCode.ITEM_ALREADY_OWNED -> runCatching { BuyResult.Done(owned()) }.getOrElse { BuyResult.Done(Ownership.OWNED) }
        else -> BuyResult.Failed(problemOf(result))
    }

    /** Pro's state among [purchases], acknowledging a paid one Play hasn't been told about yet. */
    private suspend fun ownershipOf(purchases: List<Purchase>): Ownership {
        val pro = purchases.filter { Pro.PRODUCT_ID in it.products }
        pro.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }?.let { paid ->
            if (!paid.isAcknowledged) acknowledge(paid)
            return Ownership.OWNED
        }
        return if (pro.any { it.purchaseState == Purchase.PurchaseState.PENDING }) Ownership.PENDING else Ownership.NONE
    }

    /** Best effort: if it fails (offline), the next check tries again, well within Play's 3 days. */
    private suspend fun acknowledge(purchase: Purchase) {
        suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    // ---- Connection and the product ----

    private suspend fun connect() = connecting.withLock {
        if (client.isReady) return@withLock
        val result = suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) { if (cont.isActive) cont.resume(result) }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build())
                }
            })
        }
        if (result.responseCode != BillingResponseCode.OK) throw StoreException(problemOf(result))
    }

    private suspend fun details(): ProductDetails {
        connect()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(Pro.PRODUCT_ID).setProductType(ProductType.INAPP).build(),
        )).build()
        val (result, found) = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { r, details ->
                if (cont.isActive) cont.resume(r to details.productDetailsList)
            }
        }
        if (result.responseCode != BillingResponseCode.OK) throw StoreException(problemOf(result))
        // Not set up in Play Console yet (or not for this country): say it's unavailable.
        return found.firstOrNull { it.productId == Pro.PRODUCT_ID } ?: throw StoreException(StoreProblem.NOT_SET_UP)
    }

    private fun problemOf(result: BillingResult): StoreProblem = when (result.responseCode) {
        BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.NETWORK_ERROR, BillingResponseCode.SERVICE_DISCONNECTED -> StoreProblem.OFFLINE
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED -> StoreProblem.UNAVAILABLE
        BillingResponseCode.ITEM_UNAVAILABLE -> StoreProblem.NOT_SET_UP
        else -> StoreProblem.ERROR
    }
}
