package com.woocommerce.android.ui.orders.tracking

import android.os.Parcelable
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.ORDER_SHIPMENT_TRACKING_CARRIER_SELECTED
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.ORDER_SHIPMENT_TRACKING_CUSTOM_PROVIDER_SELECTED
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.model.OrderShipmentProvider
import com.woocommerce.android.ui.orders.details.OrderDetailRepository
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.viewmodel.LiveDataDelegateWithArgs
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ExitWithResult
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.ResourceProvider
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.DaggerScopedViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import org.wordpress.android.fluxc.model.order.OrderIdentifier

class AddOrderTrackingProviderListViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val shipmentProvidersRepository: OrderShipmentProvidersRepository,
    private val orderDetailRepository: OrderDetailRepository,
    private val resourceProvider: ResourceProvider
) : DaggerScopedViewModel(savedState, dispatchers) {
    private val navArgs: AddOrderTrackingProviderListFragmentArgs by savedState.navArgs()

    val trackingProviderListViewStateData = LiveDataDelegateWithArgs(
        savedState = savedState,
        initialValue = ViewState()
    )
    private var trackingProviderListViewState by trackingProviderListViewStateData

    private var providersList = emptyList<OrderShipmentProvider>()

    val orderId: OrderIdentifier
        get() = navArgs.orderId

    val currentSelectedProvider: String
        get() = navArgs.selectedProvider

    val countryCode: String?
        get() = orderDetailRepository.getStoreCountryCode()

    init {
        fetchProviders()
    }

    private fun fetchProviders() {
        trackingProviderListViewState = trackingProviderListViewState.copy(showSkeleton = true)
        launch {
            val shipmentProviders = shipmentProvidersRepository.fetchOrderShipmentProviders(orderId)
            trackingProviderListViewState = trackingProviderListViewState.copy(showSkeleton = false)
            when {
                shipmentProviders == null -> {
                    triggerEvent(ShowSnackbar(R.string.order_shipment_tracking_provider_list_error_fetch_generic))
                }
                shipmentProviders.isEmpty() -> {
                    triggerEvent(ShowSnackbar(R.string.order_shipment_tracking_provider_list_error_empty_list))
                }
                else -> {
                    AnalyticsTracker.track(Stat.ORDER_TRACKING_PROVIDERS_LOADED)
                    providersList = shipmentProviders
                    trackingProviderListViewState = trackingProviderListViewState.copy(
                        providersList = shipmentProviders
                    )
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        trackingProviderListViewState = trackingProviderListViewState.copy(query = query)
        val filteredList = if (query.isEmpty()) {
            providersList
        } else {
            providersList.filter {
                it.carrierName.contains(query) ||
                    it.country.contains(query) ||
                    it.carrierLink.contains(query)
            }
        }
        trackingProviderListViewState = trackingProviderListViewState.copy(providersList = filteredList)
    }

    fun onProviderSelected(provider: OrderShipmentProvider) {
        val isCustom = provider.carrierName ==
            resourceProvider.getString(R.string.order_shipment_tracking_custom_provider_section_name)

        if (isCustom) {
            AnalyticsTracker.track(ORDER_SHIPMENT_TRACKING_CUSTOM_PROVIDER_SELECTED)
        } else {
            AnalyticsTracker.track(
                ORDER_SHIPMENT_TRACKING_CARRIER_SELECTED,
                mapOf(AnalyticsTracker.KEY_OPTION to provider.carrierName)
            )
        }
        val carrier = Carrier(
            name = if (isCustom) "" else provider.carrierName,
            isCustom = isCustom
        )
        triggerEvent(ExitWithResult(carrier))
    }

    override fun onCleared() {
        super.onCleared()
        shipmentProvidersRepository.onCleanup()
        orderDetailRepository.onCleanup()
    }

    @Parcelize
    data class ViewState(
        val showSkeleton: Boolean = false,
        val providersList: List<OrderShipmentProvider> = emptyList(),
        val query: String = ""
    ) : Parcelable

    @AssistedFactory
    interface Factory : ViewModelAssistedFactory<AddOrderTrackingProviderListViewModel>
}
