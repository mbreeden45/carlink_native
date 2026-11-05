package com.carlink.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.carlink.logging.logInfo
import com.carlink.protocol.KnownDevices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val ACTION_USB_PERMISSION = "com.carlink.USB_PERMISSION"

/**
 * USB Device Wrapper for Carlinkit Adapter Communication
 *
 * Provides a high-level interface for USB device operations including:
 * - Device discovery and permission handling
 * - Connection lifecycle management
 * - Bulk transfer operations for data exchange
 * - Interface claiming and endpoint configuration
 *
 * Ported from: lib/driver/usb/usb_device_wrapper.dart
 */
class UsbDeviceWrapper(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val logCallback: (String) -> Unit,
) {
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private val _isOpened = AtomicBoolean(false)
    private val _isReadingLoopActive = AtomicBoolean(false)

    val isOpened: Boolean get() = _isOpened.get()
    val isReadingLoopActive: Boolean get() = _isReadingLoopActive.get()

    val vendorId: Int get() = device.vendorId
    val productId: Int get() = device.productId
    val deviceName: String get() = device.deviceName

    /** True when the last permission request was refused or timed out (drives reconnect backoff). */
    @Volatile var permissionDenied: Boolean = false
        private set

    // Performance tracking
    private var bytesSent: Long = 0
    private var bytesReceived: Long = 0
    private var sendCount: Int = 0
    private var receiveCount: Int = 0
    private var sendErrors: Int = 0
    private var receiveErrors: Int = 0

    /**
     * Check if we have permission to access the USB device.
     */
    fun hasPermission(): Boolean = usbManager.hasPermission(device)

    /**
     * True while this exact device instance is still enumerated. A replug or a mode-switch
     * re-enumeration gives the adapter a NEW deviceName (e.g. /dev/bus/usb/001/003 -> 004); the old
     * [UsbDevice] can then never gain permission, so waiting on it would wait forever.
     */
    fun isStillAttached(): Boolean = usbManager.deviceList.containsKey(device.deviceName)

    /** True when the last open attempt was abandoned because this device instance vanished. */
    @Volatile var detached: Boolean = false
        private set

    /**
     * True when the last permission prompt closed WITHOUT a user answer: the system reported
     * "denied" implausibly fast, or the device left the bus around it. SystemUI's USB dialogs finish
     * themselves when their device detaches and report that as a denial, so a re-enumerating adapter
     * makes the dialog "flash" and then look like a refusal. Not a refusal: retry promptly.
     */
    @Volatile var dismissedWithoutAnswer: Boolean = false
        private set

    /**
     * Request USB permission from the user.
     * This will show a system dialog asking the user to grant permission.
     *
     * At most ONE app-initiated prompt per device instance is ever on screen: if a previous attempt
     * (since cancelled by the supervisor) already raised one for this deviceName, this call does not
     * raise a second dialog; it waits for the answer to the existing one. The answer is caught by a
     * process-lifetime receiver, so it is never lost when an attempt is cancelled mid-wait.
     *
     * Completes as soon as ANY of these happens, whichever is first:
     *  - the dialog's answer arrives for THIS device (granted / denied / dismissed-by-system),
     *  - permission becomes visible via [UsbManager.hasPermission] (e.g. the attach launch granted it),
     *  - the device instance disappears from the bus (sets [detached], NOT [permissionDenied]).
     *
     * @param timeoutMs Timeout in milliseconds to wait for user response
     * @return true if permission was granted, false if denied, dismissed, detached, or timeout
     */
    suspend fun requestPermission(timeoutMs: Long = PERMISSION_TIMEOUT_MS): Boolean {
        if (usbManager.hasPermission(device)) {
            log("Permission already granted for ${device.deviceName}")
            return true
        }

        permissionDenied = false
        detached = false
        dismissedWithoutAnswer = false
        val name = device.deviceName

        PermissionPrompts.ensureReceiver(context)
        val outcome = PermissionPrompts.waiterFor(name)
        val requestedAt: Long
        val existing = PermissionPrompts.pendingSince(name)
        if (existing != null) {
            requestedAt = existing
            log("[USB_PERM] Prompt for $name already on screen (${SystemClock.uptimeMillis() - existing}ms) - not raising another")
        } else {
            requestedAt = SystemClock.uptimeMillis()
            log("[USB_PERM] Raising permission prompt for ${describe()}")
            PermissionPrompts.markRequested(name, requestedAt)
            // Create pending intent with explicit Intent (package set) for Android 12+ security
            // FLAG_MUTABLE is required because UsbManager adds extras to the intent
            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            usbManager.requestPermission(device, pendingIntent)
        }

        // NOTE: cancellation (supervisor restart) leaves the prompt marked as pending on purpose, so
        // the next attempt for the same device instance waits on it instead of stacking a new dialog.
        val granted =
            withTimeoutOrNull(timeoutMs) {
                while (!outcome.isCompleted) {
                    if (usbManager.hasPermission(device)) {
                        log("[USB_PERM] Permission appeared while prompt was pending (attach-launch grant)")
                        outcome.complete(true)
                        break
                    }
                    if (!isStillAttached()) {
                        log("[USB_PERM] $name detached while prompt was pending")
                        detached = true
                        outcome.complete(false)
                        break
                    }
                    delay(PERMISSION_POLL_MS)
                }
                outcome.await()
            }
        PermissionPrompts.clear(name)

        if (granted == null) log("[USB_PERM] Permission request timed out after ${timeoutMs}ms")
        val result = granted == true
        if (!result && granted != null && !detached) {
            val elapsed = SystemClock.uptimeMillis() - requestedAt
            // Give a detach that caused the dismissal a moment to land in the device list.
            delay(DISMISS_SETTLE_MS)
            val attached = isStillAttached()
            if (!attached) detached = true
            if (!attached || elapsed < AUTO_DISMISS_MS) {
                dismissedWithoutAnswer = true
                log(
                    "[USB_PERM] Prompt closed after ${elapsed}ms with no user answer (attached=$attached) - " +
                        "treating as a system dismissal, not a refusal",
                )
            } else {
                log("[USB_PERM] User declined USB access after ${elapsed}ms")
            }
        }
        permissionDenied = !result && !detached && !dismissedWithoutAnswer
        return result
    }

    /** One-line identity of this device for logs (serial only once permission is held). */
    fun describe(): String = describeDevice(device, usbManager.hasPermissionSafe(device))

    /**
     * Open the USB device and claim the interface.
     *
     * @return true if device was opened successfully
     */
    fun open(): Boolean {
        if (_isOpened.get()) {
            log("Device already opened")
            return true
        }

        // Check permission
        if (!usbManager.hasPermission(device)) {
            log("No permission for device ${device.deviceName}")
            return false
        }

        // Open connection
        connection = usbManager.openDevice(device)
        if (connection == null) {
            log("Failed to open device connection")
            return false
        }

        // Find and claim the bulk transfer interface
        if (!claimBulkInterface()) {
            connection?.close()
            connection = null
            return false
        }

        _isOpened.set(true)
        log("Device opened: VID=0x${vendorId.toString(16)} PID=0x${productId.toString(16)}")
        // With permission held the serial is readable. The system keys a persisted "Always open"
        // default on VID/PID AND these strings, so if they differ between boots the default never
        // matches again and the prompt returns -- this line is how to check that across two boots.
        log("[USB_ID] ${describe()}")
        return true
    }

    /**
     * Request permission if needed and open the USB device.
     * This is a convenience suspend function that combines requestPermission() and open().
     *
     * @return true if device was opened successfully
     */
    suspend fun openWithPermission(): Boolean {
        if (!hasPermission()) {
            // Appliance path: when the adapter powers up with the USB bus and the user has ticked
            // "Always open Carlink for this device", Android relaunches us through the
            // USB_DEVICE_ATTACHED intent and grants access for this connection WITHOUT any dialog.
            // That grant can land a beat AFTER the device enumerates, so a connect attempt that
            // peeks at hasPermission() too early would needlessly pop the system dialog on an
            // otherwise silent cold boot. Wait out a short grace window for the implicit grant
            // first; only prompt if it never arrives (first install, or GM cleared the default).
            if (!awaitImplicitGrant(IMPLICIT_GRANT_GRACE_MS)) {
                if (detached) {
                    // Re-enumerated under a new path during the grace window. Do NOT pop a dialog
                    // for a device that no longer exists; fail fast so the supervisor (or the
                    // attach intent for the new path) picks up the fresh instance.
                    log("Device ${device.deviceName} vanished during grace window - not prompting")
                    return false
                }
                log("No implicit USB grant after ${IMPLICIT_GRANT_GRACE_MS}ms - falling back to permission dialog")
                if (!requestPermission()) {
                    return false
                }
            }
        }
        return open()
    }

    /**
     * Poll [UsbManager.hasPermission] for up to [graceMs], returning true the moment the implicit
     * "Always" grant from the attach-launch is visible. Keeps the appliance dialog-free on every
     * normal trip without a race against enumeration order. Returns false early (with [detached]
     * set) if this device instance leaves the bus.
     */
    private suspend fun awaitImplicitGrant(graceMs: Long): Boolean {
        detached = false
        val start = SystemClock.uptimeMillis()
        val deadline = start + graceMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (usbManager.hasPermission(device)) {
                // Latency is logged so the first drive shows how close the 2 s window is to the edge.
                log("USB permission held (attach-launch grant) after ${SystemClock.uptimeMillis() - start}ms - no dialog")
                permissionDenied = false
                return true
            }
            if (!isStillAttached()) {
                detached = true
                return false
            }
            delay(IMPLICIT_GRANT_POLL_MS)
        }
        return false
    }

    /**
     * Close the USB device and release resources.
     */
    fun close() {
        stopReadingLoop()

        claimedInterface?.let { iface ->
            try {
                connection?.releaseInterface(iface)
            } catch (e: Exception) {
                log("Error releasing interface: ${e.message}")
            }
        }
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null

        connection?.close()
        connection = null
        _isOpened.set(false)

        log("Device closed (sent: $sendCount/$bytesSent bytes, received: $receiveCount/$bytesReceived bytes)")
    }

    /**
     * Reset the USB device.
     *
     * @return true if reset was successful
     */
    fun reset(): Boolean {
        val conn = connection ?: return false

        return try {
            // Note: controlTransfer with USB_DEVICE_RESET is not directly available
            // Using close/reopen pattern for reset
            close()
            Thread.sleep(500)
            open()
        } catch (e: Exception) {
            log("Device reset failed: ${e.message}")
            false
        }
    }

    /**
     * Write data to the USB device.
     *
     * @param data Data to send
     * @param timeout Timeout in milliseconds
     * @return Number of bytes actually sent, or -1 on error
     */
    fun write(
        data: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot write: device not open")
                return -1
            }

        val endpoint =
            outEndpoint ?: run {
                log("Cannot write: no OUT endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, data, data.size, timeout)
            if (result >= 0) {
                bytesSent += result
                sendCount++
            } else {
                sendErrors++
            }
            result
        } catch (e: Exception) {
            sendErrors++
            log("Write error: ${e.message}")
            -1
        }
    }

    /**
     * Read data from the USB device.
     *
     * @param buffer Buffer to receive data
     * @param timeout Timeout in milliseconds
     * @return Number of bytes read, or -1 on error/timeout
     */
    fun read(
        buffer: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot read: device not open")
                return -1
            }

        val endpoint =
            inEndpoint ?: run {
                log("Cannot read: no IN endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, buffer, buffer.size, timeout)
            if (result >= 0) {
                bytesReceived += result
                receiveCount++
            } else if (result != -1) {
                // -1 is timeout, not an error
                receiveErrors++
            }
            result
        } catch (e: Exception) {
            receiveErrors++
            log("Read error: ${e.message}")
            -1
        }
    }

    /**
     * Callback interface for direct video data processing.
     * This enables zero-copy video data handling similar to Flutter's processDataDirect pattern.
     */
    interface VideoDataProcessor {
        /**
         * Process video data directly from USB.
         *
         * @param payloadLength Total payload length (including 20-byte video header)
         * @param readCallback Callback to read data into the provided buffer
         */
        fun processVideoDirect(
            payloadLength: Int,
            readCallback: (buffer: ByteArray, offset: Int, length: Int) -> Int,
        )
    }

    /**
     * Callback interface for reading loop events.
     */
    interface ReadingLoopCallback {
        fun onMessage(
            type: Int,
            data: ByteArray?,
        )

        fun onError(error: String)
    }

    /**
     * Start the continuous reading loop.
     *
     * @param callback Callback for received messages
     * @param timeout Read timeout in milliseconds
     * @param videoProcessor Optional processor for direct video data handling (bypasses message parsing)
     */
    fun startReadingLoop(
        callback: ReadingLoopCallback,
        timeout: Int = 30000,
        videoProcessor: VideoDataProcessor? = null,
    ) {
        if (_isReadingLoopActive.getAndSet(true)) {
            log("Reading loop already active")
            return
        }

        Thread {
            log("Reading loop started")
            val headerBuffer = ByteArray(16)

            try {
                while (_isReadingLoopActive.get() && _isOpened.get()) {
                    // Read header
                    val headerResult = read(headerBuffer, timeout)
                    if (headerResult != 16) {
                        if (_isReadingLoopActive.get()) {
                            if (headerResult == -1) {
                                // Timeout - continue loop
                                continue
                            }
                            log("Incomplete header read: $headerResult bytes")
                        }
                        continue
                    }

                    // Parse header
                    val header =
                        try {
                            com.carlink.protocol.MessageParser
                                .parseHeader(headerBuffer)
                        } catch (e: com.carlink.protocol.HeaderParseException) {
                            log("Header parse error: ${e.message}")
                            continue
                        }

                    // Handle VIDEO_DATA specially with direct processing for zero-copy performance
                    // This bypasses message parsing and writes directly to the renderer's ring buffer
                    if (header.type == com.carlink.protocol.MessageType.VIDEO_DATA &&
                        header.length > 0 && videoProcessor != null
                    ) {
                        // Direct video processing - read USB data directly into ring buffer
                        // CRITICAL: Wrap in try-catch to prevent exceptions from crashing reading loop
                        // which would trigger disconnect and session close
                        val conn = connection
                        val endpoint = inEndpoint
                        if (conn != null && endpoint != null) {
                            try {
                                videoProcessor.processVideoDirect(header.length) { buffer, offset, length ->
                                    // Read chunks directly into the provided buffer
                                    var totalRead = 0
                                    while (totalRead < length && _isReadingLoopActive.get()) {
                                        val remaining = length - totalRead
                                        val chunkSize = minOf(remaining, 16384)
                                        val chunkRead =
                                            conn.bulkTransfer(
                                                endpoint,
                                                buffer,
                                                offset + totalRead,
                                                chunkSize,
                                                timeout,
                                            )
                                        if (chunkRead > 0) {
                                            totalRead += chunkRead
                                            bytesReceived += chunkRead
                                            receiveCount++
                                        } else if (chunkRead <= 0) {
                                            break
                                        }
                                    }
                                    totalRead
                                }

                                // Notify callback that video data was received (empty payload signals streaming)
                                callback.onMessage(header.type.id, ByteArray(0))
                            } catch (e: Exception) {
                                // Log but don't propagate - prevents reading loop crash on video processing errors
                                log("Video processing error (non-fatal): ${e.message}")
                                receiveErrors++
                            }
                        }
                        continue
                    }

                    // Read payload for non-video messages (or video if no processor)
                    val payload =
                        if (header.length > 0) {
                            val payloadBuffer = ByteArray(header.length)
                            var totalRead = 0
                            while (totalRead < header.length && _isReadingLoopActive.get()) {
                                val remaining = header.length - totalRead
                                val chunk = ByteArray(minOf(remaining, 16384))
                                val chunkRead = read(chunk, timeout)
                                if (chunkRead > 0) {
                                    System.arraycopy(chunk, 0, payloadBuffer, totalRead, chunkRead)
                                    totalRead += chunkRead
                                } else if (chunkRead == -1) {
                                    // Timeout
                                    break
                                }
                            }
                            if (totalRead == header.length) payloadBuffer else null
                        } else {
                            null
                        }

                    // Deliver message
                    try {
                        callback.onMessage(header.type.id, payload)
                    } catch (e: Exception) {
                        log("Message callback error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (_isReadingLoopActive.get()) {
                    log("Reading loop error: ${e.message}")
                    callback.onError(e.message ?: "Unknown error")
                }
            } finally {
                _isReadingLoopActive.set(false)
                log("Reading loop stopped")
            }
        }.apply {
            name = "USB-ReadLoop"
            isDaemon = true
            start()
        }
    }

    /**
     * Stop the reading loop.
     */
    fun stopReadingLoop() {
        _isReadingLoopActive.set(false)
    }

    /**
     * Get performance statistics.
     */
    fun getPerformanceStats(): Map<String, Any> =
        mapOf(
            "bytesSent" to bytesSent,
            "bytesReceived" to bytesReceived,
            "sendCount" to sendCount,
            "receiveCount" to receiveCount,
            "sendErrors" to sendErrors,
            "receiveErrors" to receiveErrors,
        )

    // ==================== Private Methods ====================

    private fun claimBulkInterface(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            log(
                "Interface $i: class=0x${iface.interfaceClass.toString(16)} " +
                    "sub=0x${iface.interfaceSubclass.toString(16)} proto=0x${iface.interfaceProtocol.toString(16)} " +
                    "endpoints=${iface.endpointCount}",
            )

            // A mass-storage (U-disk) interface also has exactly one bulk IN + one bulk OUT. Claiming
            // it with force=true would detach the kernel's usb-storage driver and push protocol frames
            // into a SCSI bulk-only-transport pipe. That is never the CarPlay protocol interface.
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                log("Interface $i is USB mass storage (U-disk personality) - skipping")
                continue
            }

            // Look for bulk endpoints
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                        inEp = endpoint
                    } else {
                        outEp = endpoint
                    }
                }
            }

            // If we found both endpoints, claim this interface
            if (inEp != null && outEp != null) {
                val claimed = connection?.claimInterface(iface, true) ?: false
                if (claimed) {
                    claimedInterface = iface
                    inEndpoint = inEp
                    outEndpoint = outEp
                    log("Claimed interface $i: IN=${inEp.address.toString(16)} OUT=${outEp.address.toString(16)}")
                    return true
                }
            }
        }

        log("No suitable bulk interface found")
        return false
    }

    private fun log(message: String) {
        logCallback("[USB] $message")
    }

    companion object {
        /**
         * How long to wait for someone to answer the system permission dialog. Long on purpose: the
         * dialog can sit on the head unit while the driver is busy, and answering it late must still
         * work. (A short timeout used to discard a late "Allow" and then re-prompt.)
         */
        private const val PERMISSION_TIMEOUT_MS = 5 * 60_000L

        /**
         * How long [openWithPermission] waits for an implicit grant before raising the prompt.
         *
         * Field logs (build 1005, gminfo37): the grant NEVER arrives -- GM AAOS routes every USB attach
         * to a fixed system handler, so the "Always open" default is never consulted -- and an
         * unopened adapter re-enumerates ~9 s after attaching, killing the prompt. Every millisecond
         * waited here is taken from the user's window to tap OK, so this is only a token race guard.
         */
        private const val IMPLICIT_GRANT_GRACE_MS = 200L
        private const val IMPLICIT_GRANT_POLL_MS = 100L

        /** While the dialog is up, how often to check for an out-of-band grant or a detach. */
        private const val PERMISSION_POLL_MS = 250L

        /**
         * A "denied" faster than this cannot be a human tap: the dialog was closed by the system
         * (typically because the adapter re-enumerated). Treated as a dismissal, retried promptly.
         */
        private const val AUTO_DISMISS_MS = 1_500L
        private const val DISMISS_SETTLE_MS = 300L

        private fun hex4(v: Int) = "%04x".format(v)

        /** Everything the system keys a persisted "Always open" default on, for the log. */
        fun describeDevice(
            d: UsbDevice,
            canReadSerial: Boolean,
        ): String {
            val classes = (0 until d.interfaceCount).joinToString(",") { "0x%02x".format(d.getInterface(it).interfaceClass) }
            val serial =
                if (canReadSerial) {
                    runCatching { d.serialNumber }.getOrNull() ?: "none"
                } else {
                    "n/a(no permission yet)"
                }
            val mfr = runCatching { d.manufacturerName }.getOrNull()
            val product = runCatching { d.productName }.getOrNull()
            val version = runCatching { d.version }.getOrNull()
            return "${d.deviceName} ${hex4(d.vendorId)}:${hex4(d.productId)} devClass=0x%02x ifaces=[$classes] ".format(d.deviceClass) +
                "mfr='$mfr' product='$product' ver=$version serial=$serial"
        }

        /** Snapshot of EVERY device on the host bus (not just Carlinkit), for cold-boot diagnosis. */
        fun describeBus(usbManager: UsbManager): String {
            val all = usbManager.deviceList.values
            if (all.isEmpty()) return "USB bus: no devices enumerated"
            return "USB bus (${all.size}): " +
                all.joinToString(" | ") { d ->
                    val known = if (KnownDevices.isKnownDevice(d.vendorId, d.productId)) " [KNOWN]" else ""
                    describeDevice(d, usbManager.hasPermissionSafe(d)) + known
                }
        }

        /**
         * Find all connected Carlinkit devices.
         */
        fun findDevices(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { device ->
                KnownDevices.isKnownDevice(device.vendorId, device.productId)
            }

        /**
         * Create a wrapper for the first available Carlinkit device.
         */
        fun findFirst(
            context: Context,
            usbManager: UsbManager,
            logCallback: (String) -> Unit,
        ): UsbDeviceWrapper? {
            val device = findDevices(usbManager).firstOrNull() ?: return null
            return UsbDeviceWrapper(context, usbManager, device, logCallback)
        }
    }
}

private fun UsbManager.hasPermissionSafe(d: UsbDevice): Boolean = runCatching { hasPermission(d) }.getOrDefault(false)

/**
 * Process-wide bookkeeping for app-initiated USB permission prompts.
 *
 * The receiver lives for the whole process (registered once on the application context), so a
 * dialog answer is captured even if the connection attempt that raised it was cancelled meanwhile.
 * [pendingSince] lets a later attempt for the same device instance wait on the dialog already on
 * screen instead of raising a second one.
 */
private object PermissionPrompts {
    private val since = ConcurrentHashMap<String, Long>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile private var registered = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                if (ACTION_USB_PERMISSION != intent.action) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val dev =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                val name = dev?.deviceName
                logInfo("[USB_PERM] Prompt answer for ${name ?: "<unknown device>"}: granted=$granted", tag = "USB")
                if (name == null) {
                    since.clear()
                    waiters.values.forEach { it.complete(granted) }
                    waiters.clear()
                    return
                }
                since.remove(name)
                waiters.remove(name)?.complete(granted)
            }
        }

    fun ensureReceiver(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            // RECEIVER_NOT_EXPORTED: only this app's PendingIntent can deliver here.
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
    }

    /** Uptime when a still-live prompt was raised for [name], or null. Expires with the wait timeout. */
    fun pendingSince(name: String): Long? {
        val t = since[name] ?: return null
        if (SystemClock.uptimeMillis() - t > PROMPT_EXPIRY_MS) {
            since.remove(name)
            return null
        }
        return t
    }

    fun markRequested(
        name: String,
        atMs: Long,
    ) {
        since[name] = atMs
    }

    fun waiterFor(name: String): CompletableDeferred<Boolean> =
        waiters.compute(name) { _, old -> if (old == null || old.isCompleted) CompletableDeferred() else old }!!

    fun clear(name: String) {
        since.remove(name)
        waiters.remove(name)
    }

    /** Matches UsbDeviceWrapper's dialog wait (5 min); after that a prompt is assumed gone. */
    private const val PROMPT_EXPIRY_MS = 5 * 60_000L
}
