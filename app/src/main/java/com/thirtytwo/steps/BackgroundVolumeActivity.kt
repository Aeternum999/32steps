package com.thirtytwo.steps

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Performs one volume step from a short-lived, isolated NoDisplay task.
 * It reports completion so the accessibility service can process queued
 * physical key presses sequentially without opening the main app window.
 */
class BackgroundVolumeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()

        try {
            when (intent?.getIntExtra(EXTRA_DIRECTION, 0)) {
                DIRECTION_UP -> VolumeController.getInstance(this).stepUp()
                DIRECTION_DOWN -> VolumeController.getInstance(this).stepDown()
                else -> Log.w(TAG, "Missing or invalid volume direction")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background volume step failed", e)
        } finally {
            sendBroadcast(Intent(ACTION_STEP_COMPLETED).setPackage(packageName))
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_DIRECTION = "com.thirtytwo.steps.EXTRA_DIRECTION"
        const val DIRECTION_UP = 1
        const val DIRECTION_DOWN = -1
        const val ACTION_STEP_COMPLETED = "com.thirtytwo.steps.ACTION_STEP_COMPLETED"
        private const val TAG = "32StepsBgVolume"
    }
}
