package com.kjwindham.audiocool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.kjwindham.audiocool.BuildConfig
import com.kjwindham.audiocool.transcribe.SpeechModel

/** The app's version, and the credits and notices its speech components ask for. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AudioCool ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Notes linked to the moment in the recording, and transcripts made on your phone.")
                Credit("Speech model: NVIDIA ${SpeechModel.NAME}. ${SpeechModel.LICENSE_NOTICE}.", "View the licence", SpeechModel.LICENSE_URL)
                Credit("Speech engine: sherpa-onnx (Apache-2.0).", "sherpa-onnx on GitHub", "https://github.com/k2-fsa/sherpa-onnx")
                Credit("Speech detection: Silero VAD (MIT).", "Silero VAD on GitHub", "https://github.com/snakers4/silero-vad")
                Credit("Noise filtering for speech detection: GTCRN (MIT).", "GTCRN on GitHub", "https://github.com/Xiaobin-Rong/gtcrn")
                Credit("Source code and updates.", "AudioCool on GitHub", "https://github.com/ZENinjaneer/audiocool")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Credit(text: String, linkLabel: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Column {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { uriHandler.openUri(url) }, contentPadding = PaddingValues(0.dp)) { Text(linkLabel) }
    }
}
