package com.carlink.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterWifiKickTest {
    @Test
    fun md5MatchesKnownVector() {
        // RFC 1321 test vector.
        assertEquals("900150983cd24fb0d6963f7d28e17f72", AdapterWifiKick.md5Hex("abc"))
    }

    @Test
    fun formMatchesTheShortcut() {
        val form = AdapterWifiKick.buildForm(tsMs = 1700000000123L, secret = "S3CRET")
        // Signed text: query fields in the Shortcut's order (cmd, item, ts, val) + secret, no space.
        assertEquals("cmd=set&item=Udisk&ts=1700000000123&val=1S3CRET", form.signedText)
        val sign = AdapterWifiKick.md5Hex(form.signedText)
        assertEquals("cmd=set&item=Udisk&val=1&ts=1700000000123&sign=$sign", form.body)
    }

    @Test
    fun spaceVariantOnlyChangesTheSignedText() {
        val form = AdapterWifiKick.buildForm(tsMs = 1L, secret = "k", separator = " ")
        assertEquals("cmd=set&item=Udisk&ts=1&val=1 k", form.signedText)
    }

    @Test
    fun successIsColonZero() {
        assertTrue(AdapterWifiKick.isSuccess("{\"err\":0}"))
        assertFalse(AdapterWifiKick.isSuccess("{\"err\":-1}"))
        assertFalse(AdapterWifiKick.isSuccess(null))
    }
}
