package com.woocommerce.android.ui.orders.shippinglabels.creation

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.extensions.isEqualTo
import com.woocommerce.android.model.Order
import com.woocommerce.android.model.ShippingRate
import com.woocommerce.android.model.ShippingRate.Option
import com.woocommerce.android.model.ShippingRate.Option.ADULT_SIGNATURE
import com.woocommerce.android.model.ShippingRate.Option.DEFAULT
import com.woocommerce.android.model.ShippingRate.Option.SIGNATURE
import com.woocommerce.android.ui.orders.shippinglabels.ShippingLabelRepository
import com.woocommerce.android.ui.orders.shippinglabels.creation.ShippingCarrierRatesAdapter.PackageRateListItem
import com.woocommerce.android.ui.orders.shippinglabels.creation.ShippingCarrierRatesAdapter.ShippingRateItem
import com.woocommerce.android.ui.orders.shippinglabels.creation.ShippingCarrierRatesAdapter.ShippingRateItem.ShippingCarrier.FEDEX
import com.woocommerce.android.ui.orders.shippinglabels.creation.ShippingCarrierRatesAdapter.ShippingRateItem.ShippingCarrier.UPS
import com.woocommerce.android.ui.orders.shippinglabels.creation.ShippingCarrierRatesAdapter.ShippingRateItem.ShippingCarrier.USPS
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.util.CurrencyFormatter
import com.woocommerce.android.util.PriceUtils
import com.woocommerce.android.viewmodel.LiveDataDelegateWithArgs
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.Exit
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ExitWithResult
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.ResourceProvider
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.DaggerScopedViewModel
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.launch
import org.wordpress.android.fluxc.model.shippinglabels.WCShippingRatesResult.ShippingPackage
import org.wordpress.android.fluxc.network.BaseRequest.GenericErrorType.NOT_FOUND
import org.wordpress.android.fluxc.network.rest.wpcom.wc.shippinglabels.ShippingLabelRestClient.ShippingRatesApiResponse.ShippingOption.Rate
import java.math.BigDecimal

class ShippingCarrierRatesViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val shippingLabelRepository: ShippingLabelRepository,
    private val resourceProvider: ResourceProvider,
    private val currencyFormatter: CurrencyFormatter
) : DaggerScopedViewModel(savedState, dispatchers) {
    companion object {
        private const val DEFAULT_RATE_OPTION = "default"
        private const val SIGNATURE_RATE_OPTION = "signature_required"
        private const val ADULT_SIGNATURE_RATE_OPTION = "adult_signature_required"
        private const val CARRIER_USPS_KEY = "usps"
        private const val CARRIER_UPS_KEY = "ups"
        private const val CARRIER_FEDEX_KEY = "fedex"
        private const val FLAT_RATE_KEY = "flat_rate"
        private const val FREE_SHIPPING_KEY = "free_shipping"
        private const val LOCAL_PICKUP_KEY = "local_pickup"
        private const val SHIPPING_METHOD_USPS_TITLE = "USPS"
        private const val SHIPPING_METHOD_DHL_TITLE = "DHL Express"
        private const val SHIPPING_METHOD_FEDEX_TITLE = "Fedex"
        private const val SHIPPING_METHOD_USPS_KEY = "wc_services_usps"
        private const val SHIPPING_METHOD_DHL_KEY = "wc_services_dhlexpress"
        private const val SHIPPING_METHOD_FEDEX_KEY = "wc_services_fedex"
    }
    private val arguments: ShippingCarrierRatesFragmentArgs by savedState.navArgs()

    val viewStateData = LiveDataDelegateWithArgs(savedState, ViewState())
    private var viewState by viewStateData

    private val _shippingRates = MutableLiveData<List<PackageRateListItem>>()
    val shippingRates: LiveData<List<PackageRateListItem>> = _shippingRates

    init {
        if (shippingRates.value.isNullOrEmpty()) {
            loadShippingRates()
        }
    }

    private fun loadShippingRates() {
        launch {
            viewState = viewState.copy(isSkeletonVisible = true)
            loadRates()
            viewState = viewState.copy(isSkeletonVisible = false)
        }
    }

    private suspend fun loadRates() {
        val carrierRatesResult = shippingLabelRepository.getShippingRates(
            arguments.order,
            arguments.originAddress,
            arguments.destinationAddress,
            arguments.packages.toList()
        )

        if (carrierRatesResult.isError) {
            viewState = viewState.copy(isEmptyViewVisible = true, isDoneButtonVisible = false)
            if (carrierRatesResult.error.original != NOT_FOUND) {
                triggerEvent(ShowSnackbar(R.string.shipping_label_shipping_carrier_rates_generic_error))
                triggerEvent(Exit)
            }
        } else {
            updateRates(generateRateModels(carrierRatesResult.model!!))

            var banner: String? = null
            if (arguments.order.shippingTotal > BigDecimal.ZERO) {
                banner = resourceProvider.getString(
                    R.string.shipping_label_shipping_carrier_flat_fee_banner_message,
                    arguments.order.shippingTotal.format(),
                    getShippingMethods(arguments.order).joinToString()
                )
            }
            viewState = viewState.copy(isEmptyViewVisible = false, bannerMessage = banner)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateRateModels(packageRates: List<ShippingPackage>): List<PackageRateListItem> {
        // different shipping options for each package (each package contains a list of different carrier options,
        // such as Express shipping, Priority shipping, etc.)
        return packageRates.mapIndexed { i, pkg ->
            // rates from across different carrier options, grouped by type (here, a default option without any extras)
            val defaultRates = pkg.shippingOptions.first { it.optionId == DEFAULT_RATE_OPTION }.rates

            // rate group (across different carrier options) that require signature option selected
            val ratesWithSignature = pkg.shippingOptions.first { it.optionId == SIGNATURE_RATE_OPTION }.rates

            // rate group (across different carrier options) that require adult signature option selected
            val ratesWithAdultSignature = pkg.shippingOptions.first { it.optionId == ADULT_SIGNATURE_RATE_OPTION }.rates

            val shippingRates = defaultRates.map { default ->
                val defaultDiscount = default.retailRate.minus(default.rate)

                val signature = ratesWithSignature.firstOrNull { it.serviceId == default.serviceId }
                val signatureFee = signature?.rate?.minus(default.rate)
                val signatureDiscount = signature?.retailRate?.minus(signature.rate) ?: BigDecimal.ZERO

                val adultSignature = ratesWithAdultSignature.firstOrNull { it.serviceId == default.serviceId }
                val adultSignatureFee = adultSignature?.rate?.minus(default.rate)
                val adultSignatureDiscount = adultSignature?.retailRate?.minus(adultSignature.rate) ?: BigDecimal.ZERO

                // we can use the default rate as a base for most of the properties (these are the same for all
                // extra options for a particular carrier option) and we just calculate the price for each extra option
                val options = mapOf(
                    DEFAULT to ShippingRate(
                        pkg.boxId,
                        default.shipmentId,
                        default.rateId,
                        default.serviceId,
                        default.carrierId,
                        default.title,
                        default.deliveryDays,
                        default.rate,
                        defaultDiscount,
                        default.rate.format(),
                        DEFAULT
                    ),
                    SIGNATURE to signature?.let { option ->
                        ShippingRate(
                            pkg.boxId,
                            option.shipmentId,
                            option.rateId,
                            option.serviceId,
                            option.carrierId,
                            option.title,
                            default.deliveryDays,
                            option.rate,
                            signatureDiscount,
                            signatureFee.format(),
                            SIGNATURE
                        )
                    },
                    ADULT_SIGNATURE to adultSignature?.let { option ->
                        ShippingRate(
                            pkg.boxId,
                            option.shipmentId,
                            option.rateId,
                            option.serviceId,
                            option.carrierId,
                            option.title,
                            default.deliveryDays,
                            option.rate,
                            adultSignatureDiscount,
                            adultSignatureFee.format(),
                            ADULT_SIGNATURE
                        )
                    }
                ).filterValues { it != null } as Map<Option, ShippingRate>

                val selectedOption = arguments.selectedRates.firstOrNull {
                    it.packageId == pkg.boxId && it.serviceId == default.serviceId
                }?.option

                ShippingRateItem(
                    serviceId = default.serviceId,
                    title = default.title,
                    deliveryEstimate = default.deliveryDays,
                    deliveryDate = default.deliveryDate,
                    carrier = getCarrier(default),
                    isTrackingAvailable = default.hasTracking,
                    isFreePickupAvailable = default.isPickupFree,
                    isInsuranceAvailable = default.insurance > BigDecimal.ZERO,
                    insuranceCoverage = default.insurance.format(),
                    options = options,
                    selectedOption = selectedOption
                )
            }

            PackageRateListItem(
                pkg.boxId,
                itemCount = arguments.packages[i].items.size,
                rateOptions = shippingRates
            )
        }
    }

    private fun getShippingMethods(order: Order): List<String> {
        return order.shippingMethods.map {
            when (it.id) {
                FLAT_RATE_KEY -> resourceProvider.getString(R.string.shipping_label_shipping_method_flat_rate)
                FREE_SHIPPING_KEY -> resourceProvider.getString(R.string.shipping_label_shipping_method_free_shipping)
                LOCAL_PICKUP_KEY -> resourceProvider.getString(R.string.shipping_label_shipping_method_local_pickup)
                SHIPPING_METHOD_USPS_KEY -> SHIPPING_METHOD_USPS_TITLE
                SHIPPING_METHOD_FEDEX_KEY -> SHIPPING_METHOD_FEDEX_TITLE
                SHIPPING_METHOD_DHL_KEY -> SHIPPING_METHOD_DHL_TITLE
                else -> resourceProvider.getString(R.string.other)
            }
        }
    }

    // TODO: Once we start supporting countries other than the US, we'll need to verify what currency the shipping labels purchases use
    private fun BigDecimal?.format(): String {
        return when {
            this == null -> "N/A"
            this.isEqualTo(BigDecimal.ZERO) -> resourceProvider.getString(R.string.free)
            else -> "+${PriceUtils.formatCurrency(this, arguments.order.currency, currencyFormatter)}"
        }
    }

    private fun getCarrier(it: Rate) =
        when (it.carrierId) {
            CARRIER_USPS_KEY -> USPS
            CARRIER_FEDEX_KEY -> FEDEX
            CARRIER_UPS_KEY -> UPS
            else -> throw IllegalArgumentException("Unsupported carrier ID: `${it.carrierId}`")
        }

    fun onShippingRateSelected(rate: ShippingRate) {
        shippingRates.value?.let { packageRates ->
            updateRates(
                packageRates.map {
                    // update the selected rate for the specific package
                    if (it.id == rate.packageId) {
                        it.updateSelectedRateAndCopy(rate)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun updateRates(packageRates: List<PackageRateListItem>) {
        _shippingRates.value = packageRates
        viewState = viewState.copy(isDoneButtonVisible = packageRates.all { it.hasSelectedOption })
    }

    fun onDoneButtonClicked() {
        AnalyticsTracker.track(Stat.SHIPPING_LABEL_SHIPPING_CARRIER_DONE_BUTTON_TAPPED)

        val selectedRates = shippingRates.value?.let { rates ->
            rates.map { it.selectedRate }
        }
        triggerEvent(ExitWithResult(selectedRates))
    }

    fun onExit() {
        triggerEvent(Exit)
    }

    @Parcelize
    data class ViewState(
        val bannerMessage: String? = null,
        val isSkeletonVisible: Boolean = false,
        val isEmptyViewVisible: Boolean = false,
        val isDoneButtonVisible: Boolean = false
    ) : Parcelable

    @AssistedFactory
    interface Factory : ViewModelAssistedFactory<ShippingCarrierRatesViewModel>
}
