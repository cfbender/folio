package dev.cfbender.folio.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

/**
 * Credentials for a KOReader sync server. [userKey] is the MD5 of the password, which is what the
 * protocol sends and what KOReader itself stores, so the password never needs to be kept.
 */
data class KoSyncAccount(val serverUrl: String, val username: String, val userKey: String) {
    companion object {
        fun userKey(password: String): String =
            MessageDigest.getInstance("MD5").digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class KoProgress(
    /** KOReader's partial MD5 of the book file. */
    val document: String,
    /** crengine XPointer of the position, when the pusher knew it. */
    val progress: String? = null,
    /** Fraction of the book read, 0–1. */
    val percentage: Float? = null,
    val device: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    /** Seconds since the epoch at which the server stored this. */
    val timestamp: Long? = null,
)

class KoSyncException(val code: Int, message: String) : IOException(message)

/**
 * Client for the KOReader progress sync protocol (kosync), as served by koreader-sync-server and
 * by Grimmory under `/api/koreader`.
 */
class KoSyncClient(private val http: OkHttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Verifies the account's credentials; throws [KoSyncException] if they are rejected. */
    suspend fun authorize(account: KoSyncAccount) {
        val reply = call(account, "users/auth") { get() }
        // A web app that answers every address with its own page, as Grimmory does outside
        // /api, says 200 here too; only a sync server answers with JSON.
        if (!reply.trimStart().startsWith("{")) throw KoSyncException(404, "No KOReader sync server answered at that address")
    }

    /**
     * Signs in to a sync server at [address]. People tend to enter the address of the server
     * itself, so if no sync server answers there the path Grimmory serves it under is tried too.
     */
    suspend fun signIn(address: String, username: String, password: String): KoSyncAccount {
        val base = address.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
        if (base.toHttpUrlOrNull() == null) throw IllegalArgumentException("That is not a web address")
        val account = KoSyncAccount(base, username.trim(), KoSyncAccount.userKey(password))
        try {
            authorize(account)
            return account
        } catch (e: KoSyncException) {
            if (e.code != 404 || base.endsWith(GRIMMORY_PATH)) throw e
            val nested = account.copy(serverUrl = base + GRIMMORY_PATH)
            try {
                authorize(nested)
            } catch (atNested: KoSyncException) {
                // A sync server there that refuses the login is worth reporting; otherwise the
                // address as typed is the one to report on.
                throw if (atNested.code == 401 || atNested.code == 403) atNested else e
            }
            return nested
        }
    }

    /** Latest stored progress for a document, or null if the server has none. */
    suspend fun pull(account: KoSyncAccount, document: String): KoProgress? {
        val body = try {
            call(account, "syncs/progress/$document") { get() }
        } catch (e: KoSyncException) {
            // Grimmory answers 404 for an unknown book or a book never synced; the reference
            // server answers 200 with an empty object.
            if (e.code == 404) return null
            throw e
        }
        if (body.isBlank()) return null
        return try {
            json.decodeFromString<PulledProgress>(body).takeIf { it.percentage != null || it.progress != null }
                ?.let { KoProgress(document, it.progress, it.percentage, it.device, it.deviceId, it.timestamp) }
        } catch (e: Exception) {
            throw IOException("The sync server sent a reply this app does not understand", e)
        }
    }

    suspend fun push(account: KoSyncAccount, progress: KoProgress) {
        val payload = json.encodeToString(KoProgress.serializer(), progress).toRequestBody(JSON)
        call(account, "syncs/progress") { put(payload) }
    }

    private suspend fun call(account: KoSyncAccount, path: String, method: Request.Builder.() -> Unit): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(account.serverUrl.trimEnd('/') + "/" + path)
                .header("x-auth-user", account.username)
                .header("x-auth-key", account.userKey)
                .header("Accept", "application/vnd.koreader.v1+json")
                .apply(method)
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw KoSyncException(
                        response.code,
                        when (response.code) {
                            401, 403 -> "The sync server rejected the username or password"
                            404 -> "Not found on the sync server"
                            else -> "The sync server answered with HTTP ${response.code}"
                        },
                    )
                }
                body
            }
        }

    /** The pull reply, in which every field is optional. */
    @Serializable
    private data class PulledProgress(
        val progress: String? = null,
        val percentage: Float? = null,
        val device: String? = null,
        @SerialName("device_id") val deviceId: String? = null,
        val timestamp: Long? = null,
    )

    private companion object {
        val JSON = "application/json".toMediaType()
        const val GRIMMORY_PATH = "/api/koreader"
    }
}
