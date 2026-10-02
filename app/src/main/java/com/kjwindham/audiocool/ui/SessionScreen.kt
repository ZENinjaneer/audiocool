package com.kjwindham.audiocool.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.audio.Dictation
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.audio.Waveform
import com.kjwindham.audiocool.data.MARK_TEXT
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.NoteFocus
import com.kjwindham.audiocool.data.Photos
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TimelineMode
import com.kjwindham.audiocool.data.TimelineRow
import com.kjwindham.audiocool.data.highlightedNoteId
import com.kjwindham.audiocool.data.newId
import com.kjwindham.audiocool.data.noteKey
import com.kjwindham.audiocool.data.playbackStartFor
import com.kjwindham.audiocool.data.playingSpeechKey
import com.kjwindham.audiocool.data.timelineRows
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.search.HitKind
import com.kjwindham.audiocool.search.SearchHit
import com.kjwindham.audiocool.search.searchSession
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.Prefs
import com.kjwindham.audiocool.util.defaultSessionTitle
import com.kjwindham.audiocool.util.formatDate
import com.kjwindham.audiocool.util.formatTime
import com.kjwindham.audiocool.util.isEnterKeystroke
import com.kjwindham.audiocool.util.noteLabel
import com.kjwindham.audiocool.util.removeEnter
import com.kjwindham.audiocool.util.shareSession
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A moment in one of the session's recordings. */
private data class Stamp(val recId: String, val offsetMs: Long)

private const val TAG = "SessionScreen"

private const val NO_CAMERA_MESSAGE =
    "To take photos, allow AudioCool to use the camera (in Settings, under Permissions). Meanwhile, ⋮ › Add photos from gallery works."

/**
 * A session: everything in it on one timeline (notes, photos and what was said), the recording or
 * playback controls, and the note box. [showSpeech] opens it on the full view whatever was picked
 * last, as when coming from something said that a search found.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(session: Session, onBack: () -> Unit, showSpeech: Boolean = false) {
    val context = LocalContext.current
    val rec by RecorderController.state.collectAsStateWithLifecycle()
    val player by PlayerController.state.collectAsStateWithLifecycle()
    val transcription by TranscriptionController.state.collectAsStateWithLifecycle()
    val live by LiveTranscription.state.collectAsStateWithLifecycle()
    val desktop by DesktopSync.pairing.collectAsStateWithLifecycle()
    val desktopProgress by DesktopSync.progress.collectAsStateWithLifecycle()
    val dictation by Dictation.state.collectAsStateWithLifecycle()
    val waveforms by Waveform.levels.collectAsStateWithLifecycle()
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
    val canDictate = transcription.modelReady && !recordingElsewhere &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    var draft by rememberSaveable(session.id) { mutableStateOf("") }
    // Where the note being typed will link to, kept as two saveable values so it survives rotation.
    var draftStampRec by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var draftStampMs by rememberSaveable(session.id) { mutableLongStateOf(0L) }
    var showMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickLeadIn by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }
    // Recordings waiting on the user to OK the one-time model download.
    var awaitingDownload by remember { mutableStateOf<List<String>?>(null) }
    var confirmLiveDownload by remember { mutableStateOf(false) }
    // The photo note being looked at full screen.
    var viewing by remember { mutableStateOf<String?>(null) }
    // The photo the camera app is taking, and the moment it links to (kept if Android restarts the app meanwhile).
    var capturePath by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var captureRec by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var captureMs by rememberSaveable(session.id) { mutableLongStateOf(-1L) }

    // The timeline: how much of what was said it shows, searching it, and the scrubber's preview.
    var mode by rememberSaveable(session.id) {
        mutableStateOf(if (showSpeech) TimelineMode.EVERYTHING else runCatching { TimelineMode.valueOf(prefs.timelineMode) }.getOrDefault(TimelineMode.EVERYTHING))
    }
    var searching by rememberSaveable(session.id) { mutableStateOf(false) }
    var query by rememberSaveable(session.id) { mutableStateOf("") }
    var peekId by remember { mutableStateOf<String?>(null) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var jumpTo by remember { mutableStateOf<String?>(null) }

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
    LaunchedEffect(dictation.problem) {
        dictation.problem?.let {
            Dictation.clearProblem()
            snackbar.showSnackbar(it)
        }
    }
    // Leaving the screen ends a spoken note in progress.
    DisposableEffect(Unit) { onDispose { Dictation.stop(context) } }
    LaunchedEffect(selected?.id) { selected?.let { Waveform.request(session.id, it) } }
    // A preview is a quick look; it goes away by itself.
    LaunchedEffect(peekId) {
        if (peekId != null) {
            delay(8_000)
            peekId = null
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

    fun canPlay(): Boolean {
        if (rec.status == RecorderController.Status.IDLE) return true
        toast("Stop recording to play back")
        return false
    }

    fun playNote(note: Note) {
        val r = session.recording(note.recId) ?: return
        val offset = note.offsetMs ?: return
        if (!canPlay()) return
        peekId = null
        selectedRecId = r.id
        val start = playbackStartFor(note, session.notes, leadInSec * 1000L)
        PlayerController.playFrom(session.id, r, start, NoteFocus(note.id, r.id, fromMs = start, untilMs = offset))
    }

    fun playFrom(recId: String?, atMs: Long) {
        val r = session.recording(recId) ?: return
        if (!canPlay()) return
        peekId = null
        selectedRecId = r.id
        // A moment early, so the first word isn't clipped.
        PlayerController.playFrom(session.id, r, (atMs - 300).coerceAtLeast(0L))
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

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        Log.i(TAG, "Camera app returned: taken=$taken, file=$capturePath (${capturePath?.let { File(it).length() }} bytes)")
        val file = capturePath?.let(::File) ?: return@rememberLauncherForActivityResult
        capturePath = null
        if (taken && file.length() > 0) {
            Photos.addTaken(context, session.id, file, captureRec, captureMs.takeIf { it >= 0 }) { ok -> if (!ok) toast("Couldn't read that photo.") }
        } else {
            file.delete()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            Photos.addPicked(context, session.id, uris) { failed -> if (failed > 0) toast("Couldn't read $failed of those pictures.") }
        }
    }

    /** Says the camera isn't allowed, with a way to the app's settings, since Android stops asking after a second no. */
    fun cameraNotAllowed() {
        scope.launch {
            if (snackbar.showSnackbar(NO_CAMERA_MESSAGE, actionLabel = "Settings", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        }
    }

    /** Opens the camera app for a photo linked to [stamp]. */
    fun openCamera(stamp: Stamp?) {
        val (file, uri) = Photos.newCapture(context)
        capturePath = file.path
        captureRec = stamp?.recId
        captureMs = stamp?.offsetMs ?: -1L
        Log.i(TAG, "Opening the camera app for a photo at ${stamp?.offsetMs} ms of ${stamp?.recId}")
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            capturePath = null
            file.delete()
            toast("There's no camera app to take a photo with.")
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open the camera app", e)
            capturePath = null
            file.delete()
            cameraNotAllowed()
        }
    }

    // The moment the camera button was tapped, kept while Android asks about the camera.
    var pendingPhotoRec by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var pendingPhotoMs by rememberSaveable(session.id) { mutableLongStateOf(-1L) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val stamp = pendingPhotoRec?.let { Stamp(it, pendingPhotoMs) }
        pendingPhotoRec = null
        if (granted) openCamera(stamp) else cameraNotAllowed()
    }

    /**
     * Takes a photo with the camera app; it links to this moment (when the button was tapped). Because
     * the app declares the camera permission (for the lock screen's camera), Android only lets it open
     * the camera app once that's granted, so ask first.
     */
    fun takePhoto() {
        val stamp = currentStamp()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera(stamp)
        } else {
            pendingPhotoRec = stamp?.recId
            pendingPhotoMs = stamp?.offsetMs ?: -1L
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun pickMode(m: TimelineMode) {
        mode = m
        prefs.timelineMode = m.name
    }

    // What the timeline shows, and where playback (or the scrubber's finger) is on it.
    val rows = remember(session, mode) { timelineRows(session, mode) }
    val hasSpeech = session.recordings.any { !it.transcript.isNullOrEmpty() }
    val ordered = remember(session.notes, session.recordings) { session.orderedNotes() }
    val engagedHere = selected != null && playerHere && player.recId == selected.id && player.engaged
    val position = scrubMs ?: if (engagedHere) player.positionMs else null
    val focusId = position?.let { highlightedNoteId(ordered, selected?.id, it, if (scrubMs == null) player.focus else null) }
    val playingKey = position?.let { playingSpeechKey(rows, selected?.id, it) }
    val followKey = run {
        val focusKey = focusId?.let(::noteKey)
        if (mode != TimelineMode.EVERYTHING) return@run focusKey
        // Keep what's playing in view, except that a photo opens up just above the paragraph after
        // it: then follow the photo, so both show.
        val focusIndex = rows.indexOfFirst { it.key == focusKey }
        val photoFirst = focusIndex >= 0 && rows[focusIndex] is TimelineRow.Photo && rows.getOrNull(focusIndex + 1)?.key == playingKey
        if (photoFirst) focusKey else playingKey ?: focusKey
    }
    val follow = scrubMs != null || (engagedHere && player.isPlaying)
    // Keep a note you just added in view.
    val newestId = session.notes.maxByOrNull { it.createdAt }?.id
    var seenNewest by remember(session.id) { mutableStateOf(newestId) }
    LaunchedEffect(newestId) {
        if (newestId != null && newestId != seenNewest) jumpTo = noteKey(newestId)
        seenNewest = newestId
    }
    val imeVisible = WindowInsets.isImeVisible
    // Kept between playback ticks, so the scrubber's markers aren't rebuilt ten times a second.
    val markers = remember(session.notes, selected?.id) { session.notes.filter { it.recId == selected?.id && it.offsetMs != null } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column(Modifier.clickable(onClickLabel = "Rename") { renaming = true }) {
                        Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            sessionSummary(session),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = if (searching) "Close search" else "Search this session")
                    }
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
                            DropdownMenuItem(text = { Text("Add photos from gallery") }, onClick = {
                                showMenu = false
                                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            if (desktop != null) {
                                DropdownMenuItem(
                                    text = { Text("Transcribe on desktop") },
                                    enabled = !recordingHere && session.recordings.any { it.durationMs > 0 },
                                    onClick = {
                                        showMenu = false
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
            val topPanel: (@Composable () -> Unit)? = when {
                recordingHere -> {
                    {
                        RecordingControls(
                            rec,
                            liveLine = if (mode == TimelineMode.EVERYTHING) null else session.recording(rec.recId)?.transcript?.lastOrNull()?.text,
                            transcription = transcription,
                            onDownloadModel = { confirmLiveDownload = true },
                        )
                    }
                }
                recordingElsewhere -> {
                    { Text("Recording in another session. Stop it there to record or play here.", style = MaterialTheme.typography.bodyMedium) }
                }
                selected == null -> {
                    { StartRecordingPrompt(onRecord = ::onRecord) }
                }
                else -> null
            }
            topPanel?.let { panel ->
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { panel() }
                }
            }
            if (searching) {
                SearchField(query, onQueryChange = { query = it }, onClose = {
                    searching = false
                    query = ""
                })
            } else if (hasSpeech && !imeVisible) {
                ModeSwitch(mode, ::pickMode)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (searching) {
                    SessionSearch(
                        session = session,
                        query = query,
                        onOpen = { hit ->
                            val note = hit.noteId?.let { id -> session.notes.firstOrNull { it.id == id } }
                            when {
                                note != null && note.offsetMs != null && session.recording(note.recId) != null -> playNote(note)
                                note?.photo != null -> viewing = note.id
                                note != null -> editing = note
                                hit.atMs != null -> playFrom(hit.recId, hit.atMs)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TimelinePane(
                        session = session,
                        rows = rows,
                        playingKey = playingKey,
                        focusId = focusId,
                        peekId = peekId,
                        followKey = followKey,
                        follow = follow,
                        engaged = position != null,
                        canPlay = rec.status == RecorderController.Status.IDLE,
                        liveTail = recordingHere,
                        jumpTo = jumpTo,
                        onJumpDone = { jumpTo = null },
                        header = {
                            SummaryCard(session)
                            TranscriptStatus(
                                session = session,
                                transcription = transcription,
                                live = live,
                                desktopProgress = desktopProgress[session.id],
                                recordingHere = recordingHere,
                                onTranscribe = { transcribe(session.recordings.filter { it.durationMs > 0 && it.transcript == null }.map { it.id }) },
                                onRetranscribe = ::transcribe,
                            )
                        },
                        empty = when {
                            recordingHere && canDictate -> "Type below, or hold the mic and say it. Each note is linked to this moment in the recording."
                            recordingHere -> "Type below. Each note is linked to this moment in the recording."
                            session.recordings.isEmpty() -> "Record something, and your notes, photos and what was said line up here."
                            hasSpeech && mode == TimelineMode.NOTES -> "No notes yet. Everything shows what was said."
                            else -> "No notes yet."
                        },
                        onPlaySpeech = { playFrom(it.recId, it.startMs) },
                        onPlayNote = ::playNote,
                        onOpenPhoto = { viewing = it.id },
                        onEdit = { editing = it },
                        onFold = {
                            pickMode(TimelineMode.EVERYTHING)
                            jumpTo = it.firstKey
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val peekNote = peekId?.let { id -> session.notes.firstOrNull { it.id == id } }
                if (peekNote != null && selected != null) {
                    val duration = (if (playerHere && player.recId == selected.id && player.durationMs > 0) player.durationMs else selected.durationMs)
                        .coerceAtLeast(1L)
                    val cardWidth = min(300.dp, maxWidth - 24.dp)
                    val anchor = markerX((peekNote.offsetMs ?: 0L).toFloat() / duration, maxWidth)
                    val x = (anchor - cardWidth / 2).coerceIn(12.dp, maxWidth - cardWidth - 12.dp)
                    PeekCard(
                        session = session,
                        note = peekNote,
                        photoNumber = ordered.filter { it.photo != null }.indexOfFirst { it.id == peekNote.id } + 1,
                        context = peekNote.offsetMs?.let { saidAround(session.recording(peekNote.recId), it) },
                        onPlay = { playNote(peekNote) },
                        onClose = { peekId = null },
                        modifier = Modifier.align(Alignment.BottomStart).offset(x = x, y = (-10).dp).width(cardWidth),
                    )
                }
            }
            if (selected != null && !recordingHere && !recordingElsewhere && !imeVisible) {
                Scrubber(
                    session = session,
                    rec = selected,
                    playable = playable,
                    player = player,
                    levels = waveforms[selected.id],
                    markers = markers,
                    focusId = focusId,
                    peekId = peekId,
                    scrubMs = scrubMs,
                    onScrub = {
                        scrubMs = it
                        if (it != null) peekId = null
                    },
                    onSeek = { if (ensureLoaded()) PlayerController.seekTo(it) },
                    onPeek = { peekId = it },
                    onSelectRec = {
                        selectedRecId = it
                        peekId = null
                    },
                    onToggle = { if (ensureLoaded()) PlayerController.toggle() },
                    onSkip = { if (ensureLoaded()) PlayerController.skipBy(it) },
                    onSpeed = PlayerController::setSpeed,
                    onRecordMore = ::onRecord,
                )
            }
            Composer(
                draft = draft,
                stampLabel = draftStamp()?.let { noteLabel(session, Note("", "", 0, it.recId, it.offsetMs)) },
                canStamp = canStamp,
                dictation = dictation,
                canDictate = canDictate,
                onDraftChange = ::onDraftChange,
                onSend = ::submitDraft,
                onMark = { addNote(MARK_TEXT, currentStamp()) },
                onPhoto = ::takePhoto,
                onDictateStart = {
                    val stamp = currentStamp()
                    Dictation.start(context, session.id, stamp?.recId, stamp?.offsetMs)
                },
                onDictateStop = { Dictation.stop(context) },
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
    if (confirmLiveDownload) {
        ModelDownloadDialog(
            onConfirm = {
                confirmLiveDownload = false
                TranscriptionController.downloadModel()
            },
            onDismiss = { confirmLiveDownload = false },
            confirmLabel = "Download",
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
    viewing?.let { id -> session.notes.firstOrNull { it.id == id } }?.let { note ->
        PhotoViewer(
            file = SessionRepository.photoFile(session.id, note.photo!!),
            label = noteLabel(session, note),
            isThumbnail = session.thumbnailNote()?.id == note.id,
            onPlay = if (note.offsetMs != null && session.recording(note.recId) != null) {
                {
                    viewing = null
                    playNote(note)
                }
            } else {
                null
            },
            onUseAsThumbnail = { SessionRepository.setThumbnail(session.id, note.id) },
            onDismiss = { viewing = null },
        )
    }
    editing?.let { note ->
        TextInputDialog(
            title = when {
                note.photo != null -> "Caption"
                note.isMark -> "Note at this mark"
                else -> "Edit note"
            },
            initial = if (note.isMark) "" else note.text,
            singleLine = false,
            onConfirm = {
                // An empty note at a mark keeps it a mark.
                SessionRepository.editNote(session.id, note.id, if (note.isMark && it.isBlank()) MARK_TEXT else it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** "Oct 1, 2026 · 2:04 PM · 06:12 · 3 notes · 4 photos": the date only when the title isn't already it. */
fun sessionSummary(session: Session): String {
    val notes = session.notes.count { it.photo == null }
    val photos = session.notes.count { it.photo != null }
    return listOfNotNull(
        formatDate(session.createdAt).takeIf { session.title != defaultSessionTitle(session.createdAt) },
        formatTime(session.totalDurationMs).takeIf { session.totalDurationMs > 0 },
        (if (notes == 1) "1 note" else "$notes notes").takeIf { notes > 0 },
        (if (photos == 1) "1 photo" else "$photos photos").takeIf { photos > 0 },
    ).joinToString(" · ").ifEmpty { "Nothing recorded yet" }
}

/** What was being said just before and at [atMs] in [rec]: a mark's context. */
private fun saidAround(rec: Recording?, atMs: Long): String? =
    rec?.transcript.orEmpty()
        .filter { it.endMs >= atMs - 8_000 && it.startMs <= atMs + 1_000 }
        .joinToString(" ") { it.text.trim() }
        .ifBlank { null }

/** Everything, Notes + context, Notes only: how much of what was said the timeline shows. */
@Composable
private fun ModeSwitch(mode: TimelineMode, onPick: (TimelineMode) -> Unit) {
    val options = listOf(TimelineMode.EVERYTHING to "Everything", TimelineMode.CONTEXT to "Notes + context", TimelineMode.NOTES to "Notes only")
    val colors = timelineColors()
    Box(Modifier.fillMaxWidth().background(colors.page).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((value, label) in options) {
                val on = value == mode
                Box(
                    Modifier
                        .weight(1f)
                        .then(if (on) Modifier.shadow(2.dp, CircleShape) else Modifier)
                        .clip(CircleShape)
                        .background(if (on) colors.picked else Color.Transparent)
                        .semantics {
                            role = Role.Tab
                            selected = on
                        }
                        .clickable { onPick(value) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).focusRequester(focus),
        placeholder = { Text("Search notes, slides and what was said") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") } },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
    )
}

/** What in the session matches the search: notes, text on photos and things said, in timeline order. */
@Composable
private fun SessionSearch(session: Session, query: String, onOpen: (SearchHit) -> Unit, modifier: Modifier) {
    val hits = remember(session, query) { searchSession(session, query) }
    LazyColumn(modifier) {
        when {
            query.isBlank() -> item { Hint("Find notes, text on photos and what was said in this session.") }
            hits.isEmpty() -> item { Hint("Nothing here matches “${query.trim()}”.") }
            else -> {
                item {
                    Text(
                        if (hits.size == 1) "1 match" else "${hits.size} matches",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(hits, key = { hit ->
                    when (hit.kind) {
                        HitKind.NOTE -> "note:${hit.noteId}"
                        HitKind.PHOTO -> "photo:${hit.noteId}"
                        HitKind.SPEECH -> "said:${hit.recId}:${hit.atMs}"
                    }
                }) { hit -> HitRow(session, hit, onOpen) }
            }
        }
    }
}

@Composable
private fun RecordingControls(
    rec: RecorderController.State,
    liveLine: String?,
    transcription: TranscriptionController.State,
    onDownloadModel: () -> Unit,
) {
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
    // With a headset plugged in, choose which mic records the room; the other one takes spoken notes.
    rec.externalMic?.let { external ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Record with", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = !rec.usingExternalMic, onClick = { RecorderController.useExternalMic(false) }, label = { Text("Phone mic") })
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = rec.usingExternalMic,
                onClick = { RecorderController.useExternalMic(true) },
                label = { Text(external.replaceFirstChar { it.uppercase() }) },
            )
        }
    }
    // Live transcription (and spoken notes) need the speech model; say so here, where it's missed.
    if (!transcription.modelReady && TranscriptionController.autoTranscribe) {
        if (transcription.phase == TranscriptionController.Phase.DOWNLOADING_MODEL) {
            Text(
                "Downloading the speech model… ${(transcription.progress * 100).toInt()}%. " +
                    "This recording will be transcribed as soon as it's done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(progress = { transcription.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "Live transcription needs the speech model, downloaded once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDownloadModel) { Text("Download") }
            }
        }
    }
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

@Composable
private fun Composer(
    draft: String,
    stampLabel: String?,
    canStamp: Boolean,
    dictation: Dictation.State,
    canDictate: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMark: () -> Unit,
    onPhoto: () -> Unit,
    onDictateStart: () -> Unit,
    onDictateStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 4.dp, vertical = 6.dp)) {
            val status = when {
                dictation.listening -> "Listening on the ${dictation.mic}…"
                dictation.transcribing -> "Adding your spoken note…"
                stampLabel != null -> "Links to $stampLabel"
                else -> null
            }
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 60.dp, end = 16.dp, bottom = 2.dp)) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (dictation.listening) {
                        Spacer(Modifier.width(8.dp))
                        LinearProgressIndicator(progress = { dictation.level }, modifier = Modifier.weight(1f))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMark, enabled = canStamp) {
                    Icon(Icons.Filled.Star, contentDescription = "Mark this moment")
                }
                if (canDictate) DictateButton(dictation.listening, onDictateStart, onDictateStop)
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (canStamp) "Type a note, Enter adds it" else "Type a note") },
                    // A photo of a slide is a note too; like a messaging app, the camera is in the box until you type.
                    trailingIcon = if (draft.isEmpty()) {
                        { IconButton(onClick = onPhoto) { Icon(AppIcons.Camera, contentDescription = "Take a photo") } }
                    } else {
                        null
                    },
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

