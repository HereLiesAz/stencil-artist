package com.hereliesaz.stencilartist.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.stencilartist.core.paint.BarcodeScan
import com.hereliesaz.stencilartist.core.paint.GtinCheck
import com.hereliesaz.stencilartist.core.paint.ScanDebouncer
import kotlin.time.TimeSource

/**
 * Why the camera scanner could not work. Each case says what the user can do about it in [message],
 * which is written to be shown as it is.
 */
sealed interface ScannerError {
    /** One line for the user: what went wrong and what to do. */
    val message: String

    /** The user (or the browser's or the phone's settings) said no to the camera. Nothing is scanned. */
    data object PermissionDenied : ScannerError {
        override val message = "Camera blocked: allow camera access in your browser or app settings, then scan again."
    }

    /** There is no camera, or another app has it. */
    data object NoCamera : ScannerError {
        override val message = "No camera found, or another app is using it."
    }

    /** The code that reads barcodes could not be loaded or started; [cause] is what the platform said. */
    data class DecoderUnavailable(val cause: String) : ScannerError {
        override val message = "The barcode reader couldn't start" + (if (cause.isBlank()) "." else " ($cause).") + " Check your connection and try again."
    }

    /** This browser or device cannot scan at all, for [reason] (a camera needs a secure page, for one). */
    data class Unsupported(val reason: String) : ScannerError {
        override val message = reason
    }
}

/**
 * The live camera with a thin reticle drawn over it, decoding frames while [active] and nothing otherwise.
 *
 * [onGtin] gets each valid retail barcode (EAN-13, EAN-8, UPC-A or UPC-E) the camera sees, as
 * [BarcodeScan.accept] reports it: check digit verified, UPC-A as its EAN-13 form. The same number seen
 * again within about two seconds is not reported again. [onError] is told when the camera or the reader
 * cannot be used; the view is then blank until [active] goes false and true again. The camera is released
 * as soon as [active] is false or the view leaves the composition.
 *
 * On the web the picture is a DOM video element laid over the page's canvas (Compose draws to a single
 * canvas, which cannot show a video), so nothing drawn by Compose appears above it; the reticle is part
 * of the view for that reason. It needs a secure page (https or localhost). On Android it is CameraX
 * with ML Kit, and asks for the camera permission itself.
 */
@Composable
expect fun ScannerView(active: Boolean, onGtin: (String) -> Unit, onError: (ScannerError) -> Unit, modifier: Modifier = Modifier)

/**
 * The step between a decoder and the app: checks a raw read with [BarcodeScan.accept], applies the two
 * second debounce and calls [onGtin]. Each platform's scanner feeds every decoded string to [offer].
 */
internal class ScanSink(private val onGtin: (String) -> Unit) {
    private val debouncer = ScanDebouncer()
    private val origin = TimeSource.Monotonic.markNow()

    /** Forgets the last number, so a can scanned a moment ago can be scanned again. Called when a scan starts. */
    fun reset() = debouncer.reset()

    /** Handles one decoded [raw] string; [upcE] says the decoder called the symbol UPC-E. */
    fun offer(raw: String, upcE: Boolean = false) {
        val gtin = BarcodeScan.accept(raw, upcE) ?: return
        if (debouncer.accept(gtin, origin.elapsedNow().inWholeMilliseconds)) onGtin(gtin)
    }
}

/** What typing a barcode by hand came to: the number to use, or the line to show instead. */
internal sealed interface ManualEntry {
    data class Valid(val gtin: String) : ManualEntry
    data class Rejected(val message: String) : ManualEntry

    companion object {
        /** The right number is 8, 12, 13 or 14 digits; spaces and hyphens people type between groups are ignored. */
        fun parse(text: String): ManualEntry {
            val digits = text.filter { it != ' ' && it != '-' }
            val shapeOk = digits.isNotEmpty() && digits.all { it in '0'..'9' } &&
                (digits.length == 8 || digits.length == 12 || digits.length == 13 || digits.length == 14)
            if (!shapeOk) return Rejected("Enter the 8, 12 or 13 digits printed under the bars.")
            if (!GtinCheck.isValid(digits)) return Rejected("That barcode's check digit is wrong.")
            return Valid(BarcodeScan.accept(digits) ?: digits)
        }
    }
}

/**
 * A self-contained "scan a can" panel: a button that opens the camera in a square, rounded view, one
 * line saying what is happening, a Stop / Scan again control, the last number read, and a field to
 * type the barcode instead. It keeps its own state; the caller only hears of numbers.
 *
 * [onGtin] gets every valid barcode, scanned or typed. [onClose] is called when the user closes the
 * panel, after the camera has been released. [resultNote], if the caller has something to say about the
 * last number ("Added Montana Black", "Not in the catalogue"), shows under it.
 */
@Composable
fun ScannerPanel(onGtin: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, resultNote: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val subdued = scheme.onSurfaceVariant
    val gtinSink by rememberUpdatedState(onGtin)
    var open by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<ScannerError?>(null) }

    fun received(gtin: String) {
        last = gtin
        gtinSink(gtin)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SCAN", style = MaterialTheme.typography.labelSmall, color = subdued)
        if (!open) {
            OutlinedButton(
                onClick = { open = true; scanning = true; error = null },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan a can") }
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(scheme.surface).border(1.dp, scheme.outline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (scanning) {
                    ScannerView(
                        active = true,
                        onGtin = ::received,
                        onError = { error = it; scanning = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(if (error == null) "Camera off" else "Camera unavailable", style = MaterialTheme.typography.bodySmall, color = subdued)
                }
            }
            Text(
                when {
                    error != null -> error!!.message
                    scanning && last == null -> "Point at the barcode."
                    scanning -> "Read $last. Point at the next can, or stop."
                    else -> "Stopped."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) scheme.onSurface else subdued,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { if (scanning) scanning = false else { error = null; scanning = true } },
                    modifier = Modifier.weight(1f),
                ) { Text(if (scanning) "Stop" else "Scan again") }
                OutlinedButton(
                    onClick = { scanning = false; open = false; error = null; onClose() },
                    modifier = Modifier.weight(1f),
                ) { Text("Close") }
            }
        }
        last?.let {
            Text("Last scanned: $it", style = MaterialTheme.typography.bodyMedium)
            if (resultNote != null) Text(resultNote, style = MaterialTheme.typography.bodySmall, color = subdued)
        }
        ManualBarcodeField(onNumber = ::received)
    }
}

/** "Or type the barcode": a field and an Add button that submit through [ManualEntry.parse] and say why a number was refused. */
@Composable
private fun ManualBarcodeField(onNumber: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    fun submit() {
        when (val entry = ManualEntry.parse(text)) {
            is ManualEntry.Valid -> { problem = null; text = ""; onNumber(entry.gtin) }
            is ManualEntry.Rejected -> problem = entry.message
        }
    }

    Column {
        Text("OR TYPE THE BARCODE", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; problem = null },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.weight(1f).height(40.dp)
                    .clip(RoundedCornerShape(6.dp)).background(scheme.surfaceVariant)
                    .border(1.dp, scheme.outline, RoundedCornerShape(6.dp))
                    .semantics { contentDescription = "Barcode number" },
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("Digits under the bars", color = scheme.onSurfaceVariant, fontSize = 14.sp)
                        inner()
                    }
                },
            )
            OutlinedButton(onClick = ::submit, modifier = Modifier.width(72.dp)) { Text("Add") }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}
