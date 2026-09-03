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
     * The single best match, or null.
     *
     * One result rather than a list on purpose: this is used by the jam chat,
     * where typing a song name should put a song on rather than open a picker.
     * Being wrong occasionally is a better trade than making someone choose
     * every time.
     */
    suspend fun best(query: String): Hit? = withContext(Dispatchers.IO) {
        if (!isConfigured || query.isBlank()) return@withContext null

        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search" +
                "?part=snippet&type=video&videoCategoryId=10&maxResults=1" +
                "&q=$encoded&key=${BuildConfig.YOUTUBE_KEY}"

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
            }

            val text = try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val item = JSONObject(text)
                .optJSONArray("items")
                ?.optJSONObject(0)
                ?: return@runCatching null

            val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
            if (id.isBlank()) return@runCatching null

            Hit(
                videoId = id,
                title = item.optJSONObject("snippet")?.optString("title").orEmpty(),
            )
        }.getOrNull()
    }
}
