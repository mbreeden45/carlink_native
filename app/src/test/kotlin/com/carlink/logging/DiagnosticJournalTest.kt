package com.carlink.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticJournalTest {
    @Before
    fun setUp() = DiagnosticJournal.clear()

    @After
    fun tearDown() = DiagnosticJournal.clear()

    @Test
    fun keepsOnlyDiagnosticLines() {
        DiagnosticJournal.offer("[USB_EVENT] DETACHED t=123 /dev/bus/usb/001/004 1314:1520")
        DiagnosticJournal.offer("[RES] Decoded video size reported: 2400x960") // not diagnostic
        DiagnosticJournal.offer("[USB] [USB_PERM] Raising permission prompt") // wrapper-prefixed
        DiagnosticJournal.offer("[STATUS] Adapter not detected on USB - retrying")
        val lines = DiagnosticJournal.lines.value
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("[USB_EVENT] DETACHED t=123 /dev/bus/usb/001/004 1314:1520"))
        assertTrue(lines[1].contains("[USB_PERM]"))
        assertTrue(lines[2].contains("[STATUS]"))
    }

    @Test
    fun dropsOldestBeyondCapacity() {
        repeat(DiagnosticJournal.CAPACITY + 25) { DiagnosticJournal.offer("[STATUS] line $it") }
        val lines = DiagnosticJournal.lines.value
        assertEquals(DiagnosticJournal.CAPACITY, lines.size)
        assertTrue(lines.first().endsWith("[STATUS] line 25"))
        assertTrue(lines.last().endsWith("[STATUS] line ${DiagnosticJournal.CAPACITY + 24}"))
    }

    @Test
    fun clearEmptiesTheJournal() {
        DiagnosticJournal.offer("[KEY] KEYCODE_SEARCH action=1")
        DiagnosticJournal.clear()
        assertEquals(0, DiagnosticJournal.lines.value.size)
    }
}
