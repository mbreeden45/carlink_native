package com.carlink.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlink.BuildConfig
import com.carlink.util.AudioDebugLogger
import com.carlink.util.LogCallback
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CARLINK_MIC"

/**
 * Microphone format configuration matching CPC200-CCPA protocol voice formats.
 */
data class MicFormatConfig(
    val sampleRate: Int,
    val channelCount: Int,
) {
    val channelConfig: Int
        get() =
            if (channelCount == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }

    val encoding: Int
        get() = AudioFormat.ENCODING_PCM_16BIT

    val bytesPerSample: Int
        get() = channelCount * 2 // 16-bit = 2 bytes per channel

    /** Bytes in one 20 ms chunk, the unit sent to the adapter. */
    val chunkBytes: Int
        get() = (sampleRate * bytesPerSample * MicrophoneCaptureManager.CHUNK_MS) / 1000
}

/**
 * Predefined microphone formats from CPC200-CCPA protocol.
 */
object MicFormats {
    val PHONE_CALL = MicFormatConfig(8000, 1) // decodeType=3: Phone calls
    val SIRI_VOICE = MicFormatConfig(16000, 1) // decodeType=5: Siri/voice assistant
    val ENHANCED = MicFormatConfig(24000, 1) // decodeType=6: Enhanced voice
    val STEREO_VOICE = MicFormatConfig(16000, 2) // decodeType=7: Stereo voice

    fun fromDecodeType(decodeType: Int): MicFormatConfig =
        when (decodeType) {
            3 -> PHONE_CALL
            5 -> SIRI_VOICE
            6 -> ENHANCED
            7 -> STEREO_VOICE
            else -> SIRI_VOICE // Default to 16kHz mono
        }
}

/**
 * Receives captured PCM, already chunked to 20 ms. Called on the capture thread; must not block for
 * long (a USB bulk write with a short timeout is fine).
 */
fun interface MicSink {
    fun onAudio(pcm: ByteArray)
}

/**
 * MicrophoneCaptureManager - captures the head unit's microphone for the CPC200-CCPA adapter.
 *
 * The iPhone treats the CarPlay microphone as *the* input while a session is active ("CarPlay
 * replaced iPhone Microphone"), so Siri, phone calls, voice messages and apps such as Snapchat all
 * read audio that originates here. If this path produces silence, every one of them records silence.
 *
 * DESIGN
 * ```
 * MicCapture thread (URGENT_AUDIO)
 *     AudioRecord.read(20 ms)  ──►  MicSink.onAudio()  ──►  AdapterDriver.sendAudio() (USB)
 * ```
 * The capture thread itself paces the stream off the audio hardware clock. (An earlier version used
 * a java.util.Timer plus a ring buffer to decouple capture from sending; that added jitter and a
 * second clock for no benefit.)
 *
 * AAOS HARDENING
 *  - **Source fallback.** VOICE_COMMUNICATION is tried first (echo cancellation for calls) but some
 *    AAOS audio policies route it to a telephony input that delivers digital silence while a
 *    projection call is not active. Each candidate source is probed briefly; the first one that
 *    produces non-zero samples wins and is remembered for the rest of the process.
 *  - **Silence diagnostics.** If every source is silent we log whether Android reports the client as
 *    silenced (background-app microphone restriction), which is the usual cause on Android 11+.
 *    The matching fix is the `microphone` foreground-service type, see CarlinkMediaBrowserService.
 */
class MicrophoneCaptureManager(
    private val context: Context,
    private val logCallback: LogCallback,
) {
    companion object {
        const val CHUNK_MS = 20

        /** How long each candidate source is listened to before judging it silent. */
        private const val PROBE_MS = 240

        /** Candidate capture sources, in preference order. */
        private val SOURCES =
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
            )

        fun sourceName(source: Int): String =
            when (source) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
                MediaRecorder.AudioSource.MIC -> "MIC"
                MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
                else -> "source=$source"
            }

        /** True if any 16-bit little-endian sample in [buf] is non-zero. */
        fun hasSignal(
            buf: ByteArray,
            length: Int,
        ): Boolean {
            for (i in 0 until length) if (buf[i].toInt() != 0) return true
            return false
        }
    }

    private var audioRecord: AudioRecord? = null
    private var currentFormat: MicFormatConfig? = null
    private var captureThread: MicCaptureThread? = null
    private val isRunning = AtomicBoolean(false)

    /** Source that last proved it delivers audio; skips probing on later starts. */
    @Volatile private var validatedSource: Int? = null

    // Statistics
    private var startTime: Long = 0
    private var totalBytesCapture: Long = 0
    private var silentChunks: Long = 0
    private var activeSource: Int = -1

    private val lock = Any()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Start capturing and delivering 20 ms chunks to [sink].
     *
     * @param decodeType CPC200-CCPA decode type (3=8k phone, 5=16k siri, 6=24k, 7=16k stereo)
     * @return true if capture started
     */
    fun start(
        decodeType: Int = 5,
        sink: MicSink,
    ): Boolean {
        synchronized(lock) {
            if (isRunning.get()) {
                log("[MIC] Already capturing")
                return true
            }
            if (!hasPermission()) {
                log("[MIC] ERROR: RECORD_AUDIO permission not granted")
                return false
            }

            val format = MicFormats.fromDecodeType(decodeType)
            val minBuffer = AudioRecord.getMinBufferSize(format.sampleRate, format.channelConfig, format.encoding)
            if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
                log("[MIC] ERROR: Invalid buffer size for ${format.sampleRate}Hz")
                return false
            }
            val recordBufferSize = maxOf(minBuffer * 3, format.chunkBytes * 8)

            val order = candidateOrder()
            var chosen: AudioRecord? = null
            var chosenSource = -1
            var firstUsable: AudioRecord? = null
            var firstUsableSource = -1

            for (source in order) {
                val record = tryCreate(source, format, recordBufferSize) ?: continue
                if (validatedSource == source) {
                    chosen = record
                    chosenSource = source
                    break
                }
                val signal = probe(record, format)
                if (signal) {
                    log("[MIC] Source ${sourceName(source)} delivers audio - using it")
                    validatedSource = source
                    chosen = record
                    chosenSource = source
                    break
                }
                log("[MIC] Source ${sourceName(source)} is silent, trying next")
                if (firstUsable == null) {
                    firstUsable = record
                    firstUsableSource = source
                } else {
                    release(record)
                }
            }

            if (chosen == null) {
                // Nothing proved itself. A quiet moment is not proof of a dead mic, so keep the most
                // preferred source that initialised and surface diagnostics.
                if (firstUsable == null) {
                    log("[MIC] ERROR: no AudioRecord source could be initialised")
                    return false
                }
                log("[MIC] WARNING: all sources silent during probe - continuing with ${sourceName(firstUsableSource)}")
                logSilenceDiagnostics()
                chosen = firstUsable
                chosenSource = firstUsableSource
            } else if (firstUsable != null) {
                release(firstUsable)
            }

            audioRecord = chosen
            activeSource = chosenSource
            currentFormat = format
            startTime = System.currentTimeMillis()
            totalBytesCapture = 0
            silentChunks = 0
            isRunning.set(true)

            try {
                if (chosen.recordingState != AudioRecord.RECORDSTATE_RECORDING) chosen.startRecording()
            } catch (e: IllegalStateException) {
                log("[MIC] ERROR: startRecording failed: ${e.message}")
                isRunning.set(false)
                release(chosen)
                audioRecord = null
                return false
            }

            captureThread = MicCaptureThread(chosen, format, sink).also { it.start() }

            log("[MIC] Capture started: ${format.sampleRate}Hz ${format.channelCount}ch src=${sourceName(chosenSource)}")
            AudioDebugLogger.logMicStart(format.sampleRate, format.channelCount, 0)
            return true
        }
    }

    /** Stop capture and release resources. */
    fun stop() {
        synchronized(lock) {
            // The capture thread clears isRunning itself on a fatal read error, so also key off the
            // resources: they still need releasing.
            val wasRunning = isRunning.getAndSet(false)
            if (!wasRunning && audioRecord == null && captureThread == null) return

            log("[MIC] Stopping capture")
            val thread = captureThread
            captureThread = null
            thread?.interrupt()
            try {
                thread?.join(1000)
            } catch (_: InterruptedException) {
                // Ignore
            }

            audioRecord?.let { release(it) }
            audioRecord = null

            val durationMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0
            AudioDebugLogger.logMicStop(durationMs, totalBytesCapture, 0)
            log(
                "[MIC] Capture stopped (${durationMs}ms, ${totalBytesCapture}B, silentChunks=$silentChunks)",
            )
            currentFormat = null
        }
    }

    fun isCapturing(): Boolean = isRunning.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun getStats(): Map<String, Any> =
        synchronized(lock) {
            val durationMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0
            mapOf(
                "isCapturing" to isRunning.get(),
                "format" to (currentFormat?.let { "${it.sampleRate}Hz ${it.channelCount}ch" } ?: "none"),
                "source" to sourceName(activeSource),
                "durationSeconds" to durationMs / 1000.0,
                "totalBytesCaptured" to totalBytesCapture,
                "silentChunks" to silentChunks,
            )
        }

    fun release() {
        stop()
        log("[MIC] MicrophoneCaptureManager released")
    }

    // ---------------------------------------------------------------------------------------

    private fun candidateOrder(): List<Int> {
        val preferred = validatedSource ?: return SOURCES.toList()
        return listOf(preferred) + SOURCES.filter { it != preferred }
    }

    private fun tryCreate(
        source: Int,
        format: MicFormatConfig,
        bufferSize: Int,
    ): AudioRecord? =
        try {
            val record = AudioRecord(source, format.sampleRate, format.channelConfig, format.encoding, bufferSize)
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                log("[MIC] ${sourceName(source)}: AudioRecord failed to initialise")
                record.release()
                null
            }
        } catch (e: SecurityException) {
            log("[MIC] ${sourceName(source)}: permission denied: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            log("[MIC] ${sourceName(source)}: invalid parameters: ${e.message}")
            null
        }

    /** Record for [PROBE_MS] and report whether any non-zero sample arrived. Leaves recording started. */
    private fun probe(
        record: AudioRecord,
        format: MicFormatConfig,
    ): Boolean {
        return try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
            val buf = ByteArray(format.chunkBytes)
            var elapsed = 0
            while (elapsed < PROBE_MS) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) return false
                if (hasSignal(buf, n)) return true
                elapsed += CHUNK_MS
            }
            false
        } catch (e: IllegalStateException) {
            log("[MIC] probe failed: ${e.message}")
            false
        }
    }

    private fun release(record: AudioRecord) {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } catch (_: IllegalStateException) {
            // already stopped
        }
        try {
            record.release()
        } catch (_: Exception) {
            // already released
        }
    }

    /** Explain *why* the mic is silent: Android reports silenced clients in active recording configs. */
    private fun logSilenceDiagnostics() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val configs = am.activeRecordingConfigurations
            log("[MIC] Active recording sessions: ${configs.size}")
            for (c in configs) {
                log(
                    "[MIC]   source=${sourceName(c.clientAudioSource)} silenced=${c.isClientSilenced} " +
                        "device=${c.audioDevice?.type}",
                )
            }
            if (configs.any { it.isClientSilenced }) {
                log(
                    "[MIC] Android is SILENCING capture (app not eligible to record in background). " +
                        "Needs foreground service type 'microphone' started while the app is visible.",
                )
            }
        } catch (e: Exception) {
            log("[MIC] diagnostics unavailable: ${e.message}")
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
        logCallback.log(message)
    }

    /** Dedicated capture thread: reads fixed 20 ms chunks and hands them to the sink. */
    private inner class MicCaptureThread(
        private val record: AudioRecord,
        private val format: MicFormatConfig,
        private val sink: MicSink,
    ) : Thread("MicCapture") {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = format.chunkBytes
            log("[MIC] Capture thread started, chunk=${chunk}B")

            var loggedSilence = false
            while (isRunning.get() && !isInterrupted) {
                val out = ByteArray(chunk)
                var filled = 0
                try {
                    // read() may return fewer bytes than asked; assemble full chunks
                    while (filled < chunk && isRunning.get()) {
                        val n = record.read(out, filled, chunk - filled)
                        if (n < 0) {
                            handleReadError(n)
                            return
                        }
                        filled += n
                    }
                    if (filled < chunk) break

                    totalBytesCapture += chunk
                    if (hasSignal(out, chunk)) {
                        loggedSilence = false
                    } else {
                        silentChunks++
                        // ~2 s of continuous digital silence is a dead mic, not a quiet cabin
                        if (!loggedSilence && silentChunks % 100L == 0L) {
                            loggedSilence = true
                            log("[MIC] WARNING: ${silentChunks} silent chunks so far")
                            logSilenceDiagnostics()
                        }
                    }
                    sink.onAudio(out)
                    AudioDebugLogger.logMicSend(chunk, 0)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "[MIC] Capture thread error: ${e.message}")
                }
            }
            log("[MIC] Capture thread stopped, total captured: ${totalBytesCapture}B")
        }

        private fun handleReadError(code: Int) {
            val name =
                when (code) {
                    AudioRecord.ERROR_INVALID_OPERATION -> "INVALID_OPERATION"
                    AudioRecord.ERROR_BAD_VALUE -> "BAD_VALUE"
                    AudioRecord.ERROR_DEAD_OBJECT -> "DEAD_OBJECT"
                    else -> "ERROR($code)"
                }
            AudioDebugLogger.logMicError(name, "AudioRecord.read returned $name")
            Log.e(TAG, "[MIC] ERROR: read returned $name - capture thread exiting")
            // Make isCapturing() false so the owner can restart capture on the next request
            isRunning.set(false)
        }
    }
}
