package dev.sharedledger.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sharedledger.BuildConfig
import dev.sharedledger.data.ledgerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class Session(val userId: String, val access: String, val refresh: String, val expiresAt: Long, val email: String = "")

class CloudError(
    val status: Int, message: String, val code: String? = null, val retryAfterSeconds: Long? = null
) : IllegalArgumentException(message)

interface AuthTransport {
    suspend fun call(path: String, body: JsonObject? = null, token: String? = null, method: String? = null): JsonElement
}

interface AuthVault {
    fun read(): Session?
    fun write(session: Session)
    fun clear()
    fun readValue(name: String): String?
    fun writeValue(name: String, value: String)
    fun clearValue(name: String)
}

@Singleton
class CloudApi @Inject constructor(val client: OkHttpClient) : AuthTransport {
    val url = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val configured = url.startsWith("https://") && key.isNotBlank()
    val realtimeUrl get() = url.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"

    override suspend fun call(path: String, body: JsonObject?, token: String?, method: String?): JsonElement =
        withContext(Dispatchers.IO) {
            require(configured) { "Add a Supabase project URL and publishable key to enable sync." }
            val builder = Request.Builder().url(url + path).header("apikey", key).header("Accept", "application/json")
            if (token != null) builder.header("Authorization", "Bearer $token")
            val raw = body?.toString()?.toRequestBody("application/json".toMediaType())
            builder.method(method ?: if (raw == null) "GET" else "POST", raw)
            try {
                client.newCall(builder.build()).execute().use { response ->
                    val value = response.body?.string().orEmpty()
                    val parsed = try { ledgerJson.parseToJsonElement(value) } catch (_: Exception) { JsonNull }
                    if (!response.isSuccessful) {
                        val objectBody = parsed as? JsonObject
                        val message = if (path.startsWith("/auth/v1/")) null else listOf("msg", "error_description", "message", "error").firstNotNullOfOrNull { field ->
                            (objectBody?.get(field) as? JsonPrimitive)?.contentOrNull?.take(180)
                        }
                        val code = (objectBody?.string("error_code") ?: objectBody?.string("code"))
                            ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) }
                        throw CloudError(response.code, message ?: "The server returned HTTP ${response.code}.",
                            code, response.header("Retry-After")?.toLongOrNull())
                    }
                    parsed
                }
            } catch (_: IOException) {
                throw CloudError(0, "The server is unreachable. Check your connection and try again.")
            }
        }

    suspend fun rpc(name: String, token: String, body: JsonObject = buildJsonObject { }): JsonElement =
        call("/rest/v1/rpc/$name", body, token)
}

@Singleton
class SessionVault @Inject constructor(@ApplicationContext context: Context) : AuthVault {
    private val prefs = context.getSharedPreferences("sharedledger.auth", Context.MODE_PRIVATE)
    private val alias = "dev.sharedledger.session.v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey = keyStore.getKey(alias, null) as? SecretKey
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }

    @Synchronized override fun read(): Session? = readValue("identity")?.let {
        runCatching { ledgerJson.decodeFromString<Session>(it) }.getOrElse { clear(); null }
    }

    @Synchronized override fun write(session: Session) = writeValue("identity", ledgerJson.encodeToString(Session.serializer(), session))

    @Synchronized override fun readValue(name: String): String? {
        val saved = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
        } catch (_: Exception) {
            clearValue(name)
            null
        }
    }

    @Synchronized override fun writeValue(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }

    @Synchronized override fun clearValue(name: String) { prefs.edit().remove(name).commit() }
    @Synchronized override fun clear() { clearValue("identity") }

}

fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

