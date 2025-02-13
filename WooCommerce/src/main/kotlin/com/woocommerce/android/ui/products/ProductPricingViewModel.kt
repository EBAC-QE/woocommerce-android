package com.woocommerce.android.ui.products

import android.os.Parcelable
import com.woocommerce.android.R.string
import com.woocommerce.android.RequestCodes
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.extensions.isEquivalentTo
import com.woocommerce.android.extensions.isNotSet
import com.woocommerce.android.model.TaxClass
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.ui.products.models.SiteParameters
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.viewmodel.LiveDataDelegateWithArgs
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.Exit
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ExitWithResult
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.DaggerScopedViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.parcelize.Parcelize
import org.wordpress.android.fluxc.model.WCSettingsModel.CurrencyPosition
import org.wordpress.android.fluxc.model.WCSettingsModel.CurrencyPosition.LEFT
import org.wordpress.android.fluxc.model.WCSettingsModel.CurrencyPosition.LEFT_SPACE
import org.wordpress.android.fluxc.store.WooCommerceStore
import java.math.BigDecimal
import java.util.Date

class ProductPricingViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val productRepository: ProductDetailRepository,
    wooCommerceStore: WooCommerceStore,
    selectedSite: SelectedSite,
    parameterRepository: ParameterRepository
) : DaggerScopedViewModel(savedState, dispatchers) {
    companion object {
        private const val DEFAULT_DECIMAL_PRECISION = 2
        private const val KEY_PRODUCT_PARAMETERS = "key_product_parameters"
    }

    private var originalPricingData: PricingData
    private val navArgs: ProductPricingFragmentArgs by savedState.navArgs()
    private val isProductPricing = navArgs.requestCode == RequestCodes.PRODUCT_DETAIL_PRICING

    val parameters: SiteParameters by lazy {
        parameterRepository.getParameters(KEY_PRODUCT_PARAMETERS, savedState)
    }

    val viewStateData = LiveDataDelegateWithArgs(savedState, ViewState(pricingData = navArgs.pricingData))
    private var viewState by viewStateData

    val pricingData
        get() = viewState.pricingData

    private val hasChanges: Boolean
        get() = pricingData != originalPricingData

    init {
        val decimals = wooCommerceStore.getSiteSettings(selectedSite.get())?.currencyDecimalNumber
            ?: DEFAULT_DECIMAL_PRECISION

        viewState = viewState.copy(
            currency = parameters.currencySymbol,
            currencyPosition = parameters.currencyPosition,
            decimals = decimals,
            taxClassList = if (isProductPricing) productRepository.getTaxClassesForSite() else null,
            isTaxSectionVisible = isProductPricing
        )

        originalPricingData = navArgs.pricingData
    }

    fun getTaxClassBySlug(slug: String): TaxClass? {
        return viewState.taxClassList?.filter { it.slug == slug }?.getOrNull(0)
    }

    fun onDataChanged(
        regularPrice: BigDecimal? = pricingData.regularPrice,
        salePrice: BigDecimal? = pricingData.salePrice,
        isSaleScheduled: Boolean? = pricingData.isSaleScheduled,
        saleStartDate: Date? = pricingData.saleStartDate,
        saleEndDate: Date? = pricingData.saleEndDate,
        taxStatus: ProductTaxStatus? = pricingData.taxStatus,
        taxClass: String? = pricingData.taxClass
    ) {
        viewState = viewState.copy(
            pricingData = PricingData(
                regularPrice = regularPrice,
                salePrice = salePrice,
                isSaleScheduled = isSaleScheduled,
                saleStartDate = saleStartDate,
                saleEndDate = fixEndDateIfNecessary(saleStartDate, saleEndDate),
                taxStatus = taxStatus,
                taxClass = taxClass
            )
        )
    }

    fun onRegularPriceEntered(inputValue: BigDecimal) {
        onDataChanged(regularPrice = inputValue)

        val salePrice = pricingData.salePrice ?: BigDecimal.ZERO
        viewState = if (salePrice > inputValue) {
            viewState.copy(salePriceErrorMessage = string.product_pricing_update_sale_price_error)
        } else {
            viewState.copy(salePriceErrorMessage = 0)
        }
    }

    fun onScheduledSaleChanged(isSaleScheduled: Boolean) {
        if (isSaleScheduled && pricingData.salePrice.isNotSet()) {
            viewState = viewState.copy(salePriceErrorMessage = string.product_pricing_scheduled_sale_price_error)
        } else if (viewState.salePriceErrorMessage == string.product_pricing_scheduled_sale_price_error) {
            viewState = viewState.copy(salePriceErrorMessage = 0)
        }
        onDataChanged(isSaleScheduled = isSaleScheduled)
    }

    fun onSalePriceEntered(inputValue: BigDecimal) {
        onDataChanged(salePrice = inputValue)

        val regularPrice = pricingData.regularPrice ?: BigDecimal.ZERO
        viewState = if (inputValue > regularPrice) {
            viewState.copy(salePriceErrorMessage = string.product_pricing_update_sale_price_error)
        } else if (pricingData.isSaleScheduled == true && inputValue.isNotSet())
            viewState.copy(salePriceErrorMessage = string.product_pricing_scheduled_sale_price_error)
        else {
            viewState.copy(salePriceErrorMessage = 0)
        }
    }

    private fun fixEndDateIfNecessary(startDate: Date?, endDate: Date?): Date? {
        return endDate?.let {
            if (startDate != null && endDate.before(startDate)) {
                startDate
            } else {
                endDate
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        productRepository.onCleanup()
    }

    fun onExit() {
        if (hasChanges && viewState.canSaveChanges) {
            val isSaleScheduled = pricingData.isSaleScheduled == true &&
                (pricingData.saleStartDate != null || pricingData.saleEndDate != null)
            val resultPricing = pricingData.copy(
                isSaleScheduled = isSaleScheduled,
                saleStartDate = if (isSaleScheduled) pricingData.saleStartDate else null,
                saleEndDate = if (isSaleScheduled) pricingData.saleEndDate else null
            )
            triggerEvent(ExitWithResult(resultPricing))
        } else {
            triggerEvent(Exit)
        }
    }

    fun onRemoveEndDateClicked() {
        onDataChanged(saleEndDate = null)
    }

    @Parcelize
    data class ViewState(
        val currency: String? = null,
        val decimals: Int = DEFAULT_DECIMAL_PRECISION,
        val taxClassList: List<TaxClass>? = null,
        val salePriceErrorMessage: Int? = null,
        val pricingData: PricingData = PricingData(),
        val isTaxSectionVisible: Boolean? = null,
        private val currencyPosition: CurrencyPosition? = null
    ) : Parcelable {
        val isRemoveEndDateButtonVisible: Boolean
            get() = pricingData.saleEndDate != null
        val canSaveChanges: Boolean
            get() = salePriceErrorMessage == 0 || salePriceErrorMessage == null
        val isCurrencyPrefix: Boolean
            get() = currencyPosition == LEFT || currencyPosition == LEFT_SPACE
    }

    @Parcelize
    data class PricingData(
        val taxClass: String? = null,
        val taxStatus: ProductTaxStatus? = null,
        val isSaleScheduled: Boolean? = null,
        val saleStartDate: Date? = null,
        val saleEndDate: Date? = null,
        val regularPrice: BigDecimal? = null,
        val salePrice: BigDecimal? = null
    ) : Parcelable {
        override fun equals(other: Any?): Boolean {
            val data = other as? PricingData
            return data?.let {
                taxClass == it.taxClass &&
                taxStatus == it.taxStatus &&
                isSaleScheduled == it.isSaleScheduled &&
                saleStartDate == it.saleStartDate &&
                saleEndDate == it.saleEndDate &&
                regularPrice isEquivalentTo it.regularPrice &&
                salePrice isEquivalentTo it.salePrice
            } ?: false
        }

        override fun hashCode(): Int {
            return super.hashCode()
        }
    }

    @AssistedFactory
    interface Factory : ViewModelAssistedFactory<ProductPricingViewModel>
}
