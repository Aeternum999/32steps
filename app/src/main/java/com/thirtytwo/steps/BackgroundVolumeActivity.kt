package com.thirtytwo.steps

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Performs one volume step from a short-lived Activity context.
 *
 * Some OEM Android builds reject AudioManager.setStreamVolume() when it is
 * called only from a background service. This NoDisplay Activity is an
 * experiment to make the volume change while an Activity is resumed, without
 * showing or stealing the user's screen.
 */
class BackgroundVolumeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()

        when (intent?.getIntExtra(EXTRA_DIRECTION, 0)) {
            DIRECTION_UP -> runCatching {
                VolumeController.getInstance(this).stepUp()
            }.onFailure { Log.e(TAG, "Background volume step up failed", it) }

            DIRECTION_DOWN -> runCatching {
                VolumeController.getInstance(this).stepDown()
            }.onFailure { Log.e(TAG, "Background volume step down failed", it) }

            else -> Log.w(TAG, "Missing or invalid volume direction")
        }

        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_DIRECTION = "com.thirtytwo.steps.EXTRA_DIRECTION"
        const val DIRECTION_UP = 1
        const val DIRECTION_DOWN = -1
        private const val TAG = "32StepsBgVolume"
    }
}