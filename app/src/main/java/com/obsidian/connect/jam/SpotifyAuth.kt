package com.obsidian.connect.jam

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Signing in to Spotify without a client secret.
 *
 * Authorization Code with PKCE, which is the flow meant for apps that cannot
 * keep a secret - and an Android app cannot, since anything compiled into it
 * can be read back out of the APK. Instead of a secret, the app proves it is
 * the same one that started the login by producing the original of a hash it
 * sent up front.
 *
 * Plain HttpURLConnection and the JSON parser Android already ships, as
 * elsewhere here. Two short requests do not justify a networking library.
 */
object SpotifyAuth {

    private const val AUTHORIZE = "https://accounts.spotify.com/authorize"
    private const val TOKEN = "https://accounts.spotify.com/api/token"

    /**
     * The page to send someone to, and the verifier that must survive until
     * they come back.
     */
    fun authorizeUrl(context: Context): String? {
        val clientId = SpotifyStore.clientId(context)
        if (clientId.isBlank()) return null

        val verifier = randomVerifier()
        SpotifyStore.saveVerifier(context, verifier)

        val query = listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to SpotifyStore.REDIRECT_URI,
            "code_challenge_method" to "S256",
            "code_challenge" to challengeFor(verifier),
            "scope" to SpotifyStore.SCOPES.joinToString(" "),
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        return "$AUTHORIZE?$query"
    }

    /** Turns the code from the redirect into tokens. */
    suspend fun exchange(context: Context, redirect: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val error = redirect.getQueryParameter("error")
                check(error == null) { "Spotify refused the login: $error" }

                val code = redirect.getQueryParameter("code")
                checkNotNull(code) { "No code came back from Spotify" }

                val body = form(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to SpotifyStore.REDIRECT_URI,
                    "client_id" to SpotifyStore.clientId(context),
                    "code_verifier" to SpotifyStore.verifier(context),
                )

                store(context, post(body))
            }
        }

    /**
     * Swaps the refresh token for a fresh access token.
     *
     * Called before any request that finds the current one expired, rather than
     * on a timer - a token nobody is about to use does not need renewing.
     */
    suspend fun refresh(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val refresh = SpotifyStore.refreshToken(context)
            check(refresh.isNotBlank()) { "Not connected to Spotify" }

            val body = form(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to SpotifyStore.clientId(context),
            )

            store(context, post(body))
        }
    }

    /** A valid access token, renewing it first if that is needed. */
    suspend fun validToken(context: Context): String? {
        if (!SpotifyStore.isConnected(context)) return null
        if (SpotifyStore.isExpired(context)) {
            refresh(context).onFailure { return null }
        }
        return SpotifyStore.accessToken(context).takeIf { it.isNotBlank() }
    }

    private fun store(context: Context, json: JSONObject) {
        SpotifyStore.saveTokens(
            context = context,
            access = json.getString("access_token"),
            refresh = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInSeconds = json.optLong("expires_in", 3600L),
        )
    }

    private fun post(body: String): JSONObject {
        val connection = (URL(TOKEN).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                error("Spotify returned $code: ${detail.orEmpty()}")
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    /**
     * Base64url without padding, which is what the spec requires - the standard
     * alphabet and trailing equals signs are both rejected.
     */
    private fun randomVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }
}
