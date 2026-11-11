package com.thirtytwo.steps

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeAccessibilityService : AccessibilityService() {

    private lateinit var volumeController: VolumeController
    private lateinit var prefs: PrefsManager
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    // Hold-to-repeat state
    private var holdingKey: Int? = null
    private var repeatCount = 0
    private var lastActionDownTime = 0L

    // Initial delay before repeat starts, then accelerates
    private val initialRepeatDelayMs = 400L
    private val repeatIntervalMs = 80L
    private val fastRepeatIntervalMs = 40L
    private val fastRepeatAfter = 8

    private val repeatRunnable = object : Runnable {
        override fun run() {
            // Safety: if screen is no longer interactive (power pressed), stop repeating
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive) {
                holdingKey = null
                return
            }
            holdingKey?.let { key ->
                performVolumeStep(key)
                tick()
                repeatCount++
                val interval = if (repeatCount > fastRepeatAfter) fastRepeatIntervalMs else repeatIntervalMs
                handler.postDelayed(this, interval)
            }
        }
    }

    private var isTv = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        volumeController = VolumeController.getInstance(this)
        prefs = PrefsManager(this)
        isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
        } catch (_: Throwable) {}

        val intent = Intent(this, AudioService::class.java)
        startForegroundService(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!prefs.enabled) return false

        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

        if (!isVolumeKey) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                lastActionDownTime = System.currentTimeMillis()
                if (holdingKey == null) {
                    holdingKey = event.keyCode
                    repeatCount = 0
                    performVolumeStep(event.keyCode)
                    tick()
                    handler.postDelayed(repeatRunnable, initialRepeatDelayMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                holdingKey = null
                handler.removeCallbacks(repeatRunnable)
            }
        }

        return true
    }

    /**
     * Route the step through a short-lived NoDisplay Activity. This is the
     * Realme/Oppo background setStreamVolume workaround experiment.
     */
    private fun performVolumeStep(keyCode: Int) {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> BackgroundVolumeActivity.DIRECTION_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> BackgroundVolumeActivity.DIRECTION_DOWN
            else -> return
        }

        try {
            val intent = Intent(this, BackgroundVolumeActivity::class.java).apply {
                putExtra(BackgroundVolumeActivity.EXTRA_DIRECTION, direction)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("32StepsBgVolume", "Could not start background volume activity", e)
        }
    }

    private fun tick() {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(10L)
            }
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        holdingKey = null
        handler.removeCallbacks(repeatRunnable)
    }
}