package com.arn.scrobble.api.teal

import com.arn.scrobble.BuildKonfig
import com.arn.scrobble.api.*
import com.arn.scrobble.api.cache.CacheStrategy
import com.arn.scrobble.api.lastfm.*
import com.arn.scrobble.charts.ListeningActivity
import com.arn.scrobble.charts.TimePeriod
import com.arn.scrobble.utils.PlatformStuff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

class Teal internal constructor(
    userAccount: UserAccountSerializable,
    sessionStore: TealSessionStore? = null,
    httpClient: io.ktor.client.HttpClient? = null,
) : Scrobblable(userAccount) {
    private val store = sessionStore ?: TealAccountSessionStore(
        TealApiClient.json.decodeFromString<TealSession>(userAccount.authKey).id,
        read = { PlatformStuff.mainPrefs.data.first().scrobbleAccounts },
        update = { transform ->
            PlatformStuff.mainPrefs.updateData { prefs -> prefs.copy(scrobbleAccounts = transform(prefs.scrobbleAccounts)) }
        },
    )
    private val client by lazy { TealApiClient(httpClient ?: http, store) }
    private val pages = mutableMapOf<Triple<Long, Long, Int>, MutableMap<Int, String?>>()
    private val pageLock = Mutex()

    override suspend fun scrobble(scrobbleData: ScrobbleData): Result<ScrobbleResult> = result {
        val response = client.write(TEAL_PLAY, scrobbleData.tealRecordKey(),
            scrobbleData.toTealRecord(agent), createOnly = true)
        ScrobbleResult(false, response.string("uri"))
    }

    override suspend fun scrobble(scrobbleDatas: List<ScrobbleData>): Result<ScrobbleResult> = result {
        scrobbleDatas.forEach { scrobble(it).getOrThrow() }
        ScrobbleResult(false)
    }

    override suspend fun updateNowPlaying(scrobbleData: ScrobbleData): Result<ScrobbleResult> = result {
        val now = System.currentTimeMillis()
        client.write(TEAL_STATUS, "self", buildJsonObject {
            put("\$type", TEAL_STATUS)
            put("time", Instant.fromEpochMilliseconds(now).toString())
            put("expiry", Instant.fromEpochMilliseconds(now + (scrobbleData.duration ?: 600_000L).coerceIn(30_000L, 600_000L)).toString())
            put("item", JsonObject(scrobbleData.toTealRecord(agent).filterKeys { it != "\$type" }))
        })
        ScrobbleResult(false)
    }

    override suspend fun getRecents(page: Int, username: String, cached: Boolean, from: Long,
        to: Long, includeNowPlaying: Boolean, limit: Int): Result<PageResult<Track>> = result {
        if (cached) return@result createEmptyPageResult<Track>()
        pageLock.withLock {
            require(page > 0 && limit > 0)
            val session = store.load()
            val pageSize = limit.coerceAtMost(100)
            val key = Triple(from, to, pageSize)
            val cursors = pages.getOrPut(key) { mutableMapOf(1 to null) }
            if (page == 1) { cursors.clear(); cursors[1] = null }
            var tracks = emptyList<Track>()
            var more = false
            // Reconstruct missing cursors when paging is recreated or refresh updates the account.
            for (currentPage in 1..page) {
                if (currentPage < page && cursors.containsKey(currentPage + 1)) continue
                var cursor = cursors[currentPage]
                val collected = mutableListOf<Track>()
                var requests = 0
                do {
                    val response = client.listRecords(session.pds, session.did, TEAL_PLAY,
                        pageSize - collected.size, cursor)
                    val records = response["records"]?.jsonArray ?: throw IOException("PDS response is missing records.")
                    collected += records.mapNotNull { record ->
                        val obj = record.jsonObject
                        toTrack(obj["value"]?.jsonObject ?: return@mapNotNull null, obj.string("uri"))
                    }.filter { (from < 0 || (it.date ?: 0) >= from) && (to < 0 || (it.date ?: 0) <= to) }
                    val next = response["cursor"]?.jsonPrimitive?.contentOrNull
                    check(next == null || next != cursor) { "PDS returned a repeated cursor." }
                    cursor = next
                    requests++
                    if (requests >= 100 && collected.size < pageSize && cursor != null)
                        throw IOException("Too many records for this date filter. Try a more recent date.")
                } while (cursor != null && collected.size < pageSize)
                tracks = collected.sortedByDescending { it.date }
                more = cursor != null
                if (more) cursors[currentPage + 1] = cursor
                else if (currentPage < page) { tracks = emptyList(); break }
            }
            if (includeNowPlaying && page == 1) {
                try {
                    val status = client.getRecord(session.pds, session.did, TEAL_STATUS, "self")["value"]!!.jsonObject
                    val time = Instant.parse(status.string("time")).toEpochMilliseconds()
                    val expiry = status["expiry"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it).toEpochMilliseconds() }
                        ?: (time + 600_000)
                    if (expiry > System.currentTimeMillis()) {
                        toTrack(status["item"]!!.jsonObject, null)?.copy(date = null, isNowPlaying = true)?.let { tracks = listOf(it) + tracks }
                    }
                } catch (e: TealApiException) {
                    if (e.error != "RecordNotFound") throw e
                }
            }
            PageResult(PageAttr(page, if (more) page + 1 else page, null), tracks)
        }
    }

    // teal's play/status lexicons do not expose these Last.fm features.
    override suspend fun loveOrUnlove(track: Track, love: Boolean) = Result.success(ScrobbleResult(true))
    override suspend fun delete(track: Track): Result<Unit> = Result.failure(UnsupportedOperationException("teal.fm deletion is not supported."))
    override suspend fun getLoves(page: Int, username: String, cacheStrategy: CacheStrategy, limit: Int) = Result.success(createEmptyPageResult<Track>())
    override suspend fun getFriends(page: Int, username: String, cached: Boolean, limit: Int) = Result.success(createEmptyPageResult<User>())
    override suspend fun loadDrawerData(username: String): Result<DrawerData>? = null
    override suspend fun getCharts(type: Int, timePeriod: TimePeriod, page: Int, username: String, cacheStrategy: CacheStrategy, limit: Int): Result<PageResult<out MusicEntry>> = Result.success(createEmptyPageResult<Track>())
    override suspend fun getListeningActivity(timePeriod: TimePeriod, user: UserCached?, cacheStrategy: CacheStrategy) = ListeningActivity()

    companion object {
        private val http by lazy { Requesters.baseKtorClient.config { followRedirects = false } }
        private val agent get() = "com.arn.scrobble/${BuildKonfig.VER_NAME}"

        suspend fun login(identifier: String, appPassword: String): Result<Unit> = result {
            val session = TealApiClient(http).login(identifier, appPassword)
            Scrobblables.add(UserAccountSerializable(
                type = AccountType.TEALFM,
                user = UserCached(session.handle, "https://atproto.at/viewer?uri=${session.did}/$TEAL_PLAY", session.handle, "", -1),
                authKey = TealApiClient.json.encodeToString(session),
                apiRoot = session.pds,
            ))
        }

        private fun toTrack(record: JsonObject, uri: String?): Track? {
            val name = record["trackName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val artists = record["artists"]?.jsonArray?.mapNotNull { it.jsonObject["artistName"]?.jsonPrimitive?.contentOrNull }
                ?: record["artistNames"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            return Track(name = name, artist = Artist(artists.joinToString(", ")),
                album = record["releaseName"]?.jsonPrimitive?.contentOrNull?.let { Album(it) },
                duration = record["duration"]?.jsonPrimitive?.longOrNull?.times(1000),
                date = record["playedTime"]?.jsonPrimitive?.contentOrNull?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() },
                url = uri?.let { "https://atproto.at/viewer?uri=$it" }, msid = uri)
        }

        private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
            try { Result.success(block()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
}
