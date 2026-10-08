package com.carlink.usb

import com.carlink.protocol.KnownDevices
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest's USB device filter decides which attach events launch the app (and therefore get a
 * silent permission grant); KnownDevices decides which devices the app will actually open. If they
 * drift, a supported adapter either never launches the app or launches it and is ignored.
 */
class DeviceFilterTest {
    @Test
    fun usbDeviceFilterMatchesKnownDevices() {
        val xml = File("src/main/res/xml/usb_device_filter.xml")
        assertTrue("filter not found from ${File(".").absoluteFile}", xml.exists())
        val fromXml =
            Regex("""vendor-id="(\d+)"\s+product-id="(\d+)"""")
                .findAll(xml.readText())
                .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                .toSet()
        assertEquals(KnownDevices.DEVICES.toSet(), fromXml)
    }
}
