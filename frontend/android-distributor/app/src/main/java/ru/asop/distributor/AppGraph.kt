package ru.asop.distributor

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import ru.asop.distributor.cert.AsopKeyManager
import ru.asop.distributor.cert.CertManager
import ru.asop.distributor.core.KeyProvider
import ru.asop.distributor.core.PaymentClient
import ru.asop.distributor.core.TariffProvider
import ru.asop.distributor.network.CertSignApi
import ru.asop.distributor.network.CertSignHmacInterceptor
import ru.asop.distributor.network.DistributorSyncApi
import ru.asop.distributor.sync.ProvisionSyncManager
import ru.asop.distributor.sync.SyncStore
import ru.asop.nfc.TerminalKeyCryptor
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Ручной граф зависимостей (без Hilt, как в остальном distributor).
 * Строится из Application; экраны тянут singletons через graph.
 */
class AppGraph(context: Context) {

    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val cryptor: TerminalKeyCryptor = TerminalKeyCryptor()
    val certManager: CertManager = CertManager(context)
    val syncStore: SyncStore = SyncStore(context, moshi)

    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Plain (без mTLS) клиент для cert-sign + polling событий. */
    private val plainOkHttp: OkHttpClient by lazy {
        val trustAll = trustAll()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(CertSignHmacInterceptor(BuildConfig.CERT_SIGN_API_KEY, BuildConfig.CERT_SIGN_HMAC_SECRET))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    /** mTLS клиент (сертификат дистрибьютора из AndroidKeyStore). */
    private val mTlsOkHttp: OkHttpClient by lazy {
        val trustAll = trustAll()
        val keyManager = AsopKeyManager(certManager)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(keyManager), arrayOf(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS })
            .build()
    }

    val certSignApi: CertSignApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.GATEWAY_BASE_URL)
            .client(plainOkHttp)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CertSignApi::class.java)
    }

    val distributorSyncApi: DistributorSyncApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.GATEWAY_BASE_URL)
            .client(mTlsOkHttp)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DistributorSyncApi::class.java)
    }

    val syncManager: ProvisionSyncManager by lazy {
        ProvisionSyncManager(context, certManager, syncStore, certSignApi, distributorSyncApi, moshi)
    }

    val keyProvider: KeyProvider by lazy { KeyProvider(syncStore, cryptor) }
    val tariffProvider: TariffProvider by lazy { TariffProvider(syncStore) }
    val paymentClient: PaymentClient = PaymentClient()
}