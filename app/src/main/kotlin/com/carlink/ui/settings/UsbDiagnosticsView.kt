package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.carlink.logging.DiagnosticJournal

/** Card in Settings -> Logs that opens the on-screen USB diagnostics journal. */
@Composable
fun UsbDiagnosticsCard(onOpen: () -> Unit) {
    val lines by DiagnosticJournal.lines.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "USB diagnostics", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "Adapter, USB permission and key events since the app started (${lines.size} lines). " +
                            "Works at any log level. Open it and photograph the screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.padding(horizontal = 8.dp))
            Button(onClick = onOpen) { Text("Show") }
        }
    }
}

/**
 * Full-screen, live-updating view of [DiagnosticJournal], oldest first, auto-scrolled to the newest
 * line. Monospace and dense so a few photos capture a whole connect sequence on the ultrawide panel.
 */
@Composable
fun UsbDiagnosticsDialog(onDismiss: () -> Unit) {
    val lines by DiagnosticJournal.lines.collectAsState()
    val listState = rememberLazyListState()
    val colorScheme = MaterialTheme.colorScheme

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "USB diagnostics - ${lines.size} lines (newest at bottom)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { DiagnosticJournal.clear() }) { Text("Clear") }
                    FilledTonalButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                if (lines.isEmpty()) {
                    Text(
                        text = "Nothing yet. Plug in / unplug the adapter or run the Shortcut, then come back.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                color = lineColor(line),
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun lineColor(line: String) =
    when {
        "[USB_BUS]" in line || "Failed" in line || "declined" in line -> MaterialTheme.colorScheme.error
        "[STATUS]" in line -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
