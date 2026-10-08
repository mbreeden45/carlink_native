package com.carlink.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * After the car takes media focus away for good (driver switched to radio/Bluetooth), the phone keeps
 * streaming for a moment until our PAUSE lands. Re-requesting focus on those straggler packets would
 * immediately steal the output back and undo the driver's choice, so media focus requests are
 * suppressed for [windowMs] after a permanent loss.
 */
class FocusCooldown(
    private val windowMs: Long = 5_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile private var suppressedUntil = Long.MIN_VALUE

    fun markLost() {
        suppressedUntil = clock() + windowMs
    }

    fun isSuppressed(): Boolean = clock() < suppressedUntil

    fun clear() {
        suppressedUntil = Long.MIN_VALUE
    }
}

/**
 * Participates in Android audio focus on behalf of the projected phone's audio streams.
 *
 * Without this the app played audio through AudioTracks while holding *no* focus. On AAOS the car's
 * own sources (Bluetooth media, radio, the OEM voice assistant) then had no signal that CarPlay was
 * playing, so they kept or won the output, which is the "silent Spotify, audio went to Bluetooth"
 * failure. Holding focus for the duration of each stream makes the car pause/duck competing sources
 * and makes our streams predictable.
 *
 * Each CarPlay stream gets its own request, with the gain type and usage that map onto the matching
 * AAOS CarAudioContext (the same usages the AudioTracks already use).
 *
 * @param onMediaFocusLostPermanently the car moved to another media source for good; the owner should
 *   pause the phone so it stops streaming into the void.
 */
class AudioFocusController(
    context: Context,
    private val log: (String) -> Unit,
    private val onMediaFocusLostPermanently: () -> Unit,
) {
    enum class Channel(
        val gain: Int,
        val usage: Int,
        val contentType: Int,
    ) {
        MEDIA(AudioManager.AUDIOFOCUS_GAIN, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC),
        NAVIGATION(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.CONTENT_TYPE_SPEECH,
        ),
        ASSISTANT(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH),
        CALL(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.CONTENT_TYPE_SPEECH,
        ),
    }

    companion object {
        /** Map the protocol's audioType field to a focus channel (1 media, 2 nav, 3 call, 4 siri). */
        fun channelForAudioType(audioType: Int): Channel? =
            when (audioType) {
                AudioStreamType.MEDIA -> Channel.MEDIA
                AudioStreamType.NAVIGATION -> Channel.NAVIGATION
                AudioStreamType.PHONE_CALL -> Channel.CALL
                AudioStreamType.SIRI -> Channel.ASSISTANT
                else -> null
            }
    }

    /** User kill-switch / Bluetooth-audio-mode off-switch. Disabling releases everything held. */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) abandonAll()
        }

    private val mediaCooldown = FocusCooldown()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val held = HashMap<Channel, AudioFocusRequest>()
    private val lock = Any()

    /** Idempotent: a no-op when the channel's focus is already held. */
    fun request(channel: Channel) {
        if (!enabled) return
        synchronized(lock) {
            if (held.containsKey(channel)) return
            if (channel == Channel.MEDIA && mediaCooldown.isSuppressed()) return

            val request =
                AudioFocusRequest
                    .Builder(channel.gain)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(channel.usage)
                            .setContentType(channel.contentType)
                            .build(),
                    ).setOnAudioFocusChangeListener { change -> onFocusChange(channel, change) }
                    // Duck/pause is handled by the car's audio policy and the phone; we never want the
                    // framework to auto-duck our own tracks underneath us.
                    .setWillPauseWhenDucked(false)
                    .setAcceptsDelayedFocusGain(false)
                    .build()

            val result =
                try {
                    audioManager.requestAudioFocus(request)
                } catch (e: Exception) {
                    log("[FOCUS] ${channel.name}: request threw ${e.message}")
                    AudioManager.AUDIOFOCUS_REQUEST_FAILED
                }
            // Hold the request even if it failed: playback proceeds regardless (it did before this
            // class existed) and we must not hammer the audio service on every packet.
            held[channel] = request
            log("[FOCUS] ${channel.name}: requested -> ${resultName(result)}")
        }
    }

    fun abandon(channel: Channel) {
        synchronized(lock) {
            val request = held.remove(channel) ?: return
            try {
                audioManager.abandonAudioFocusRequest(request)
            } catch (e: Exception) {
                log("[FOCUS] ${channel.name}: abandon threw ${e.message}")
            }
            log("[FOCUS] ${channel.name}: abandoned")
        }
    }

    fun abandonAll() {
        Channel.entries.forEach { abandon(it) }
    }

    private fun onFocusChange(
        channel: Channel,
        change: Int,
    ) {
        log("[FOCUS] ${channel.name}: change=${changeName(change)}")
        // Only a permanent loss on the media channel means "the car switched sources". Transient loss
        // and ducking are the normal result of our own nav/Siri requests and must not pause the phone.
        if (channel == Channel.MEDIA && change == AudioManager.AUDIOFOCUS_LOSS) {
            synchronized(lock) { held.remove(channel) }
            mediaCooldown.markLost()
            onMediaFocusLostPermanently()
        }
    }

    private fun resultName(result: Int): String =
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
            else -> "FAILED"
        }

    private fun changeName(change: Int): String =
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> change.toString()
        }
}
