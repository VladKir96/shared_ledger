package dev.sharedledger.data

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate

class RateProvidersTest {
    private val day = LocalDate.parse("2025-01-19")

    @Test fun frankfurterKeepsDatedObservationsAndRejectsFutureOrInvalidRows() {
        val rows = parseFrankfurter(
            """
            [
              {"date":"2025-01-16","base":"EUR","quote":"RSD","rate":117.1100001},
              {"date":"2025-01-17","base":"EUR","quote":"RSD","rate":117.16},
              {"date":"2025-01-17","base":"EUR","quote":"RUB","rate":105.59},
              {"date":"2025-01-20","base":"EUR","quote":"RUB","rate":105.80},
              {"date":"2025-01-19","base":"EUR","quote":"CHF","rate":0},
              {"date":"invalid","base":"EUR","quote":"CHF","rate":1},
              {"date":"2025-01-19","base":"USD","quote":"CHF","rate":1}
            ]
            """.trimIndent(), "EUR", setOf("RSD", "RUB", "CHF"), day
        )
        assertEquals(listOf("117.1100001", "117.16", "105.59"), rows.map(Rate::value))
        assertEquals(listOf("2025-01-16", "2025-01-17", "2025-01-17"), rows.map(Rate::date))
        assertTrue(rows.all { it.requestedDate == day.toString() })
    }

    @Test fun datedFallbackUsesOnlyAvailableDecimalQuotes() {
        val raw = """{"date":"2025-01-19","eur":{"rsd":116.83125676,"rub":105.2594938,"usd":1.02691999}}"""
        assertEquals(mapOf("RSD" to "116.83125676", "RUB" to "105.2594938"),
            parseCurrencyApi(raw, "EUR", setOf("RSD", "RUB", "CHF"), day).associate { it.target to it.value })
        assertTrue(parseCurrencyApi(raw, "EUR", setOf("RSD"), day.minusDays(1)).isEmpty())
        assertThrows(java.io.IOException::class.java) { parseCurrencyApi("{}", "EUR", setOf("RSD"), day) }
        assertThrows(java.io.IOException::class.java) { parseFrankfurter("{}", "EUR", setOf("RSD"), day) }
    }

    @Test fun fallbackTriesPriorFilesWhenTheRequestedDayIsUnavailable() = runBlocking {
        val requested = mutableListOf<String>()
        val provider = CurrencyApiRates(object : CurrencyApi {
            override suspend fun rates(day: String, base: String): Response<okhttp3.ResponseBody> {
                requested.add(day)
                if (requested.size == 1) return Response.error(404, "".toResponseBody())
                return Response.success("""{"date":"$day","eur":{"rsd":116.83,"rub":105.2}}""".toResponseBody())
            }
        })
        val rows = provider.lookup("EUR", setOf("RSD", "RUB"), day)
        assertEquals(listOf("2025-01-19", "2025-01-18"), requested)
        assertTrue(rows.all { it.date == "2025-01-18" && it.requestedDate == "2025-01-19" })
    }

    @Test fun rateLimitHonorsRetryAfter() {
        val provider = FrankfurterRates(object : FrankfurterApi {
            override suspend fun rates(base: String, targets: String, from: String, to: String): Response<okhttp3.ResponseBody> {
                val raw = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://example.test").build())
                    .protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                    .headers(Headers.headersOf("Retry-After", "120")).build()
                return Response.error("".toResponseBody(), raw)
            }
        })
        val error = assertThrows(RateLimited::class.java) {
            runBlocking { provider.lookup("EUR", setOf("RSD"), day) }
        }
        assertEquals(120_000L, error.retryDelayMillis)
    }
}
