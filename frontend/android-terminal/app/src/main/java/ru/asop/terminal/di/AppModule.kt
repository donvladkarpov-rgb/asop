package ru.asop.terminal.di

import android.content.Context
import android.nfc.NfcAdapter
import androidx.room.Room
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import ru.asop.terminal.cert.AsopKeyManager
import ru.asop.terminal.cert.MtlsManager
import ru.asop.terminal.db.AppDatabase
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.db.dao.SessionDao
import ru.asop.terminal.db.dao.TransactionDao
import ru.asop.terminal.db.dao.SyncMetaDao
import ru.asop.terminal.db.dao.DeltaSyncJobDao
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.BuildConfig
import ru.asop.terminal.network.CertSignApi
import ru.asop.terminal.network.GatewayApi
import ru.asop.terminal.network.SyncApi
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    @PlainClient
    fun providePlainOkHttpClient(): OkHttpClient {
        val trustAll = trustAllTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    @Provides
    @Singleton
    @MtlsClient
    fun provideMtlsOkHttpClient(mtlsManager: MtlsManager): OkHttpClient {
        val trustAll = trustAllTrustManager()
        val keyManager = AsopKeyManager(mtlsManager)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(keyManager), arrayOf(trustAll), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS })
            .build()
    }

    @Provides
    @Singleton
    fun provideGatewayApi(
        @MtlsClient mtlsClient: OkHttpClient,
        moshi: Moshi
    ): GatewayApi {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.GATEWAY_BASE_URL)
            .client(mtlsClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GatewayApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSyncApi(
        @MtlsClient mtlsClient: OkHttpClient,
        moshi: Moshi
    ): SyncApi {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.GATEWAY_BASE_URL)
            .client(mtlsClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SyncApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCertSignApi(
        @PlainClient plainClient: OkHttpClient,
        moshi: Moshi
    ): CertSignApi {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.GATEWAY_BASE_URL)
            .client(plainClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CertSignApi::class.java)
    }

    // --- Room ---

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "asop_terminal.db")
            .fallbackToDestructiveMigration()
            .build()
            .also { AppDatabase.instance = it }

    @Provides
    @Singleton
    fun providePendingEventDao(db: AppDatabase): PendingEventDao = db.pendingEventDao()

    @Provides
    @Singleton
    fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()

    @Provides
    @Singleton
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    @Singleton
    fun provideSyncMetaDao(db: AppDatabase): SyncMetaDao = db.syncMetaDao()

    @Provides
    @Singleton
    fun provideDeltaSyncJobDao(db: AppDatabase): DeltaSyncJobDao = db.deltaSyncJobDao()

    @Provides
    @Singleton
    fun provideReferenceRowDao(db: AppDatabase): ReferenceRowDao = db.referenceRowDao()

    @Provides
    @Singleton
    fun provideTerminalKeyDao(db: AppDatabase): TerminalKeyDao = db.terminalKeyDao()

    @Provides
    @Singleton
    fun provideTripPaymentDao(db: AppDatabase): TripPaymentDao = db.tripPaymentDao()

    // --- DataStore ---

    @Provides
    @Singleton
    fun provideSyncPreferences(@ApplicationContext context: Context): SyncPreferences =
        SyncPreferences(context)

    // --- Location ---

    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(@ApplicationContext context: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // --- NFC ---

    @Provides
    @Singleton
    fun provideNfcAdapter(@ApplicationContext context: Context): NfcAdapter? =
        NfcAdapter.getDefaultAdapter(context)

    private fun trustAllTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}