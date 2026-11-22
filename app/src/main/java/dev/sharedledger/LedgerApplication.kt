package dev.sharedledger

import android.app.Application
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.sharedledger.data.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.inject.Inject
import dev.sharedledger.sync.Background

@HiltAndroidApp
class LedgerApplication : Application() {
    @Inject lateinit var background: Background
    override fun onCreate() {
        super.onCreate()
        background.start()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LedgerModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): LedgerDatabase =
        Room.databaseBuilder(context, LedgerDatabase::class.java, "sharedledger.db")
            .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6).build()

    @Provides
    fun dao(database: LedgerDatabase): LedgerDao = database.ledger()

    @Provides @Singleton
    fun http(): OkHttpClient = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    @Provides
    fun exchangeApi(client: OkHttpClient): FrankfurterApi =
        Retrofit.Builder().baseUrl("https://api.frankfurter.dev/").client(client).build().create(FrankfurterApi::class.java)

    @Provides
    fun currencyApi(client: OkHttpClient): CurrencyApi =
        Retrofit.Builder().baseUrl("https://cdn.jsdelivr.net/").client(client).build().create(CurrencyApi::class.java)

    @Provides
    fun providers(frankfurter: FrankfurterRates, fallback: CurrencyApiRates): List<@JvmSuppressWildcards RateProvider> =
        listOf(frankfurter, fallback)
}
