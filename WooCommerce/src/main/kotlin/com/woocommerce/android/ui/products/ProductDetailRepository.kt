package com.woocommerce.android.ui.products

import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_LOADED
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_UPDATE_ERROR
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_UPDATE_SUCCESS
import com.woocommerce.android.annotations.OpenClassOnDebug
import com.woocommerce.android.model.Product
import com.woocommerce.android.model.RequestResult
import com.woocommerce.android.model.ShippingClass
import com.woocommerce.android.model.TaxClass
import com.woocommerce.android.model.toAppModel
import com.woocommerce.android.model.toDataModel
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.util.WooLog.T.PRODUCTS
import com.woocommerce.android.util.suspendCancellableCoroutineWithTimeout
import com.woocommerce.android.util.suspendCoroutineWithTimeout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.action.WCProductAction.FETCH_PRODUCT_SHIPPING_CLASS_LIST
import org.wordpress.android.fluxc.action.WCProductAction.FETCH_PRODUCT_SKU_AVAILABILITY
import org.wordpress.android.fluxc.action.WCProductAction.FETCH_SINGLE_PRODUCT
import org.wordpress.android.fluxc.action.WCProductAction.UPDATED_PRODUCT
import org.wordpress.android.fluxc.generated.WCProductActionBuilder
import org.wordpress.android.fluxc.store.WCProductStore
import org.wordpress.android.fluxc.store.WCProductStore.FetchProductShippingClassListPayload
import org.wordpress.android.fluxc.store.WCProductStore.FetchProductSkuAvailabilityPayload
import org.wordpress.android.fluxc.store.WCProductStore.OnProductChanged
import org.wordpress.android.fluxc.store.WCProductStore.OnProductShippingClassesChanged
import org.wordpress.android.fluxc.store.WCProductStore.OnProductSkuAvailabilityChanged
import org.wordpress.android.fluxc.store.WCProductStore.OnProductUpdated
import org.wordpress.android.fluxc.store.WCTaxStore
import javax.inject.Inject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

@OpenClassOnDebug
class ProductDetailRepository @Inject constructor(
    private val dispatcher: Dispatcher,
    private val productStore: WCProductStore,
    private val selectedSite: SelectedSite,
    private val taxStore: WCTaxStore
) {
    companion object {
        private const val ACTION_TIMEOUT = 10L * 1000
        private const val SHIPPING_CLASS_PAGE_SIZE = WCProductStore.DEFAULT_PRODUCT_SHIPPING_CLASS_PAGE_SIZE
    }

    private var continuationUpdateProduct: Continuation<Boolean>? = null
    private var continuationFetchProduct: CancellableContinuation<Boolean>? = null
    private var continuationVerifySku: CancellableContinuation<Boolean>? = null
    private var continuationShippingClasses: CancellableContinuation<Boolean>? = null

    private var isFetchingTaxClassList = false

    private var shippingClassOffset = 0
    final var canLoadMoreShippingClasses = true
        private set

    init {
        dispatcher.register(this)
    }

    fun onCleanup() {
        dispatcher.unregister(this)
    }

    suspend fun fetchProduct(remoteProductId: Long): Product? {
        try {
            continuationFetchProduct?.cancel()
            suspendCancellableCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
                continuationFetchProduct = it

                val payload = WCProductStore.FetchSingleProductPayload(selectedSite.get(), remoteProductId)
                dispatcher.dispatch(WCProductActionBuilder.newFetchSingleProductAction(payload))
            }
        } catch (e: CancellationException) {
            WooLog.d(PRODUCTS, "CancellationException while fetching single product")
        }

        continuationFetchProduct = null
        return getProduct(remoteProductId)
    }

    /**
     * Fires the request to update the product
     *
     * @return the result of the action as a [Boolean]
     */
    suspend fun updateProduct(updatedProduct: Product): Boolean {
        return try {
            suspendCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
                continuationUpdateProduct = it

                val payload = WCProductStore.UpdateProductPayload(
                        selectedSite.get(), updatedProduct.toDataModel(getCachedWCProductModel(updatedProduct.remoteId))
                )
                dispatcher.dispatch(WCProductActionBuilder.newUpdateProductAction(payload))
            } ?: false // request timed out
        } catch (e: CancellationException) {
            WooLog.e(PRODUCTS, "Exception encountered while updating product", e)
            false
        }
    }

    /**
     * Fires the request to check if sku is available for a given [selectedSite]
     *
     * @return the result of the action as a [Boolean]
     */
    suspend fun verifySkuAvailability(sku: String): Boolean? {
        continuationVerifySku?.cancel()
        return try {
            suspendCancellableCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
                continuationVerifySku = it

                val payload = FetchProductSkuAvailabilityPayload(selectedSite.get(), sku)
                dispatcher.dispatch(WCProductActionBuilder.newFetchProductSkuAvailabilityAction(payload))
            } // request timed out
        } catch (e: CancellationException) {
            WooLog.e(PRODUCTS, "Exception encountered while verifying product sku availability", e)
            null
        }
    }

    /**
     * Fires the request to fetch list of [TaxClass] for a given [selectedSite]
     *
     * @return the result of the action as a [RequestResult]
     */
    suspend fun loadTaxClassesForSite(): RequestResult {
        return withContext(Dispatchers.IO) {
            if (!isFetchingTaxClassList) {
                isFetchingTaxClassList = true
                val result = taxStore.fetchTaxClassList(selectedSite.get())
                isFetchingTaxClassList = false
                if (result.isError) {
                    WooLog.e(PRODUCTS, "Exception encountered while fetching tax class list: ${result.error.message}")
                    RequestResult.ERROR
                } else RequestResult.SUCCESS
            } else RequestResult.NO_ACTION_NEEDED
        }
    }

    /**
     * Fetches the list of shipping classes for the [selectedSite]
     */
    suspend fun fetchShippingClassesForSite(loadMore: Boolean = false): List<ShippingClass> {
        try {
            continuationShippingClasses?.cancel()
            suspendCancellableCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
                continuationShippingClasses = it
                shippingClassOffset = if (loadMore) {
                    shippingClassOffset + SHIPPING_CLASS_PAGE_SIZE
                } else {
                    0
                }
                val payload = FetchProductShippingClassListPayload(
                        selectedSite.get(),
                        pageSize = SHIPPING_CLASS_PAGE_SIZE,
                        offset = shippingClassOffset
                )
                dispatcher.dispatch(WCProductActionBuilder.newFetchProductShippingClassListAction(payload))
            }
        } catch (e: CancellationException) {
            WooLog.d(PRODUCTS, "CancellationException while fetching product shipping classes")
        }

        continuationShippingClasses = null
        return getProductShippingClassesForSite()
    }

    private fun getCachedWCProductModel(remoteProductId: Long) =
            productStore.getProductByRemoteId(selectedSite.get(), remoteProductId)

    fun getProduct(remoteProductId: Long): Product? = getCachedWCProductModel(remoteProductId)?.toAppModel()

    fun geProductExistsBySku(sku: String) = productStore.geProductExistsBySku(selectedSite.get(), sku)

    fun getCachedVariantCount(remoteProductId: Long) =
            productStore.getVariationsForProduct(selectedSite.get(), remoteProductId).size

    fun getTaxClassesForSite(): List<TaxClass> =
            taxStore.getShippingClassListForSite(selectedSite.get()).map { it.toAppModel() }

    fun getProductShippingClassesForSite(): List<ShippingClass> =
            productStore.getShippingClassListForSite(selectedSite.get()).map { it.toAppModel() }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = MAIN)
    fun onProductChanged(event: OnProductChanged) {
        if (event.causeOfChange == FETCH_SINGLE_PRODUCT) {
            if (event.isError) {
                continuationFetchProduct?.resume(false)
            } else {
                AnalyticsTracker.track(PRODUCT_DETAIL_LOADED)
                continuationFetchProduct?.resume(true)
            }
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = MAIN)
    fun onProductUpdated(event: OnProductUpdated) {
        if (event.causeOfChange == UPDATED_PRODUCT) {
            if (event.isError) {
                AnalyticsTracker.track(PRODUCT_DETAIL_UPDATE_ERROR, mapOf(
                        AnalyticsTracker.KEY_ERROR_CONTEXT to this::class.java.simpleName,
                        AnalyticsTracker.KEY_ERROR_TYPE to event.error?.type?.toString(),
                        AnalyticsTracker.KEY_ERROR_DESC to event.error?.message))
                continuationUpdateProduct?.resume(false)
            } else {
                AnalyticsTracker.track(PRODUCT_DETAIL_UPDATE_SUCCESS)
                continuationUpdateProduct?.resume(true)
            }
            continuationUpdateProduct = null
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = MAIN)
    fun onProductSkuAvailabilityChanged(event: OnProductSkuAvailabilityChanged) {
        if (event.causeOfChange == FETCH_PRODUCT_SKU_AVAILABILITY) {
            // TODO: add event to track sku availability success
            continuationVerifySku?.resume(event.available)
            continuationVerifySku = null
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = MAIN)
    fun onProductShippingClassesChanged(event: OnProductShippingClassesChanged) {
        if (event.causeOfChange == FETCH_PRODUCT_SHIPPING_CLASS_LIST) {
            canLoadMoreShippingClasses = event.canLoadMore
            if (event.isError) {
                continuationShippingClasses?.resume(false)
            } else {
                continuationShippingClasses?.resume(true)
            }
        }
    }
}
