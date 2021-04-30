package com.woocommerce.android.di

import com.woocommerce.android.cardreader.CardReaderManager
import com.woocommerce.android.cardreader.CardReaderManagerFactory
import com.woocommerce.android.cardreader.CardReaderStore
import com.woocommerce.android.tools.SelectedSite
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import org.wordpress.android.fluxc.store.WCPayStore
import javax.annotation.Nullable
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class CardReaderModule {
    @Provides
    @Singleton
    fun provideCardReaderManager(selectedSite: SelectedSite, payStore: WCPayStore): CardReaderManager {
        return CardReaderManagerFactory.createCardReaderManager(object : CardReaderStore {
            override suspend fun getConnectionToken(): String {
                val result = payStore.fetchConnectionToken(selectedSite.get())
                return result.model?.token.orEmpty()
            }

            override suspend fun capturePaymentIntent(id: String): Boolean {
                // TODO cardreader Invoke capturePayment on WCPayStore
                delay(1000)
                return true
            }
        })
    }
}
