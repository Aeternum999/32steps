package com.thirtytwo.steps

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque

class VolumeAccessibilityService : AccessibilityService() {

    private lateinit var volumeController: VolumeController
    private lateinit var prefs: PrefsManager
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    // Hold-to-repeat state
    private var holdingKey: Int? = null

    // Every physical press is queued and processed in order. This prevents
    // quick taps from being lost while the short-lived Activity is starting.
    private val pendingDirections = ArrayDeque<Int>()
    private var stepInFlight = false
    private val stepTimeoutMs = 1500L
    private val activityLaunchDelayMs = 80L

    private val initialRepeatDelayMs = 400L
    private val repeatIntervalMs = 220L

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive) {
                holdingKey = null
                return
            }
            holdingKey?.let { key ->
                enqueueVolumeStep(key)
                tick()
                handler.postDelayed(this, repeatIntervalMs)
            }
        }
    }

    private val stepTimeoutRunnable = Runnable {
        // Recover if Android/OEM prevents the completion broadcast from arriving.
        stepInFlight = false
        processNextVolumeStep()
    }

    private val stepCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BackgroundVolumeActivity.ACTION_STEP_COMPLETED) {
                handler.removeCallbacks(stepTimeoutRunnable)
                stepInFlight = false
                processNextVolumeStep()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        volumeController = VolumeController.getInstance(this)
        prefs = PrefsManager(this)

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
        } catch (_: Throwable) {}

        val filter = IntentFilter(BackgroundVolumeActivity.ACTION_STEP_COMPLETED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stepCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stepCompletedReceiver, filter)
        }

        startForegroundService(Intent(this, AudioService::class.java))
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!prefs.enabled) return false

        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (holdingKey == null) {
                    holdingKey = event.keyCode
                    enqueueVolumeStep(event.keyCode)
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

    private fun enqueueVolumeStep(keyCode: Int) {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> BackgroundVolumeActivity.DIRECTION_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> BackgroundVolumeActivity.DIRECTION_DOWN
            else -> return
        }
        pendingDirections.addLast(direction)
        processNextVolumeStep()
    }

    private fun processNextVolumeStep() {
        if (stepInFlight || pendingDirections.isEmpty()) return

        val direction = pendingDirections.removeFirst()
        stepInFlight = true

        try {
            startService(Intent(this, AudioService::class.java).apply {
                action = AudioService.ACTION_PREPARE_BACKGROUND_VOLUME
            })

            handler.postDelayed({
                val intent = Intent(this, BackgroundVolumeActivity::class.java).apply {
                    putExtra(BackgroundVolumeActivity.EXTRA_DIRECTION, direction)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                try {
                    handler.postDelayed(stepTimeoutRunnable, stepTimeoutMs)
                    startActivity(intent)
                } catch (e: Exception) {
                    handler.removeCallbacks(stepTimeoutRunnable)
                    Log.e("32StepsBgVolume", "Could not start background volume activity", e)
                    stepInFlight = false
                    processNextVolumeStep()
                }
            }, activityLaunchDelayMs)
        } catch (e: Exception) {
            Log.e("32StepsBgVolume", "Could not prepare background volume activity", e)
            stepInFlight = false
            processNextVolumeStep()
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
        pendingDirections.clear()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(stepCompletedReceiver)
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}
