package com.obsidian.connect.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
