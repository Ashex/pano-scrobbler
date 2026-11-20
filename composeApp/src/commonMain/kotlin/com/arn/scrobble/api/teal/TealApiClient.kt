package com.arn.scrobble.api.teal

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The app password is used only at login; only the returned session is persisted. */
@Serializable
internal data class TealSession(
    val did: String,
    val handle: String,
    val pds: String,
    val accessJwt: String,
    val refreshJwt: String,
    val id: String = UUID.randomUUID().toString(),
) {
    override fun toString() = "TealSession(did=$did)"
}

internal interface TealSessionStore {
    suspend fun load(): TealSession
    suspend fun save(session: TealSession)
}

internal class TealApiException(val error: String, val status: Int) : IOException(
    when {
        error == "AuthFactorTokenRequired" -> "Use an app password, not your account password."
        error in setOf("AuthenticationRequired", "InvalidToken", "ExpiredToken") || status == 401 ->
            "teal.fm session expired or app password rejected. Reconnect in Settings → Accounts using an app password."
        status == 429 -> "Your PDS is rate limiting requests. Please try again later."
        else -> "PDS request failed ($status, ${error.take(80)})."
    }
)

internal class TealApiClient(
    private val http: HttpClient,
    private val store: TealSessionStore? = null,
) {
    // All instances for a persisted session share the lock, including after preference updates.
    private suspend fun <T> authenticated(block: suspend (TealSession) -> T): T {
        val store = checkNotNull(store)
        val initial = store.load()
        return locks.getOrPut(initial.id) { Mutex() }.withLock {
            var session = store.load()
            try {
                block(session)
            } catch (e: TealApiException) {
                if (e.error != "ExpiredToken") throw e
                val refreshed = request(session.pds, "com.atproto.server.refreshSession",
                    buildJsonObject {}, session.refreshJwt)
                require(refreshed.string("did") == session.did) { "PDS returned a different account" }
                session = session.copy(
                    handle = refreshed.string("handle"),
                    accessJwt = refreshed.string("accessJwt"),
                    refreshJwt = refreshed.string("refreshJwt"),
                )
                store.save(session)
                block(session)
            }
        }
    }

    suspend fun login(identifier: String, appPassword: String): TealSession {
        require(identifier.isNotBlank() && appPassword.isNotBlank()) { "Enter your handle and app password." }
        val input = identifier.trim().removePrefix("@")
        val did = run {
            require(input.matches(Regex("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"))) {
                "Enter your full handle, such as ilovetealfm.bsky.social."
            }
            get("https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle", mapOf("handle" to input.lowercase())).string("did")
        }
        val docUrl = when {
            did.startsWith("did:plc:") && did.removePrefix("did:plc:").matches(Regex("[a-z2-7]{24}")) -> "https://plc.directory/$did"
            did.startsWith("did:web:") && did.removePrefix("did:web:").matches(Regex("[a-zA-Z0-9.-]+")) ->
                "https://${did.removePrefix("did:web:")}/.well-known/did.json"
            else -> throw IllegalArgumentException("Unsupported or invalid DID.")
        }
        val doc = get(docUrl)
        require(doc.string("id") == did) { "Identity document does not match the account." }
        if (!input.startsWith("did:")) {
            require(doc["alsoKnownAs"]?.jsonArray?.any { it.jsonPrimitive.content == "at://${input.lowercase()}" } == true) {
                "The handle does not match its identity document. Check your handle and try again."
            }
        }
        val service = doc["service"]?.jsonArray?.map { it.jsonObject }?.firstOrNull {
            it["id"]?.jsonPrimitive?.content in setOf("#atproto_pds", "$did#atproto_pds") &&
                it["type"]?.jsonPrimitive?.content == "AtprotoPersonalDataServer"
        } ?: throw IllegalArgumentException("No PDS was found for this account.")
        val pds = httpsOrigin(service.string("serviceEndpoint"))
        val response = request(pds, "com.atproto.server.createSession", buildJsonObject {
            put("identifier", did)
            put("password", appPassword)
        })
        require(response.string("did") == did) { "PDS returned a different account." }
        require(response["active"]?.jsonPrimitive?.booleanOrNull != false) { "This account is not active." }
        return TealSession(did, response.string("handle"), pds,
            response.string("accessJwt"), response.string("refreshJwt"))
    }

    suspend fun write(collection: String, rkey: String, record: JsonObject, createOnly: Boolean = false) = authenticated { session ->
        try {
            request(session.pds, "com.atproto.repo.putRecord", buildJsonObject {
                put("repo", session.did)
                put("collection", collection)
                put("rkey", rkey)
                put("record", record)
                // Explicit null is a compare-and-swap precondition: do not replace an existing play.
                if (createOnly) put("swapRecord", JsonNull)
            }, session.accessJwt)
        } catch (e: TealApiException) {
            if (!createOnly || e.error != "InvalidSwap") throw e
            val existing = getRecord(session.pds, session.did, collection, rkey)
            if (existing["value"]?.jsonObject?.filterKeys { it != "submissionClientAgent" } != record.filterKeys { it != "submissionClientAgent" }) throw IOException("A different listen already uses this record key.")
            existing
        }
    }

    suspend fun listRecords(pds: String, did: String, collection: String, limit: Int, cursor: String?) =
        get("$pds/xrpc/com.atproto.repo.listRecords", buildMap {
            put("repo", did)
            put("collection", collection)
            put("limit", limit.coerceIn(1, 100).toString())
            put("reverse", "true")
            cursor?.let { put("cursor", it) }
        })

    suspend fun getRecord(pds: String, did: String, collection: String, rkey: String) =
        get("$pds/xrpc/com.atproto.repo.getRecord", mapOf("repo" to did, "collection" to collection, "rkey" to rkey))

    private suspend fun request(pds: String, method: String, body: JsonObject, token: String? = null): JsonObject {
        val response = http.post("${httpsOrigin(pds)}/xrpc/$method") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
            token?.let { bearerAuth(it) }
        }
        return decode(response.status.value, response.status.isSuccess(), response.bodyAsText())
    }

    private suspend fun get(url: String, parameters: Map<String, String> = emptyMap()): JsonObject {
        require(Url(url).protocol.name == "https") { "HTTPS is required." }
        val response = http.get(url) { url { parameters.forEach { (k, v) -> this.parameters.append(k, v) } } }
        return decode(response.status.value, response.status.isSuccess(), response.bodyAsText())
    }

    private fun decode(status: Int, success: Boolean, body: String): JsonObject {
        // Never include response bodies in exceptions: authentication responses contain secrets.
        val value = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (!success) {
            val error = value?.get("error")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: "RequestFailed"
            throw TealApiException(error, status)
        }
        return value ?: throw IOException("PDS returned an invalid response.")
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
        private val locks = ConcurrentHashMap<String, Mutex>()

        internal fun httpsOrigin(value: String): String {
            val url = Url(value)
            require(url.protocol.name == "https" && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
                url.parameters.isEmpty() && url.fragment.isEmpty() && url.encodedPath in listOf("", "/") &&
                url.host.isNotBlank() && url.host != "localhost") { "The PDS must have an HTTPS origin URL." }
            return value.trimEnd('/')
        }
    }
}

internal fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    ?: throw IOException("PDS response is missing $key.")
