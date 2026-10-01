package com.kjwindham.audiocool.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.NoteFocus
import com.kjwindham.audiocool.data.highlightedNoteId
import com.kjwindham.audiocool.data.newId
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.data.playbackStartFor
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.Prefs
import com.kjwindham.audiocool.util.formatTime
import com.kjwindham.audiocool.util.isEnterKeystroke
import com.kjwindham.audiocool.util.noteLabel
import com.kjwindham.audiocool.util.removeEnter
import com.kjwindham.audiocool.util.shareSession
import kotlinx.coroutines.launch

/** A moment in one of the session's recordings. */
private data class Stamp(val recId: String, val offsetMs: Long)

private val Speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(session: Session, onBack: () -> Unit, initialTab: Int = 0) {
    val context = LocalContext.current
    val rec by RecorderController.state.collectAsStateWithLifecycle()
    val player by PlayerController.state.collectAsStateWithLifecycle()
    val transcription by TranscriptionController.state.collectAsStateWithLifecycle()
    val live by LiveTranscription.state.collectAsStateWithLifecycle()
    val desktop by DesktopSync.pairing.collectAsStateWithLifecycle()
    val desktopProgress by DesktopSync.progress.collectAsStateWithLifecycle()
    val prefs = remember { Prefs(context) }
    var leadInSec by remember { mutableIntStateOf(prefs.leadInSeconds) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val recordingHere = rec.status != RecorderController.Status.IDLE && rec.sessionId == session.id
    val recordingElsewhere = rec.status != RecorderController.Status.IDLE && rec.sessionId != session.id
    val playable = session.recordings.filterNot { recordingHere && it.id == rec.recId }
    val playerHere = player.sessionId == session.id
    var selectedRecId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    val selected = playable.firstOrNull { it.id == selectedRecId }
        ?: playable.firstOrNull { playerHere && it.id == player.recId }
        ?: playable.lastOrNull()
    val canStamp = recordingHere || (playerHere && player.engaged)

    var draft by rememberSaveable(session.id) { mutableStateOf("") }
    // Where the note being typed will link to, kept as two saveable values so it survives rotation.
    var draftStampRec by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var draftStampMs by rememberSaveable(session.id) { mutableLongStateOf(0L) }
    var showMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickLeadIn by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var tab by rememberSaveable(session.id) { mutableIntStateOf(initialTab) }
    // Recordings waiting on the user to OK the one-time model download.
    var awaitingDownload by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(rec.error) {
        rec.error?.let {
            snackbar.showSnackbar(it)
            RecorderController.clearError()
        }
    }
    LaunchedEffect(player.error) {
        player.error?.let {
            snackbar.showSnackbar(it)
            PlayerController.clearError()
        }
    }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    /** Where a note written right now should point: the live recording, else the playback position. */
    fun currentStamp(): Stamp? {
        val r = RecorderController.state.value
        if (r.status != RecorderController.Status.IDLE && r.sessionId == session.id && r.recId != null) {
            return Stamp(r.recId, RecorderController.currentOffsetMs())
        }
        val p = PlayerController.state.value
        if (p.sessionId == session.id && p.recId != null && p.engaged) {
            return Stamp(p.recId, PlayerController.positionMs())
        }
        return null
    }

    fun addNote(text: String, stamp: Stamp?) {
        val t = text.trim()
        if (t.isEmpty()) return
        SessionRepository.addNote(session.id, Note(newId(), t, System.currentTimeMillis(), stamp?.recId, stamp?.offsetMs))
    }

    fun draftStamp(): Stamp? = draftStampRec?.let { Stamp(it, draftStampMs) }

    fun setDraftStamp(stamp: Stamp?) {
        draftStampRec = stamp?.recId
        draftStampMs = stamp?.offsetMs ?: 0L
    }

    fun submitDraft() {
        addNote(draft, draftStamp() ?: currentStamp())
        draft = ""
        setDraftStamp(null)
    }

    fun onDraftChange(new: String) {
        // A note links to the moment you started typing it, not when you finished.
        if (draft.isBlank() && new.isNotBlank()) setDraftStamp(currentStamp())
        if (isEnterKeystroke(draft, new)) {
            draft = removeEnter(draft, new)
            submitDraft()
        } else {
            draft = new
            if (new.isBlank()) setDraftStamp(null)
        }
    }

    fun playNote(note: Note) {
        val r = session.recording(note.recId) ?: return
        val offset = note.offsetMs ?: return
        if (rec.status != RecorderController.Status.IDLE) {
            toast("Stop recording to play back")
            return
        }
        selectedRecId = r.id
        val start = playbackStartFor(note, session.notes, leadInSec * 1000L)
        PlayerController.playFrom(session.id, r, start, NoteFocus(note.id, r.id, fromMs = start, untilMs = offset))
    }

    fun startRecording() {
        PlayerController.release()
        RecorderController.start(session.id)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            toast("AudioCool needs microphone access to record. Allow it in Settings › Apps › AudioCool › Permissions.")
        }
    }

    fun onRecord() {
        if (recordingElsewhere) {
            toast("Already recording in another session")
            return
        }
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        // Ask for the notification permission once; recording works without it.
        if (Manifest.permission.RECORD_AUDIO in missing || (missing.isNotEmpty() && !prefs.askedNotificationPermission)) {
            prefs.askedNotificationPermission = true
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startRecording()
        }
    }

    fun ensureLoaded(): Boolean = selected != null && PlayerController.load(session.id, selected)

    fun transcribe(ids: List<String>) {
        if (ids.isEmpty()) return
        if (transcription.modelReady) TranscriptionController.enqueue(session.id, ids) else awaitingDownload = ids
    }

    fun playSegment(recording: Recording, segment: TranscriptSegment) {
        if (rec.status != RecorderController.Status.IDLE) {
            toast("Stop recording to play back")
            return
        }
        selectedRecId = recording.id
        // A moment early, so the first word isn't clipped.
        PlayerController.playFrom(session.id, recording, (segment.startMs - 300).coerceAtLeast(0L))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Text(
                        session.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                actions = {
                    IconButton(onClick = { shareSession(context, session) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share notes and audio")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                showMenu = false
                                renaming = true
                            })
                            if (desktop != null) {
                                DropdownMenuItem(
                                    text = { Text("Transcribe on desktop") },
                                    enabled = !recordingHere && session.recordings.any { it.durationMs > 0 },
                                    onClick = {
                                        showMenu = false
                                        tab = 1
                                        DesktopSync.send(session.id)
                                    },
                                )
                            }
                            DropdownMenuItem(text = { Text("Tap a note: start ${leadInSec}s before") }, onClick = {
                                showMenu = false
                                pickLeadIn = true
                            })
                            DropdownMenuItem(text = { Text("Delete session") }, onClick = {
                                showMenu = false
                                confirmDelete = true
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar, Modifier.navigationBarsPadding().imePadding().padding(bottom = 72.dp))
        },
        // The composer at the bottom handles the navigation bar and keyboard insets itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    when {
                        recordingHere -> RecordingControls(
                            rec,
                            liveLine = session.recording(rec.recId)?.transcript?.lastOrNull()?.text,
                        )
                        recordingElsewhere -> Text(
                            "Recording in another session. Stop it there to record or play here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        selected == null -> StartRecordingPrompt(onRecord = ::onRecord)
                        else -> PlayerControls(
                            session = session,
                            player = player,
                            playable = playable,
                            selected = selected,
                            noteOffsets = session.notes.filter { it.recId == selected.id }.mapNotNull { it.offsetMs },
                            onSelect = { selectedRecId = it },
                            onRecord = ::onRecord,
                            ensureLoaded = ::ensureLoaded,
                        )
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Notes") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Transcript") })
            }
            if (tab == 0) {
                NotesList(
                    session = session,
                    player = player,
                    recordingHere = recordingHere,
                    modifier = Modifier.weight(1f),
                    onTap = { note -> if (note.offsetMs != null) playNote(note) else editing = note },
                    onEdit = { editing = it },
                )
            } else {
                TranscriptPane(
                    session = session,
                    player = player,
                    transcription = transcription,
                    live = live,
                    desktopProgress = desktopProgress[session.id],
                    recordingHere = recordingHere,
                    onTranscribe = { transcribe(session.recordings.filter { it.durationMs > 0 && it.transcript == null }.map { it.id }) },
                    onRetranscribe = ::transcribe,
                    onPlay = ::playSegment,
                    modifier = Modifier.weight(1f),
                )
            }
            Composer(
                draft = draft,
                stampLabel = draftStamp()?.let { noteLabel(session, Note("", "", 0, it.recId, it.offsetMs)) },
                canStamp = canStamp,
                onDraftChange = ::onDraftChange,
                onSend = ::submitDraft,
                onMark = { addNote("★ Marked", currentStamp()) },
            )
        }
    }

    awaitingDownload?.let { ids ->
        ModelDownloadDialog(
            onConfirm = {
                awaitingDownload = null
                TranscriptionController.enqueue(session.id, ids)
            },
            onDismiss = { awaitingDownload = null },
        )
    }
    if (renaming) {
        TextInputDialog(
            title = "Rename session",
            initial = session.title,
            onConfirm = {
                SessionRepository.rename(session.id, it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
    if (pickLeadIn) {
        LeadInDialog(
            current = leadInSec,
            onPick = {
                leadInSec = it
                prefs.leadInSeconds = it
            },
            onDismiss = { pickLeadIn = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete session?",
            message = "“${session.title}” and its recordings will be permanently deleted.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                deleteSession(session.id)
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    editing?.let { note ->
        TextInputDialog(
            title = "Edit note",
            initial = note.text,
            singleLine = false,
            onConfirm = {
                SessionRepository.editNote(session.id, note.id, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun RecordingControls(rec: RecorderController.State, liveLine: String?) {
    val recording = rec.status == RecorderController.Status.RECORDING
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(12.dp).clip(CircleShape)
                .background(if (recording) RecordRed else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (recording) "REC" else "PAUSED",
            style = MaterialTheme.typography.labelLarge,
            color = if (recording) RecordRed else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(formatTime(rec.elapsedMs), style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"))
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { if (recording) RecorderController.pause() else RecorderController.resume() },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(if (recording) AppIcons.Pause else AppIcons.Mic, contentDescription = if (recording) "Pause" else "Resume")
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { RecorderController.stop() },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = RecordRed, contentColor = Color.White),
        ) {
            Icon(AppIcons.Stop, contentDescription = "Stop recording")
        }
    }
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(progress = { rec.level }, modifier = Modifier.fillMaxWidth())
    // The latest live-transcribed phrase, so you can see it working without switching tabs.
    if (liveLine != null) {
        Text(
            liveLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StartRecordingPrompt(onRecord: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onRecord,
            colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Icon(AppIcons.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start recording")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Notes you type while recording are linked to that moment in the audio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControls(
    session: Session,
    player: PlayerController.State,
    playable: List<Recording>,
    selected: Recording,
    noteOffsets: List<Long>,
    onSelect: (String) -> Unit,
    onRecord: () -> Unit,
    ensureLoaded: () -> Boolean,
) {
    val loaded = player.sessionId == session.id && player.recId == selected.id
    val position = if (loaded) player.positionMs else 0L
    val duration = (if (loaded && player.durationMs > 0) player.durationMs else selected.durationMs).coerceAtLeast(1L)
    var dragMs by remember(selected.id) { mutableStateOf<Float?>(null) }

    if (playable.size > 1) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            playable.forEach { r ->
                FilterChip(
                    selected = r.id == selected.id,
                    onClick = { onSelect(r.id) },
                    label = { Text("Rec ${session.recordingNumber(r.id)} · ${formatTime(r.durationMs)}") },
                )
            }
        }
    }
    Slider(
        value = (dragMs ?: position.toFloat()).coerceIn(0f, duration.toFloat()),
        onValueChange = { dragMs = it },
        onValueChangeFinished = {
            dragMs?.let { if (ensureLoaded()) PlayerController.seekTo(it.toLong()) }
            dragMs = null
        },
        valueRange = 0f..duration.toFloat(),
        track = { state -> NoteMarkerTrack(state, noteOffsets.map { (it.toFloat() / duration).coerceIn(0f, 1f) }) },
    )
    Row(Modifier.fillMaxWidth()) {
        val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
        Text(formatTime(dragMs?.toLong() ?: position), style = timeStyle)
        Spacer(Modifier.weight(1f))
        Text(formatTime(duration), style = timeStyle)
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = { PlayerController.setSpeed(Speeds[(Speeds.indexOf(player.speed) + 1) % Speeds.size]) }) {
            Text(speedLabel(player.speed))
        }
        SkipButton(back = true) { if (ensureLoaded()) PlayerController.skipBy(-10_000) }
        FilledIconButton(onClick = { if (ensureLoaded()) PlayerController.toggle() }, modifier = Modifier.size(56.dp)) {
            val playing = loaded && player.isPlaying
            Icon(
                if (playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(32.dp),
            )
        }
        SkipButton(back = false) { if (ensureLoaded()) PlayerController.skipBy(10_000) }
        IconButton(onClick = onRecord) {
            Icon(AppIcons.Mic, contentDescription = "Record more", tint = RecordRed)
        }
    }
}

/** The standard slider track with a small pill wherever a note was taken. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteMarkerTrack(state: SliderState, markers: List<Float>) {
    val colors = SliderDefaults.colors()
    SliderDefaults.Track(
        sliderState = state,
        colors = colors,
        modifier = Modifier.drawWithContent {
            drawContent()
            val range = state.valueRange.endInclusive - state.valueRange.start
            val played = if (range > 0f) (state.value - state.valueRange.start) / range else 0f
            val pill = Size(3.dp.toPx(), 10.dp.toPx())
            // Keep end-of-recording notes inside the track rather than half off it.
            val inset = 4.dp.toPx()
            for (fraction in markers) {
                val x = (size.width * fraction).coerceIn(inset, size.width - inset)
                drawRoundRect(
                    color = if (fraction <= played) colors.activeTickColor else colors.inactiveTickColor,
                    topLeft = Offset(x - pill.width / 2, (size.height - pill.height) / 2),
                    size = pill,
                    cornerRadius = CornerRadius(pill.width / 2),
                )
            }
        },
    )
}

private fun speedLabel(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "×"

@Composable
private fun SkipButton(back: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                AppIcons.Replay,
                contentDescription = if (back) "Back 10 seconds" else "Forward 10 seconds",
                modifier = Modifier.size(30.dp).graphicsLayer { if (!back) scaleX = -1f },
            )
            Text("10", fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun NotesList(
    session: Session,
    player: PlayerController.State,
    recordingHere: Boolean,
    modifier: Modifier,
    onTap: (Note) -> Unit,
    onEdit: (Note) -> Unit,
) {
    val notes = remember(session.notes, session.recordings) { session.orderedNotes() }
    val playerHere = player.sessionId == session.id && player.recId != null
    val currentId = if (playerHere && player.engaged) {
        highlightedNoteId(notes, player.recId, player.positionMs, player.focus)
    } else {
        null
    }
    val listState = rememberLazyListState()

    // Keep the note you just added in view.
    val newestId = notes.maxByOrNull { it.createdAt }?.id
    LaunchedEffect(newestId) {
        val i = notes.indexOfFirst { it.id == newestId }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    // Follow along during playback.
    LaunchedEffect(currentId) {
        if (!player.isPlaying || currentId == null) return@LaunchedEffect
        val i = notes.indexOfFirst { it.id == currentId }
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == i }
        val fullyVisible = item != null && item.offset >= layout.viewportStartOffset &&
            item.offset + item.size <= layout.viewportEndOffset
        if (i >= 0 && !fullyVisible) listState.animateScrollToItem((i - 1).coerceAtLeast(0))
    }

    if (notes.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                if (recordingHere) "Type below. Each note is linked to this moment in the recording." else "No notes yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(notes, key = { it.id }) { note ->
            val upcoming = player.isPlaying && playerHere && note.recId == player.recId &&
                note.id != currentId && (note.offsetMs ?: 0L) > player.positionMs
            NoteRow(
                note = note,
                label = noteLabel(session, note),
                highlighted = note.id == currentId,
                dimmed = upcoming,
                onTap = { onTap(note) },
                onEdit = { onEdit(note) },
                onDelete = { SessionRepository.deleteNote(session.id, note.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: Note,
    label: String?,
    highlighted: Boolean,
    dimmed: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .semantics { selected = highlighted }
                .combinedClickable(onClick = onTap, onLongClick = { menu = true })
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .alpha(if (dimmed) 0.45f else 1f),
            verticalAlignment = Alignment.Top,
        ) {
            if (label != null) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        Modifier.padding(start = 4.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(note.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 1.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    stampLabel: String?,
    canStamp: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMark: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 4.dp, vertical = 6.dp)) {
            if (stampLabel != null) {
                Text(
                    "Links to $stampLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 60.dp, bottom = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMark, enabled = canStamp) {
                    Icon(Icons.Filled.Star, contentDescription = "Mark this moment")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (canStamp) "Type a note, Enter adds it" else "Type a note") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(24.dp),
                )
                IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add note")
                }
            }
        }
    }
}
