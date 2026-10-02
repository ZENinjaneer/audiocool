package com.kjwindham.audiocool.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.audio.Dictation
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.Photos
import com.kjwindham.audiocool.slides.AutoSlides
import com.kjwindham.audiocool.slides.SlideWatcher
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.formatTime
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

private val Ground = Color(0xFF0E0F14)

/** Hands-free slides looks at the camera this often, and dims the screen after this long untouched. */
private const val FRAME_MS = 250L
private const val DIM_AFTER_MS = 30_000L
private val Faint = Color(0x26FFFFFF)
private val Muted = Color(0xFFB9BCCB)

/**
 * The lock-screen capture screen: a camera for the slides, and buttons to speak a note, mark the
 * moment, pause and stop. Everything lands in the recording in progress. With Auto slides on, the
 * camera saves each new slide by itself (hands-free, with the phone propped up facing the screen).
 * [autoRequests] counts the times hands-free slides were asked for.
 */
@Composable
fun CaptureScreen(onDone: () -> Unit, onOpenApp: (String?) -> Unit, autoRequests: Int = 0) {
    val context = LocalContext.current
    val rec by RecorderController.state.collectAsStateWithLifecycle()
    val dictation by Dictation.state.collectAsStateWithLifecycle()
    val transcription by TranscriptionController.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // The session to open afterwards, remembered past the recording's end.
    var sessionId by remember { mutableStateOf(rec.sessionId) }
    if (rec.sessionId != null && rec.sessionId != sessionId) sessionId = rec.sessionId
    var message by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf(false) }
    var cameraAllowed by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraAllowed = ok
        if (!ok) message = "Allow the camera in AudioCool to take photos from here."
    }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val recording = rec.status != RecorderController.Status.IDLE
    val paused = rec.status == RecorderController.Status.PAUSED

    // Hands-free slides.
    var auto by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoRequests) { if (autoRequests > 0) auto = true }
    val slides by AutoSlides.state.collectAsStateWithLifecycle()
    val watching = auto && cameraAllowed && recording && !paused
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    // Watching before the screen was turned (it starts over): the camera may point differently now.
    var watchedBefore by rememberSaveable { mutableStateOf(false) }
    val restored = remember { watchedBefore }
    var startedHere by remember { mutableStateOf(false) }
    // Dimmed while it watches and nobody touches it, to save the battery through a long talk.
    var touchedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var dimmed by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }
    LaunchedEffect(flash) {
        if (flash) {
            delay(120)
            flash = false
        }
    }
    LaunchedEffect(dictation.problem) {
        dictation.problem?.let {
            Dictation.clearProblem()
            message = it
        }
    }
    LaunchedEffect(rec.markedAtMs) { rec.markedAtMs?.let { message = "★ Marked at ${formatTime(it)}" } }
    DisposableEffect(Unit) { onDispose { Dictation.stop(context) } }
    LaunchedEffect(watching, touchedAt) {
        dimmed = false
        if (watching) {
            delay(DIM_AFTER_MS)
            dimmed = true
        }
    }
    val activity = LocalActivity.current
    DisposableEffect(dimmed) {
        fun brightness(level: Float) {
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level } }
        }
        brightness(if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }

    /** Photographs the slide [decision] is about: a new one, or a better picture of one saved. */
    fun takeSlide(sessionId: String, decision: SlideWatcher.Decision) {
        val st = RecorderController.state.value
        if (st.sessionId != sessionId || st.status != RecorderController.Status.RECORDING) {
            AutoSlides.notTaken(sessionId, decision)
            return
        }
        // A new slide lands where it went up, a moment before it was still long enough to save.
        val at = (decision as? SlideWatcher.Decision.New)?.let { (RecorderController.currentOffsetMs() - (SystemClock.elapsedRealtime() - it.shownAt)).coerceAtLeast(0L) }
        val file = File(File(context.cacheDir, "capture").apply { mkdirs() }, "slide-${System.currentTimeMillis()}.jpg")
        // No buzz: it could nudge the phone off the screen.
        flash = true
        Log.i("CaptureScreen", "Auto slides: $decision")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    AutoSlides.save(context, sessionId, st.recId, decision, file, at) { ok ->
                        if (ok) message = if (at != null) "Slide added at ${formatTime(at)}" else "Slide retaken with what's new on it"
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CaptureScreen", "Couldn't photograph the slide", exception)
                    file.delete()
                    AutoSlides.notTaken(sessionId, decision)
                }
            },
        )
    }

    DisposableEffect(watching, rec.sessionId) {
        val sessionId = rec.sessionId
        if (!watching || sessionId == null) return@DisposableEffect onDispose {}
        AutoSlides.start(sessionId, reframed = restored && !startedHere)
        startedHere = true
        watchedBefore = true
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        var lastAt = 0L
        analysis.setAnalyzer(worker) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt >= FRAME_MS) {
                    lastAt = now
                    val plane = image.planes[0]
                    val grid = SlideWatcher.grid(plane.buffer, image.width, image.height, plane.rowStride)
                    AutoSlides.frame(grid, now)?.let { decision -> main.execute { takeSlide(sessionId, decision) } }
                }
            } finally {
                image.close()
            }
        }
        onDispose {
            analysis.clearAnalyzer()
            worker.shutdown()
            AutoSlides.stop()
        }
    }

    fun shoot() {
        val st = RecorderController.state.value
        val session = st.sessionId ?: return
        val recId = st.recId ?: return
        val at = RecorderController.currentOffsetMs()
        val file = File(File(context.cacheDir, "capture").apply { mkdirs() }, "lock-${System.currentTimeMillis()}.jpg")
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        flash = true
        Log.i("CaptureScreen", "Taking a photo at $at ms of $recId")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Photos.addTaken(context, session, file, recId, at) { ok ->
                        message = if (ok) "Photo added at ${formatTime(at)}" else "Couldn't save that photo."
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CaptureScreen", "Couldn't take the photo", exception)
                    file.delete()
                    message = "Couldn't take the photo."
                }
            },
        )
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    touchedAt = SystemClock.elapsedRealtime()
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(Ground).systemBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (recording && !paused) RecordRed else Muted))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !recording -> "Recording saved"
                        paused -> "Paused · ${formatTime(rec.elapsedMs)}"
                        else -> "Recording · ${formatTime(rec.elapsedMs)}"
                    },
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDone) { Text("Done", color = Color.White) }
            }
            Spacer(Modifier.height(12.dp))
            if (recording && cameraAllowed) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    val colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Color.White,
                        activeContentColor = Ground,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = Color.White,
                        activeBorderColor = Faint,
                        inactiveBorderColor = Faint,
                    )
                    SegmentedButton(selected = !auto, onClick = { auto = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), colors = colors) { Text("Photo") }
                    SegmentedButton(selected = auto, onClick = { auto = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), colors = colors) { Text("Auto slides") }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                if (cameraAllowed) {
                    CameraView(capture, if (auto && recording) analysis else null, Modifier.fillMaxSize(), onZoom = { AutoSlides.reframed(SystemClock.elapsedRealtime()) }) { message = it }
                    if (flash) Box(Modifier.fillMaxSize().background(Color(0xB3FFFFFF)))
                    if (recording && auto) {
                        AutoSlidesStatus(
                            state = slides,
                            paused = paused,
                            // This screen redraws as the recording's clock ticks, so "how long ago" keeps up.
                            now = System.currentTimeMillis(),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        )
                    } else if (recording) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 20.dp)
                                .size(76.dp)
                                .border(4.dp, Color.White, CircleShape)
                                .padding(7.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .semantics { contentDescription = "Take a photo" }
                                .pointerInput(Unit) { detectTapGestures { shoot() } },
                        )
                    }
                } else {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(AppIcons.Camera, contentDescription = null, tint = Muted, modifier = Modifier.size(40.dp))
                        Text("Photograph the slides from here, without unlocking.", color = Muted, textAlign = TextAlign.Center)
                        Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    }
                }
                message?.let {
                    Text(
                        it,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (recording) {
                val listening = dictation.listening
                Text(
                    when {
                        listening -> "Listening… let go to add your note"
                        dictation.transcribing -> "Adding your spoken note…"
                        !transcription.modelReady -> "Spoken notes need the speech model; download it in the app."
                        else -> "Hold the mic to speak a note"
                    },
                    color = Muted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                if (listening) LinearProgressIndicator(progress = { dictation.level }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                    Labeled("Speak") {
                        if (transcription.modelReady) {
                            DictateButton(
                                listening = listening,
                                onStart = {
                                    val st = RecorderController.state.value
                                    st.sessionId?.let { Dictation.start(context, it, st.recId, RecorderController.currentOffsetMs()) }
                                },
                                onStop = { Dictation.stop(context) },
                                size = 60.dp,
                                idleBackground = Faint,
                                idleTint = Color.White,
                            )
                        } else {
                            Box(Modifier.size(60.dp).clip(CircleShape).background(Faint), contentAlignment = Alignment.Center) {
                                Icon(AppIcons.Mic, contentDescription = "Speak a note (needs the speech model)", tint = Muted)
                            }
                        }
                    }
                    Labeled("Mark") {
                        RoundButton(onClick = { RecorderController.mark() }, description = "Mark this moment") {
                            Text("★", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Labeled(if (paused) "Resume" else "Pause") {
                        RoundButton(
                            onClick = { if (paused) RecorderController.resume() else RecorderController.pause() },
                            description = if (paused) "Resume recording" else "Pause recording",
                        ) {
                            Icon(if (paused) AppIcons.Mic else AppIcons.Pause, contentDescription = null, tint = Color.White)
                        }
                    }
                    Labeled("Stop") {
                        RoundButton(onClick = { RecorderController.stop() }, description = "Stop recording", background = RecordRed) {
                            Icon(AppIcons.Stop, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            } else {
                Text("It's in AudioCool with your photos and notes.", color = Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onOpenApp(sessionId) }, modifier = Modifier.weight(1f)) { Text("Open AudioCool", color = Color.White) }
                    Button(
                        onClick = { RecorderController.startNewSession() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
                    ) { Text("New recording") }
                }
            }
        }
    if (dimmed) {
        // Dark (an OLED screen draws nothing for black), and the first tap only brightens it.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { touchedAt = SystemClock.elapsedRealtime() } }
                .semantics { contentDescription = "Dimmed to save the battery. Tap to brighten." },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(autoSlidesLine(slides, paused = false, now = System.currentTimeMillis()), color = Muted)
                Text("Tap to brighten", color = Muted.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    }
}

/** Over the camera with Auto slides on: what it's doing, and how to set it up until the first slide. */
@Composable
private fun AutoSlidesStatus(state: AutoSlides.State, paused: Boolean, now: Long, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (paused) Muted else RecordRed))
            Spacer(Modifier.width(8.dp))
            Text(autoSlidesLine(state, paused, now), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.slides == 0 && !paused) {
            Text(
                "Prop the phone up facing the screen, zoomed in so the slides fill the picture. Each new slide is saved once it holds still; repeats are skipped.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** "Watching the screen · 12 slides · just now". */
private fun autoSlidesLine(state: AutoSlides.State, paused: Boolean, now: Long): String {
    if (paused) return "Paused · slides are watched for again when you resume"
    if (state.slides == 0) return "Watching for slides"
    val count = if (state.slides == 1) "1 slide" else "${state.slides} slides"
    val minutes = state.lastSavedAt?.let { (now - it) / 60_000 } ?: 0
    val latest = when {
        minutes < 1 -> "just now"
        else -> "latest $minutes min ago"
    }
    return "Watching the screen · $count · $latest"
}

@Composable
private fun Labeled(label: String, button: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        button()
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, description: String, background: Color = Faint, content: @Composable () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(60.dp).semantics { contentDescription = description },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = background, contentColor = Color.White),
    ) { content() }
}

/**
 * The back camera's live picture: pinch to zoom (handy for slides across a hall), tap to focus. With
 * [analysis], its frames go there too (hands-free slides).
 */
@Composable
private fun CameraView(capture: ImageCapture, analysis: ImageAnalysis?, modifier: Modifier, onZoom: () -> Unit = {}, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    DisposableEffect(lifecycleOwner, analysis) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    val cases = listOfNotNull(preview, capture, analysis).toTypedArray()
                    camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *cases)
                } catch (e: Exception) {
                    onError("The camera isn't available right now.")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { runCatching { if (future.isDone) future.get().unbindAll() } }
    }
    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Gestures on a layer above the camera view, which would otherwise take the touches.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        camera?.let { c ->
                            val ratio = c.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            c.cameraControl.setZoomRatio(ratio * zoom)
                            if (zoom != 1f) onZoom()
                        }
                    }
                }
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        camera?.let { c ->
                            val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                            c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        }
                    }
                },
        )
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
