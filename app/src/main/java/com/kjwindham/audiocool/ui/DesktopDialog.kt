package com.kjwindham.audiocool.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.desktop.PairingInfo
import com.kjwindham.audiocool.desktop.parsePairing
import kotlinx.coroutines.launch

/** Pair with AudioCool Desktop by scanning its QR code (or typing its address and code). */
@Composable
fun DesktopDialog(onDismiss: () -> Unit) {
    val pairing by DesktopSync.pairing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun connect(info: PairingInfo?) {
        if (info == null) {
            message = "That isn't an AudioCool Desktop pairing code."
            return
        }
        busy = true
        message = null
        scope.launch {
            val result = DesktopSync.pair(info)
            busy = false
            message = result.fold({ "Connected to ${it.name}." }, { "Couldn't connect: ${it.message ?: "no answer"}. Is it on the same Wi-Fi?" })
        }
    }

    val paired = pairing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Desktop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paired != null) {
                    Text(
                        "Connected to ${paired.name} (${paired.url}). In a session, choose ⋮ › Transcribe on desktop " +
                            "to get a transcript from its bigger models.",
                    )
                } else {
                    Text(
                        "Transcribe with your computer's better hardware: run AudioCool Desktop on a PC on the same " +
                            "Wi-Fi, open its Pair page, and scan the code.",
                    )
                    Button(onClick = { scan(context) { connect(parsePairing(it)) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Scan pairing code")
                    }
                    Text("Or type what the Pair page shows:", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Address, e.g. 192.168.1.20") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Pairing code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { connect(parsePairing(address, code)) },
                        enabled = !busy && address.isNotBlank() && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Connect") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (paired != null) TextButton(onClick = { DesktopSync.unpair() }) { Text("Disconnect") }
        },
    )
}

/** Google's built-in code scanner: no camera permission needed. */
private fun scan(context: Context, onResult: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan().addOnSuccessListener { barcode -> barcode.rawValue?.let(onResult) }
}
