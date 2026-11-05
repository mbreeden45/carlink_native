package com.carlink

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carlink.logging.FileLogManager
import com.carlink.logging.LogPreset
import com.carlink.logging.LoggingPreferences
import com.carlink.logging.Logger
import com.carlink.logging.apply
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.CommandMapping
import com.carlink.protocol.KnownDevices
import com.carlink.ui.MainScreen
import com.carlink.ui.SettingsScreen
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModePreference
import com.carlink.ui.theme.CarlinkTheme
import com.carlink.usb.UsbDeviceWrapper
import com.carlink.util.AudioDebugLogger
import com.carlink.util.IconAssets
import com.carlink.util.VideoDebugLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Main Activity - Entry Point for Carlink Native
 *
 * Responsibilities:
 * - App initialization and lifecycle management
 * - Permission handling (microphone, USB)
 * - Immersive fullscreen mode for automotive display
 * - Navigation between main projection and settings
 *
 * Ported from: example/lib/main.dart
 */
class MainActivity : ComponentActivity() {
    companion object {
        private val VOICE_KEYS = setOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST)
    }

    // Nullable to prevent UninitializedPropertyAccessException if Activity
    // is destroyed before initialization completes (e.g., low memory kill)
    private var carlinkManager: CarlinkManager? = null
    private var fileLogManager: FileLogManager? = null
    private var currentDisplayMode: DisplayMode = DisplayMode.SYSTEM_UI_VISIBLE
    // Permission launcher
    private val micPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            logInfo("Microphone permission ${if (isGranted) "granted" else "denied"}", tag = "MAIN")
        }

    /**
     * Handles a USB_DEVICE_ATTACHED launch/redelivery.
     *
     * The manifest intent-filter path (cold start -> onCreate, running -> onNewIntent). NOTE: on GM
     * AAOS a fixed system USB handler receives every attach, so this path does not fire there; the
     * ACTION_USB_DEVICE_ATTACHED broadcast (see usbDetachReceiver) does, and drives the connect.
     *
     * Being launched by this intent is also what grants this app permission to the device without
     * any dialog, so reacting here is what makes reconnects silent.
     */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
        if (device != null) {
            logInfo("[USB_EVENT] ATTACHED (intent) ${UsbDeviceWrapper.describeDevice(device, false)}", tag = "MAIN")
        }
        if (device == null || !KnownDevices.isKnownDevice(device.vendorId, device.productId)) return

        logInfo(
            "[USB_ATTACH] Carlinkit attached via intent: VID=0x${device.vendorId.toString(16)} " +
                "PID=0x${device.productId.toString(16)} path=${device.deviceName}",
            tag = "MAIN",
        )
        carlinkManager?.onUsbDeviceAttached(device.deviceName)
    }

    /**
     * Steering-wheel / hardware keys. Every key that reaches this activity is logged, which is how to
     * tell from a drive log what the wheel actually delivers to apps.
     *
     * Voice keys (SEARCH / VOICE_ASSIST / ASSIST) are forwarded to Siri while an adapter session is up.
     * On AAOS the push-to-talk button is normally consumed by CarInputService and handed to the system
     * assistant before any app sees it (taking it over needs the privileged CAR_PROJECTION permission),
     * so this only fires on builds that DO deliver the key to the focused app. When no session is up the
     * key is left to the system as before.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount == 0 || event.action == KeyEvent.ACTION_UP) {
            logInfo(
                "[KEY] ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} repeat=${event.repeatCount} " +
                    "longPress=${event.isLongPress} source=0x${event.source.toString(16)}",
                tag = "MAIN",
            )
        }
        if (event.keyCode in VOICE_KEYS) {
            val manager = carlinkManager
            val sessionUp =
                manager != null &&
                    (manager.state == CarlinkManager.State.DEVICE_CONNECTED || manager.state == CarlinkManager.State.STREAMING)
            if (sessionUp) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    val sent = manager!!.sendKey(CommandMapping.SIRI)
                    logInfo("[KEY] Voice key -> Siri (sent=$sent)", tag = "MAIN")
                }
                return true // consume DOWN and UP so the system search/assistant does not also fire
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    /**
     * BroadcastReceiver for USB device detachment events.
     *
     * Provides immediate detection when the Carlinkit adapter is physically
     * disconnected, enabling faster recovery than waiting for USB transfer errors.
     *
     * This is a feature that neither the original Flutter carlink nor carlink_native
     * had - both relied on transfer error detection for physical disconnection.
     */
    private val usbDetachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // Journal EVERY USB attach/detach on the bus (any device, with uptime) so a log shows
                // exactly when the adapter re-enumerates.
                val eventDevice =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                val kind = if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) "ATTACHED (broadcast)" else "DETACHED"
                logInfo(
                    "[USB_EVENT] $kind t=${android.os.SystemClock.uptimeMillis()} " +
                        (eventDevice?.let { UsbDeviceWrapper.describeDevice(it, false) } ?: "<no device extra>"),
                    tag = "MAIN",
                )
                // On GM AAOS the manifest attach intent never reaches this app (a fixed system USB
                // handler receives every attach), but this broadcast does. An unopened adapter
                // re-enumerates every ~9 s, so the permission prompt must go up the moment it appears.
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                    eventDevice?.let {
                        if (KnownDevices.isKnownDevice(it.vendorId, it.productId)) {
                            carlinkManager?.onUsbDeviceAttached(it.deviceName)
                        }
                    }
                }
                if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                    val device = eventDevice

                    device?.let {
                        // Only handle if it's a known Carlinkit device
                        if (KnownDevices.isKnownDevice(it.vendorId, it.productId)) {
                            logWarn(
                                "[USB_DETACH] Carlinkit device detached: VID=0x${it.vendorId.toString(16)} " +
                                    "PID=0x${it.productId.toString(16)} path=${it.deviceName}",
                                tag = "MAIN",
                            )
                            // Notify CarlinkManager of the detachment (null-safe)
                            carlinkManager?.onUsbDeviceDetached()
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        enableEdgeToEdge()

        // Keep screen on during projection
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize logging
        initializeLogging()

        // Load display mode preference and apply BEFORE calculating display dimensions
        // This ensures correct viewport sizing - fullscreen immersive uses full screen (1920x1080),
        // other modes use usable area excluding visible system bars
        loadAndApplyDisplayMode()

        // Initialize Carlink manager (must be AFTER immersive mode is applied)
        // so display dimensions are calculated correctly for the active mode
        initializeCarlinkManager()

        // Request microphone permission if needed
        requestMicrophonePermission()

        // Register USB detachment receiver for immediate disconnect detection
        registerUsbDetachReceiver()
        // A USB attach intent can be what launched us (adapter plugged in / car woke up)
        handleUsbIntent(intent)

        // Set up Compose UI
        // carlinkManager is guaranteed non-null here since initializeCarlinkManager()
        // completed synchronously above. Use !! with confidence.
        val manager = carlinkManager!!
        setContent {
            CarlinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CarlinkApp(
                        carlinkManager = manager,
                        fileLogManager = fileLogManager,
                        displayMode = currentDisplayMode,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore display mode when returning to app
        // System may have shown bars while app was in background
        applyDisplayMode(currentDisplayMode)
    }

    override fun onStart() {
        super.onStart()
        // Resume video decoding when app returns to foreground
        // On AAOS, Surface may remain valid while app is in background, but
        // BufferQueue can stall. Resume codec and request keyframe for immediate video.
        logInfo("[LIFECYCLE] onStart - resuming video", tag = "MAIN")
        carlinkManager?.resumeVideo()
        // Visible again: the one moment a microphone-type foreground service may be (re)started.
        carlinkManager?.onAppVisible()
    }

    override fun onStop() {
        super.onStop()
        // Pause video decoding when app goes to background
        // On AAOS, when another app covers this app (Maps, Phone, etc.), the Surface
        // may remain valid but SurfaceFlinger stops consuming frames. This causes
        // BufferQueue to fill up, stalling the decoder. Flushing prevents this.
        // USB connection and audio continue unaffected.
        logInfo("[LIFECYCLE] onStop - pausing video", tag = "MAIN")
        carlinkManager?.pauseVideo()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unregister USB detachment receiver
        unregisterUsbDetachReceiver()

        // Release resources (null-safe in case Activity destroyed before init completed)
        carlinkManager?.release()
        fileLogManager?.release()

        logInfo("MainActivity destroyed", tag = "MAIN")
    }

    private fun initializeLogging() {
        // Initialize file logging
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val appVersion = "${packageInfo.versionName}+${packageInfo.longVersionCode}"

        fileLogManager =
            FileLogManager(
                context = this,
                sessionPrefix = "carlink",
                appVersion = appVersion,
            )

        // Configure debug-only logging based on build type
        // In release builds, verbose video/audio pipeline logging is disabled for performance
        val isDebugBuild = BuildConfig.DEBUG
        Logger.setDebugLoggingEnabled(isDebugBuild)
        VideoDebugLogger.setDebugEnabled(isDebugBuild)
        AudioDebugLogger.setDebugEnabled(isDebugBuild)

        // Restore the user's saved log level and "Save logs to file" switch. Previously a release
        // build forced SILENT on every launch and never re-enabled file logging, so after any restart
        // (app update, car power cycle) the Settings switch still showed ON while nothing was written.
        // Read synchronously (bounded) so cold-boot USB events are captured from the first line.
        val loggingPrefs = LoggingPreferences.getInstance(this)
        val (savedPreset, saveToFile) =
            runBlocking {
                withTimeoutOrNull(500L) { loggingPrefs.getLogLevel() to loggingPrefs.isLoggingEnabled() }
            } ?: (LogPreset.SILENT to false)
        if (!isDebugBuild) {
            savedPreset.apply()
        }
        if (saveToFile) {
            // This activity's own instance: it is the one SettingsScreen toggles and lists.
            fileLogManager?.enable()
        }
        logInfo("[LOGGING] Restored preset=${savedPreset.displayName}, saveToFile=$saveToFile", tag = "MAIN")

        logInfo("Carlink Native starting - version $appVersion", tag = "MAIN")
        logInfo("[LOGGING] Debug logging: ${if (isDebugBuild) "ENABLED" else "DISABLED (release build)"}", tag = "MAIN")

        if (isDebugBuild) {
            logInfo(
                "[LOGGING] Video pipeline debug tags enabled: VIDEO_USB, VIDEO_RING, VIDEO_CODEC, VIDEO_SURFACE, VIDEO_PERF",
                tag = "MAIN",
            )
            logInfo(
                "[LOGGING] Audio pipeline debug tags enabled: AUDIO_USB, AUDIO_BUFFER, AUDIO_TRACK, AUDIO_STREAM, AUDIO_PERF",
                tag = "MAIN",
            )
        }
    }

    private fun initializeCarlinkManager() {
        // Load icons from assets for adapter initialization
        val (icon120, icon180, icon256) = IconAssets.loadIcons(this)
        val iconsLoaded = icon120 != null && icon180 != null && icon256 != null

        // Load user-configured adapter settings from sync cache (instant, no I/O blocking)
        val userConfig = AdapterConfigPreference.getInstance(this).getUserConfigSync()

        // Map user config enums to AdapterConfig values
        val micType =
            when (userConfig.micSource) {
                com.carlink.ui.settings.MicSourceConfig.APP -> "os"
                com.carlink.ui.settings.MicSourceConfig.PHONE -> "box"
            }
        val wifiType =
            when (userConfig.wifiBand) {
                com.carlink.ui.settings.WiFiBandConfig.BAND_5GHZ -> "5ghz"
                com.carlink.ui.settings.WiFiBandConfig.BAND_24GHZ -> "24ghz"
            }

        // Get refresh rate and DPI (these are safe to read early)
        val refreshRate = windowManager.defaultDisplay.refreshRate.toInt()
        val dpi = resources.displayMetrics.densityDpi

        val config =
            AdapterConfig(
                width = 0,           // Will be set later from actual VideoSurface size
                height = 0,          // Will be set later from actual VideoSurface size
                fps = refreshRate,
                dpi = dpi,
                icon120Data = icon120,
                icon180Data = icon180,
                icon256Data = icon256,
                // User-configured audio transfer mode (false=adapter, true=bluetooth)
                audioTransferMode = userConfig.audioTransferMode,
                // User-configured sample rate for media audio
                sampleRate = userConfig.sampleRate.hz,
                // User-configured mic, wifi, and call quality
                micType = micType,
                wifiType = wifiType,
                callQuality = userConfig.callQuality.value,
            )

        logInfo(
            "Creating CarlinkManager - resolution will be configured dynamically from VideoSurface",
            tag = "MAIN",
        )
        logInfo(
            "Display config: ${config.width}x${config.height}@${config.fps}fps, ${config.dpi}dpi (initial placeholders)",
            tag = "MAIN",
        )
        logInfo(
            "Icons loaded: $iconsLoaded (120: ${icon120?.size ?: 0}B, 180: ${icon180?.size ?: 0}B, 256: ${icon256?.size ?: 0}B)",
            tag = "MAIN",
        )
        logInfo(
            "[ADAPTER_CONFIG] User config: audioTransferMode=${if (userConfig.audioTransferMode) "bluetooth" else "adapter"}, " +
                "sampleRate=${userConfig.sampleRate.hz}Hz, mic=$micType, wifi=$wifiType, callQuality=${userConfig.callQuality.name}",
            tag = "MAIN",
        )

        carlinkManager = CarlinkManager(this, config)
    }

    private fun requestMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> {
                logInfo("Microphone permission already granted", tag = "MAIN")
            }

            else -> {
                logInfo("Requesting microphone permission", tag = "MAIN")
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    /**
     * Loads display mode preference and applies it.
     * Uses synchronous SharedPreferences cache to avoid ANR.
     * Matches Flutter: main.dart lines 50-57
     */
    private fun loadAndApplyDisplayMode() {
        // Read preference from sync cache (instant, no I/O blocking)
        // This ensures the viewport is correctly sized before the first build
        currentDisplayMode = DisplayModePreference.getInstance(this).getDisplayModeSync()

        applyDisplayMode(currentDisplayMode)
        logInfo("[DISPLAY_MODE] Applied mode: ${currentDisplayMode.name}", tag = "MAIN")
    }

    /**
     * Applies the specified display mode by showing/hiding system bars.
     *
     * @param mode The display mode to apply
     */
    private fun applyDisplayMode(mode: DisplayMode) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        when (mode) {
            DisplayMode.SYSTEM_UI_VISIBLE -> {
                // Show all system bars - let AAOS manage display bounds
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            }

            DisplayMode.STATUS_BAR_HIDDEN -> {
                // Hide status bar only, keep navigation bar visible
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
                windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                // Hide all system bars for maximum projection area
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
    
    /**
     * Registers the USB detachment BroadcastReceiver.
     *
     * This enables immediate detection of physical adapter removal,
     * providing faster recovery than waiting for USB transfer errors.
     */
    private fun registerUsbDetachReceiver() {
        val filter =
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED) // connects immediately, see usbDetachReceiver
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, filter)
        }
        logInfo("[USB_DETACH] Registered USB detachment receiver", tag = "MAIN")
    }

    /**
     * Unregisters the USB detachment BroadcastReceiver.
     */
    private fun unregisterUsbDetachReceiver() {
        try {
            unregisterReceiver(usbDetachReceiver)
            logInfo("[USB_DETACH] Unregistered USB detachment receiver", tag = "MAIN")
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered or already unregistered
            logWarn("[USB_DETACH] Receiver already unregistered: ${e.message}", tag = "MAIN")
        }
    }
}

/**
 * Main Composable App with Overlay Navigation
 *
 * ARCHITECTURE: Uses overlay/stack pattern instead of screen replacement.
 * This matches Flutter's Navigator.push() behavior where the MainPage stays
 * mounted in the widget tree when SettingsPage is pushed on top.
 *
 * WHY: The VideoSurface in MainScreen uses a TextureView with SurfaceTexture.
 * When MainScreen is replaced (disposed), the SurfaceTexture is destroyed,
 * causing "BufferQueue has been abandoned" errors if the MediaCodec is still
 * running. By keeping MainScreen always in composition and overlaying
 * SettingsScreen on top, the video continues playing uninterrupted.
 *
 * BEHAVIOR:
 * - MainScreen is ALWAYS rendered (video keeps playing)
 * - SettingsScreen slides in ON TOP when showSettings is true
 * - Back button closes SettingsScreen overlay
 * - Video is never stopped during navigation
 */
@Composable
fun CarlinkApp(
    carlinkManager: CarlinkManager,
    fileLogManager: FileLogManager?,
    displayMode: DisplayMode,
) {
    var showSettings by remember { mutableStateOf(false) }

    // Log screen changes for debugging
    LaunchedEffect(showSettings) {
        val screenName = if (showSettings) "SettingsScreen (overlay)" else "MainScreen (Projection)"
        logInfo("[UI_NAV] Active screen: $screenName", tag = "UI")
    }

    // Handle back button to close settings overlay
    BackHandler(enabled = showSettings) {
        logInfo("[UI_NAV] Back pressed: Closing SettingsScreen overlay", tag = "UI")
        showSettings = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // MainScreen is ALWAYS in composition - VideoSurface never gets disposed
        // This keeps the SurfaceTexture alive and video playing uninterrupted
        MainScreen(
            carlinkManager = carlinkManager,
            displayMode = displayMode,
            onNavigateToSettings = {
                logInfo("[UI_NAV] Opening SettingsScreen overlay (video continues)", tag = "UI")
                showSettings = true
            },
        )

        // SettingsScreen slides in ON TOP of MainScreen
        // MainScreen remains visible underneath (just covered)
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            SettingsScreen(
                carlinkManager = carlinkManager,
                fileLogManager = fileLogManager,
                onNavigateBack = {
                    logInfo("[UI_NAV] Closing SettingsScreen overlay", tag = "UI")
                    showSettings = false
                },
            )
        }
    }
}
