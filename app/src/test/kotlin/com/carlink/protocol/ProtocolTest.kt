package com.carlink.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {
    private fun header(bytes: ByteArray) = MessageParser.parseHeader(bytes.copyOfRange(0, HEADER_SIZE))

    private fun payload(bytes: ByteArray) = bytes.copyOfRange(HEADER_SIZE, bytes.size)

    private fun parse(bytes: ByteArray): Message {
        val h = header(bytes)
        return MessageParser.parseMessage(h, payload(bytes))
    }

    @Test
    fun everyCommandRoundTrips() {
        for (cmd in CommandMapping.entries) {
            if (cmd == CommandMapping.INVALID) continue
            val msg = parse(MessageSerializer.serializeCommand(cmd))
            assertTrue("$cmd should parse as CommandMessage", msg is CommandMessage)
            assertEquals(cmd, (msg as CommandMessage).command)
        }
    }

    @Test
    fun recordAudioCommandsAreDistinctAndParsed() {
        // The adapter raises these when any app opens the CarPlay mic; the app must see them.
        assertEquals(CommandMapping.START_RECORD_AUDIO, CommandMapping.fromId(1))
        assertEquals(CommandMapping.STOP_RECORD_AUDIO, CommandMapping.fromId(2))
    }

    @Test
    fun unknownCommandIdIsInvalid() {
        assertEquals(CommandMapping.INVALID, CommandMapping.fromId(98765))
    }

    @Test
    fun micAudioRoundTrips() {
        val pcm = ByteArray(640) { (it % 251).toByte() }
        val bytes = MessageSerializer.serializeAudio(pcm, decodeType = 5, audioType = 3)
        assertEquals(MessageType.AUDIO_DATA, header(bytes).type)
        val msg = parse(bytes) as AudioDataMessage
        assertEquals(5, msg.decodeType)
        assertEquals(3, msg.audioType)
        assertNotNull(msg.data)
        assertArrayEquals(pcm, msg.data)
    }

    @Test
    fun singleByteAudioPayloadIsACommand() {
        val body =
            ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(5).putFloat(0f).putInt(3).put(AudioCommand.AUDIO_SIRI_START.id.toByte()).array()
        val msg = MessageParser.parseMessage(MessageHeader(body.size, MessageType.AUDIO_DATA), body) as AudioDataMessage
        assertEquals(AudioCommand.AUDIO_SIRI_START, msg.command)
        assertEquals(null, msg.data)
    }

    @Test
    fun badMagicIsRejected() {
        val bytes = MessageSerializer.serializeCommand(CommandMapping.FRAME).copyOfRange(0, HEADER_SIZE)
        bytes[0] = 0
        try {
            MessageParser.parseHeader(bytes)
            fail("expected HeaderParseException")
        } catch (_: HeaderParseException) {
        }
    }

    @Test
    fun badTypeCheckIsRejected() {
        val bytes = MessageSerializer.serializeCommand(CommandMapping.FRAME).copyOfRange(0, HEADER_SIZE)
        bytes[12] = (bytes[12] + 1).toByte()
        try {
            MessageParser.parseHeader(bytes)
            fail("expected HeaderParseException")
        } catch (_: HeaderParseException) {
        }
    }

    @Test
    fun wrongHeaderSizeIsRejected() {
        try {
            MessageParser.parseHeader(ByteArray(8))
            fail("expected HeaderParseException")
        } catch (_: HeaderParseException) {
        }
    }

    // ---- init sequence: routing assertions ---------------------------------------------------

    private fun commandsIn(messages: List<ByteArray>): List<CommandMapping> =
        messages.mapNotNull {
            val h = header(it)
            if (h.type != MessageType.COMMAND) null else (MessageParser.parseMessage(h, payload(it)) as CommandMessage).command
        }

    @Test
    fun minimalInitStillAssertsMicAndAudioRouting() {
        val cmds = commandsIn(MessageSerializer.generateInitSequence(AdapterConfig(), "MINIMAL_ONLY"))
        assertTrue("host mic must be re-asserted", CommandMapping.MIC in cmds)
        assertTrue("audio transfer must be re-asserted", CommandMapping.AUDIO_TRANSFER_OFF in cmds)
        assertEquals("wifi enable must stay last", CommandMapping.WIFI_ENABLE, cmds.last())
    }

    @Test
    fun boxMicAndBluetoothAudioAreHonouredWhenConfigured() {
        val cfg = AdapterConfig(micType = "box", audioTransferMode = true)
        val cmds = commandsIn(MessageSerializer.generateInitSequence(cfg, "MINIMAL_ONLY"))
        assertTrue(CommandMapping.BOX_MIC in cmds)
        assertTrue(CommandMapping.AUDIO_TRANSFER_ON in cmds)
    }

    @Test
    fun pendingChangesAreNotDuplicatedByAssertions() {
        val cmds =
            commandsIn(
                MessageSerializer.generateInitSequence(
                    AdapterConfig(),
                    "MINIMAL_PLUS_CHANGES",
                    setOf(MessageSerializer.ConfigKey.MIC_SOURCE),
                ),
            )
        assertEquals(1, cmds.count { it == CommandMapping.MIC })
        assertEquals(1, cmds.count { it == CommandMapping.AUDIO_TRANSFER_OFF })
    }

    @Test
    fun fullInitSendsMicAndAudioExactlyOnce() {
        val cmds = commandsIn(MessageSerializer.generateInitSequence(AdapterConfig(), "FULL"))
        assertEquals(1, cmds.count { it == CommandMapping.MIC })
        assertEquals(1, cmds.count { it == CommandMapping.AUDIO_TRANSFER_OFF })
    }

    @Test
    fun knownDevicesAreRecognised() {
        assertTrue(KnownDevices.isKnownDevice(0x1314, 0x1520))
        assertTrue(KnownDevices.isKnownDevice(0x1314, 0x1521))
        assertTrue(KnownDevices.isKnownDevice(0x08E4, 0x01C0))
        assertTrue(!KnownDevices.isKnownDevice(0x1314, 0x9999))
    }
}
