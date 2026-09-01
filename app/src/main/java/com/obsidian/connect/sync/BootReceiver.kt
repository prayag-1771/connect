package com.obsidian.connect.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the sync schedule after a reboot.
 *
 * WorkManager does survive restarts on its own, but a reboot is also the
 * moment the widget is most likely to be stale — the device may have been off
 * for hours. So this fires an immediate sync as well as re-arming the periodic
 * one, meaning the home screen is current by the time the user reaches it.
 *
 * BOOT_COMPLETED is one of the few implicit broadcasts a manifest-registered
 * receiver may still listen for since Android 8.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SyncScheduler.schedulePeriodic(context)
        SyncScheduler.now(context)
    }
}
