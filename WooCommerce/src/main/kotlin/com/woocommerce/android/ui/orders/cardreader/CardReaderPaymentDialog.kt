package com.woocommerce.android.ui.orders.cardreader

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.woocommerce.android.R
import com.woocommerce.android.WooCommerce
import com.woocommerce.android.databinding.FragmentCardReaderPaymentBinding
import com.woocommerce.android.util.UiHelpers
import com.woocommerce.android.viewmodel.ViewModelFactory
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

class CardReaderPaymentDialog : DialogFragment(R.layout.fragment_card_reader_payment), HasAndroidInjector {
    @Inject lateinit var viewModelFactory: ViewModelFactory
    @Inject internal lateinit var androidInjector: DispatchingAndroidInjector<Any>

    val viewModel: CardReaderPaymentViewModel by viewModels { viewModelFactory }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = FragmentCardReaderPaymentBinding.bind(view)

        initObservers(binding)
        initViewModel()
    }

    private fun initViewModel() {
        val manager = (requireActivity().application as WooCommerce).cardReaderManager
        // TODO card reader: remove !! when cardReaderManager is changed to a nonnullable type in WooCommerce
        viewModel.start(manager!!)
    }

    private fun initObservers(binding: FragmentCardReaderPaymentBinding) {
        viewModel.viewStateData.observe(viewLifecycleOwner, Observer { viewState ->
            UiHelpers.setTextOrHide(binding.headerLabel, viewState.headerLabel)
            UiHelpers.setTextOrHide(binding.amountLabel, viewState.amountWithCurrencyLabel)
            UiHelpers.setImageOrHide(binding.illustration, viewState.illustration)
            UiHelpers.setTextOrHide(binding.paymentStateLabel, viewState.paymentStateLabel)
            UiHelpers.setTextOrHide(binding.hintLabel, viewState.hintLabel)
            UiHelpers.setTextOrHide(binding.primaryActionBtn, viewState.primaryActionLabel)
            UiHelpers.setTextOrHide(binding.secondaryActionBtn, viewState.secondaryActionLabel)
            UiHelpers.updateVisibility(binding.progressBar, viewState.isProgressVisible)
            binding.primaryActionBtn.setOnClickListener {
                viewState.onPrimaryActionClicked?.invoke()
            }
            binding.secondaryActionBtn.setOnClickListener {
                viewState.onSecondaryActionClicked?.invoke()
            }
        })
    }

    override fun androidInjector(): AndroidInjector<Any> = androidInjector
}
