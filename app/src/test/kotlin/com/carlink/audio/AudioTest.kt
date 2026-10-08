package com.carlink.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {
    @Test
    fun ringBufferPreservesOrderAcrossWrap() {
        val rb = AudioRingBuffer(capacityMs = 10, sampleRate = 1000, channels = 1) // 20 bytes
        val out = ByteArray(8)
        rb.write(ByteArray(8) { it.toByte() })
        assertEquals(8, rb.read(out, 0, 8))
        rb.write(ByteArray(16) { (100 + it).toByte() }) // wraps
        val out2 = ByteArray(16)
        assertEquals(16, rb.read(out2, 0, 16))
        assertArrayEquals(ByteArray(16) { (100 + it).toByte() }, out2)
    }

    @Test
    fun ringBufferOverwritesOldestWhenFull() {
        val rb = AudioRingBuffer(capacityMs = 10, sampleRate = 1000, channels = 1)
        rb.write(ByteArray(15) { 1 })
        rb.write(ByteArray(10) { 2 })
        assertTrue(rb.overflowCount > 0)
        val out = ByteArray(40)
        val n = rb.read(out, 0, 40)
        assertTrue(n <= 20)
        assertEquals(2, out[n - 1].toInt()) // newest data survives
    }

    @Test
    fun emptyReadCountsUnderflow() {
        val rb = AudioRingBuffer(capacityMs = 10, sampleRate = 1000, channels = 1)
        assertEquals(0, rb.read(ByteArray(4), 0, 4))
        assertEquals(1, rb.underflowCount)
    }

    @Test
    fun micChunkIs20ms() {
        assertEquals(640, MicFormats.SIRI_VOICE.chunkBytes) // 16 kHz mono
        assertEquals(320, MicFormats.PHONE_CALL.chunkBytes) // 8 kHz mono
        assertEquals(1280, MicFormats.STEREO_VOICE.chunkBytes) // 16 kHz stereo
    }

    @Test
    fun micFormatLookupDefaultsToSiri() {
        assertEquals(MicFormats.PHONE_CALL, MicFormats.fromDecodeType(3))
        assertEquals(MicFormats.SIRI_VOICE, MicFormats.fromDecodeType(5))
        assertEquals(MicFormats.SIRI_VOICE, MicFormats.fromDecodeType(99))
    }

    @Test
    fun silenceDetection() {
        assertFalse(MicrophoneCaptureManager.hasSignal(ByteArray(640), 640))
        val buf = ByteArray(640).also { it[639] = 1 }
        assertTrue(MicrophoneCaptureManager.hasSignal(buf, 640))
        assertFalse("only the first 'length' bytes count", MicrophoneCaptureManager.hasSignal(buf, 600))
    }

    @Test
    fun audioTypesMapToFocusChannels() {
        assertEquals(AudioFocusController.Channel.MEDIA, AudioFocusController.channelForAudioType(AudioStreamType.MEDIA))
        assertEquals(AudioFocusController.Channel.NAVIGATION, AudioFocusController.channelForAudioType(AudioStreamType.NAVIGATION))
        assertEquals(AudioFocusController.Channel.CALL, AudioFocusController.channelForAudioType(AudioStreamType.PHONE_CALL))
        assertEquals(AudioFocusController.Channel.ASSISTANT, AudioFocusController.channelForAudioType(AudioStreamType.SIRI))
        assertNull(AudioFocusController.channelForAudioType(99))
    }
}

class FocusCooldownTest {
    @Test
    fun suppressesOnlyWithinWindowAfterLoss() {
        var now = 1_000L
        val c = FocusCooldown(windowMs = 5_000, clock = { now })
        assertFalse(c.isSuppressed())
        c.markLost()
        assertTrue(c.isSuppressed())
        now += 4_999
        assertTrue(c.isSuppressed())
        now += 2
        assertFalse(c.isSuppressed())
    }

    @Test
    fun clearEndsSuppressionEarly() {
        val c = FocusCooldown(windowMs = 5_000, clock = { 0L })
        c.markLost()
        c.clear()
        assertFalse(c.isSuppressed())
    }
}
