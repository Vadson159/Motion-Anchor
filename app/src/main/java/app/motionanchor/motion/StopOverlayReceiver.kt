// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopOverlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MotionAnchorService.stop(context)
    }
}
