package com.woocommerce.android.ui.products.variations.attributes.edit

import android.os.Parcelable
import androidx.lifecycle.MutableLiveData
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.extensions.pairMap
import com.woocommerce.android.model.ProductAttribute
import com.woocommerce.android.model.VariantOption
import com.woocommerce.android.ui.products.ProductDetailRepository
import com.woocommerce.android.ui.products.variations.VariationDetailRepository
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.viewmodel.LiveDataDelegateWithArgs
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.Exit
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ExitWithResult
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.DaggerScopedViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

class EditVariationAttributesViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val productRepository: ProductDetailRepository,
    private val variationRepository: VariationDetailRepository
) : DaggerScopedViewModel(savedState, dispatchers) {
    private val _editableVariationAttributeList =
        MutableLiveData<List<VariationAttributeSelectionGroup>>()

    val editableVariationAttributeList = _editableVariationAttributeList

    val viewStateLiveData = LiveDataDelegateWithArgs(savedState, ViewState())
    private var viewState by viewStateLiveData

    private val selectedVariation
        get() = variationRepository.getVariation(
            viewState.parentProductID,
            viewState.editableVariationID
        )

    private val parentProduct
        get() = productRepository.getProduct(viewState.parentProductID)

    private val hasChanges
        get() = editableVariationAttributeList.value?.toTypedArray()
            ?.contentDeepEquals(viewState.updatedAttributeSelection.toTypedArray())
            ?: false

    fun start(productId: Long, variationId: Long) {
        viewState = viewState.copy(
            parentProductID = productId,
            editableVariationID = variationId
        )
        loadProductAttributes()
    }

    fun exit() {
        if (hasChanges) triggerEvent(ExitWithResult(
            viewState.updatedAttributeSelection
                .mapNotNull { it.toVariantOption() }
                .toTypedArray()
        ))
        else triggerEvent(Exit)
    }

    fun updateData(attributeSelection: List<VariationAttributeSelectionGroup>) {
        viewState = viewState.copy(updatedAttributeSelection = attributeSelection)
    }

    private fun loadProductAttributes() =
        viewState.copy(isSkeletonShown = true).let { viewState = it }.also {
            launch(context = dispatchers.computation) {
                parentProduct?.variationEnabledAttributes
                    ?.pairAttributeWithSelectedOption()
                    ?.pairAttributeWithUnselectedOption()
                    ?.mapToAttributeSelectionGroupList()
                    ?.dispatchListResult()
                    ?: updateSkeletonVisibility(visible = false)
            }
        }

    private fun List<ProductAttribute>.pairAttributeWithSelectedOption() =
        mapNotNull { attribute ->
            selectedVariation?.attributes
                ?.find { it.name == attribute.name }
                ?.let { Pair(attribute, it) }
        }

    private fun List<Pair<ProductAttribute, VariantOption>>.pairAttributeWithUnselectedOption() =
        map { it.first }.let { selectedAttributes ->
            parentProduct?.variationEnabledAttributes
                ?.filter { selectedAttributes.contains(it).not() }
                ?.map { it to VariantOption.empty }
                ?.let { toMutableList().apply { addAll(it) } }
        }

    private fun List<Pair<ProductAttribute, VariantOption>>.mapToAttributeSelectionGroupList() =
        pairMap { productAttribute, selectedOption ->
            VariationAttributeSelectionGroup(
                id = productAttribute.id,
                attributeName = productAttribute.name,
                options = productAttribute.terms,
                selectedOptionIndex = productAttribute.terms.indexOf(selectedOption.option),
                noOptionSelected = selectedOption.option.isNullOrEmpty()
            )
        }

    private suspend fun List<VariationAttributeSelectionGroup>.dispatchListResult() =
        withContext(dispatchers.main) {
            editableVariationAttributeList.value = this@dispatchListResult
            updateSkeletonVisibility(visible = false)
        }

    private suspend fun updateSkeletonVisibility(visible: Boolean) =
        withContext(dispatchers.main) {
            viewState = viewState.copy(isSkeletonShown = visible)
        }

    @Parcelize
    data class ViewState(
        val isSkeletonShown: Boolean? = null,
        val parentProductID: Long = 0L,
        val editableVariationID: Long = 0L,
        val updatedAttributeSelection: List<VariationAttributeSelectionGroup> = emptyList()
    ) : Parcelable

    @AssistedFactory
    interface Factory : ViewModelAssistedFactory<EditVariationAttributesViewModel>
}
