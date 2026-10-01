package com.kjwindham.audiocool.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.search.HitKind
import com.kjwindham.audiocool.search.SearchHit
import com.kjwindham.audiocool.search.searchAll
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.defaultSessionTitle
import com.kjwindham.audiocool.util.formatDate
import com.kjwindham.audiocool.util.formatTime
import com.kjwindham.audiocool.util.timeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessions: List<Session>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onCreate: () -> Unit,
) {
    val rec by RecorderController.state.collectAsStateWithLifecycle()
    val transcription by TranscriptionController.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var showMenu by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showDesktop by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmModelDownload by remember { mutableStateOf(false) }
    var autoTranscribe by remember { mutableStateOf(TranscriptionController.autoTranscribe) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sorted = remember(sessions) { sessions.sortedByDescending { it.updatedAt } }
    val untranscribed = sessions.sumOf { s ->
        s.recordings.count { it.durationMs > 0 && it.transcript == null && !transcription.isPending(s.id, it.id) }
    }

    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    // The query [hits] are for; until a new search finishes, the previous results stay up.
    var hitsFor by remember { mutableStateOf("") }
    LaunchedEffect(query, sessions) {
        if (query.isBlank()) {
            hits = emptyList()
            hitsFor = ""
            return@LaunchedEffect
        }
        delay(150) // wait for a pause in typing
        hits = withContext(Dispatchers.Default) { searchAll(sessions, query) }
        hitsFor = query
    }

    fun closeSearch() {
        searching = false
        onQueryChange("")
    }

    fun transcribeAll() {
        for (s in sessions) {
            val ids = s.recordings.filter { it.durationMs > 0 && it.transcript == null }.map { it.id }
            if (ids.isNotEmpty()) TranscriptionController.enqueue(s.id, ids)
        }
    }

    BackHandler(enabled = searching) { closeSearch() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = ::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
                    }
                },
                title = { if (searching) SearchBox(query, onQueryChange) else Text("AudioCool") },
                actions = {
                    if (searching) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                        }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                if (untranscribed > 0) {
                                    DropdownMenuItem(
                                        text = { Text("Transcribe all recordings ($untranscribed)") },
                                        onClick = {
                                            showMenu = false
                                            if (transcription.modelReady) transcribeAll() else confirmModelDownload = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Transcribe while recording") },
                                    trailingIcon = { if (autoTranscribe) Icon(Icons.Filled.Check, contentDescription = "On") },
                                    onClick = {
                                        autoTranscribe = !autoTranscribe
                                        TranscriptionController.autoTranscribe = autoTranscribe
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Desktop transcription") },
                                    onClick = {
                                        showMenu = false
                                        showDesktop = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Backup & restore") },
                                    onClick = {
                                        showMenu = false
                                        showBackup = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("About") },
                                    onClick = {
                                        showMenu = false
                                        showAbout = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!searching) {
                ExtendedFloatingActionButton(
                    onClick = onCreate,
                    // Material3 hides the FAB's text from accessibility, so the icon carries the label.
                    icon = { Icon(Icons.Filled.Add, contentDescription = "New session") },
                    text = { Text("New session") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (searching && query.isNotBlank()) {
            SearchResults(hits, searched = hitsFor == query, sessions, query, Modifier.fillMaxSize().padding(padding), onOpenHit)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (searching) {
                item(key = "search-hint") {
                    Text(
                        "Search your notes and everything that was said.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            val activeId = rec.sessionId
            if (rec.status != RecorderController.Status.IDLE && activeId != null) {
                item(key = "recording") {
                    RecordingBanner(
                        title = sessions.firstOrNull { it.id == activeId }?.title.orEmpty(),
                        elapsedMs = rec.elapsedMs,
                        paused = rec.status == RecorderController.Status.PAUSED,
                        onClick = { onOpen(activeId) },
                    )
                }
            }
            if (sorted.isEmpty()) {
                item(key = "empty") { EmptyState() }
            }
            items(sorted, key = { it.id }) { s ->
                SessionCard(s, onClick = { onOpen(s.id) }, onRename = { renaming = s }, onDelete = { deleting = s })
            }
        }
    }

    renaming?.let { s ->
        TextInputDialog(
            title = "Rename session",
            initial = s.title,
            onConfirm = {
                SessionRepository.rename(s.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Delete session?",
            message = "“${s.title}” and its recordings will be deleted from the app.",
            confirmLabel = "Delete",
            onConfirm = {
                deleteSession(s.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    if (confirmModelDownload) {
        ModelDownloadDialog(
            onConfirm = {
                confirmModelDownload = false
                transcribeAll()
            },
            onDismiss = { confirmModelDownload = false },
        )
    }
    if (showDesktop) DesktopDialog(onDismiss = { showDesktop = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showBackup) {
        BackupDialog(
            onDismiss = { showBackup = false },
            onMessage = { scope.launch { snackbar.showSnackbar(it) } },
        )
    }
}

@Composable
private fun SearchBox(query: String, onQueryChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        placeholder = { Text("Search notes and what was said") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SearchResults(
    hits: List<SearchHit>,
    searched: Boolean,
    sessions: List<Session>,
    query: String,
    modifier: Modifier,
    onOpenHit: (SearchHit) -> Unit,
) {
    val byId = remember(sessions) { sessions.associateBy { it.id } }
    val groups = remember(hits) { hits.groupBy { it.sessionId } }
    if (hits.isEmpty()) {
        if (!searched) return // still searching
        Box(modifier.padding(24.dp)) {
            Text(
                "Nothing matches “${query.trim()}”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "count") {
            Text(
                if (hits.size == 1) "1 result" else "${hits.size} results",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        groups.forEach { (sessionId, sessionHits) ->
            val session = byId[sessionId] ?: return@forEach
            item(key = "session:$sessionId") {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(sessionHits, key = { if (it.kind == HitKind.NOTE) "note:${it.noteId}" else "said:${it.recId}:${it.atMs}" }) { hit ->
                HitRow(session, hit, onOpenHit)
            }
        }
    }
}

@Composable
private fun HitRow(session: Session, hit: SearchHit, onOpenHit: (SearchHit) -> Unit) {
    val said = hit.kind == HitKind.SPEECH
    val label = hit.atMs?.let { timeLabel(session, hit.recId, it) }
    Row(
        Modifier.fillMaxWidth().clickable { onOpenHit(hit) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (said) AppIcons.Mic else Icons.Filled.Edit,
            contentDescription = if (said) "Said" else "Note",
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(highlighted(hit.text, hit.matches), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(s: Session, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardDefaults.shape)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    summary(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onRename()
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

private fun summary(s: Session): String {
    val parts = mutableListOf<String>()
    // A session still named after its start time doesn't need the date twice.
    if (s.title != defaultSessionTitle(s.createdAt)) parts += formatDate(s.createdAt)
    if (s.recordings.isNotEmpty()) parts += "${formatTime(s.totalDurationMs)} audio"
    parts += when (s.notes.size) {
        0 -> "no notes"
        1 -> "1 note"
        else -> "${s.notes.size} notes"
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingBanner(title: String, elapsedMs: Long, paused: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(RecordRed))
            Spacer(Modifier.width(12.dp))
            Text(
                "${if (paused) "Paused" else "Recording"} · ${formatTime(elapsedMs)} · $title",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No sessions yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Start a session, hit record, and type notes as you listen. Each note is linked to that " +
                "moment in the audio. Tap it later to hear what was being said.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
