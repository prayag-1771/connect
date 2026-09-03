package com.obsidian.connect.jam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spotify playback, over the Web API.
 *
 * The Web API rather than the App Remote SDK. The SDK is not published to any
 * Maven repository - it is an archive downloaded by hand and committed into the
 * project - and it would add several megabytes to an APK that has already
 * tripled in size for WebRTC. The Web API needs nothing but HTTP, and exposes
 * the same three things that matter: play at a position, pause, and seek.
 *
 * Every one of these requires Premium. Spotify returns 403 for a free account,
 * which is reported as it is rather than swallowed - somebody whose jam quietly
 * does nothing deserves to be told why.
 */
object SpotifyApi {

    private const val BASE = "https://api.spotify.com/v1/me/player"

    /** What Spotify is doing right now, or null when it cannot be asked. */
    data class State(
        val trackUri: String,
        val title: String,
        val artist: String,
        val playing: Boolean,
        val positionMs: Long,
    )

    suspend fun play(context: Context, trackUri: String, positionMs: Long): Result<Unit> =
        request(
            context = context,
            path = "/play",
            method = "PUT",
            body = JSONObject().apply {
                put("uris", org.json.JSONArray().put(trackUri))
                put("position_ms", positionMs)
            }.toString(),
        ).map { }

    suspend fun resume(context: Context): Result<Unit> =
        request(context, "/play", "PUT", "{}").map { }

    suspend fun pause(context: Context): Result<Unit> =
        request(context, "/pause", "PUT", null).map { }

    suspend fun seek(context: Context, positionMs: Long): Result<Unit> =
        request(context, "/seek?position_ms=$positionMs", "PUT", null).map { }

    suspend fun state(context: Context): Result<State?> =
        request(context, "", "GET", null).map { text ->
            if (text.isBlank()) return@map null
            val json = JSONObject(text)
            val item = json.optJSONObject("item") ?: return@map null

            State(
                trackUri = item.optString("uri"),
                title = item.optString("name"),
                artist = item.optJSONArray("artists")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    .orEmpty(),
                playing = json.optBoolean("is_playing"),
                positionMs = json.optLong("progress_ms"),
            )
        }

    /**
     * Finds a track to put on.
     *
     * Search is the one thing here that does not need Premium, so a free
     * account can still choose the music even if it cannot drive playback.
     */
    suspend fun search(context: Context, query: String): Result<List<State>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = SpotifyAuth.validToken(context) ?: error("Not connected to Spotify")
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://api.spotify.com/v1/search?q=$encoded&type=track&limit=15"

                val text = call(url, "GET", null, token)
                val items = JSONObject(text)
                    .optJSONObject("tracks")
                    ?.optJSONArray("items")
                    ?: return@runCatching emptyList()

                (0 until items.length()).mapNotNull { index ->
                    val item = items.optJSONObject(index) ?: return@mapNotNull null
                    State(
                        trackUri = item.optString("uri"),
                        title = item.optString("name"),
                        artist = item.optJSONArray("artists")
                            ?.optJSONObject(0)
                            ?.optString("name")
                            .orEmpty(),
                        playing = false,
                        positionMs = 0L,
                    )
                }
            }
        }

    private suspend fun request(
        context: Context,
        path: String,
        method: String,
        body: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = SpotifyAuth.validToken(context) ?: error("Not connected to Spotify")
            call(BASE + path, method, body, token)
        }
    }

    private fun call(url: String, method: String, body: String?, token: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
            if (body != null) doOutput = true
        }

        return try {
            body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }

            when (val code = connection.responseCode) {
                // 204 is the usual answer to a playback command, and means it
                // worked. There is simply nothing to say back.
                204 -> ""
                in 200..299 -> connection.inputStream.bufferedReader().use { it.readText() }
                403 -> error(
                    "Spotify needs Premium for this. Playback control is not " +
                        "available on a free account.",
                )
                404 -> error(
                    "No active Spotify device. Open Spotify and play something " +
                        "for a second, then try again.",
                )
                else -> {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    error("Spotify returned $code: ${detail.orEmpty()}")
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
