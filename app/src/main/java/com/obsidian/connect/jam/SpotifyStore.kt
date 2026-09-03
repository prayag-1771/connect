package com.obsidian.connect.jam

import android.content.Context

/**
 * What this phone knows about its Spotify connection.
 *
 * Kept on the device rather than in Firestore, deliberately. A refresh token
 * is a standing key to somebody's account; syncing one to a shared database so
 * the other phone could read it would be handing over the account rather than
 * sharing the music.
 *
 * The client id lives here too because it is not something this app can ship.
 * Spotify issues one per registered application, tied to whoever registered it,
 * so it has to be pasted in once by the person who created it.
 */
object SpotifyStore {

    private const val PREFS = "connect_spotify"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_VERIFIER = "code_verifier"

    /**
     * Registered against the client id in Spotify's dashboard.
     *
     * Shown in the setup screen so it can be copied exactly - a redirect URI
     * that differs by one character fails the login with an error that does not
     * say which character.
     */
    const val REDIRECT_URI = "connect://spotify-callback"

    /**
     * Playback control needs all three.
     *
     * Reading state is not enough to keep two phones together: this has to be
     * able to seek, and seeking is a modify scope.
     */
    val SCOPES = listOf(
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var clientIdCache: String? = null

    fun clientId(context: Context): String =
        prefs(context).getString(KEY_CLIENT_ID, "").orEmpty()

    fun setClientId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_CLIENT_ID, id.trim()).apply()
    }

    fun accessToken(context: Context): String =
        prefs(context).getString(KEY_ACCESS, "").orEmpty()

    fun refreshToken(context: Context): String =
        prefs(context).getString(KEY_REFRESH, "").orEmpty()

    /** A minute of slack, so a token does not expire mid-request. */
    fun isExpired(context: Context): Boolean =
        System.currentTimeMillis() > prefs(context).getLong(KEY_EXPIRES, 0L) - 60_000L

    fun isConnected(context: Context): Boolean =
        clientId(context).isNotBlank() && refreshToken(context).isNotBlank()

    fun saveTokens(
        context: Context,
        access: String,
        refresh: String?,
        expiresInSeconds: Long,
    ) {
        prefs(context).edit().apply {
            putString(KEY_ACCESS, access)
            // A refresh response does not always carry a new refresh token; the
            // old one stays valid when it does not.
            if (!refresh.isNullOrBlank()) putString(KEY_REFRESH, refresh)
            putLong(KEY_EXPIRES, System.currentTimeMillis() + expiresInSeconds * 1000L)
            apply()
        }
    }

    /** Held only between opening the login page and the redirect coming back. */
    fun saveVerifier(context: Context, verifier: String) {
        prefs(context).edit().putString(KEY_VERIFIER, verifier).apply()
    }

    fun verifier(context: Context): String =
        prefs(context).getString(KEY_VERIFIER, "").orEmpty()

    fun disconnect(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXPIRES)
            .remove(KEY_VERIFIER)
            .apply()
    }
}
