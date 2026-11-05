package com.carlink

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.PowerManager
import android.view.Surface
import com.carlink.audio.AudioFocusController
import com.carlink.audio.DualStreamAudioManager
import com.carlink.audio.MicFormats
import com.carlink.audio.MicrophoneCaptureManager
import com.carlink.connection.ConnectResult
import com.carlink.connection.ConnectionHooks
import com.carlink.connection.ConnectionSupervisor
import com.carlink.connection.WatchdogSnapshot
import com.carlink.logging.Logger
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logVideoUsb
import com.carlink.logging.logWarn
import com.carlink.media.CarlinkMediaBrowserService
import com.carlink.media.MediaSessionManager
import com.carlink.platform.AudioConfig
import com.carlink.platform.PlatformDetector
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.AdapterDriver
import com.carlink.protocol.AudioCommand
import com.carlink.protocol.AudioDataMessage
import com.carlink.protocol.CommandMapping
import com.carlink.protocol.CommandMessage
import com.carlink.protocol.MediaDataMessage
import com.carlink.protocol.Message
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.PhoneType
import com.carlink.protocol.PluggedMessage
import com.carlink.protocol.TouchAction
import com.carlink.protocol.UnpluggedMessage
import com.carlink.protocol.VideoDataMessage
import com.carlink.protocol.VideoStreamingSignal
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.BehaviorPreferences
import com.carlink.ui.settings.MicSourceConfig
import com.carlink.ui.settings.WiFiBandConfig
import com.carlink.usb.AdapterWifiKick
import com.carlink.usb.UsbDeviceWrapper
import com.carlink.util.AppExecutors
import com.carlink.util.LogCallback
import com.carlink.video.H264Renderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Main Carlink Manager
 *
 * Central orchestrator for the Carlink native application:
 * - USB device lifecycle management
 * - Protocol communication via AdapterDriver
 * - Video rendering via H264Renderer
 * - Audio playback via DualStreamAudioManager
 * - Microphone capture for Siri/calls
 * - MediaSession integration for AAOS
 *
 * Ported from: lib/carlink.dart
 */
class CarlinkManager(
    private val context: Context,
    initialConfig: AdapterConfig = AdapterConfig.DEFAULT,
) {
    // Config can be updated when actual surface dimensions are known
    private var config: AdapterConfig = initialConfig
    private var lastSurfaceRebindMs = 0L
    private var videoPaused = false

    // When true, perform a one-time decoder recovery the next time streaming starts.
    private var needsPostStreamRecovery = false

    // Prevent multiple recoveries in the same streaming session.
    private var didPostStreamRecovery = false

    // Guard against immediate double-reset (e.g., reset in start() then reset again on first STREAMING)
    private var lastDecoderResetMs = 0L
    
    // Reusable discard buffer for paused video (prevents GC churn)
    // 256KB reduces loop iterations on large packets without being "huge"
    private var discardVideoBuffer: ByteArray = ByteArray(256 * 1024)

    companion object {
        // Appliance fast-boot polling: grab the adapter within one poll of it enumerating on the
        // USB bus, rather than sleeping 3 s between checks. DEVICE_WAIT_MS caps the total wait so a
        // genuinely-absent adapter still returns NO_DEVICE promptly for the supervisor to retry.
        private const val DEVICE_POLL_MS = 250L
        private const val DEVICE_WAIT_MS = 12_000L

        // A single known iPhone re-pairs fast; nudge WiFi pairing sooner if the box is quiet.
        private const val PAIR_TIMEOUT_MS = 8000L

        // GM gminfo37 (Equinox EV ultrawide) native panel. documents/reference/gminfo/video.
        private const val GM_NATIVE_WIDTH = 2400
        private const val GM_NATIVE_HEIGHT = 960

        // Recovery constants (matches Flutter CarlinkPlugin.kt)
        private const val RESET_THRESHOLD = 3
        private const val RESET_WINDOW_MS = 30_000L

        // How long a connection attempt waits for the video Surface/renderer to exist
        private const val SURFACE_WAIT_MS = 20_000L

        // Let the system finish enumerating the adapter after a USB attach event
        // Short on purpose: an unopened adapter re-enumerates ~9 s after attaching, and the user needs
        // as much of that window as possible to tap OK on the USB prompt.
        private const val ATTACH_SETTLE_MS = 150L

        // Wi-Fi "kick" (AdapterWifiKick): only once the adapter has been absent from USB longer than
        // its own ~3.5 s restart gap, and at most once a minute.
        private const val KICK_AFTER_ABSENT_MS = 8_000L
        // After a failed/rejected request, try again this soon. After an accepted one, give the adapter
        // this long to appear on USB before asking again. "Not on Wi-Fi" consumes no wait at all: the
        // request fires the moment the car joins the adapter's Wi-Fi.
        private const val KICK_RETRY_MS = 20_000L
        private const val KICK_SETTLE_MS = 45_000L
        private const val KICK_TICK_MS = 5_000L

        // Surface debouncing - wait for size to stabilize before updating codec
        private const val SURFACE_DEBOUNCE_MS = 150L
    }

    /**
     * Connection state enum.
     */
    enum class State {
        DISCONNECTED,
        CONNECTING,
        DEVICE_CONNECTED,
        STREAMING,
    }

    /**
     * Media metadata information.
     */
    data class MediaInfo(
        val songTitle: String?,
        val songArtist: String?,
        val albumName: String?,
        val appName: String?,
        val albumCover: ByteArray?,
    )

    /**
     * Callback interface for Carlink events.
     */
    interface Callback {
        fun onStateChanged(state: State)

        fun onMediaInfoChanged(mediaInfo: MediaInfo)

        fun onLogMessage(message: String)

        fun onHostUIPressed()

        /** True decoded video size (crop-corrected). The UI aspect-locks the surface to it. */
        fun onVideoSizeChanged(
            width: Int,
            height: Int,
        ) {}

        /**
         * One-line, driver-readable reason for the current not-streaming state ("adapter not on USB",
         * "waiting for USB access", ...). Empty when there is nothing to add. Main thread.
         */
        fun onConnectionDetail(detail: String) {}
    }

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main)

    // Current state
    private val currentState = AtomicReference(State.DISCONNECTED)
    val state: State get() = currentState.get()

    // Callback
    private var callback: Callback? = null

    // USB
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var usbDevice: UsbDeviceWrapper? = null

    // Wake lock to prevent CPU sleep during USB streaming
    // PARTIAL_WAKE_LOCK keeps CPU running but allows screen to turn off
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Carlink::UsbStreamingWakeLock",
        )

    // Protocol
    private var adapterDriver: AdapterDriver? = null

    // Video
    private var h264Renderer: H264Renderer? = null
    private var videoSurface: Surface? = null
    private var videoInitialized = false // Track if video subsystem is ready for decoding
    private var lastVideoDiscardWarningTime = 0L // Throttle discard warnings

    // Audio
    private var audioManager: DualStreamAudioManager? = null
    private var audioInitialized = false

    // Audio focus (so the car pauses/ducks competing sources instead of fighting CarPlay audio)
    private var audioFocus: AudioFocusController? = null

    // Microphone
    private var microphoneManager: MicrophoneCaptureManager? = null
    private var currentMicDecodeType = 5 // 16kHz mono
    private var currentMicAudioType = 3 // Siri/voice input

    // The adapter announces the PCM format it wants for mic uplink via AUDIO_INPUT_CONFIG (and it is
    // echoed on every incoming audio frame's decodeType). CarPlay phone calls use 16 kHz wideband
    // (decodeType 5), Siri/voice 16 kHz, AA narrowband calls 8 kHz (decodeType 3). We capture mic at
    // whatever the box last asked for instead of assuming 16 kHz, then clamp: 24 kHz / stereo
    // (6/7) are dropped by the adapter's fixed 640 B input buffer, so they fall back to 16 kHz.
    // Only VOICE-format frames (3/5/6/7) update this. Media and nav playback frames (decodeType
    // 1/2/4: 44.1/48 kHz stereo) arrive far more often and say nothing about the uplink format;
    // letting them overwrite it meant a narrowband call's "8 kHz" was lost the instant a nav prompt
    // played. Reset on every teardown so one session's format never leaks into the next.
    @Volatile private var lastInputDecodeType = MicFormats.DEFAULT_UPLINK_DECODE_TYPE

    private fun clampMicDecodeType(decodeType: Int): Int = MicFormats.uplinkDecodeType(decodeType)

    // Last decoded video size (crop-corrected), replayed to every newly bound UI callback so a
    // re-created screen / callback never sits at "aspect unknown" (stretched) waiting for a format
    // change that the renderer will not report again (it de-duplicates by size).
    @Volatile private var lastVideoWidth = 0

    @Volatile private var lastVideoHeight = 0

    // Single in-flight USB attach debounce (see onUsbDeviceAttached).
    private var attachJob: Job? = null


    // Last USB bus snapshot logged when no adapter was found; only re-logged when it changes.
    @Volatile private var lastBusSnapshot: String? = null

    @Volatile private var connectionDetail = ""

    /** Publish [detail] to the UI (deduplicated, main thread). */
    private fun setConnectionDetail(detail: String) {
        if (detail == connectionDetail) return
        connectionDetail = detail
        if (detail.isNotEmpty()) logInfo("[STATUS] $detail", tag = Logger.Tags.USB)
        scope.launch { this@CarlinkManager.callback?.onConnectionDetail(detail) }
    }

    // MediaSession
    private var mediaSessionManager: MediaSessionManager? = null

    // Timers
    private var pairTimeout: Timer? = null
    private var frameIntervalJob: Job? = null

    // Phone type tracking for frame interval decisions
    private var currentPhoneType: PhoneType? = null

    // Recovery tracking (matches Flutter CarlinkPlugin.kt)
    private var lastResetTime: Long = 0
    private var consecutiveResets: Int = 0

    // Connection lifecycle. The supervisor lives in a manager-owned scope (NOT the UI scope) so a
    // recomposition or cancelled LaunchedEffect can never abandon a half-open connection.
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Wi-Fi kick bookkeeping (uptime ms). "Seen" starts at creation so a cold boot kicks 8 s in.
    @Volatile private var lastAdapterSeenMs = android.os.SystemClock.uptimeMillis()

    @Volatile private var nextKickAllowedMs = 0L

    @Volatile private var kickWaitingForWifi = false
    private var kickJob: Job? = null

    /**
     * The adapter has not been on USB for a while: ask it over Wi-Fi to switch to its CarPlay USB
     * mode (what the user's iOS Shortcut did). Non-blocking; the attach broadcast picks it up.
     * Called from the device search, from a 5 s ticker, and the moment the car joins a Wi-Fi network.
     */
    private fun maybeKickAdapterOverWifi() {
        if (state == State.DEVICE_CONNECTED || state == State.STREAMING) return
        val now = android.os.SystemClock.uptimeMillis()
        if (UsbDeviceWrapper.findDevices(usbManager).isNotEmpty()) {
            lastAdapterSeenMs = now
            return
        }
        if (now - lastAdapterSeenMs < KICK_AFTER_ABSENT_MS) return
        if (now < nextKickAllowedMs) return
        if (kickJob?.isActive == true) return
        if (AdapterWifiKick.findWifiNetwork(context) == null) {
            if (!kickWaitingForWifi) {
                kickWaitingForWifi = true
                logInfo("[BOX_KICK] Waiting: car is not on the adapter's Wi-Fi yet", tag = Logger.Tags.USB)
            }
            setConnectionDetail("Adapter not on USB - connect the car to the adapter's Wi-Fi (\"carlink\") to wake it")
            return // consumes no wait: fires as soon as Wi-Fi appears
        }
        kickWaitingForWifi = false
        nextKickAllowedMs = now + KICK_RETRY_MS // provisional; prevents a double fire
        setConnectionDetail("Adapter not on USB - asking it over Wi-Fi to switch to CarPlay mode")
        kickJob =
            connectionScope.launch {
                val outcome = AdapterWifiKick.send(context, BuildConfig.BOX_API_SECRET) { logInfo(it, tag = Logger.Tags.USB) }
                val after = android.os.SystemClock.uptimeMillis()
                nextKickAllowedMs =
                    when (outcome) {
                        AdapterWifiKick.Outcome.ACCEPTED -> after + KICK_SETTLE_MS
                        AdapterWifiKick.Outcome.NO_SECRET -> Long.MAX_VALUE // build has no secret: never
                        AdapterWifiKick.Outcome.NO_WIFI -> 0L // Wi-Fi dropped in between: retry when back
                        else -> after + KICK_RETRY_MS
                    }
                if (outcome == AdapterWifiKick.Outcome.ACCEPTED) {
                    setConnectionDetail("Adapter accepted the Wi-Fi request - waiting for it on USB")
                }
            }
    }

    // Re-checks every few seconds, including between connection attempts (whose back-off grows to 30 s).
    private val kickTicker =
        connectionScope.launch {
            while (true) {
                kotlinx.coroutines.delay(KICK_TICK_MS)
                runCatching { maybeKickAdapterOverWifi() }
            }
        }

    // The instant the car joins a Wi-Fi network (e.g. the adapter's, which comes up some seconds after
    // power-on), try straight away instead of waiting for the next tick or connection attempt.
    private val wifiCallback =
        object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                logInfo("[BOX_KICK] Car joined a Wi-Fi network", tag = Logger.Tags.USB)
                nextKickAllowedMs = 0L
                connectionScope.launch {
                    kotlinx.coroutines.delay(1_500) // let addressing settle
                    maybeKickAdapterOverWifi()
                }
            }
        }

    private val wifiCallbackRegistered: Boolean =
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val request =
                android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    // The adapter's Wi-Fi has no internet; don't require it.
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            cm.registerNetworkCallback(request, wifiCallback)
            true
        }.getOrDefault(false)
    private val teardownLock = Any()

    // Mic start/stop run here, strictly in order, and never on the USB read thread: starting capture
    // can spend a few hundred ms probing audio sources, which would stall video reads.
    private val micDispatcher = Executors.newSingleThreadExecutor { Thread(it, "MicControl") }.asCoroutineDispatcher()
    private val micScope = CoroutineScope(SupervisorJob() + micDispatcher)

    // Last time any message arrived from the adapter / when the protocol was started (uptime ms)
    @Volatile private var lastRxMs = 0L

    @Volatile private var adapterStartedAtMs = 0L

    private val supervisor: ConnectionSupervisor =
        ConnectionSupervisor(
            scope = connectionScope,
            hooks =
                object : ConnectionHooks {
                    override suspend fun connect(): ConnectResult = connectOnce()

                    override fun teardown() = this@CarlinkManager.teardown()

                    override fun markDisconnected() = setState(State.DISCONNECTED)

                    override fun snapshot(nowMs: Long): WatchdogSnapshot =
                        WatchdogSnapshot(
                            wanted = this@CarlinkManager.supervisor.isWanted,
                            attemptActive = this@CarlinkManager.supervisor.isAttemptActive,
                            reconnectPending = this@CarlinkManager.supervisor.isReconnectPending,
                            disconnected = state == State.DISCONNECTED,
                            streaming = state == State.STREAMING,
                            adapterStartedAtMs = adapterStartedAtMs,
                            lastRxMs = lastRxMs,
                            nowMs = nowMs,
                        )

                    override fun log(message: String) {
                        logInfo(message, tag = Logger.Tags.USB)
                    }
                },
            clock = { android.os.SystemClock.uptimeMillis() },
        ).also { it.start() }

    // Surface update debouncing - prevents repeated codec recreation during rapid surface size changes
    private var surfaceUpdateJob: Job? = null
    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0
    private var pendingCallback: Callback? = null

    // Media metadata tracking
    private var lastMediaSongName: String? = null
    private var lastMediaArtistName: String? = null
    private var lastMediaAlbumName: String? = null
    private var lastMediaAppName: String? = null
    private var lastAlbumCover: ByteArray? = null

    /** Clears cached media metadata to prevent stale data on reconnect. */
    private fun clearCachedMediaMetadata() {
        lastMediaSongName = null
        lastMediaArtistName = null
        lastMediaAlbumName = null
        lastMediaAppName = null
        lastAlbumCover = null
    }

    // Executors
    private val executors = AppExecutors()

    // Detected once. Lets the appliance request the head unit's exact native resolution instead of
    // whatever the laid-out surface happens to be, so the adapter streams the panel's true aspect
    // ratio rather than snapping to a nearby tier (the usual cause of ultrawide stretching).
    private val platform by lazy { PlatformDetector.detect(context) }

    /**
     * The resolution to hand the adapter via OPEN. On the GM gminfo37 head unit this is pinned to
     * the panel's native 2400x960 (2.5:1); on any other hardware the actual surface size is used.
     */
    private fun openResolutionFor(
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): Pair<Int, Int> =
        if (platform.isGmInfo37() && surfaceWidth > 0 && surfaceHeight > 0) {
            GM_NATIVE_WIDTH to GM_NATIVE_HEIGHT
        } else {
            (surfaceWidth and 1.inv()) to (surfaceHeight and 1.inv())
        }

    // LogCallback for Java components
    private val logCallback = LogCallback { message -> log(message) }

    /**
     * Initialize the manager with a Surface and actual surface dimensions.
     *
     * Uses SurfaceView's Surface directly for optimal HWC overlay rendering.
     * This bypasses GPU composition for lower latency and power consumption.
     *
     * @param surface The Surface from SurfaceView to render video to
     * @param surfaceWidth Actual width of the surface in pixels
     * @param surfaceHeight Actual height of the surface in pixels
     * @param callback Callbacks for state changes and events
     */
    fun initialize(
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        callback: Callback,
    ) {
        // Resolution handed to the adapter. On GM gminfo37 this is pinned to the native 2400x960
        // so the adapter streams the true panel aspect ratio; elsewhere the actual (even) surface
        // size is used. Even dimensions are required for H.264 macroblock alignment.
        val (evenWidth, evenHeight) = openResolutionFor(surfaceWidth, surfaceHeight)

        if (config.width != evenWidth || config.height != evenHeight) {
            logInfo(
                "[RES] Updating config from ${config.width}x${config.height} to ${evenWidth}x$evenHeight " +
                    "(surface=${surfaceWidth}x$surfaceHeight, gmNative=${platform.isGmInfo37()})",
                tag = Logger.Tags.VIDEO,
            )
            config = config.copy(width = evenWidth, height = evenHeight)
        }

        // LIFECYCLE FIX: If a renderer already exists, ALWAYS rebind the output surface immediately
        // via MediaCodec.setOutputSurface() (invoked internally by resume()).
        //
        // CRITICAL: Do NOT use reference equality (===) to determine whether a Surface is "the same".
        // After the app is backgrounded or the system enters standby, the Java Surface object may
        // appear unchanged, but the underlying native BufferQueue is DESTROYED and recreated.
        // If the codec continues rendering to the old buffer, video output will stall or go black
        // ("BufferQueue has been abandoned").
        //
        // Correct behavior: Whenever initialize() is called with an existing renderer, immediately
        // rebind the codec to the provided Surface. This guarantees the codec always renders to a
        // valid native surface after pause/resume, standby, orientation changes, or surface recreation.
        // See: https://developer.android.com/reference/android/media/MediaCodec#setOutputSurface
        //
        // NOTE ON RAPID SIZE CHANGES:
        // During initial layout or UI transitions, the surface size may change rapidly
        // (e.g., 996->960->965->969->992). To avoid excessive repeated rebind calls during this brief
        // jitter window, a small throttle (e.g., ~120ms) may be applied around the resume()
        // invocation.
        //
        // IMPORTANT:
        // This is a THROTTLE, not a debounce. One immediate surface rebind must always occur.
        // Surface rebinding is cheap and lifecycle-critical and MUST NOT be delayed, otherwise
        // the codec may continue rendering into a destroyed BufferQueue, resulting in a black screen.

        if (h264Renderer != null) {
            surfaceUpdateJob?.cancel()
            this@CarlinkManager.callback = callback
            this@CarlinkManager.videoSurface = surface

            // Unblock gate BEFORE rebind/resume (critical to accept first keyframe packets)
            videoPaused = false

            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastSurfaceRebindMs >= 120L) {
                lastSurfaceRebindMs = now
                h264Renderer?.setOutputSurface(surface)
            }
            // NOT throttled: resume() is a no-op unless the codec is actually flushed/paused, and it
            // must never be skipped -- a throttled-away resume() after pause() left the codec flushed
            // with videoPaused=false: frames queued, no input callbacks, permanent black screen.
            h264Renderer?.resume()
            replayVideoSize()
            return
        }
        
        // First-time initialization - create new renderer
        this.callback = callback
        this.videoSurface = surface

        logInfo(
            "[RES] Initializing with surface ${evenWidth}x$evenHeight @ ${config.fps}fps, ${config.dpi}dpi",
            tag = Logger.Tags.VIDEO,
        )

        // Detect platform for optimal audio configuration
        // Pass user-configured sample rate from AdapterConfig (overrides platform default)
        val platformInfo = PlatformDetector.detect(context)
        val audioConfig = AudioConfig.forPlatform(platformInfo, userSampleRate = config.sampleRate)

        logInfo(
            "[PLATFORM] Using AudioConfig: sampleRate=${audioConfig.sampleRate}Hz, " +
                "bufferMult=${audioConfig.bufferMultiplier}x, prefill=${audioConfig.prefillThresholdMs}ms",
            tag = Logger.Tags.AUDIO,
        )
        logInfo(
            "[PLATFORM] Using VideoDecoder: ${platformInfo.hardwareH264DecoderName ?: "generic (createDecoderByType)"}",
            tag = Logger.Tags.VIDEO,
        )

        // Initialize H264 renderer with Surface for direct HWC rendering
        // Surface comes from SurfaceView - no GPU composition overhead
        // Pass platform-detected codec name for optimal hardware decoder selection
        h264Renderer =
            H264Renderer(
                context,
                config.width,
                config.height,
                surface,
                logCallback,
                executors,
                platformInfo.hardwareH264DecoderName,
            )

        // Set keyframe callback - after codec reset, we need to request a new IDR frame
        // from the adapter. Without SPS/PPS + keyframe, the decoder cannot produce output.
        h264Renderer?.setKeyframeRequestCallback {
            logInfo("[KEYFRAME] Requesting keyframe after codec reset", tag = Logger.Tags.VIDEO)
            adapterDriver?.sendCommand(CommandMapping.FRAME)
        }

        // Forward the decoded video size so the UI can aspect-lock the surface (ultrawide letterbox
        // instead of stretch). The size is cached and replayed on every surface rebind.
        // NOTE: must read the FIELD (this@CarlinkManager.callback). The bare name `callback` here is
        // initialize()'s parameter, which would pin the first screen's callback forever.
        h264Renderer?.setVideoSizeListener { w, h ->
            logInfo("[RES] Decoded video size reported: ${w}x$h", tag = Logger.Tags.VIDEO)
            lastVideoWidth = w
            lastVideoHeight = h
            replayVideoSize()
        }

        // Start the H264 renderer to initialize MediaCodec and begin decoding
        // This MUST be called before processData() - MediaCodec requires start() before queueInputBuffer()
        h264Renderer?.start()

        // Mark video as initialized - videoProcessor will now process frames instead of discarding
        videoPaused = false
        videoInitialized = true

        // If we are already connected/streaming, request a keyframe now that decoder is ready.
        if (state == State.DEVICE_CONNECTED || state == State.STREAMING) {
            val sent1 = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
            logInfo("[INIT] Keyframe request after initialize sent: $sent1", tag = Logger.Tags.VIDEO)

            scope.launch {
                delay(600)
                if (state == State.DEVICE_CONNECTED || state == State.STREAMING) {
                    val sent2 = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                    logInfo("[INIT] Second keyframe request after initialize sent: $sent2", tag = Logger.Tags.VIDEO)
                }
            }
        }

        logInfo("Video subsystem initialized and ready for decoding", tag = Logger.Tags.VIDEO)

        // Initialize audio manager with platform-specific config
        audioManager =
            DualStreamAudioManager(
                logCallback,
                audioConfig,
            )

        audioFocus =
            AudioFocusController(
                context,
                log = { logInfo(it, tag = Logger.Tags.AUDIO) },
                onMediaFocusLostPermanently = {
                    // The car switched to another media source for good: stop the phone streaming.
                    logInfo("[FOCUS] Media focus lost - pausing phone", tag = Logger.Tags.AUDIO)
                    sendKey(CommandMapping.PAUSE)
                },
            ).also { it.enabled = focusShouldBeHeld(config) }

        // Initialize microphone manager
        microphoneManager =
            MicrophoneCaptureManager(
                context,
                logCallback,
            )

        // Initialize MediaSession only for ADAPTER audio mode (not Bluetooth)
        // In Bluetooth mode, audio goes through phone BT -> car stereo directly,
        // so we don't want this app to appear as an active media source in AAOS.
        // This prevents the vehicle from switching audio source to the app when
        // the user opens or returns to it.
        if (!config.audioTransferMode) {
            mediaSessionManager =
                MediaSessionManager(context, logCallback).apply {
                    initialize()
                    setMediaControlCallback(
                        object : MediaSessionManager.MediaControlCallback {
                            override fun onPlay() {
                                sendKey(CommandMapping.PLAY)
                            }

                            override fun onPause() {
                                sendKey(CommandMapping.PAUSE)
                            }

                            override fun onStop() {
                                sendKey(CommandMapping.PAUSE)
                            }

                            override fun onSkipToNext() {
                                sendKey(CommandMapping.NEXT)
                            }

                            override fun onSkipToPrevious() {
                                sendKey(CommandMapping.PREV)
                            }
                        },
                    )
                }
            logInfo("MediaSession initialized (ADAPTER audio mode)", tag = Logger.Tags.ADAPTR)
        } else {
            logInfo("MediaSession skipped (BLUETOOTH audio mode - audio via phone BT)", tag = Logger.Tags.ADAPTR)
        }

        logInfo("CarlinkManager initialized", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Deliver the last known decoded size to the current UI callback on the main thread. Compose
     * state must not be written from the MediaCodec callback thread while it holds codecLock.
     */
    private fun replayVideoSize() {
        val w = lastVideoWidth
        val h = lastVideoHeight
        if (w <= 0 || h <= 0) return
        scope.launch { this@CarlinkManager.callback?.onVideoSizeChanged(w, h) }
    }

    /**
     * Ask for a connection to the adapter. Returns immediately; the connection runs in a
     * manager-owned scope, retries on failure, and is a no-op if a session is already healthy.
     */
    fun start() {
        supervisor.connect("start")
    }

    /** In Bluetooth audio mode no audio reaches this app, so it must not hold focus; otherwise user choice. */
    private fun focusShouldBeHeld(cfg: AdapterConfig): Boolean =
        BehaviorPreferences.audioFocusEnabled(context) && !cfg.audioTransferMode

    /** Settings toggle: applies immediately (disabling releases any focus currently held). */
    fun setAudioFocusEnabled(enabled: Boolean) {
        BehaviorPreferences.setAudioFocusEnabled(context, enabled)
        audioFocus?.enabled = focusShouldBeHeld(config)
        logInfo("[FOCUS] Audio focus ${if (enabled) "enabled" else "disabled"} by user", tag = Logger.Tags.AUDIO)
    }

    /**
     * The app became visible. If a session is live, re-send the foreground-service start so it can
     * (re)acquire the microphone type, which Android only allows while the app is visible.
     */
    fun onAppVisible() {
        if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
            CarlinkMediaBrowserService.startConnectionForeground(context)
        }
    }

    /**
     * USB attach event from the system (delivered to the Activity via its intent filter).
     * Connects unless we already hold a working session on that exact device.
     */
    // Main thread only (Activity onCreate/onNewIntent). Deliberately NOT @Synchronized: that monitor
    // is shared with start/stopMicrophoneCapture, which can block ~1 s joining the capture thread.
    fun onUsbDeviceAttached(deviceName: String?) {
        logInfo("[USB] Attach event for $deviceName", tag = Logger.Tags.USB)
        lastAdapterSeenMs = android.os.SystemClock.uptimeMillis()
        // Debounce: a fast replug / re-enumeration can deliver several attach intents inside the
        // settle window. Keep only the latest, so two delayed skipIfHealthy=false connects can never
        // fire back-to-back and tear down the session the first one just brought up.
        attachJob?.cancel()
        attachJob =
            connectionScope.launch {
                delay(ATTACH_SETTLE_MS)
                // Evaluate AFTER the settle delay: the in-flight attempt may have opened this device
                // in the meantime.
                val current = usbDevice
                if (state != State.DISCONNECTED && current != null && current.isOpened && current.deviceName == deviceName) {
                    logInfo("[USB] Attach event for the device we already hold open - ignoring", tag = Logger.Tags.USB)
                    return@launch
                }
                if (supervisor.isAttemptActive && current != null && !current.isOpened && current.deviceName != deviceName) {
                    // The running attempt is bound to a STALE device instance (pre-replug path) and is
                    // waiting on a grant that can never arrive for it. connect() would coalesce into it,
                    // so preempt it instead.
                    logWarn(
                        "[USB] Running attempt holds stale ${current.deviceName}; restarting for $deviceName",
                        tag = Logger.Tags.USB,
                    )
                    supervisor.restart("usb-attach-stale")
                    return@launch
                }
                // Same path but not yet open (attempt waiting on permission): connect() coalesces and
                // the attempt's own hasPermission() poll picks the attach-launch grant up.
                supervisor.connect("usb-attach", skipIfHealthy = false)
            }
    }

    /**
     * One connection attempt. Runs on [connectionScope] (IO) under the supervisor's mutex, after
     * [teardown]. Returns the outcome; the supervisor owns retry/backoff.
     */
    private suspend fun connectOnce(): ConnectResult {
        // The renderer is created when the Surface exists. Starting before that can discard the
        // first SPS/PPS + IDR and leave a persistent black screen, so wait for it.
        var waited = 0L
        while (h264Renderer == null && waited < SURFACE_WAIT_MS) {
            delay(200)
            waited += 200
        }
        if (h264Renderer == null) {
            logWarn("[START] Renderer still not initialized after ${waited}ms (no Surface)", tag = Logger.Tags.VIDEO)
            return ConnectResult.FAILED
        }

        // New session attempt (often after vehicle sleep). Arm a one-time post-stream recovery.
        needsPostStreamRecovery = true
        didPostStreamRecovery = false
        lastRxMs = 0L
        adapterStartedAtMs = 0L

        setState(State.CONNECTING)

        // Reset video renderer
        h264Renderer?.reset()
        lastDecoderResetMs = android.os.SystemClock.uptimeMillis()

        // Initialize audio
        if (!audioInitialized) {
            audioInitialized = audioManager?.initialize() ?: false
            if (audioInitialized) {
                logInfo("Audio playback initialized", tag = Logger.Tags.AUDIO)
            }
        }

        // Find device
        log("Searching for Carlinkit device...")
        setConnectionDetail("Looking for the adapter on USB")
        val device = findDevice()
        if (device == null) {
            logError("Failed to find Carlinkit device", tag = Logger.Tags.USB)
            setConnectionDetail(
                if (AdapterWifiKick.findWifiNetwork(context) == null) {
                    "Adapter not on USB - connect the car to the adapter's Wi-Fi (\"carlink\") to wake it"
                } else {
                    "Adapter not detected on USB - retrying"
                },
            )
            return ConnectResult.NO_DEVICE
        }

        log("Device found, opening: ${device.describe()}")
        usbDevice = device

        if (!device.hasPermission()) {
            setConnectionDetail("Adapter found - tap OK on the USB prompt now (it restarts in ~9 s if not opened)")
        }
        if (!device.openWithPermission()) {
            logError(
                "Failed to open USB device (denied=${device.permissionDenied}, " +
                    "dismissedBySystem=${device.dismissedWithoutAnswer}, detached=${device.detached})",
                tag = Logger.Tags.USB,
            )
            setConnectionDetail(
                when {
                    device.permissionDenied -> "USB access was declined - asking again in 60 s"
                    device.detached || device.dismissedWithoutAnswer ->
                        "Adapter re-connected on USB while asking for access - retrying"
                    else -> "Could not open the adapter - retrying"
                },
            )
            // A device that vanished mid-wait, or a prompt the system closed by itself, is a
            // re-enumeration, not a refusal: FAILED (short backoff), never PERMISSION_DENIED (60 s).
            return if (device.permissionDenied) ConnectResult.PERMISSION_DENIED else ConnectResult.FAILED
        }
        setConnectionDetail("Starting adapter")

        // Create video processor for direct USB -> ring buffer data flow
        // This bypasses message parsing for zero-copy performance (matches Flutter architecture)
        val videoProcessor = createVideoProcessor()

        // Create and start adapter driver
        val driver =
            AdapterDriver(
                usbDevice = device,
                messageHandler = ::handleMessage,
                errorHandler = ::handleError,
                logCallback = ::log,
                videoProcessor = videoProcessor,
            )
        adapterDriver = driver

        // Determine initialization mode based on first-run state and pending changes
        val adapterConfigPref = AdapterConfigPreference.getInstance(context)
        val initMode = adapterConfigPref.getInitializationMode()
        val pendingChanges = adapterConfigPref.getPendingChangesSync()

        // Refresh user-configurable settings from preference store before starting
        // This ensures changes made in Settings screen are applied on next connection
        // (display settings like width/height are kept from original config)
        val userConfig = adapterConfigPref.getUserConfigSync()
        val refreshedConfig =
            config.copy(
                audioTransferMode = userConfig.audioTransferMode,
                sampleRate = userConfig.sampleRate.hz,
                micType =
                    when (userConfig.micSource) {
                        MicSourceConfig.APP -> "os"
                        MicSourceConfig.PHONE -> "box"
                    },
                wifiType =
                    when (userConfig.wifiBand) {
                        WiFiBandConfig.BAND_5GHZ -> "5ghz"
                        WiFiBandConfig.BAND_24GHZ -> "24ghz"
                    },
                callQuality = userConfig.callQuality.value,
            )
        config = refreshedConfig // Update stored config for other uses
        audioFocus?.enabled = focusShouldBeHeld(refreshedConfig)

        log("[INIT] Mode: ${adapterConfigPref.getInitializationInfo()}")
        log("[INIT] Audio mode: ${if (refreshedConfig.audioTransferMode) "BLUETOOTH" else "ADAPTER"}")

        // Stamp BEFORE the (slow, blocking) init sequence so any message that arrives afterwards
        // is guaranteed to count as "heard from the adapter".
        adapterStartedAtMs = android.os.SystemClock.uptimeMillis()
        driver.start(refreshedConfig, initMode.name, pendingChanges)
        if (!driver.running) {
            logError("Adapter driver failed to start", tag = Logger.Tags.ADAPTR)
            return ConnectResult.FAILED
        }

        // Mark first init completed and clear pending changes after successful start
        videoPaused = false
        connectionScope.launch {
            if (initMode == AdapterConfigPreference.InitMode.FULL) {
                adapterConfigPref.markFirstInitCompleted()
            }
            if (pendingChanges.isNotEmpty()) {
                adapterConfigPref.clearPendingChanges()
            }
        }

        // Start pair timeout
        clearPairTimeout()
        pairTimeout =
            Timer().apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            adapterDriver?.sendCommand(CommandMapping.WIFI_PAIR)
                        }
                    },
                    PAIR_TIMEOUT_MS,
                )
            }
        return ConnectResult.CONNECTED
    }

    /**
     * Release the adapter session (protocol, USB, audio, mic). Idempotent and thread-safe; does not
     * touch connection state or retry policy -- the supervisor owns both.
     */
    private fun teardown() {
        synchronized(teardownLock) {
            logDebug("[LIFECYCLE] teardown() - clearing frame interval and phoneType", tag = Logger.Tags.VIDEO)
            didPostStreamRecovery = false
            clearPairTimeout()
            stopFrameInterval()
            currentPhoneType = null // Clear phone type on disconnect
            clearCachedMediaMetadata() // Clear stale metadata to prevent race conditions on reconnect
            stopMicrophoneCapture()
            lastInputDecodeType = MicFormats.DEFAULT_UPLINK_DECODE_TYPE

            adapterDriver?.stop()
            adapterDriver = null

            usbDevice?.close()
            usbDevice = null

            adapterStartedAtMs = 0L

            audioFocus?.abandonAll()

            // Stop audio
            if (audioInitialized) {
                audioManager?.release()
                audioInitialized = false
                logInfo("Audio released on teardown", tag = Logger.Tags.AUDIO)
            }
        }
    }

    /**
     * Intentional stop: disconnects and does NOT reconnect automatically. A USB re-attach, or an
     * explicit [start]/[restart], resumes.
     */
    fun stop() {
        supervisor.stop()
        teardown()
        setState(State.DISCONNECTED)
    }

    /**
     * Tear the session down and reconnect. The reconnect itself runs in the manager's own scope, so
     * cancelling the caller (e.g. leaving a screen) cannot leave the connection half-open.
     */
    suspend fun restart() {
        supervisor.restart("manual")?.join()
    }

    /**
     * Send a key command.
     */
    fun sendKey(command: CommandMapping): Boolean = adapterDriver?.sendCommand(command) ?: false

    /**
     * Send a touch event.
     */
    fun sendTouch(
        action: TouchAction,
        x: Float,
        y: Float,
    ): Boolean {
        val normalizedX = x / config.width
        val normalizedY = y / config.height
        return adapterDriver?.sendTouch(action, normalizedX, normalizedY) ?: false
    }

    /**
     * Send a multi-touch event.
     */
    fun sendMultiTouch(touches: List<MessageSerializer.TouchPoint>): Boolean =
        adapterDriver?.sendMultiTouch(touches) ?: false

    /**
     * Release all resources.
     */
    fun release() {
        kickTicker.cancel()
        if (wifiCallbackRegistered) {
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                    .unregisterNetworkCallback(wifiCallback)
            }
        }
        supervisor.close()
        teardown()
        setState(State.DISCONNECTED)
        connectionScope.coroutineContext[Job]?.cancel()
        micScope.coroutineContext[Job]?.cancel()
        micDispatcher.close()

        h264Renderer?.stop()
        h264Renderer = null

        audioFocus?.abandonAll()
        audioFocus = null

        audioManager?.release()
        audioManager = null

        microphoneManager?.stop()
        microphoneManager = null

        mediaSessionManager?.release()
        mediaSessionManager = null

        logInfo("CarlinkManager released", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Get performance statistics.
     */
    fun getPerformanceStats(): Map<String, Any> =
        buildMap {
            put("state", state.name)
            adapterDriver?.getPerformanceStats()?.let { putAll(it) }
        }

    /**
     * Handle USB device detachment event.
     * Called by MainActivity when USB_DEVICE_DETACHED broadcast is received.
     *
     * This provides immediate detection of physical adapter removal,
     * rather than waiting for USB transfer errors.
     */
    fun onUsbDeviceDetached() {
        logWarn("[USB] Device detached broadcast received", tag = Logger.Tags.USB)

        // Only handle if we have an active connection
        if (state == State.DISCONNECTED) {
            logInfo("[USB] Already disconnected, ignoring detach", tag = Logger.Tags.USB)
            return
        }

        // Trigger recovery through the error handler path
        // This ensures consistent recovery behavior
        handleError("USB device physically disconnected")
    }

    /**
     * Resets the H.264 video decoder/renderer.
     *
     * This operation resets the MediaCodec decoder without disconnecting the USB device.
     * Useful for recovering from video decoding errors or codec issues.
     *
     * Matches Flutter: DeviceOperations.resetH264Renderer()
     */
    fun resetVideoDecoder() {
        logInfo("[DEVICE_OPS] Resetting H264 video decoder", tag = Logger.Tags.VIDEO)
        h264Renderer?.reset()
        lastDecoderResetMs = android.os.SystemClock.uptimeMillis()
        logInfo("[DEVICE_OPS] H264 video decoder reset completed", tag = Logger.Tags.VIDEO)
        // Ensure frame interval running after manual reset
        ensureFrameIntervalRunning()
    }

    /**
     * Stops the video decoder/renderer without stopping the USB connection.
     *
     * IMPORTANT: Must be called BEFORE navigating away from the projection screen
     * to prevent BufferQueue abandoned errors. The codec outputs frames to the
     * SurfaceTexture, which is destroyed when the VideoSurface composable is disposed.
     * If the codec is still running when the surface is destroyed, it causes
     * "BufferQueue has been abandoned" errors.
     *
     * The video will automatically restart when the user returns to the MainScreen
     * and a new SurfaceTexture becomes available (via LaunchedEffect).
     */
    fun stopVideo() {
        logInfo("[VIDEO] Stopping video decoder before navigation", tag = Logger.Tags.VIDEO)
        h264Renderer?.stop()
        logInfo("[VIDEO] Video decoder stopped - safe to destroy surface", tag = Logger.Tags.VIDEO)
    }

    /**
     * Handle Surface destruction - pause codec IMMEDIATELY.
     *
     * CRITICAL: This is called when SurfaceView's Surface is destroyed, which happens
     * BEFORE onStop() is called. If we wait for onStop(), the codec will try to render
     * to a dead surface causing "BufferQueue has been abandoned" errors.
     *
     * Call this from VideoSurface's onSurfaceDestroyed callback.
     */
    fun onSurfaceDestroyed() {
        logInfo("[LIFECYCLE] Surface destroyed - pausing codec immediately", tag = Logger.Tags.VIDEO)

        // Cancel any pending surface updates
        surfaceUpdateJob?.cancel()
        surfaceUpdateJob = null
        pendingSurface = null

        // Clear surface reference - it's now invalid
        videoSurface = null

        // Ensure that next initialize always passes throttling
        lastSurfaceRebindMs = 0L

        // Pause codec immediately to prevent rendering to dead surface
        h264Renderer?.pause()
        videoPaused = true
    }

    /**
     * Pause video decoding when app goes to background.
     *
     * On AAOS, when the app is covered by another app (e.g., Maps, Phone), the Surface
     * may remain valid but SurfaceFlinger stops consuming frames. This causes
     * BufferQueue to fill up, stalling the decoder. When the user returns, video
     * appears blank while audio continues normally.
     *
     * This method flushes the codec to prevent BufferQueue stalls. The USB connection
     * and audio playback continue unaffected.
     *
     * NOTE: Surface destruction is handled separately by onSurfaceDestroyed() which
     * is called when the Surface is actually destroyed (may be before or after onStop).
     *
     * Call this from Activity.onStop().
     */
        fun pauseVideo() {
            logInfo("[LIFECYCLE] Pausing video for background", tag = Logger.Tags.VIDEO)
            h264Renderer?.pause()
            videoPaused = true
        }

    /**
     * Resumes video decoding when the app returns to the foreground.
     *
     * Re-binds the output surface (critical after standby/suspend where native BufferQueue may be recreated),
     * conditionally restarts the codec only if previously paused/flushed, clears any potential backlog defensively,
     * and requests keyframes (immediate + delayed) to accelerate recovery and prevent black/grey screens.
     *
     * Call this from Activity.onStart() for proper lifecycle symmetry.
     */
        fun resumeVideo() {
            logInfo("[LIFECYCLE] Resuming video for foreground", tag = Logger.Tags.VIDEO)

            val surface = videoSurface
            val surfaceValid = surface != null && surface.isValid

            if (!surfaceValid) {
                logInfo("[LIFECYCLE] Surface not ready or invalid - deferring full resume to initialize()", tag = Logger.Tags.VIDEO)
                if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
                    val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                    logInfo("[RESUME] Early keyframe request sent (surface invalid): $sent", tag = Logger.Tags.VIDEO)
                }
                return
            }

            // Always rebind surface first
            h264Renderer?.setOutputSurface(surface)

            // Conditional resume + defensive clear
            if (videoPaused) {
                h264Renderer?.clearRingBuffer() // optional defensive
                h264Renderer?.resume()
                videoPaused = false  // Only clear after actual resume
            } else {
                logInfo("[RESUME] Not paused - skipping codec.start()", tag = Logger.Tags.VIDEO)
            }

            // Double keyframe request
            if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
                val sent1 = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                logInfo("[RESUME] First keyframe request sent: $sent1", tag = Logger.Tags.VIDEO)

                scope.launch {
                    delay(600)
                    if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
                        val sent2 = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                        logInfo("[RESUME] Delayed second keyframe request sent: $sent2", tag = Logger.Tags.VIDEO)
                    }
                }
            }
        }

    // ==================== Private Methods ====================

    private fun setState(newState: State) {
        val oldState = currentState.getAndSet(newState)
        if (oldState != newState) {
            callback?.onStateChanged(newState)
            updateMediaSessionState(newState)
            // The adapter is up: the USB-level detail no longer applies.
            if (newState == State.DEVICE_CONNECTED || newState == State.STREAMING) setConnectionDetail("")

            if (newState == State.STREAMING) {
                val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                logInfo("[STATE] Immediate keyframe request on STREAMING state: $sent", tag = Logger.Tags.VIDEO)
            }
        }
    }

    private fun updateMediaSessionState(state: State) {
        when (state) {
            State.CONNECTING -> {
                mediaSessionManager?.setStateConnecting()
                // Acquire wake lock early to ensure USB operations aren't interrupted
                acquireWakeLock()
            }

            State.DISCONNECTED -> {
                mediaSessionManager?.setStateStopped()
                // Stop foreground service when disconnected
                CarlinkMediaBrowserService.stopConnectionForeground(context)
                // Release wake lock - CPU can sleep now
                releaseWakeLock()
            }

            State.STREAMING -> {
                // Start foreground service to keep app active when backgrounded
                CarlinkMediaBrowserService.startConnectionForeground(context)
                // Ensure wake lock is held during streaming
                acquireWakeLock()
            }

            else -> {} // Playback state updated when audio starts
        }
    }

    /**
     * Acquires a partial wake lock to prevent CPU sleep during USB streaming.
     * This ensures USB transfers and heartbeats continue when the app is backgrounded.
     */
    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(10 * 60 * 1000L) // 10 minute timeout as safety
            logInfo("[WAKE_LOCK] Acquired partial wake lock for USB streaming", tag = Logger.Tags.USB)
        }
    }

    /**
     * Releases the wake lock, allowing CPU to sleep.
     */
    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            logInfo("[WAKE_LOCK] Released wake lock", tag = Logger.Tags.USB)
        }
    }

    private suspend fun findDevice(): UsbDeviceWrapper? {
        val deadline = android.os.SystemClock.uptimeMillis() + DEVICE_WAIT_MS
        var device = UsbDeviceWrapper.findFirst(context, usbManager) { log(it) }

        while (device == null && android.os.SystemClock.uptimeMillis() < deadline) {
            maybeKickAdapterOverWifi()
            kotlinx.coroutines.delay(DEVICE_POLL_MS)
            device = UsbDeviceWrapper.findFirst(context, usbManager) { log(it) }
        }

        if (device != null) {
            lastAdapterSeenMs = android.os.SystemClock.uptimeMillis()
            log("Carlinkit device found!")
        } else {
            // Nothing matching KnownDevices. Log what IS on the bus: this is how a cold boot that
            // leaves the adapter in a non-CarPlay USB personality shows up (VID/PID/classes).
            val snapshot = UsbDeviceWrapper.describeBus(usbManager)
            if (snapshot != lastBusSnapshot) {
                lastBusSnapshot = snapshot
                logWarn("[USB_BUS] No known adapter after ${DEVICE_WAIT_MS}ms. $snapshot", tag = Logger.Tags.USB)
            }
        }

        return device
    }

    private fun maybeRunPostStreamRecovery() {
        if (!needsPostStreamRecovery || didPostStreamRecovery) return

        // Avoid immediate double-reset
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDecoderResetMs < 1500L) {
            logInfo("[RECOVERY] Skipping post-stream reset - decoder was reset recently", tag = Logger.Tags.VIDEO)
            didPostStreamRecovery = true
            needsPostStreamRecovery = false
            return
        }

        didPostStreamRecovery = true
        needsPostStreamRecovery = false

        logWarn(
            "[RECOVERY] Streaming started after reconnect - scheduling one-time decoder reset + keyframe burst",
            tag = Logger.Tags.VIDEO,
        )

        scope.launch {
            // Delay past MIN_STARTUP_TIME_MS (2000ms) so reset() actually executes
            delay(2500)

            resetVideoDecoder()  // Flush + start + keyframe request

            delay(200)
            val sent1 = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
            logInfo("[RECOVERY] First keyframe request after reset sent: $sent1", tag = Logger.Tags.VIDEO)

            delay(600)
            val sent2 = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
            logInfo("[RECOVERY] Second keyframe request after reset sent: $sent2", tag = Logger.Tags.VIDEO)
        }
    }

    private fun handleMessage(message: Message) {
        lastRxMs = android.os.SystemClock.uptimeMillis()
        when (message) {
            is PluggedMessage -> {
                logInfo(
                    "[PLUGGED] Device plugged: phoneType=${message.phoneType}, wifi=${message.wifi}",
                    tag = Logger.Tags.VIDEO,
                )
                clearPairTimeout()
                stopFrameInterval() // Stop any existing timer (clean slate)

                // Healthy session: reset reconnect backoff
                supervisor.onHealthy()

                // Store phone type for frame interval decisions during recovery
                currentPhoneType = message.phoneType
                logDebug("[PLUGGED] Stored currentPhoneType=$currentPhoneType", tag = Logger.Tags.VIDEO)

                // Plugged means a new session is forming. Don't reset yet; do recovery when streaming starts.
                // If we are already streaming, do not re-arm recovery (avoid resetting a good stream).
                if (state != State.STREAMING) {
                    needsPostStreamRecovery = true
                    didPostStreamRecovery = false
                } else {
                    logDebug("[PLUGGED] Already streaming - skipping post-stream recovery arm", tag = Logger.Tags.VIDEO)
                }

                // Start/ensure frame interval for CarPlay (periodic keyframe requests)
                ensureFrameIntervalRunning()

                setState(State.DEVICE_CONNECTED)
            }

            is UnpluggedMessage -> {
                supervisor.restart("adapter-unplugged")
            }

            is VideoDataMessage -> {
                clearPairTimeout()

                if (state != State.STREAMING) {
                    logInfo("Video streaming started", tag = Logger.Tags.VIDEO)
                    setState(State.STREAMING)
                    // Safety net: ensure frame interval running when video starts
                    ensureFrameIntervalRunning()
                }

                maybeRunPostStreamRecovery()

                // Feed video data to renderer (fallback when direct processing not used)
                message.data?.let { data ->
                    h264Renderer?.processData(data, message.flags)
                }
            }

            // VideoStreamingSignal indicates video data was processed directly by videoProcessor
            // No data to process here - just update state
            VideoStreamingSignal -> {
                clearPairTimeout()

                if (state != State.STREAMING) {
                    logInfo("Video streaming started (direct processing)", tag = Logger.Tags.VIDEO)
                    setState(State.STREAMING)
                    // Safety net: ensure frame interval running when video starts
                    ensureFrameIntervalRunning()
                }

                maybeRunPostStreamRecovery()

                // Video data already processed directly into ring buffer by videoProcessor
            }

            is AudioDataMessage -> {
                clearPairTimeout()
                processAudioData(message)
            }

            is MediaDataMessage -> {
                clearPairTimeout()
                processMediaMetadata(message)
            }

            is CommandMessage -> {
                if (message.command == CommandMapping.REQUEST_HOST_UI) {
                    callback?.onHostUIPressed()
                } else if (message.command == CommandMapping.START_RECORD_AUDIO) {
                    val wanted = clampMicDecodeType(lastInputDecodeType)
                    logInfo("[CMD] Adapter requests microphone (START_RECORD_AUDIO, decodeType=$wanted)", tag = Logger.Tags.MIC)
                    micScope.launch { startMicrophoneCapture(decodeType = wanted, audioType = 3) }
                } else if (message.command == CommandMapping.STOP_RECORD_AUDIO) {
                    logInfo("[CMD] Adapter released microphone (STOP_RECORD_AUDIO)", tag = Logger.Tags.MIC)
                    micScope.launch { stopMicrophoneCapture() }
                } else if (message.command == CommandMapping.PROJECTION_DISCONNECTED) {
                    needsPostStreamRecovery = true
                    didPostStreamRecovery = false
                    supervisor.restart("projection-disconnected")
                }
            }

            else -> {}
        }

        // Track the adapter's requested mic PCM format from every audio frame it sends, so a later
        // START_RECORD_AUDIO / Siri / call captures at the format the box actually expects.
        if (message is AudioDataMessage && MicFormats.isVoiceDecodeType(message.decodeType)) {
            lastInputDecodeType = message.decodeType
        }

        // Handle audio commands for mic capture
        if (message is AudioDataMessage && message.command != null) {
            handleAudioCommand(message.command)
        }
    }

    private fun processAudioData(message: AudioDataMessage) {
        // Handle volume ducking
        message.volumeDuration?.let { _ ->
            audioManager?.setDucking(message.volume?.toFloat() ?: 1.0f)
            return
        }

        // Skip command messages
        if (message.command != null) return

        // Skip if no audio data
        val audioData = message.data ?: return

        // Hold audio focus for as long as this stream is producing audio
        AudioFocusController.channelForAudioType(message.audioType)?.let { audioFocus?.request(it) }

        // Write audio
        audioManager?.writeAudio(audioData, message.audioType, message.decodeType)
    }

    private fun handleAudioCommand(command: AudioCommand) {
        logDebug("[AUDIO_CMD] Received audio command: ${command.name} (id=${command.id})", tag = Logger.Tags.AUDIO)

        when (command) {
            AudioCommand.AUDIO_NAVI_START -> {
                logInfo("[AUDIO_CMD] Navigation audio START command received", tag = Logger.Tags.AUDIO)
                // Nav audio data will start arriving - track is created on first packet
            }

            AudioCommand.AUDIO_NAVI_STOP -> {
                logInfo("[AUDIO_CMD] Navigation audio STOP command received", tag = Logger.Tags.AUDIO)
                audioManager?.stopNavTrack()
                audioFocus?.abandon(AudioFocusController.Channel.NAVIGATION)
            }

            AudioCommand.AUDIO_SIRI_START -> {
                logInfo("[AUDIO_CMD] Siri started - enabling microphone", tag = Logger.Tags.MIC)
                micScope.launch { startMicrophoneCapture(decodeType = clampMicDecodeType(lastInputDecodeType), audioType = 3) }
            }

            AudioCommand.AUDIO_PHONECALL_START -> {
                val wanted = clampMicDecodeType(lastInputDecodeType)
                logInfo("[AUDIO_CMD] Phone call started - enabling microphone (decodeType=$wanted)", tag = Logger.Tags.MIC)
                micScope.launch { startMicrophoneCapture(decodeType = wanted, audioType = 3) }
            }

            AudioCommand.AUDIO_SIRI_STOP -> {
                logInfo("[AUDIO_CMD] Siri stopped - disabling microphone", tag = Logger.Tags.MIC)
                micScope.launch { stopMicrophoneCapture() }
                audioManager?.stopVoiceTrack()
                audioFocus?.abandon(AudioFocusController.Channel.ASSISTANT)
            }

            AudioCommand.AUDIO_PHONECALL_STOP -> {
                logInfo("[AUDIO_CMD] Phone call stopped - disabling microphone", tag = Logger.Tags.MIC)
                micScope.launch { stopMicrophoneCapture() }
                audioManager?.stopCallTrack()
                audioFocus?.abandon(AudioFocusController.Channel.CALL)
            }

            AudioCommand.AUDIO_MEDIA_START -> {
                logDebug("[AUDIO_CMD] Media audio START command received", tag = Logger.Tags.AUDIO)
                // Media audio data will start arriving - track is created on first packet
            }

            AudioCommand.AUDIO_MEDIA_STOP -> {
                logDebug("[AUDIO_CMD] Media audio STOP command received", tag = Logger.Tags.AUDIO)
                // Playback paused/stopped on the phone: let the car's own sources have the output back
                audioFocus?.abandon(AudioFocusController.Channel.MEDIA)
                // Media track typically stays active, but log for debugging
            }

            AudioCommand.AUDIO_INPUT_CONFIG -> {
                val wanted = clampMicDecodeType(lastInputDecodeType)
                logInfo("[AUDIO_CMD] Audio input config received (decodeType=$wanted)", tag = Logger.Tags.MIC)
                // If capture is already live at a different format, re-open it at the new one so the
                // adapter keeps receiving PCM it can forward (a mid-session 16k<->8k switch otherwise
                // left the box receiving wrongly-sized frames and the far end hearing silence).
                if (microphoneManager?.isCapturing() == true && currentMicDecodeType != wanted) {
                    logInfo("[AUDIO_CMD] INPUT_CONFIG: switching mic $currentMicDecodeType -> $wanted", tag = Logger.Tags.MIC)
                    micScope.launch { startMicrophoneCapture(decodeType = wanted, audioType = currentMicAudioType) }
                }
            }

            AudioCommand.AUDIO_OUTPUT_START -> {
                logDebug("[AUDIO_CMD] Audio output START command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_OUTPUT_STOP -> {
                logDebug("[AUDIO_CMD] Audio output STOP command received", tag = Logger.Tags.AUDIO)
            }

            else -> {
                logDebug("[AUDIO_CMD] Unhandled audio command: ${command.name}", tag = Logger.Tags.AUDIO)
            }
        }
    }

    /**
     * Start streaming the head-unit microphone to the adapter.
     *
     * Triggered by Siri/phone-call audio commands AND by the adapter's START_RECORD_AUDIO command,
     * which is what the phone raises when any other app (voice messages, Snapchat, ...) opens the
     * CarPlay microphone. Previously only the first two were handled, so those apps got silence.
     */
    @Synchronized
    private fun startMicrophoneCapture(
        decodeType: Int,
        audioType: Int,
    ) {
        val mic = microphoneManager ?: return
        if (config.micType == "box") {
            logInfo("Mic source is the adapter's own mic - not capturing from the head unit", tag = Logger.Tags.MIC)
            return
        }
        if (mic.isCapturing()) {
            if (currentMicDecodeType == decodeType && currentMicAudioType == audioType) {
                return
            }
            mic.stop()
        }

        currentMicDecodeType = decodeType
        currentMicAudioType = audioType

        val started =
            mic.start(decodeType) { pcm ->
                // Runs on the mic capture thread; the capture clock paces USB sends.
                adapterDriver?.sendAudio(data = pcm, decodeType = currentMicDecodeType, audioType = currentMicAudioType)
            }
        if (started) {
            logInfo("Microphone capture started (decodeType=$decodeType audioType=$audioType)", tag = Logger.Tags.MIC)
        } else {
            logError("Microphone capture FAILED to start", tag = Logger.Tags.MIC)
        }
    }

    @Synchronized
    private fun stopMicrophoneCapture() {
        val mic = microphoneManager ?: return
        mic.stop()
        logInfo("Microphone capture stopped", tag = Logger.Tags.MIC)
    }

    private fun processMediaMetadata(message: MediaDataMessage) {
        val payload = message.payload

        // Extract new song title (if present)
        val newSongName = (payload["MediaSongName"] as? String)?.takeIf { it.isNotEmpty() }

        // If song title changed, clear all cached metadata to prevent stale data mixing
        if (newSongName != null && newSongName != lastMediaSongName) {
            lastMediaSongName = null
            lastMediaArtistName = null
            lastMediaAlbumName = null
            lastAlbumCover = null
            // Keep appName - typically doesn't change mid-session
        }

        // Extract text metadata
        newSongName?.let {
            lastMediaSongName = it
        }
        (payload["MediaArtistName"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            lastMediaArtistName = it
        }
        (payload["MediaAlbumName"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            lastMediaAlbumName = it
        }
        (payload["MediaAPPName"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            lastMediaAppName = it
        }

        // Process album cover after song change detection
        val albumCover = payload["AlbumCover"] as? ByteArray
        if (albumCover != null) {
            lastAlbumCover = albumCover
        }

        val mediaInfo =
            MediaInfo(
                songTitle = lastMediaSongName,
                songArtist = lastMediaArtistName,
                albumName = lastMediaAlbumName,
                appName = lastMediaAppName,
                albumCover = lastAlbumCover,
            )

        callback?.onMediaInfoChanged(mediaInfo)

        // Update MediaSession
        mediaSessionManager?.updateMetadata(
            title = mediaInfo.songTitle,
            artist = mediaInfo.songArtist,
            album = mediaInfo.albumName,
            appName = mediaInfo.appName,
            albumArt = mediaInfo.albumCover,
        )
        mediaSessionManager?.updatePlaybackState(playing = true)
    }

    /**
     * Simple error handler matching Flutter CarlinkPlugin.kt pattern.
     *
     * Recovery strategy (simple, proven):
     * 1. Check if error is recoverable
     * 2. Track consecutive resets within time window
     * 3. If threshold reached, perform emergency cleanup
     * 4. Otherwise, notify and let system recover naturally
     * 5. For USB disconnects, schedule auto-reconnect with exponential backoff
     */
    private fun handleError(error: String) {
        clearPairTimeout()

        logError("Adapter error: $error", tag = Logger.Tags.ADAPTR)

        // Check if this is a recoverable error and track for error recovery
        if (isRecoverableError(error)) {
            // For recoverable errors (codec reset), keep frame interval running
            // This ensures periodic keyframe requests continue during recovery
            handleCodecReset()
            // Don't change state for recoverable errors - stay in STREAMING if we were
            return
        }

        // Only stop frame interval for non-recoverable errors
        stopFrameInterval()
        currentPhoneType = null

        // Any non-recoverable adapter/USB error means the session is dead. The supervisor marks us
        // disconnected and retries with capped backoff until the adapter is back. Idempotent: bursts
        // of errors (every failed send reports one) collapse into a single reconnect.
        supervisor.onConnectionLost(error)
    }

    /**
     * Checks if an error is recoverable.
     * Matches Flutter: CarlinkPlugin.kt isRecoverableError()
     */
    private fun isRecoverableError(error: String): Boolean {
        val lowerError = error.lowercase()
        return when {
            // MediaCodec-specific errors that typically indicate recoverable issues
            lowerError.contains("reset codec") -> true

            lowerError.contains("mediacodec") && lowerError.contains("illegalstateexception") -> true

            lowerError.contains("codecexception") -> true

            // Surface texture related errors that may be recoverable
            lowerError.contains("surface") && lowerError.contains("invalid") -> true

            else -> false
        }
    }

    /**
     * Handles MediaCodec reset tracking with thread-safe error recovery.
     * Matches Flutter: CarlinkPlugin.kt handleCodecReset()
     *
     * Synchronized to prevent race conditions when multiple threads
     * encounter errors simultaneously.
     */
    @Synchronized
    private fun handleCodecReset() {
        val currentTime = System.currentTimeMillis()

        // Reset counter if outside the window
        if (currentTime - lastResetTime > RESET_WINDOW_MS) {
            consecutiveResets = 0
        }

        consecutiveResets++
        lastResetTime = currentTime

        logInfo("[ERROR RECOVERY] Reset count: $consecutiveResets in window", tag = Logger.Tags.ADAPTR)

        // CRITICAL: Ensure frame interval is running after codec reset
        // This fixes the bug where timer stopped during disconnect and never restarted
        logDebug("[ERROR RECOVERY] Ensuring frame interval running after codec reset", tag = Logger.Tags.VIDEO)
        ensureFrameIntervalRunning()

        // If we've hit the threshold, perform complete cleanup
        if (consecutiveResets >= RESET_THRESHOLD) {
            logWarn("[ERROR RECOVERY] Threshold reached, performing complete system cleanup", tag = Logger.Tags.ADAPTR)
            performEmergencyCleanup()
            consecutiveResets = 0 // Reset counter after cleanup
        }
    }

    /**
     * Performs emergency cleanup to prevent cascade failures.
     * Matches Flutter: CarlinkPlugin.kt performEmergencyCleanup()
     *
     * Simple cleanup - just reset video and close USB.
     * Then asks the connection supervisor to reconnect so no manual action is needed.
     */
    private fun performEmergencyCleanup() {
        try {
            logWarn("[EMERGENCY CLEANUP] Starting conservative system cleanup", tag = Logger.Tags.ADAPTR)

            // Reset video renderer using manager
            try {
                h264Renderer?.reset()
                lastDecoderResetMs = android.os.SystemClock.uptimeMillis()
                logInfo("[EMERGENCY CLEANUP] Video renderer reset", tag = Logger.Tags.ADAPTR)
            } catch (e: Exception) {
                logError("[EMERGENCY CLEANUP] Video reset error: ${e.message}", tag = Logger.Tags.ADAPTR)
            }

            // Close USB connection properly
            try {
                adapterDriver?.stop()
                adapterDriver = null
                usbDevice?.close()
                usbDevice = null
                logInfo("[EMERGENCY CLEANUP] USB connection closed", tag = Logger.Tags.ADAPTR)
            } catch (e: Exception) {
                logError("[EMERGENCY CLEANUP] USB close error: ${e.message}", tag = Logger.Tags.ADAPTR)
            }

            logWarn("[EMERGENCY CLEANUP] Conservative cleanup finished", tag = Logger.Tags.ADAPTR)
            // Self-heal: a car should not need a human to recover from a codec/USB cascade failure.
            supervisor.restart("emergency-cleanup")
        } catch (e: Exception) {
            logError("[EMERGENCY CLEANUP] State error: ${e.message}", tag = Logger.Tags.ADAPTR)
        }
    }

    private fun clearPairTimeout() {
        pairTimeout?.cancel()
        pairTimeout = null
    }

    /**
     * Ensures the periodic keyframe request is running for CarPlay connections.
     *
     * Safe to call multiple times - will not create duplicate jobs.
     * Uses coroutines for better lifecycle management and error handling.
     *
     * Per CPC200-CCPA protocol, FRAME command should be sent every 5 seconds
     * during active CarPlay sessions to maintain decoder stability.
     */
    @Synchronized
    private fun ensureFrameIntervalRunning() {
        val phoneType = currentPhoneType
        val jobActive = frameIntervalJob?.isActive == true

        // Only for CarPlay - protocol specifies 5s keyframe interval
        if (phoneType != PhoneType.CARPLAY) {
            logDebug("[FRAME_INTERVAL] Skipping - phoneType=$phoneType (not CarPlay)", tag = Logger.Tags.VIDEO)
            return
        }

        // Already running - nothing to do
        if (jobActive) {
            logDebug("[FRAME_INTERVAL] Already running - no action needed", tag = Logger.Tags.VIDEO)
            return
        }

        logInfo("[FRAME_INTERVAL] Starting periodic keyframe request (every 5s) for CarPlay", tag = Logger.Tags.VIDEO)

        frameIntervalJob =
            scope.launch(Dispatchers.IO) {
                var requestCount = 0
                while (isActive) {
                    delay(5000)
                    requestCount++
                    val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                    logDebug("[FRAME_INTERVAL] Keyframe request #$requestCount sent=$sent", tag = Logger.Tags.VIDEO)
                }
                logDebug("[FRAME_INTERVAL] Coroutine ended after $requestCount requests", tag = Logger.Tags.VIDEO)
            }
    }

    /**
     * Stops the periodic keyframe request.
     */
    @Synchronized
    private fun stopFrameInterval() {
        val wasActive = frameIntervalJob?.isActive == true
        if (wasActive) {
            logInfo("[FRAME_INTERVAL] Stopping periodic keyframe request (wasActive=$wasActive)", tag = Logger.Tags.VIDEO)
            frameIntervalJob?.cancel()
        } else {
            logDebug("[FRAME_INTERVAL] Stop called but job not active (wasActive=$wasActive)", tag = Logger.Tags.VIDEO)
        }
        frameIntervalJob = null
    }

    /**
     * Create a video processor for direct USB -> ring buffer data flow.
     * This bypasses message parsing and matches Flutter's zero-copy architecture.
     *
     * The processor reads USB data directly into the H264Renderer's ring buffer,
     * skipping the 20-byte video header (width, height, flags, length, unknown).
     */
        private fun createVideoProcessor(): UsbDeviceWrapper.VideoDataProcessor {
            return object : UsbDeviceWrapper.VideoDataProcessor {
                override fun processVideoDirect(
                    payloadLength: Int,
                    readCallback: (buffer: ByteArray, offset: Int, length: Int) -> Int,
                ) {
                    lastRxMs = android.os.SystemClock.uptimeMillis()

                    // Gate: Drop video while paused or renderer missing
                    if (videoPaused || h264Renderer == null) {
                        var remaining = payloadLength
                        while (remaining > 0) {
                            val toRead = minOf(remaining, discardVideoBuffer.size)
                            val bytesRead = readCallback(discardVideoBuffer, 0, toRead)
                            if (bytesRead <= 0) {
                                logWarn("[VIDEO_GATE] Partial USB read during discard - may stall endpoint", tag = Logger.Tags.VIDEO)
                                break
                            }
                            remaining -= bytesRead
                        }

                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastVideoDiscardWarningTime > 5000) {
                            lastVideoDiscardWarningTime = now
                            logInfo("[VIDEO_GATE] Dropped video packet while paused (size=$payloadLength)", tag = Logger.Tags.VIDEO)
                        }
                        return
                    }

                    // Normal processing
                    logVideoUsb { "processVideoDirect: payloadLength=$payloadLength" }
                    h264Renderer?.processDataDirect(payloadLength, 20) { buffer, offset ->
                        readCallback(buffer, offset, payloadLength)
                    }
                }
            }
        }

    private fun log(message: String) {
        logDebug(message, tag = Logger.Tags.ADAPTR)
        callback?.onLogMessage(message)
    }
}

