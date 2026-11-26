// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings background trip detection back after a restart of the phone or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, QUICK_BOOT ->
                MotionAnchorService.resumeInBackground(context)
        }
    }

    private companion object {
        // Some vendors send this instead of BOOT_COMPLETED after a fast restart.
        const val QUICK_BOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
