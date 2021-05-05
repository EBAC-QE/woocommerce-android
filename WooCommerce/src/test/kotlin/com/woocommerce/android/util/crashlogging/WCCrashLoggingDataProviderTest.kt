package com.woocommerce.android.util.crashlogging

import com.automattic.android.tracks.crashlogging.EventLevel.INFO
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import com.woocommerce.android.AppPrefs
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.util.crashlogging.WCCrashLoggingDataProvider.Companion.EXTRA_UUID
import com.woocommerce.android.util.crashlogging.WCCrashLoggingDataProvider.Companion.SITE_ID_KEY
import com.woocommerce.android.util.crashlogging.WCCrashLoggingDataProvider.Companion.SITE_URL_KEY
import com.woocommerce.android.util.locale.LocaleProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import org.wordpress.android.fluxc.model.AccountModel
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.store.AccountStore
import java.util.Locale

@RunWith(MockitoJUnitRunner::class)
class WCCrashLoggingDataProviderTest {
    lateinit var sut: WCCrashLoggingDataProvider

    private val localeProvider: LocaleProvider = mock()
    private val accountStore: AccountStore = mock()
    private val selectedSite: SelectedSite = mock()
    private val appPrefs: AppPrefs = mock()
    private val encryptedLogging: EncryptedLogging = mock()
    private val uuidGenerator: UuidGenerator = mock()

    @Before
    fun setUp() {
        sut = WCCrashLoggingDataProvider(
            localeProvider = localeProvider,
            accountStore = accountStore,
            selectedSite = selectedSite,
            appPrefs = appPrefs,
            encryptedLogging = encryptedLogging,
            uuidGenerator = uuidGenerator
        )
    }

    @Test
    fun `should provide site id and site url in apps context if selected site exists`() {
        whenever(selectedSite.getIfExists()).thenReturn(TEST_SITE_MODEL)

        val appContext = sut.applicationContextProvider()

        assertThat(appContext).containsAllEntriesOf(
            mapOf(
                SITE_ID_KEY to TEST_SITE_MODEL.siteId.toString(),
                SITE_URL_KEY to TEST_SITE_MODEL.url
            )
        )
    }

    @Test
    fun `should provide empty apps context if selected site does not exist`() {
        whenever(selectedSite.getIfExists()).thenReturn(null)

        val appContext = sut.applicationContextProvider()

        assertThat(appContext).isEmpty()
    }

    @Test
    fun `should enable crash logging if crash logging is enabled`() {
        whenever(appPrefs.isCrashReportingEnabled()).thenReturn(true)

        val crashLoggingEnabled = sut.crashLoggingEnabled()

        assertThat(crashLoggingEnabled).isTrue
    }

    @Test
    fun `should disable crash logging if crash logging is disabled`() {
        whenever(appPrefs.isCrashReportingEnabled()).thenReturn(false)

        val crashLoggingEnabled = sut.crashLoggingEnabled()

        assertThat(crashLoggingEnabled).isFalse
    }

    @Test
    fun `should not include extra keys for events`() {
        assertThat(sut.extraKnownKeys()).containsOnly(EXTRA_UUID)
    }

    @Test
    fun `should provide correctly mapped user if user exists`() {
        whenever(accountStore.account).thenReturn(TEST_ACCOUNT)

        val user = sut.userProvider()

        SoftAssertions().apply {
            assertThat(user?.username).isEqualTo(TEST_ACCOUNT.userName)
            assertThat(user?.email).isEqualTo(TEST_ACCOUNT.email)
            assertThat(user?.userID).isEqualTo(TEST_ACCOUNT.userId.toString())
        }.assertAll()
    }

    @Test
    fun `should not provide user if user does not exist`() {
        whenever(accountStore.account).thenReturn(null)

        val user = sut.userProvider()

        assertThat(user).isNull()
    }

    @Test
    fun `should provide recent locale after locale change`() {
        whenever(localeProvider.provideLocale()).thenReturn(Locale.US)

        assertThat(sut.locale).isEqualTo(Locale.US)

        whenever(localeProvider.provideLocale()).thenReturn(Locale.CANADA)

        assertThat(sut.locale).isEqualTo(Locale.CANADA)
    }

    @Test
    fun `should request encrypted logs upload when providing extras for event`() {
        val generatedUuid = "123"
        whenever(uuidGenerator.generateUuid()).thenReturn(generatedUuid)

        val extras = sut.provideExtrasForEvent(currentExtras = emptyMap(), eventLevel = INFO)

        verify(encryptedLogging, times(1)).enqueue(generatedUuid, false)
        assertThat(extras).containsValue(generatedUuid)
    }

    @Test
    fun `should not request encrypted logs upload when uuid is already provided`() {
        val generatedUuid = "123"

        val extras = sut.provideExtrasForEvent(currentExtras = mapOf("uuid" to generatedUuid), eventLevel = INFO)

        verify(encryptedLogging, never()).enqueue(any(), any())
        assertThat(extras).containsValue(generatedUuid)
    }

    companion object {
        val TEST_ACCOUNT = AccountModel().apply {
            userId = 123L
            email = "mail@a8c.com"
            userName = "username"
        }

        val TEST_SITE_MODEL = SiteModel().apply {
            siteId = 7L
            url = "automattic.com"
        }
    }
}