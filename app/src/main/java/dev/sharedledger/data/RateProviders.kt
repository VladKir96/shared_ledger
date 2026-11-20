package dev.sharedledger.data

import dev.sharedledger.domain.storage
import kotlinx.serialization.json.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

interface RateProvider {
    val name: String
    suspend fun lookup(base: String, targets: Set<String>, day: LocalDate): List<Rate>
}

interface FrankfurterApi {
    @GET("v2/rates")
    suspend fun rates(@Query("base") base: String, @Query("quotes") targets: String,
                      @Query("from") from: String, @Query("to") to: String): Response<ResponseBody>
}

interface CurrencyApi {
    @GET("npm/@fawazahmed0/currency-api@{date}/v1/currencies/{base}.min.json")
    suspend fun rates(@Path("date") day: String, @Path("base") base: String): Response<ResponseBody>
}

class FrankfurterRates @Inject constructor(private val api: FrankfurterApi) : RateProvider {
    override val name = "Frankfurter"

    override suspend fun lookup(base: String, targets: Set<String>, day: LocalDate): List<Rate> {
        val response = api.rates(base, targets.sorted().joinToString(","), day.minusDays(RATE_LOOKBACK).toString(), day.toString())
        return response.payload()?.let { parseFrankfurter(it, base, targets, day) }.orEmpty()
    }
}

class CurrencyApiRates @Inject constructor(private val api: CurrencyApi) : RateProvider {
    override val name = "Currency API"

    override suspend fun lookup(base: String, targets: Set<String>, day: LocalDate): List<Rate> {
        val code = base.lowercase(Locale.ROOT)
        val results = mutableListOf<Rate>()
        val missing = targets.toMutableSet()
        // Dated files are published daily. Also check the prior files when the requested file is missing.
        for (offset in 0L..3L) {
            val raw = api.rates(day.minusDays(offset).toString(), code).payload() ?: continue
            val found = parseCurrencyApi(raw, base, missing, day)
            results.addAll(found)
            missing.removeAll(found.map { it.target }.toSet())
            if (missing.isEmpty() || found.isEmpty()) break
        }
        return results
    }
}

internal const val RATE_LOOKBACK = 7L

internal class RateLimited(val retryDelayMillis: Long) : IOException("Rate provider limit reached")

internal fun parseFrankfurter(raw: String, base: String, targets: Set<String>, day: LocalDate): List<Rate> {
    val array = ledgerJson.parseToJsonElement(raw) as? JsonArray ?: throw IOException("Invalid rate response")
    return array.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val target = row.text("quote") ?: return@mapNotNull null
        if (row.text("base") != base || target !in targets) return@mapNotNull null
        observation(base, target, day, row.text("date"), row.text("rate"), "Frankfurter")
    }.also {
        if (array.isNotEmpty() && it.isEmpty()) throw IOException("Response contained no valid requested rates")
    }
}

internal fun parseCurrencyApi(raw: String, base: String, targets: Set<String>, day: LocalDate): List<Rate> {
    val row = ledgerJson.parseToJsonElement(raw) as? JsonObject ?: throw IOException("Invalid rate response")
    val date = row.text("date") ?: throw IOException("Missing publication date")
    val rates = row[base.lowercase(Locale.ROOT)] as? JsonObject ?: throw IOException("Missing base currency")
    return targets.mapNotNull { target ->
        observation(base, target, day, date, rates.text(target.lowercase(Locale.ROOT)), "Currency API")
    }
}

private fun observation(base: String, target: String, day: LocalDate, effective: String?, value: String?, source: String): Rate? {
    val date = effective?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val factor = value?.takeIf { it.length <= 60 }?.toBigDecimalOrNull() ?: return null
    if (date > day || date < day.minusDays(RATE_LOOKBACK) || factor.signum() <= 0 ||
        factor.precision() - factor.scale() > 20 || factor.scale() > 40) return null
    return Rate(base, target, day.toString(), date.toString(), factor.storage(), source)
}

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

private fun Response<ResponseBody>.payload(): String? {
    if (isSuccessful) return body()?.use { it.string() } ?: throw IOException("Empty rate response")
    errorBody()?.close()
    if (code() == 429) {
        val seconds = headers()["Retry-After"]?.toLongOrNull()?.coerceIn(30, 21_600) ?: 60
        throw RateLimited(seconds * 1000)
    }
    if (code() in listOf(400, 404, 422)) return null
    throw IOException("Rate provider returned HTTP ${code()}")
}
