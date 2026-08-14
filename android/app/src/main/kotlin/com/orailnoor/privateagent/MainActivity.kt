package com.orailnoor.privateagent

import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import android.graphics.PixelFormat
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.Button
import android.net.Uri
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.privateagent/accessibility"
    private val EVENT_CHANNEL = "com.privateagent/accessibility_events"
    private var eventSink: EventChannel.EventSink? = null
    private var overlayView: View? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        registerQuickControlsChannel(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    AgentAccessibilityService.eventListener = { eventMap ->
                        runOnUiThread {
                            eventSink?.success(eventMap)
                        }
                    }
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                    AgentAccessibilityService.eventListener = null
                }
            }
        )

        registerAccessibilityChannel(flutterEngine, this)
    }

    private fun registerQuickControlsChannel(flutterEngine: FlutterEngine) {
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.privateagent/quick_controls"
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "isDeviceAdminActive" -> {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(this, DeviceAdminReceiverImpl::class.java)
                    result.success(dpm.isAdminActive(admin))
                }

                "requestDeviceAdmin" -> {
                    val admin = ComponentName(this, DeviceAdminReceiverImpl::class.java)
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Allow PrivateAgent to lock this device when you press the Lock Screen button."
                        )
                    }
                    startActivity(intent)
                    result.success(true)
                }

                "lockScreen" -> {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(this, DeviceAdminReceiverImpl::class.java)
                    if (!dpm.isAdminActive(admin)) {
                        result.success("DEVICE_ADMIN_REQUIRED")
                    } else {
                        try {
                            dpm.lockNow()
                            result.success("LOCKED")
                        } catch (e: SecurityException) {
                            result.success("LOCK_FAILED:${e.message ?: "permission denied"}")
                        }
                    }
                }

                "wakeScreen" -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(true)
                            setTurnScreenOn(true)
                        } else {
                            @Suppress("DEPRECATION")
                            window.addFlags(
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            )
                        }

                        val pm = getSystemService(POWER_SERVICE) as PowerManager
                        @Suppress("DEPRECATION")
                        val wakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "$packageName:quick_controls_wake"
                        )
                        wakeLock.acquire(1500L)
                        if (wakeLock.isHeld) wakeLock.release()
                        result.success("WAKE_REQUESTED")
                    } catch (e: Exception) {
                        result.success("WAKE_FAILED:${e.message ?: "unavailable"}")
                    }
                }

                "hasCameraPermission" -> {
                    result.success(
                        checkSelfPermission(Manifest.permission.CAMERA) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                }

                "requestCameraPermission" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), 701)
                    }
                    result.success("CAMERA_PERMISSION_REQUESTED")
                }

                "setFlash" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    if (checkSelfPermission(Manifest.permission.CAMERA) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        result.success("CAMERA_PERMISSION_REQUIRED")
                    } else {
                        result.success(setTorch(enabled))
                    }
                }

                "playAlert" -> {
                    try {
                        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
                        Thread {
                            try {
                                Thread.sleep(1100)
                                tone.release()
                            } catch (_: Exception) {}
                        }.start()
                        result.success("ALERT_PLAYED")
                    } catch (e: Exception) {
                        result.success("ALERT_FAILED:${e.message ?: "unavailable"}")
                    }
                }

                "vibrate" -> {
                    try {
                        val vibrator: Vibrator =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val manager = getSystemService(VIBRATOR_MANAGER_SERVICE)
                                    as VibratorManager
                                manager.defaultVibrator
                            } else {
                                @Suppress("DEPRECATION")
                                getSystemService(VIBRATOR_SERVICE) as Vibrator
                            }
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(
                                500L,
                                VibrationEffect.DEFAULT_AMPLITUDE
                            )
                        )
                        result.success("VIBRATED")
                    } catch (e: Exception) {
                        result.success("VIBRATE_FAILED:${e.message ?: "unavailable"}")
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun setTorch(enabled: Boolean): String {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        return try {
            val id = cameraManager.cameraIdList.firstOrNull { cameraId ->
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "NO_FLASH"

            cameraManager.setTorchMode(id, enabled)
            if (enabled) "FLASH_ON" else "FLASH_OFF"
        } catch (e: Exception) {
            "FLASH_FAILED:${e.message ?: "camera unavailable"}"
        }
    }

    companion object {
        fun registerAccessibilityChannel(flutterEngine: FlutterEngine, context: android.content.Context) {
            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.privateagent/accessibility")
                .setMethodCallHandler { call, result ->
                    android.util.Log.d("PrivateAgentKotlin", "Received method call: ${call.method}")
                    when (call.method) {
                        "ping" -> result.success(true)

                        "logToNative" -> {
                            val msg = call.argument<String>("message") ?: ""
                            android.util.Log.d("PrivateAgentDart", msg)
                            result.success(true)
                        }

                        "isServiceRunning" -> {
                            result.success(AgentAccessibilityService.isRunning())
                        }

                        "checkOverlayPermission" -> {
                            result.success(Settings.canDrawOverlays(context))
                        }

                        "requestOverlayPermission" -> {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            result.success(true)
                        }

                        "showMacroOverlay" -> {
                            // Macro overlay requires an Activity context, so we just ignore or return error if called from background
                            result.error("NOT_SUPPORTED", "Macro overlay not supported from background", null)
                        }

                        "hideMacroOverlay" -> {
                            result.success(true)
                        }

                        "openAccessibilitySettings" -> {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            result.success(true)
                        }

                        "dumpScreen" -> {
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                val nodes = service.dumpScreen()
                                result.success(nodes)
                            }
                        }

                        "takeScreenshot" -> {
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                    service.takeScreenshot { base64 ->
                                        if (base64 != null) {
                                            result.success(base64)
                                        } else {
                                            result.error("SCREENSHOT_FAILED", "Failed to capture screenshot", null)
                                        }
                                    }
                                } else {
                                    result.error("UNSUPPORTED_VERSION", "Screenshot requires Android 11 (API 30) or higher", null)
                                }
                            }
                        }

                        "clickByText" -> {
                            val text = call.argument<String>("text") ?: ""
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.clickByText(text))
                            }
                        }

                        "clickAt" -> {
                            val x = call.argument<Double>("x")?.toFloat() ?: 0f
                            val y = call.argument<Double>("y")?.toFloat() ?: 0f
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.clickAtCoordinates(x, y))
                            }
                        }

                        "typeText" -> {
                            val text = call.argument<String>("text") ?: ""
                            val hint = call.argument<String>("fieldHint")
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.typeText(text, hint))
                            }
                        }

                        "pressEnter" -> {
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.pressEnter())
                            }
                        }

                        "scroll" -> {
                            val direction = call.argument<String>("direction") ?: "down"
                            val target = call.argument<String>("target")
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.scroll(direction, target))
                            }
                        }

                        "showToast" -> {
                            val message = call.argument<String>("message") ?: ""
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                            result.success(true)
                        }

                        "swipe" -> {
                            val startX = call.argument<Double>("startX")?.toFloat() ?: 0f
                            val startY = call.argument<Double>("startY")?.toFloat() ?: 0f
                            val endX = call.argument<Double>("endX")?.toFloat() ?: 0f
                            val endY = call.argument<Double>("endY")?.toFloat() ?: 0f
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.swipe(startX, startY, endX, endY))
                            }
                        }

                        "pressBack" -> {
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.pressBack())
                            }
                        }

                        "pressHome" -> {
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.pressHome())
                            }
                        }

                        "openNotifications" -> {
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.openNotifications())
                            }
                        }

                        "getCurrentPackage" -> {
                            val service = AgentAccessibilityService.instance
                            if (service == null) {
                                result.error("SERVICE_NOT_RUNNING", "Accessibility service is not running", null)
                            } else {
                                result.success(service.getCurrentPackage())
                            }
                        }

                        else -> result.notImplemented()
                    }
                }
        }
    }
}

class BackgroundEngineReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        val engine = io.flutter.embedding.engine.FlutterEngineCache
            .getInstance()
            .get("myCachedEngine")
        if (engine == null) {
            android.util.Log.e("PrivateAgent", "Background engine myCachedEngine was not found")
            return
        }

        android.util.Log.d(
            "PrivateAgent",
            "Registering accessibility channel on myCachedEngine " +
                "(engine=${System.identityHashCode(engine)}, " +
                "dartExecuting=${engine.dartExecutor.isExecutingDart})"
        )
        MainActivity.registerAccessibilityChannel(engine, context.applicationContext)
    }
}
