// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Provides an overlay window token. It does not subscribe to screen events. */
class AnchorAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        connectionListener?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) {
            current = null
            connectionListener?.invoke()
        }
        super.onDestroy()
    }

    companion object {
        var current: AnchorAccessibilityService? = null
            private set
        var connectionListener: (() -> Unit)? = null
    }
}
