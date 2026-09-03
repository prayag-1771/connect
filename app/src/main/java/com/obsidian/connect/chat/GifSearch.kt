package com.obsidian.connect.chat

import com.obsidian.connect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GIF search, through GIPHY.
 *
 * Plain HttpURLConnection and the JSON parser Android already ships. Two
 * endpoints returning a list of URLs does not justify pulling in Retrofit and
 * a serialisation library.
 *
 * Only URLs are ever stored on a message. A GIF is routinely one to three
 * megabytes — far past Firestore's 1MiB document limit — so the bytes stay on
 * GIPHY's CDN and each phone loads them when it draws the bubble. The
 * trade-off is that a GIF needs a connection to display, and would break if
 * GIPHY ever removed it.
 */
object GifSearch {

    private const val BASE = "https://api.giphy.com/v1/gifs"
    private const val LIMIT = 30

    /** Whether a key was supplied at build time. */
    val isConfigured: Boolean get() = BuildConfig.GIPHY_KEY.isNotBlank()

    data class Gif(
        val id: String,
        /** Animated, sized for a chat bubble rather than full resolution. */
        val previewUrl: String,
        val sendUrl: String,
    )

    suspend fun trending(): List<Gif> = query("$BASE/trending?limit=$LIMIT&rating=pg-13")

    suspend fun search(term: String): List<Gif> {
        if (term.isBlank()) return trending()
        val encoded = URLEncoder.encode(term, "UTF-8")
        return query("$BASE/search?q=$encoded&limit=$LIMIT&rating=pg-13")
    }

    private suspend fun query(urlWithoutKey: String): List<Gif> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()

        val url = "$urlWithoutKey&api_key=${BuildConfig.GIPHY_KEY}"
        val body = runCatching { fetch(url) }.getOrNull() ?: return@withContext emptyList()

        runCatching {
            val data = JSONObject(body).getJSONArray("data")
            (0 until data.length()).mapNotNull { index ->
                val item = data.getJSONObject(index)
                val images = item.getJSONObject("images")

                // downsized has a size cap GIPHY enforces, which keeps a bubble
                // from pulling several megabytes for one reaction.
                val preview = images.optJSONObject("fixed_width")?.optString("url")
                val send = images.optJSONObject("downsized")?.optString("url") ?: preview

                if (preview.isNullOrBlank() || send.isNullOrBlank()) return@mapNotNull null
                Gif(id = item.optString("id"), previewUrl = preview, sendUrl = send)
            }
        }.getOrDefault(emptyList())
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
