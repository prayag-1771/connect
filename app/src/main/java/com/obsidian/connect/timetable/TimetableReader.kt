package com.obsidian.connect.timetable

import android.util.Base64
import com.obsidian.connect.BuildConfig
import com.obsidian.connect.core.model.Timetable
import com.obsidian.connect.core.model.TimetableEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a photograph of a timetable into something the app can lay out.
 *
 * A timetable photograph is a grid, and grids are exactly what plain text
 * recognition is worst at - it reads across rows and loses which column a cell
 * was in, which is the only thing that matters here. A model that sees the
 * image keeps that structure, which is why this asks one rather than doing
 * character recognition locally.
 *
 * The key is compiled into the app, so anybody who pulls the APK apart can read
 * it. That is true of every key here and is an accepted trade for a private
 * two-person app; it would not be acceptable for something published.
 */
object TimetableReader {

    private const val MODEL = "gemini-3.6-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    val isConfigured: Boolean get() = BuildConfig.GEMINI_KEY.isNotBlank()

    /**
     * The instruction, written to leave as little to interpretation as
     * possible.
     *
     * Timetables are full of things that are nearly information - room codes,
     * lecturer initials, colour keys - and a model asked loosely will merge
     * them into the title. Naming the fields and demanding bare JSON is what
     * keeps the result parseable.
     */
    private val PROMPT = """
        Read this timetable image and return ONLY a JSON array. No prose, no
        markdown fences.

        Each element must be an object with exactly these keys:
          "day"      full English weekday name, e.g. "Monday"
          "start"    24-hour zero-padded time, e.g. "09:00"
          "end"      24-hour zero-padded time, e.g. "10:30"
          "title"    what happens then, e.g. a subject name
          "location" room or place, or "" if the image does not say

        Rules:
        - One object per scheduled slot. A block spanning two hours is one slot.
        - If a slot repeats on several days, emit one object per day.
        - If the image gives no end time, set "end" to "".
        - Ignore breaks, headers, legends and empty cells.
        - If the image is not a timetable, return [].
    """.trimIndent()

    /**
     * Returns the entries found, or fails with something worth showing.
     *
     * [jpeg] should already be downscaled by the caller. The limit is generous
     * but a phone photograph at full resolution is several megabytes of upload
     * for no gain in legibility.
     */
    suspend fun read(jpeg: ByteArray): Result<List<TimetableEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(isConfigured) { "No Gemini key is set in this build" }

                val body = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray()
                                    .put(JSONObject().put("text", PROMPT))
                                    .put(
                                        JSONObject().put(
                                            "inline_data",
                                            JSONObject()
                                                .put("mime_type", "image/jpeg")
                                                .put(
                                                    "data",
                                                    Base64.encodeToString(jpeg, Base64.NO_WRAP),
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                    )
                }.toString()

                parse(post(body))
            }
        }

    private fun post(body: String): String {
        val connection = (URL("$ENDPOINT?key=${BuildConfig.GEMINI_KEY}").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000
            // Reading a dense timetable takes a while; a short timeout here
            // fails on exactly the images most worth reading.
            readTimeout = 90_000
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                error("Gemini returned ${connection.responseCode}: ${detail.orEmpty().take(200)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Digs the array out of the response.
     *
     * Models are asked for bare JSON and still sometimes wrap it in a markdown
     * fence or a sentence, so the text is trimmed to the outermost brackets
     * rather than parsed as-is.
     */
    private fun parse(response: String): List<TimetableEntry> {
        val text = JSONObject(response)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.let { parts ->
                (0 until parts.length())
                    .mapNotNull { parts.optJSONObject(it)?.optString("text") }
                    .firstOrNull { it.isNotBlank() }
            }
            ?: error("Gemini sent nothing back")

        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        check(start >= 0 && end > start) { "Could not find a timetable in that image" }

        val array = JSONArray(text.substring(start, end + 1))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            TimetableEntry(
                id = java.util.UUID.randomUUID().toString(),
                day = normaliseDay(item.optString("day")),
                start = item.optString("start"),
                end = item.optString("end"),
                title = item.optString("title").trim(),
                location = item.optString("location").trim(),
            ).takeIf { it.isUsable }
        }
    }

    /** "mon", "MONDAY" and "Mon." all have to land on the same day. */
    private fun normaliseDay(raw: String): String {
        val cleaned = raw.trim().lowercase()
        return Timetable.DAYS.firstOrNull { day ->
            cleaned.startsWith(day.take(3).lowercase())
        }.orEmpty()
    }
}
