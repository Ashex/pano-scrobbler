package com.arn.scrobble.api.teal

import com.arn.scrobble.api.lastfm.ScrobbleData
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.time.Instant

internal const val TEAL_PLAY = "fm.teal.feed.play"
internal const val TEAL_STATUS = "fm.teal.actor.status"

internal fun ScrobbleData.toTealRecord(agent: String): JsonObject = buildJsonObject {
    require(track.isNotBlank() && track.toByteArray(Charsets.UTF_8).size <= 256) { "The track name is empty or too long for teal.fm." }
    require(artist.toByteArray(Charsets.UTF_8).size <= 256 && (album?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= 256) { "The artist or album name is too long for teal.fm." }
    put("\$type", TEAL_PLAY)
    put("trackName", track)
    putJsonArray("artists") {
        if (artist.isNotBlank()) add(buildJsonObject { put("artistName", artist) })
    }
    album?.takeIf { it.isNotBlank() }?.let { put("releaseName", it) }
    duration?.takeIf { it > 0 }?.let { put("duration", it / 1000) }
    put("playedTime", Instant.fromEpochMilliseconds(timestamp).toString())
    put("submissionClientAgent", agent)
}

/** Stable across retries, process restarts and batches. The TID time is the listen's start time. */
internal fun ScrobbleData.tealRecordKey(): String {
    require(timestamp in 1..8_000_000_000_000L) { "Invalid listen timestamp." }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$artist\u0000$track\u0000${appId.orEmpty()}".toByteArray(Charsets.UTF_8))
    val clock = ((digest[0].toInt() and 0xff) shl 2) or (digest[1].toInt() and 3)
    var value = (timestamp * 1000 shl 10) or clock.toLong()
    val alphabet = "234567abcdefghijklmnopqrstuvwxyz"
    return CharArray(13).also { chars ->
        for (i in chars.lastIndex downTo 0) {
            chars[i] = alphabet[(value and 31).toInt()]
            value = value ushr 5
        }
    }.concatToString()
}
