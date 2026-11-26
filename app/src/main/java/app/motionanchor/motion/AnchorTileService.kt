// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.motionanchor.MainActivity
import app.motionanchor.R
import app.motionanchor.localized

@Suppress("DEPRECATION")
@TargetApi(24)
class AnchorTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.label = localized().getString(R.string.app_name)
            tile.state = if (MotionAnchorService.state == SessionState.Off) {
                Tile.STATE_INACTIVE
            } else {
                Tile.STATE_ACTIVE
            }
            tile.updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (MotionAnchorService.state != SessionState.Off) {
            MotionAnchorService.stop(this)
            return
        }
        val destination = if (MotionAnchorService.canShowOverlay(this)) {
            TileActionActivity::class.java
        } else {
            MainActivity::class.java
        }
        val intent = Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

class TileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Also opened from the notification that resumes background trip detection.
        val automatic = intent.getBooleanExtra(MotionAnchorService.EXTRA_AUTOMATIC, false)
        val ready = !automatic || MotionAnchorService.hasPreciseLocation(this)
        if (!ready || !MotionAnchorService.start(this, automatic)) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
