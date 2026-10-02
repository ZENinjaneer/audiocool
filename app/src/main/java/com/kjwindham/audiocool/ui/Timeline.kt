package com.kjwindham.audiocool.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TimelineRow
import com.kjwindham.audiocool.data.noteKey
import com.kjwindham.audiocool.util.noteLabel
import com.kjwindham.audiocool.util.timeLabel

/** The colours of each kind of thing on a timeline. Fixed rather than from the theme, so a kind always looks the same. */
@Immutable
data class KindColors(val accent: Color, val tint: Color, val dot: Color)

@Immutable
data class TimelineColors(
    val note: KindColors,
    val spoken: KindColors,
    val mark: KindColors,
    val photoLabel: Color,
    val photoRing: Color,
    /** Behind the timeline, and behind the paragraph playing. */
    val page: Color,
    val raised: Color,
    /** The view picked in the Everything / Notes + context / Notes only switch. */
    val picked: Color,
) {
    fun of(note: Note): KindColors = when {
        note.isMark -> mark
        note.spoken -> spoken
        else -> this.note
    }
}

@Composable
fun timelineColors(): TimelineColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(scheme, dark) {
        if (dark) {
            TimelineColors(
                note = KindColors(Color(0xFFAFC4FF), Color(0xFF1F2B4F), Color(0xFF8AA6FF)),
                spoken = KindColors(Color(0xFFCDBDFF), Color(0xFF2C2452), Color(0xFFB39DFF)),
                mark = KindColors(Color(0xFFFFB68A), Color(0xFF4A2A18), Color(0xFFFF9A5C)),
                photoLabel = Color(0xFF6EE0D0),
                photoRing = Color(0xFF2DD4BF),
                page = scheme.surfaceContainerLow,
                raised = scheme.surfaceContainerHighest,
                picked = scheme.secondaryContainer,
            )
        } else {
            TimelineColors(
                note = KindColors(Color(0xFF2F55C7), Color(0xFFE7EDFF), Color(0xFF2F55C7)),
                spoken = KindColors(Color(0xFF6741D9), Color(0xFFEFEAFF), Color(0xFF6741D9)),
                mark = KindColors(Color(0xFFB4430A), Color(0xFFFFEFE2), Color(0xFFD9600F)),
                photoLabel = Color(0xFF0B6B63),
                photoRing = Color(0xFF2DD4BF),
                page = scheme.surfaceContainerLow,
                raised = scheme.surfaceContainerLowest,
                picked = scheme.surfaceContainerLowest,
            )
        }
    }
}

/** How big a photo is drawn: big while it's the moment playing, small while another is, in between when nothing plays. */
enum class PhotoSize { FOCUSED, RESTING, COLLAPSED }

/** Where the indented notes start, past the paragraphs' time column. */
private val NoteIndent = 50.dp

/**
 * The session's timeline. [playingKey] is the paragraph playing and [focusId] the note, photo or
 * mark playback last passed; while [follow] is on, [followKey] is kept in view. [peekId] is a note
 * being previewed from the scrubber: it's brought into view and outlined. [jumpTo] brings a row
 * into view once. While recording ([canPlay] off), tapping a photo shows it rather than playing from it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelinePane(
    session: Session,
    rows: List<TimelineRow>,
    playingKey: String?,
    focusId: String?,
    peekId: String?,
    followKey: String?,
    follow: Boolean,
    engaged: Boolean,
    canPlay: Boolean,
    liveTail: Boolean,
    jumpTo: String?,
    onJumpDone: () -> Unit,
    header: @Composable () -> Unit,
    empty: String?,
    onPlaySpeech: (TimelineRow.Speech) -> Unit,
    onPlayNote: (Note) -> Unit,
    onOpenPhoto: (Note) -> Unit,
    onEdit: (Note) -> Unit,
    onFold: (TimelineRow.Fold) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = timelineColors()
    val listState = rememberLazyListState()
    val thumbnailId = session.thumbnailNote()?.id
    val focusNote = focusId?.let { id -> session.notes.firstOrNull { it.id == id } }
    // Your own scrolling wins over following playback for a few seconds.
    var lastTouched by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) lastTouched = SystemClock.uptimeMillis() }
    }

    // The header is item 0, so row i is item i + 1. [from] is how far down the list it should end up.
    suspend fun bringIntoView(key: String, from: Float = 0.28f) {
        val i = rows.indexOfFirst { it.key == key }
        if (i < 0) return
        listState.animateScrollToItem(i + 1, -(listState.layoutInfo.viewportSize.height * from).toInt())
    }
    LaunchedEffect(followKey, follow, peekId == null) {
        if (!follow || followKey == null || peekId != null) return@LaunchedEffect
        if (SystemClock.uptimeMillis() - lastTouched < 4_000) return@LaunchedEffect
        bringIntoView(followKey)
    }
    // Near the top, clear of the preview card at the bottom.
    LaunchedEffect(peekId) { peekId?.let { bringIntoView(noteKey(it), from = 0.04f) } }
    LaunchedEffect(jumpTo) {
        jumpTo?.let {
            bringIntoView(it)
            onJumpDone()
        }
    }
    // While recording, keep the newest in view, unless you've scrolled up to read.
    LaunchedEffect(rows.size, liveTail) {
        if (!liveTail || rows.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= rows.size - 2) listState.animateScrollToItem(rows.size + 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().background(colors.page),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
    ) {
        item(key = "header") { header() }
        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
            when (row) {
                is TimelineRow.RecordingStart -> Text(
                    "Recording ${row.number} · ${com.kjwindham.audiocool.util.formatTime(row.rec.durationMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                )
                is TimelineRow.Speech -> {
                    val tintNote = focusNote?.takeIf { it.id in row.noteIds }
                    val dotNote = row.noteIds.firstNotNullOfOrNull { id -> session.notes.firstOrNull { it.id == id } }
                    SpeechParagraph(
                        label = timeLabel(session, row.recId, row.startMs).orEmpty(),
                        row = row,
                        playing = row.key == playingKey,
                        tint = tintNote?.let { colors.of(it).tint },
                        dot = dotNote?.let { colors.of(it).dot },
                        colors = colors,
                        onClick = { onPlaySpeech(row) },
                    )
                }
                is TimelineRow.Photo -> PhotoChapter(
                    session = session,
                    row = row,
                    size = when {
                        row.note.id == focusId -> PhotoSize.FOCUSED
                        engaged -> PhotoSize.COLLAPSED
                        else -> PhotoSize.RESTING
                    },
                    isThumbnail = row.note.id == thumbnailId,
                    peeked = row.note.id == peekId,
                    colors = colors,
                    onClick = {
                        // The first tap plays from it (and so opens it up); once it's open, a tap shows it full screen.
                        if (!canPlay || row.note.id == focusId || row.note.offsetMs == null) onOpenPhoto(row.note) else onPlayNote(row.note)
                    },
                    onOpen = { onOpenPhoto(row.note) },
                    onEdit = { onEdit(row.note) },
                )
                is TimelineRow.Written -> NoteCard(
                    label = noteLabel(session, row.note),
                    note = row.note,
                    focused = row.note.id == focusId,
                    peeked = row.note.id == peekId,
                    kind = colors.of(row.note),
                    onClick = { if (row.note.offsetMs != null && session.recording(row.note.recId) != null) onPlayNote(row.note) else onEdit(row.note) },
                    onEdit = { onEdit(row.note) },
                    onDelete = { SessionRepository.deleteNote(session.id, row.note.id) },
                )
                is TimelineRow.Mark -> MarkPill(
                    label = noteLabel(session, row.note),
                    note = row.note,
                    focused = row.note.id == focusId,
                    peeked = row.note.id == peekId,
                    kind = colors.mark,
                    onClick = { onPlayNote(row.note) },
                    onEdit = { onEdit(row.note) },
                    onDelete = { SessionRepository.deleteNote(session.id, row.note.id) },
                )
                is TimelineRow.Fold -> FoldDivider(row, onClick = { onFold(row) })
            }
        }
        item(key = "footer") {
            if (rows.isEmpty() && empty != null) Hint(empty)
            TranscribedWith(session)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeechParagraph(
    label: String,
    row: TimelineRow.Speech,
    playing: Boolean,
    tint: Color?,
    dot: Color?,
    colors: TimelineColors,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val background by animateColorAsState(tint ?: if (playing) colors.raised else Color.Transparent, label = "paragraph")
    val shape = RoundedCornerShape(14.dp)
    Box {
        Row(
            Modifier
                .padding(vertical = 2.dp)
                .fillMaxWidth()
                .shadow(if (playing && tint == null) 2.dp else 0.dp, shape, clip = false)
                .clip(shape)
                .background(background)
                .semantics { selected = playing }
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(40.dp).padding(top = 2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (dot != null) Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(dot))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                row.text,
                style = if (row.context) {
                    MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, lineHeight = 21.sp)
                } else {
                    MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
                },
                color = when {
                    playing || tint != null -> MaterialTheme.colorScheme.onSurface
                    row.context -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
                },
                maxLines = if (row.context) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Copy what was said") },
                leadingIcon = { Icon(AppIcons.Copy, contentDescription = null) },
                onClick = {
                    menu = false
                    copy(context, "What was said", row.text)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoChapter(
    session: Session,
    row: TimelineRow.Photo,
    size: PhotoSize,
    isThumbnail: Boolean,
    peeked: Boolean,
    colors: TimelineColors,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val note = row.note
    var menu by remember { mutableStateOf(false) }
    val file = SessionRepository.photoFile(session.id, note.photo!!)
    val title = photoTitle(note)
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)) {
                Icon(AppIcons.Image, contentDescription = null, tint = colors.photoLabel, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    photoLabel(session, row).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp),
                    color = colors.photoLabel,
                )
            }
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val image = rememberPhoto(file, 1024)
                val aspect = image?.let { it.height.toFloat() / it.width } ?: (9f / 16f)
                val target: Dp = when (size) {
                    PhotoSize.FOCUSED -> (maxWidth * aspect).coerceAtMost(300.dp)
                    PhotoSize.RESTING -> (maxWidth * aspect).coerceAtMost(150.dp)
                    PhotoSize.COLLAPSED -> 64.dp
                }
                val height by animateDpAsState(target, label = "photo")
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(height)
                        .shadow(if (size == PhotoSize.FOCUSED) 12.dp else 0.dp, shape, clip = false)
                        .clip(shape)
                        .background(Color(0xFF1C2033))
                        .then(if (peeked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
                ) {
                    if (image != null) {
                        Image(
                            image,
                            contentDescription = "Photo",
                            contentScale = if (size == PhotoSize.FOCUSED) ContentScale.Fit else ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (isThumbnail && size != PhotoSize.COLLAPSED) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        ) {
                            Text("Thumbnail", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            // A caption you wrote shows as the title; otherwise it's said here.
            if (note.text.isNotBlank() && title != note.text.trim()) {
                Text(note.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
            }
            if (size != PhotoSize.COLLAPSED) note.textInPhoto?.let { PhotoText(it) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("View full screen") },
                leadingIcon = { Icon(AppIcons.Image, contentDescription = null) },
                onClick = {
                    menu = false
                    onOpen()
                },
            )
            DropdownMenuItem(
                text = { Text(if (note.text.isBlank()) "Add a caption" else "Edit caption") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            note.textInPhoto?.let { text ->
                DropdownMenuItem(
                    text = { Text("Copy text in photo") },
                    leadingIcon = { Icon(AppIcons.Copy, contentDescription = null) },
                    onClick = {
                        menu = false
                        copy(context, "Text in photo", text)
                    },
                )
            }
            if (!isThumbnail) {
                DropdownMenuItem(
                    text = { Text("Use as thumbnail") },
                    leadingIcon = { Icon(AppIcons.Image, contentDescription = null) },
                    onClick = {
                        menu = false
                        SessionRepository.setThumbnail(session.id, note.id)
                    },
                )
            }
            DeleteItem {
                menu = false
                SessionRepository.deleteNote(session.id, note.id)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    label: String?,
    note: Note,
    focused: Boolean,
    peeked: Boolean,
    kind: KindColors,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1f else 0.97f, label = "note")
    val padding by animateDpAsState(if (focused) 14.dp else 10.dp, label = "notePadding")
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.padding(start = NoteIndent, top = 6.dp, bottom = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .shadow(if (focused) 10.dp else 0.dp, shape, clip = false)
                .clip(shape)
                .background(kind.tint)
                .then(if (peeked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .semantics { selected = focused }
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = padding + 2.dp, vertical = padding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (note.spoken) AppIcons.Mic else Icons.Filled.Edit, contentDescription = null, tint = kind.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull(if (note.spoken) "Spoken note" else "Note", label).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = kind.accent,
                )
            }
            Text(
                note.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (focused) 17.sp else 15.sp,
                    lineHeight = if (focused) 23.sp else 21.sp,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        NoteMenu(menu, onDismiss = { menu = false }, onEdit = onEdit, onDelete = onDelete)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkPill(
    label: String?,
    note: Note,
    focused: Boolean,
    peeked: Boolean,
    kind: KindColors,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "mark")
    Box(Modifier.padding(start = NoteIndent, top = 6.dp, bottom = 8.dp)) {
        Row(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .shadow(if (focused) 6.dp else 0.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(kind.tint)
                .then(if (peeked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                .semantics { selected = focused }
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = kind.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                listOfNotNull("Marked", label).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = kind.accent,
            )
        }
        NoteMenu(menu, onDismiss = { menu = false }, onEdit = onEdit, onDelete = onDelete, editLabel = "Add a note here")
    }
}

@Composable
private fun FoldDivider(row: TimelineRow.Fold, onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .padding(start = NoteIndent)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Show what was said here", onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(
            "${shortDuration(row.skippedMs)} of talk",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

@Composable
private fun NoteMenu(expanded: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, editLabel: String = "Edit") {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(editLabel) },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            onClick = {
                onDismiss()
                onEdit()
            },
        )
        DeleteItem {
            onDismiss()
            onDelete()
        }
    }
}

@Composable
private fun DeleteItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Delete") },
        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
        onClick = onClick,
    )
}

/** The text read off a photo (by SlideText): a few lines of it, all of it once tapped. */
@Composable
fun PhotoText(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var longer by remember(text) { mutableStateOf(false) }
    Column(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = longer, onClickLabel = if (expanded) "Show less" else "Show all") { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("Text in photo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) longer = it.hasVisualOverflow },
        )
        if (longer) {
            Text(
                if (expanded) "Show less" else "Show all",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * A quick look at a note, photo or mark picked on the scrubber, so you can tell it's the one you
 * want before going there. [context] is what was being said at a mark.
 */
@Composable
fun PeekCard(
    session: Session,
    note: Note,
    photoNumber: Int,
    context: String?,
    onPlay: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = timelineColors()
    val kind = colors.of(note)
    val time = noteLabel(session, note)
    val (icon, label, accent) = when {
        note.photo != null -> Triple(AppIcons.Image, photoLabel(session, TimelineRow.Photo(note, photoNumber)), colors.photoLabel)
        note.isMark -> Triple<ImageVector, String, Color>(Icons.Filled.Star, listOfNotNull("Marked", time).joinToString(" · "), kind.accent)
        else -> Triple(
            if (note.spoken) AppIcons.Mic else Icons.Filled.Edit,
            listOfNotNull(if (note.spoken) "Spoken note" else "Note", time).joinToString(" · "),
            kind.accent,
        )
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 12.dp,
        tonalElevation = 3.dp,
        modifier = modifier.clickable(onClickLabel = "Play from here", onClick = onPlay),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close preview", modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    note.photo != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        val image = rememberPhoto(SessionRepository.photoFile(session.id, note.photo), 320)
                        Box(Modifier.size(width = 104.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C2033))) {
                            if (image != null) {
                                Image(image, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            photoTitle(note) ?: "No text on it",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    note.isMark -> Text(
                        context?.let { "“$it”" } ?: "Nothing was transcribed around here.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text(
                        note.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (time != null) {
                    FilledTonalButton(onClick = onPlay, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play from $time")
                    }
                }
            }
        }
    }
}

/** "Slide 3 · 04:10" for a photo with text on it, "Photo 3 · 04:10" for one without. */
fun photoLabel(session: Session, row: TimelineRow.Photo): String =
    listOfNotNull("${if (row.note.textInPhoto != null) "Slide" else "Photo"} ${row.number}", noteLabel(session, row.note)).joinToString(" · ")

/** A photo's title: your caption, else the first line read off it. */
fun photoTitle(note: Note): String? =
    note.text.trim().ifEmpty { null } ?: note.textInPhoto?.lineSequence()?.firstOrNull { it.count(Char::isLetter) >= 3 }?.trim()

/** "45 s", "2:15", "1:02:15". */
fun shortDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return when {
        total < 60 -> "$total s"
        total < 3600 -> "%d:%02d".format(total / 60, total % 60)
        else -> "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
    }
}

private fun copy(context: android.content.Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 and up show their own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
