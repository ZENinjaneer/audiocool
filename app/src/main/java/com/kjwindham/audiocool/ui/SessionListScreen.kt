package com.kjwindham.audiocool.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.kjwindham.audiocool.transcribe.SpeechModel
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.AppLog
import com.kjwindham.audiocool.util.Prefs
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
    var showLockScreen by remember { mutableStateOf(false) }
    var showSummaries by remember { mutableStateOf(false) }
    var confirmModelDownload by remember { mutableStateOf(false) }
    var confirmLiveDownload by remember { mutableStateOf(false) }
    var autoTranscribe by remember { mutableStateOf(TranscriptionController.autoTranscribe) }
    val context = LocalContext.current
    var crashedLastTime by remember { mutableStateOf(AppLog.crashedLastTime(context)) }
    val prefs = remember { Prefs(context) }
    var offerDismissed by remember { mutableStateOf(prefs.speechModelOfferDismissed) }
    var galleryView by remember { mutableStateOf(prefs.galleryView) }
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
                        IconButton(onClick = {
                            galleryView = !galleryView
                            prefs.galleryView = galleryView
                        }) {
                            Icon(
                                if (galleryView) AppIcons.List else AppIcons.Gallery,
                                contentDescription = if (galleryView) "Show as a list" else "Show as a gallery",
                            )
                        }
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
                                        if (autoTranscribe && !transcription.modelReady) {
                                            showMenu = false
                                            confirmLiveDownload = true
                                        }
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
                                    text = { Text("Lock screen controls") },
                                    onClick = {
                                        showMenu = false
                                        showLockScreen = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Summaries") },
                                    onClick = {
                                        showMenu = false
                                        showSummaries = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Share the app's log") },
                                    onClick = {
                                        showMenu = false
                                        AppLog.share(context)
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
        // One grid for both views: a single column of cards, or tiles with the title under the thumbnail.
        val wide: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
        LazyVerticalGrid(
            columns = if (galleryView) GridCells.Adaptive(minSize = 150.dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(if (galleryView) 12.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (searching) {
                item(key = "search-hint", span = wide) {
                    Text(
                        "Search your notes, the text on your photos, and everything that was said.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            val activeId = rec.sessionId
            if (rec.status != RecorderController.Status.IDLE && activeId != null) {
                item(key = "recording", span = wide) {
                    RecordingBanner(
                        title = sessions.firstOrNull { it.id == activeId }?.title.orEmpty(),
                        elapsedMs = rec.elapsedMs,
                        paused = rec.status == RecorderController.Status.PAUSED,
                        onClick = { onOpen(activeId) },
                    )
                }
            }
            // Transcription is on by default but needs a one-time download; offer it up front.
            val downloading = transcription.phase == TranscriptionController.Phase.DOWNLOADING_MODEL
            if (!searching && !transcription.modelReady && autoTranscribe && (downloading || !offerDismissed || transcription.error != null)) {
                item(key = "speech-model", span = wide) {
                    SpeechModelCard(
                        transcription,
                        onDownload = TranscriptionController::downloadModel,
                        onDismiss = {
                            TranscriptionController.clearError()
                            prefs.speechModelOfferDismissed = true
                            offerDismissed = true
                        },
                    )
                }
            }
            if (sorted.isEmpty()) {
                item(key = "empty", span = wide) { EmptyState() }
            }
            items(sorted, key = { it.id }) { s ->
                if (galleryView) {
                    SessionTile(s, onClick = { onOpen(s.id) }, onRename = { renaming = s }, onDelete = { deleting = s })
                } else {
                    SessionCard(s, onClick = { onOpen(s.id) }, onRename = { renaming = s }, onDelete = { deleting = s })
                }
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
    if (showDesktop) DesktopDialog(onDismiss = { showDesktop = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showLockScreen) LockScreenDialog(onDismiss = { showLockScreen = false })
    if (showSummaries) SummariesDialog(onDismiss = { showSummaries = false })
    if (crashedLastTime) {
        AlertDialog(
            onDismissRequest = {
                AppLog.dismissCrash(context)
                crashedLastTime = false
            },
            title = { Text("AudioCool closed unexpectedly") },
            text = { Text("Sharing the app's log (what it was doing, and the error) helps get it fixed. It doesn't include your notes or recordings.") },
            confirmButton = {
                TextButton(onClick = {
                    crashedLastTime = false
                    AppLog.share(context)
                }) { Text("Share the log") }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppLog.dismissCrash(context)
                    crashedLastTime = false
                }) { Text("Not now") }
            },
        )
    }
    if (showBackup) {
        BackupDialog(
            onDismiss = { showBackup = false },
            onMessage = { scope.launch { snackbar.showSnackbar(it) } },
        )
    }
}

/** Offers the one-time speech model download, then shows its progress. */
@Composable
private fun SpeechModelCard(transcription: TranscriptionController.State, onDownload: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (transcription.phase == TranscriptionController.Phase.DOWNLOADING_MODEL) {
                Text("Downloading the speech model… ${(transcription.progress * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { transcription.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Once it's done, recordings are transcribed as you record, even one that's already going.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Set up transcription", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Live transcripts, searching what was said, and spoken notes need the speech model " +
                        "(${SpeechModel.totalBytes / 1_000_000} MB, downloaded once; use Wi-Fi). It runs on your phone; nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                transcription.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text("${SpeechModel.LICENSE_NOTICE}.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Not now") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDownload) { Text(if (transcription.error != null) "Try again" else "Download") }
                }
            }
        }
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
    var picked by rememberSaveable(stateSaver = KindsSaver) { mutableStateOf(emptySet<HitKind>()) }
    val shown = remember(hits, picked) { if (picked.isEmpty()) hits else hits.filter { it.kind in picked } }
    // Sessions whose names match come first, then what's in each session.
    val titles = remember(shown) { shown.filter { it.kind == HitKind.TITLE } }
    val groups = remember(shown) { shown.filter { it.kind != HitKind.TITLE }.groupBy { it.sessionId } }
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
    Column(modifier) {
        SearchFilters(hits, HitKind.entries, picked) { picked = picked.toggled(it) }
        if (shown.isEmpty()) {
            NothingPicked(picked, query) { picked = emptySet() }
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "count") {
            Text(
                if (shown.size == 1) "1 result" else "${shown.size} results",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        items(titles, key = { hitKey(it, 0) }) { hit ->
            byId[hit.sessionId]?.let { TitleRow(it, hit, onOpenHit) }
        }
        groups.forEach { (sessionId, sessionHits) ->
            val session = byId[sessionId] ?: return@forEach
            item(key = "session:$sessionId") {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    session.thumbnailNote()?.photo?.let { photo ->
                        PhotoThumbnail(
                            SessionRepository.photoFile(session.id, photo),
                            sizePx = 240,
                            modifier = Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(session.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            itemsIndexed(sessionHits, key = { i, hit -> hitKey(hit, i) }) { _, hit ->
                HitRow(session, hit, onOpenHit)
            }
        }
        }
    }
}

/** What a search can be narrowed to, as its filters call it. */
internal fun kindLabel(kind: HitKind) = when (kind) {
    HitKind.TITLE -> "Sessions"
    HitKind.SUMMARY -> "Summaries"
    HitKind.NOTE -> "Notes"
    HitKind.PHOTO -> "Slides"
    HitKind.SPEECH -> "What was said"
}

internal fun Set<HitKind>.toggled(kind: HitKind) = if (kind in this) this - kind else this + kind

/** Keeps the picked filters when Android recreates the screen. */
internal val KindsSaver = Saver<Set<HitKind>, String>(
    save = { kinds -> kinds.joinToString(",") { it.name } },
    restore = { saved -> saved.split(',').filter { it.isNotEmpty() }.map { HitKind.valueOf(it) }.toSet() },
)

/** A key for [hit] in a list; [index] tells apart summary lines that happen to read the same. */
internal fun hitKey(hit: SearchHit, index: Int) = when (hit.kind) {
    HitKind.TITLE -> "title:${hit.sessionId}"
    HitKind.SUMMARY -> "summary:${hit.sessionId}:${hit.chapterKey ?: "session"}:$index"
    HitKind.NOTE -> "note:${hit.noteId}"
    HitKind.PHOTO -> "photo:${hit.noteId}"
    HitKind.SPEECH -> "said:${hit.recId}:${hit.atMs}"
}

/** Narrows a search to some kinds of result (none picked shows them all); each says how many it found. */
@Composable
internal fun SearchFilters(hits: List<SearchHit>, kinds: List<HitKind>, picked: Set<HitKind>, onToggle: (HitKind) -> Unit) {
    val counts = remember(hits) { hits.groupingBy { it.kind }.eachCount() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (kind in kinds) {
            val count = counts[kind] ?: 0
            FilterChip(
                selected = kind in picked,
                onClick = { onToggle(kind) },
                label = { Text(if (count > 0) "${kindLabel(kind)} · $count" else kindLabel(kind)) },
            )
        }
    }
}

/** The filters leave nothing: say so, with a way back to everything. */
@Composable
internal fun NothingPicked(picked: Set<HitKind>, query: String, onClear: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(
            "No ${picked.joinToString(" or ") { kindLabel(it).lowercase() }} match “${query.trim()}”.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClear) { Text("Show everything") }
    }
}

/** A session whose name matches: its thumbnail, the name with the words lit up, and when it was. */
@Composable
private fun TitleRow(session: Session, hit: SearchHit, onOpenHit: (SearchHit) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpenHit(hit) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val photo = session.thumbnailNote()?.photo
        if (photo != null) {
            PhotoThumbnail(
                SessionRepository.photoFile(session.id, photo),
                sizePx = 240,
                modifier = Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Box(
                Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(highlighted(hit.text, hit.matches), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(summary(session), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun HitRow(session: Session, hit: SearchHit, onOpenHit: (SearchHit) -> Unit) {
    val label = hit.atMs?.let { timeLabel(session, hit.recId, it) }
    Row(
        Modifier.fillMaxWidth().clickable { onOpenHit(hit) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (hit.kind == HitKind.SUMMARY || hit.kind == HitKind.TITLE) {
            Text(
                "✦",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.width(18.dp).semantics { contentDescription = "Summary" },
            )
        } else {
            Icon(
                when (hit.kind) {
                    HitKind.SPEECH -> AppIcons.Mic
                    HitKind.PHOTO -> AppIcons.Image
                    else -> Icons.Filled.Edit
                },
                contentDescription = when (hit.kind) {
                    HitKind.SPEECH -> "Said"
                    HitKind.PHOTO -> "On a photo"
                    else -> "Note"
                },
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    val thumbnail = s.thumbnailNote()?.photo
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardDefaults.shape)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Row(Modifier.padding(if (thumbnail != null) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (thumbnail != null) {
                    PhotoThumbnail(
                        SessionRepository.photoFile(s.id, thumbnail),
                        sizePx = 320,
                        modifier = Modifier.size(width = 96.dp, height = 72.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        summary(s),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SessionMenu(menu, onDismiss = { menu = false }, onRename, onDelete)
    }
}

/** A session in the gallery view: its thumbnail (or a placeholder), with the title underneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTile(s: Session, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val thumbnail = s.thumbnailNote()?.photo
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(bottom = 4.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    PhotoThumbnail(SessionRepository.photoFile(s.id, thumbnail), sizePx = 480, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(AppIcons.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
                }
                if (s.recordings.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    ) {
                        Text(
                            formatTime(s.totalDurationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                s.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
            )
            if (s.title != defaultSessionTitle(s.createdAt)) {
                Text(
                    formatDate(s.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        SessionMenu(menu, onDismiss = { menu = false }, onRename, onDelete)
    }
}

@Composable
private fun SessionMenu(expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Box {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    onDismiss()
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
    val photos = s.notes.count { it.photo != null }
    val notes = s.notes.size - photos
    when {
        notes == 1 -> parts += "1 note"
        notes > 1 -> parts += "$notes notes"
        photos == 0 -> parts += "no notes"
    }
    if (photos > 0) parts += if (photos == 1) "1 photo" else "$photos photos"
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
