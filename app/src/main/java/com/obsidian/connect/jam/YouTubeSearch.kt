package com.obsidian.connect.jam

import com.obsidian.connect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finding a song on YouTube by name.
 *
 * The Data API needs a key, which cannot be shipped in an APK that other people
 * install - a key compiled in is a key anyone can extract and spend. It is read
 * from the signing properties instead, the same way the GIF key is.
 *
 * Without one this returns nothing rather than failing, so a jam chat still
 * works as a chat. Typing a song title simply stays a message.
 */
object YouTubeSearch {

    data class Hit(val videoId: String, val title: String)

    val isConfigured: Boolean get() = BuildConfig.YOUTUBE_KEY.isNotBlank()

    /**
     * The title of a video, without needing a key.
     *
     * oEmbed is public and unauthenticated, which matters because a pasted link
     * would otherwise show up as the link itself - and a jam whose now-playing
     * line reads "https://youtu.be/..." is telling you nothing you did not just
     * type.
     */
    suspend fun titleFor(videoId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.youtube.com/oembed" +
                "?url=https://www.youtube.com/watch?v=$videoId&format=json"

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val text = try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            JSONObject(text).optString("title").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * The best match that will actually play, or null.
     *
     * Several results are asked for rather than one, because the top hit is
     * often an official upload with embedding disabled - and a search that
     * returns a video the player then refuses is worse than no search at all.
     * A second request asks which of them are embeddable, and the first that is
     * gets used.
     *
     * That second call costs one unit against a daily ten thousand, where the
     * search itself costs a hundred. It is not worth optimising away.
     */
    suspend fun best(query: String): Hit? = withContext(Dispatchers.IO) {
        if (!isConfigured || query.isBlank()) return@withContext null

        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val search = get(
                "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet&type=video&videoCategoryId=10&maxResults=$CANDIDATES" +
                    "&q=$encoded&key=${BuildConfig.YOUTUBE_KEY}",
            ) ?: return@runCatching null

            val items = JSONObject(search).optJSONArray("items") ?: return@runCatching null
            val found = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                if (id.isBlank()) return@mapNotNull null
                Hit(
                    videoId = id,
                    title = item.optJSONObject("snippet")?.optString("title").orEmpty(),
                )
            }
            if (found.isEmpty()) return@runCatching null

            val playable = embeddable(found.map { it.videoId })
            found.firstOrNull { it.videoId in playable } ?: found.first()
        }.getOrNull()
    }

    /**
     * Which of these will play inside another app.
     *
     * One request for all of them. Asking per video would turn a cheap check
     * into a slow one for no better answer.
     */
    private fun embeddable(ids: List<String>): Set<String> {
        val response = get(
            "https://www.googleapis.com/youtube/v3/videos" +
                "?part=status&id=${ids.joinToString(",")}&key=${BuildConfig.YOUTUBE_KEY}",
        ) ?: return ids.toSet()

        val items = JSONObject(response).optJSONArray("items") ?: return ids.toSet()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val ok = item.optJSONObject("status")?.optBoolean("embeddable") ?: true
            item.optString("id").takeIf { ok && it.isNotBlank() }
        }.toSet()
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Several playable results for a query, minus anything already heard.
     *
     * Used when a queue runs dry: the point is to carry on in the same
     * territory without repeating, so a wider net is cast than for a direct
     * search and the exclusions are applied afterwards.
     */
    suspend fun similar(query: String, exclude: List<String>): List<Hit> =
        withContext(Dispatchers.IO) {
            if (!isConfigured || query.isBlank()) return@withContext emptyList()

            runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val search = get(
                    "https://www.googleapis.com/youtube/v3/search" +
                        "?part=snippet&type=video&videoCategoryId=10&maxResults=$RELATED" +
                        "&q=$encoded&key=${BuildConfig.YOUTUBE_KEY}",
                ) ?: return@runCatching emptyList()

                val items = JSONObject(search).optJSONArray("items")
                    ?: return@runCatching emptyList()

                val found = (0 until items.length()).mapNotNull { index ->
                    val item = items.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                    if (id.isBlank() || id in exclude) return@mapNotNull null
                    Hit(
                        videoId = id,
                        title = item.optJSONObject("snippet")?.optString("title").orEmpty(),
                    )
                }
                if (found.isEmpty()) return@runCatching emptyList()

                val playable = embeddable(found.map { it.videoId })
                found.filter { it.videoId in playable }
            }.getOrDefault(emptyList())
        }

    /** Enough that a blocked official upload is not the end of the search. */
    private const val CANDIDATES = 5

    /** Wider, because most of these will be excluded or unplayable. */
    private const val RELATED = 15

}
