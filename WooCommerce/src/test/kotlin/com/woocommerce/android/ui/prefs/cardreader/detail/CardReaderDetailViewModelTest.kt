package com.woocommerce.android.ui.prefs.cardreader.detail

import androidx.lifecycle.SavedStateHandle
import com.woocommerce.android.ui.prefs.cardreader.detail.CardReaderDetailViewModel.NavigationTarget
import com.woocommerce.android.util.CoroutineTestRule
import com.woocommerce.android.viewmodel.BaseUnitTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class CardReaderDetailViewModelTest : BaseUnitTest() {
    @get:Rule
    val coroutinesTestRule = CoroutineTestRule()

    private lateinit var viewModel: CardReaderDetailViewModel

    @Before
    fun setUp() {
        viewModel = CardReaderDetailViewModel(
            SavedStateHandle(),
            coroutinesTestRule.testDispatchers
        )
    }

    @Test
    fun `when connect button clicked, then the app navigates to connect screen`() {
        viewModel.onConnectBtnClicked()

        assertThat(viewModel.event.value)
            .isInstanceOf(NavigationTarget.CardReaderConnectScreen::class.java)
    }
}
