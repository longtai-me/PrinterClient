package me.longtai.core.ui.hardware

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import me.longtai.core.common.scan.ScanEvent
import me.longtai.core.common.scan.ScanSource
import me.longtai.core.hardware.Hardware
import me.longtai.core.hardware.nfc.NfcTag
import me.longtai.core.hardware.printer.SimulatedPrintout
import me.longtai.core.hardware.scanner.ScannerManager
import me.longtai.core.ui.theme.MonospaceStyle

val LocalHardware = staticCompositionLocalOf<Hardware> { error("Hardware not provided; wrap content in HardwareHost") }

/** Provides [Hardware] to the composition and shows simulated printouts. */
@Composable
fun HardwareHost(hardware: Hardware, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHardware provides hardware) {
        content()
        SimulatedPrintDialog(hardware)
    }
}

/**
 * Delivers scans to [onScan] only while the calling screen is resumed, so a screen in
 * the back stack never consumes scans meant for the visible one.
 */
@Composable
fun ScanEffect(onScan: (ScanEvent) -> Unit) {
    val hardware = LocalHardware.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(onScan)
    LaunchedEffect(lifecycleOwner, hardware) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hardware.scanner.events.collect { current(it) }
        }
    }
}

/** Delivers NFC tags to [onTag] only while the calling screen is resumed. */
@Composable
fun NfcEffect(onTag: (NfcTag) -> Unit) {
    val hardware = LocalHardware.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(onTag)
    LaunchedEffect(lifecycleOwner, hardware) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hardware.nfc.tags.collect { current(it) }
        }
    }
}

/** Returns a function that opens the camera scanner app; results arrive through [ScanEffect]. */
@Composable
fun rememberCameraScanner(title: String = "掃描條碼"): () -> Unit {
    val hardware = LocalHardware.current
    val launcher = rememberLauncherForActivityResult(ScannerManager.CameraScanContract()) { result ->
        if (result != null) hardware.scanner.emit(result, ScanSource.CAMERA)
    }
    return { launcher.launch(title) }
}

@Composable
private fun SimulatedPrintDialog(hardware: Hardware) {
    var printout by remember { mutableStateOf<SimulatedPrintout?>(null) }
    LaunchedEffect(hardware) {
        hardware.simulatedPrinter.output.collect { printout = it }
    }
    val current = printout ?: return
    AlertDialog(
        onDismissRequest = { printout = null },
        confirmButton = { TextButton(onClick = { printout = null }) { Text("關閉") } },
        title = { Text(if (current.isLabel) "模擬列印（標籤）" else "模擬列印" + if (current.copies > 1) " ×${current.copies}" else "") },
        text = { PrintPreview(current.lines) },
    )
}

/** Monospace rendering of a printout, scrollable in both directions. */
@Composable
fun PrintPreview(lines: List<String>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = 480.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            lines.forEach { Text(it.ifEmpty { " " }, style = MonospaceStyle, softWrap = false) }
        }
    }
}
