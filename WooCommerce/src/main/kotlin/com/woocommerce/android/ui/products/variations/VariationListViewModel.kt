package com.woocommerce.android.ui.products.variations

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import com.woocommerce.android.R.string
import com.woocommerce.android.analytics.AnalyticsTracker.Companion.KEY_PRODUCT_ID
import com.woocommerce.android.analytics.AnalyticsTracker.Companion.track
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_ATTRIBUTE_EDIT_BUTTON_TAPPED
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_VARIATION_VIEW_VARIATION_DETAIL_TAPPED
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.extensions.isNotSet
import com.woocommerce.android.extensions.isSet
import com.woocommerce.android.model.Product
import com.woocommerce.android.model.ProductVariation
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.ui.products.ProductDetailRepository
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.util.CurrencyFormatter
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.viewmodel.LiveDataDelegateWithArgs
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ExitWithResult
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.DaggerScopedViewModel
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [Product] and [ProductVariation] are two models fetched in separate endpoints,
 * but to allow us to create and delete variations correctly, consistency between
 * site and app data around both models is necessary to handle the correct flow
 * to the user.
 *
 * This happens because when any change happens at the variation list
 * from a product, the [Product.numVariations] is also updated by the site,
 * causing the need to fetch the product data after that, allowing
 * us to be able to tell at any Product view if we shall make available
 * the first variation creation flow or just allow the user the access the variation
 * list view directly without affecting the ability of the Fragment to manage drafts.
 *
 * With that said, when we update the Variation list, we should also update the
 * [ViewState.parentProduct] so the correct information is returned [onExit]
 */
class VariationListViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val variationListRepository: VariationListRepository,
    private val productRepository: ProductDetailRepository,
    private val networkStatus: NetworkStatus,
    private val currencyFormatter: CurrencyFormatter
) : DaggerScopedViewModel(savedState, dispatchers) {
    private var remoteProductId = 0L

    private val _variationList = MutableLiveData<List<ProductVariation>>()
    val variationList: LiveData<List<ProductVariation>> = Transformations.map(_variationList) { variations ->
        variations.apply {
            viewState = viewState.parentProduct
                ?.takeIf { it.variationEnabledAttributes.isEmpty() }
                ?.let { viewState.copy(isEmptyViewVisible = true) }
                ?: any { it.isVisible && it.regularPrice.isNotSet() && it.salePrice.isNotSet() }
                    .let { viewState.copy(isWarningVisible = it) }
        }
    }

    val viewStateLiveData = LiveDataDelegateWithArgs(savedState, ViewState())
    private var viewState by viewStateLiveData

    private var loadingJob: Job? = null

    val isEmpty
        get() = _variationList.value?.isEmpty() ?: true

    fun start(remoteProductId: Long, createNewVariation: Boolean = false) {
        productRepository.getProduct(remoteProductId)?.let {
            viewState = viewState.copy(parentProduct = it)
            when (createNewVariation) {
                true -> handleVariationCreation(openVariationDetails = false)
                else -> handleVariationLoading(remoteProductId)
            }
        }
    }

    fun onLoadMoreRequested(remoteProductId: Long) {
        loadVariations(remoteProductId, loadMore = true)
    }

    override fun onCleared() {
        super.onCleared()
        variationListRepository.onCleanup()
    }

    fun onItemClick(variation: ProductVariation) {
        track(PRODUCT_VARIATION_VIEW_VARIATION_DETAIL_TAPPED)
        triggerEvent(ShowVariationDetail(variation))
    }

    fun onAddEditAttributesClick() {
        trackWithProductId(PRODUCT_ATTRIBUTE_EDIT_BUTTON_TAPPED)
        triggerEvent(ShowAttributeList)
    }

    fun onCreateEmptyVariationClick() {
        trackWithProductId(Stat.PRODUCT_VARIATION_ADD_MORE_TAPPED)
        handleVariationCreation(openVariationDetails = true)
    }

    fun onCreateFirstVariationRequested() {
        trackWithProductId(Stat.PRODUCT_VARIATION_ADD_FIRST_TAPPED)
        triggerEvent(ShowAddAttributeView)
    }

    fun onVariationDeleted(productID: Long, variationID: Long) = launch {
        variationList.value?.toMutableList()?.apply {
            find { it.remoteVariationId == variationID }
                ?.let { remove(it) }
        }?.toList().let { _variationList.value = it }

        productRepository.fetchProduct(productID)
            ?.let { viewState = viewState.copy(parentProduct = it) }
    }

    fun onExit() {
        triggerEvent(ExitWithResult(VariationListData(viewState.parentProduct?.numVariations)))
    }

    fun refreshVariations(remoteProductId: Long) {
        viewState = viewState.copy(isRefreshing = true)
        loadVariations(remoteProductId)
    }

    private fun handleVariationLoading(productID: Long) {
        viewState = viewState.copy(isSkeletonShown = true)
        loadVariations(productID)
    }

    private fun handleVariationCreation(
        openVariationDetails: Boolean
    ) = launch {
        viewState = viewState.copy(
            isProgressDialogShown = true,
            isEmptyViewVisible = false
        )

        viewState.parentProduct
            ?.createVariation()
            .takeIf { openVariationDetails }
            ?.let { triggerEvent(ShowVariationDetail(it)) }
            .also { viewState = viewState.copy(isProgressDialogShown = false) }
    }

    private suspend fun Product.createVariation() =
            variationListRepository.createEmptyVariation(this)
                ?.copy(remoteProductId = remoteId)
                ?.apply { syncProductToVariations(remoteId) }

    private suspend fun syncProductToVariations(productID: Long) {
        loadVariations(productID, withSkeletonView = false)
        productRepository.fetchProduct(productID)
            ?.let { viewState = viewState.copy(parentProduct = it) }
    }

    private fun loadVariations(
        remoteProductId: Long,
        loadMore: Boolean = false,
        withSkeletonView: Boolean = true
    ) {
        if (loadMore && !variationListRepository.canLoadMoreProductVariations) {
            WooLog.d(WooLog.T.PRODUCTS, "can't load more product variations")
            return
        }

        if (loadingJob?.isActive == true) {
            WooLog.d(WooLog.T.PRODUCTS, "already loading product variations")
            return
        }

        this.remoteProductId = remoteProductId

        loadingJob = launch {
            viewState = viewState.copy(isLoadingMore = loadMore)
            if (!loadMore) {
                // if this is the initial load, first get the product variations from the db and if there are any show
                // them immediately, otherwise make sure the skeleton shows
                val variationsInDb = variationListRepository.getProductVariationList(remoteProductId)
                if (variationsInDb.isNullOrEmpty()) {
                    viewState = viewState.copy(isSkeletonShown = withSkeletonView)
                } else {
                    _variationList.value = combineData(variationsInDb)
                }
            }

            fetchVariations(remoteProductId, loadMore = loadMore)
        }
    }

    private suspend fun fetchVariations(remoteProductId: Long, loadMore: Boolean = false) {
        if (networkStatus.isConnected()) {
            val fetchedVariations = variationListRepository.fetchProductVariations(remoteProductId, loadMore)
            if (fetchedVariations.isNullOrEmpty()) {
                if (!loadMore) {
                    _variationList.value = emptyList()
                    viewState = viewState.copy(isEmptyViewVisible = true)
                }
            } else {
                _variationList.value = combineData(fetchedVariations)
            }
        } else {
            triggerEvent(ShowSnackbar(string.offline_error))
        }
        viewState = viewState.copy(
            isSkeletonShown = false,
            isRefreshing = false,
            isLoadingMore = false
        )
    }

    private fun combineData(variations: List<ProductVariation>): List<ProductVariation> {
        val currencyCode = variationListRepository.getCurrencyCode()
        variations.map { variation ->
            if (variation.isSaleInEffect) {
                variation.priceWithCurrency = currencyCode?.let {
                    currencyFormatter.formatCurrency(variation.salePrice!!, it)
                } ?: variation.salePrice!!.toString()
            } else if (variation.regularPrice.isSet()) {
                variation.priceWithCurrency = currencyCode?.let {
                    currencyFormatter.formatCurrency(variation.regularPrice!!, it)
                } ?: variation.regularPrice!!.toString()
            }
        }
        return variations
    }

    private fun trackWithProductId(event: Stat) {
        viewState.parentProduct?.let { track(event, mapOf(KEY_PRODUCT_ID to it.remoteId)) }
    }

    @Parcelize
    data class ViewState(
        val isSkeletonShown: Boolean? = null,
        val isRefreshing: Boolean? = null,
        val isLoadingMore: Boolean? = null,
        val canLoadMore: Boolean? = null,
        val isEmptyViewVisible: Boolean? = null,
        val isWarningVisible: Boolean? = null,
        val isProgressDialogShown: Boolean? = null,
        val parentProduct: Product? = null
    ) : Parcelable

    @Parcelize
    data class VariationListData(
        val currentVariationAmount: Int? = null
    ) : Parcelable

    data class ShowVariationDetail(val variation: ProductVariation) : Event()
    object ShowAddAttributeView : Event()
    object ShowAttributeList : Event()

    @AssistedFactory
    interface Factory : ViewModelAssistedFactory<VariationListViewModel>
}
