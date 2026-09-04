package com.obsidian.connect.widget

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

/**
 * Follows the phone by cell tower and wifi, continuously and almost for free.
 *
 * The network provider is the one train-tracking apps lean on: it works out
 * roughly where you are from the towers and access points the phone can already
 * see, without ever waking the GPS. Accuracy is a few hundred metres, which is
 * the right order for a question asked with a hundred-and-fifty metre radius,
 * and the power cost is close to nothing because none of it is measured for
 * this app's benefit - the radio is talking to towers regardless.
 *
 * GPS is left to the two-minute beat. It is the accurate one and the expensive
 * one, and asking it constantly for an answer that changes when you walk
 * somewhere would be spending a battery to learn nothing.
 *
 * The honest limit: Android throttles location for an app nobody is looking at,
 * so "continuous" means continuous while the app is open or something else is
 * keeping the process up. Genuinely uninterrupted background tracking needs a
 * foreground service and its permanent notification, which is a poor trade for
 * hiding a photograph.
 */
object PlaceWatcher {

    private var listening = false

    /** Coarse and often, because a saved place is a neighbourhood not a doorstep. */
    private const val EVERY_MS = 30_000L
    private const val EVERY_METRES = 40f

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (listening) return
        if (!PlaceGuard.isEnabled(context)) return
        if (!PlaceGuard.hasPermission(context)) return

        val app = context.applicationContext
        val manager = app.getSystemService(LocationManager::class.java) ?: return
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return

        runCatching {
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                EVERY_MS,
                EVERY_METRES,
                listener,
            )
        }.onSuccess { listening = true }
    }

    fun stop(context: Context) {
        if (!listening) return
        runCatching {
            context.applicationContext
                .getSystemService(LocationManager::class.java)
                ?.removeUpdates(listener)
        }
        listening = false
    }

    /**
     * Redraws only when the answer changes.
     *
     * A fix arrives every half minute and nearly all of them say the same
     * thing. Redrawing on each would cost a bitmap render and a round trip to
     * the launcher for no visible difference.
     */
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // The context comes back through the manager rather than being
            // held: a listener that outlives its screen holding an Activity is
            // how leaks happen.
            held?.let { context ->
                if (PlaceGuard.stateChanged(context)) {
                    WatchWidgetProvider.refreshAll(context)
                }
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /** The application context, which is safe to keep. */
    private var held: Context? = null

    fun attach(context: Context) {
        held = context.applicationContext
        start(context)
    }
}
