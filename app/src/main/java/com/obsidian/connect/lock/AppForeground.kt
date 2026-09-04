package com.obsidian.connect.lock

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any Connect screen is still on top.
 *
 * The lock needs to tell two things apart that look identical from a single
 * activity: stepping into another of our own screens - a photo, the jam,
 * settings - and actually leaving the app. In both cases the current activity
 * stops, which is why re-locking on stop asked for a fingerprint on the way
 * back from everywhere.
 *
 * Counting them answers it exactly. Starting our own screen raises the count
 * before the old one lowers it, so it never reaches zero; going home or
 * switching apps takes it to zero and the unlock is dropped.
 *
 * The system photo picker and the camera belong to other apps, so using those
 * does count as leaving. That is the honest reading of "the app was closed",
 * even though it means being asked again after picking a photo.
 */
object AppForeground : Application.ActivityLifecycleCallbacks {

    private var running = 0

    val isForeground: Boolean get() = running > 0

    override fun onActivityStarted(activity: Activity) {
        running++
    }

    override fun onActivityStopped(activity: Activity) {
        running = (running - 1).coerceAtLeast(0)
        if (running == 0) AppLock.forget()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
