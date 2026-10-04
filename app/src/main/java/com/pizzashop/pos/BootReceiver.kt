package com.pizzashop.pos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the order alerts again after the phone restarts or the app is updated, so nobody has to open the app first.
 * It only does something when the person is still logged in and alerts are on (AlertService.sync checks that).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AlertService.sync(context)
        }
    }
}
