package com.obsidian.connect.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Places where the watch face should switch itself off.
 *
 * The office, a parent's house, anywhere a photo on your home screen is not
 * something you want appearing over your shoulder. Saved as a point and a
 * radius rather than an address, because the phone knows where it is and does
 * not know what that place is called.
 *
 * Coordinates never leave the phone. They are not written to Firestore and not
 * sent to the other person - a list of the places you go is about the last
 * thing that should be synced anywhere.
 */
object PlaceGuard {

    private const val PREFS = "connect_places"
    private const val KEY_PLACES = "places"
    private const val KEY_ENABLED = "location_guard"
    private const val KEY_WAS_AT_PLACE = "was_at_place"

    /** Roughly a building and its car park. */
    const val DEFAULT_RADIUS_M = 150f

    data class Place(
        val label: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMetres: Float = DEFAULT_RADIUS_M,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun places(context: Context): List<Place> =
        prefs(context).getStringSet(KEY_PLACES, emptySet()).orEmpty().mapNotNull(::parse)

    fun add(context: Context, place: Place) {
        val existing = prefs(context).getStringSet(KEY_PLACES, emptySet()).orEmpty().toMutableSet()
        existing += encode(place)
        prefs(context).edit().putStringSet(KEY_PLACES, existing).apply()
    }

    fun remove(context: Context, place: Place) {
        val existing = prefs(context).getStringSet(KEY_PLACES, emptySet()).orEmpty().toMutableSet()
        existing -= encode(place)
        prefs(context).edit().putStringSet(KEY_PLACES, existing).apply()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether the phone is currently at one of the saved places.
     *
     * Deliberately returns false when it cannot tell - no permission, no fix,
     * nothing saved. Guessing "probably at a saved place" would blank the
     * widget for reasons nobody could see; guessing the other way at worst
     * leaves it doing what it was already doing.
     */
    fun isAtSavedPlace(context: Context): Boolean {
        if (!isEnabled(context)) return false
        if (!hasPermission(context)) return false

        val saved = places(context)
        if (saved.isEmpty()) return false

        val here = lastKnown(context) ?: return false

        return saved.any { place ->
            val results = FloatArray(1)
            Location.distanceBetween(
                here.latitude,
                here.longitude,
                place.latitude,
                place.longitude,
                results,
            )
            results[0] <= place.radiusMetres
        }
    }

    /**
     * The most recent fix any provider has, without asking for a new one.
     *
     * Requesting a fresh fix would mean waking the GPS on a schedule, which is
     * a real battery cost for a question that a minutes-old answer settles just
     * as well. Somebody who has just walked into their office was somewhere
     * else a minute ago either way.
     */
    fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    /**
     * Asks for a position now, rather than reading whatever was lying around.
     *
     * The network provider first: it works out where you are from cell towers
     * and nearby wifi, costs almost nothing, and is accurate to a few hundred
     * metres - which is the right order for a question asked with a
     * hundred-and-fifty metre radius. GPS is only worth waking for when that
     * comes back with nothing.
     *
     * Everything here is best-effort. A fix that does not arrive leaves the
     * cached one in place rather than failing.
     */
    fun requestFix(context: Context) {
        if (!isEnabled(context)) return
        if (!hasPermission(context)) return

        val manager = context.getSystemService(LocationManager::class.java) ?: return

        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> return
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(
                    provider,
                    null,
                    context.mainExecutor,
                ) { /* Cached by the system; read on the next evaluation. */ }
            } else {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(
                    provider,
                    { /* Same - the point is to refresh what lastKnown returns. */ },
                    null,
                )
            }
        }
    }

    /**
     * Whether the answer has changed since it was last acted on.
     *
     * Remembered, so the widget is redrawn on arriving and on leaving and not
     * on every check in between - a redraw costs a bitmap and a round trip to
     * the launcher, and the answer is the same nearly every time.
     */
    fun stateChanged(context: Context): Boolean {
        val now = isAtSavedPlace(context)
        val before = prefs(context).getBoolean(KEY_WAS_AT_PLACE, false)
        if (now == before) return false

        prefs(context).edit().putBoolean(KEY_WAS_AT_PLACE, now).apply()
        return true
    }

    private fun encode(place: Place): String =
        listOf(
            place.label.replace(FIELD, " "),
            place.latitude.toString(),
            place.longitude.toString(),
            place.radiusMetres.toString(),
        ).joinToString(FIELD)

    private fun parse(raw: String): Place? {
        val parts = raw.split(FIELD)
        if (parts.size < 4) return null
        return Place(
            label = parts[0],
            latitude = parts[1].toDoubleOrNull() ?: return null,
            longitude = parts[2].toDoubleOrNull() ?: return null,
            radiusMetres = parts[3].toFloatOrNull() ?: DEFAULT_RADIUS_M,
        )
    }

    /** A separator no place name is going to contain. */
    private const val FIELD = "|"
}
