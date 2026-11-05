package com.carlink.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory journal of the lines that matter for diagnosing USB / adapter connection problems in the
 * car, shown on screen (Settings -> Logs -> USB diagnostics) so they can be photographed.
 *
 * Why it exists: exported log files can only be saved to the head unit's own storage on GM AAOS, so
 * they cannot be got off the car. This journal does not depend on the log level or on file logging:
 * [Logger.log] feeds every message here BEFORE any level/tag filtering.
 *
 * Process lifetime only (a cold boot starts a fresh process, which is exactly the window of interest).
 */
object DiagnosticJournal {
    /** Most recent lines kept. ~300 lines covers several minutes of retries plus a full connect. */
    const val CAPACITY = 300

    /** A message is journaled if it contains any of these markers. */
    val MARKERS =
        listOf(
            "[USB_EVENT]",
            "[USB_PERM]",
            "[USB_ID]",
            "[USB_BUS]",
            "[BOX_KICK]",
            "[BOX_WIFI]",
            "[USB_ATTACH]",
            "[USB_DETACH]",
            "[STATUS]",
            "[KEY]",
            "[MEDIA_SESSION] Media button",
            "[SUPERVISOR]",
            "[LOGGING]",
            "[USB] Interface",
            "[USB] Attach event",
            "[USB] Running attempt",
            "Device opened",
            "Carlink Native starting",
        )

    private val buffer = ArrayDeque<String>(CAPACITY)
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Snapshot of the journal, oldest first. Updates live. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun isRelevant(message: String): Boolean = MARKERS.any { message.contains(it) }

    /** Record [message] if it is relevant. Cheap no-op otherwise; safe from any thread. */
    fun offer(
        message: String,
        timeMs: Long = System.currentTimeMillis(),
    ) {
        if (!isRelevant(message)) return
        val line =
            synchronized(timeFormat) { timeFormat.format(Date(timeMs)) } + "  " + message
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
            _lines.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }
}
