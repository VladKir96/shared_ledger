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
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class Session(val userId: String, val access: String, val refresh: String, val expiresAt: Long, val email: String = "")

class CloudError(val status: Int, message: String) : IllegalArgumentException(message)

@Singleton
class CloudApi @Inject constructor(val client: OkHttpClient) {
    val url = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val configured = url.startsWith("https://") && key.isNotBlank()
    val realtimeUrl get() = url.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"

    suspend fun call(path: String, body: JsonObject? = null, token: String? = null, method: String? = null): JsonElement =
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
                        val message = listOf("msg", "error_description", "message", "error").firstNotNullOfOrNull { field ->
                            (objectBody?.get(field) as? JsonPrimitive)?.contentOrNull?.take(180)
                        }
                        throw CloudError(response.code, message ?: "The server returned HTTP ${response.code}.")
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
class SessionVault @Inject constructor(@ApplicationContext context: Context) {
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

    @Synchronized fun read(): Session? = readValue("identity")?.let {
        runCatching { ledgerJson.decodeFromString<Session>(it) }.getOrElse { clear(); null }
    }

    @Synchronized fun write(session: Session) = writeValue("identity", ledgerJson.encodeToString(Session.serializer(), session))

    @Synchronized fun readValue(name: String): String? {
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

    @Synchronized fun writeValue(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }

    @Synchronized fun clearValue(name: String) { prefs.edit().remove(name).commit() }
    @Synchronized fun clear() { clearValue("identity") }

}

@Singleton
class CloudAuth @Inject constructor(private val api: CloudApi, private val vault: SessionVault, private val repository: dev.sharedledger.data.LedgerRepository) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    val session = kotlinx.coroutines.flow.MutableStateFlow<Session?>(null)

    suspend fun restore() = locked { if (session.value == null) publish(vault.read()) }

    suspend fun signIn(email: String, password: String, name: String?) = locked {
        require(email.trim().length in 3..254 && '@' in email && password.length in 8..128) { "Enter a valid email and a password of at least eight characters." }
        val body = buildJsonObject {
            put("email", email.trim()); put("password", password)
            if (name != null) put("data", buildJsonObject { put("display_name", name.trim().take(80)) })
        }
        val result = api.call(if (name == null) "/auth/v1/token?grant_type=password" else "/auth/v1/signup", body).jsonObject
        sessionFrom(result)?.let { publish(it); true } ?: false
    }

    suspend fun requestRecovery(email: String) = locked {
        require(email.trim().length in 3..254 && '@' in email) { "Enter your email address." }
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val verifier = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), flags)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()), flags)
        vault.writeValue("recovery", verifier)
        api.call("/auth/v1/recover?redirect_to=sharedledger%3A%2F%2Fauth%2Frecovery",
            buildJsonObject {
                put("email", email.trim())
                put("code_challenge", challenge)
                put("code_challenge_method", "s256")
            })
        Unit
    }

    suspend fun finishRecovery(code: String, password: String) = locked {
        require(password.length in 8..128) { "Use at least eight characters." }
        require(code.length in 8..512) { "The password reset link is invalid." }
        val verifier = requireNotNull(vault.readValue("recovery")) { "Request a reset from this device and open the new link here." }
        val response = api.call("/auth/v1/token?grant_type=pkce", buildJsonObject {
            put("auth_code", code)
            put("code_verifier", verifier)
        }).jsonObject
        vault.clearValue("recovery")
        val access = response.string("access_token") ?: throw CloudError(401, "The password reset link has expired.")
        api.call("/auth/v1/user", buildJsonObject { put("password", password) }, access, "PUT")
        try { api.call("/auth/v1/logout?scope=local", buildJsonObject {}, access) } catch (_: CloudError) { }
        Unit
    }

    suspend fun current(forceRefresh: Boolean = false): Session? = locked {
        val old = session.value ?: vault.read()?.also { publish(it) } ?: return@locked null
        if (!forceRefresh && old.expiresAt > System.currentTimeMillis() / 1000 + 45) return@locked old
        try {
            val value = api.call("/auth/v1/token?grant_type=refresh_token", buildJsonObject {
                put("refresh_token", old.refresh)
            }).jsonObject
            val updated = sessionFrom(value) ?: throw CloudError(401, "Sign in again to resume sync.")
            require(updated.userId == old.userId) { "The session identity changed. Sign in again." }
            publish(updated)
            updated
        } catch (e: CloudError) {
            if (e.status in listOf(400, 401, 403)) publish(null)
            throw e
        }
    }

    suspend fun signOut() = locked {
        val old = session.value
        publish(null)
        vault.clearValue("recovery")
        if (old != null) try {
            api.call("/auth/v1/logout?scope=local", buildJsonObject { }, old.access)
        } catch (_: CloudError) {
            // Local sign-out succeeds when the service is unreachable.
        }
    }

    suspend fun deleteAccount() {
        val current = current() ?: throw CloudError(401, "Sign in first.")
        api.call("/functions/v1/delete-account", buildJsonObject { }, current.access)
        repository.purgePrincipal(current.userId)
        signOut()
    }

    private fun publish(value: Session?) {
        if (value == null) vault.clear() else vault.write(value)
        repository.principal.value = value?.userId
        session.value = value
    }

    private fun sessionFrom(json: JsonObject): Session? {
        val access = json.string("access_token") ?: return null
        val refresh = json.string("refresh_token") ?: return null
        val user = json["user"] as? JsonObject ?: return null
        val id = user.string("id") ?: return null
        val expires = json["expires_at"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() / 1000 + (json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600))
        return Session(id, access, refresh, expires, user.string("email").orEmpty())
    }

    private suspend fun <T> locked(action: suspend () -> T): T {
        mutex.lock()
        try { return action() } finally { mutex.unlock() }
    }
}

fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

