package com.kjwindham.audiocool.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.Waveform
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.formatTime
import com.kjwindham.audiocool.util.noteLabel
import kotlin.math.abs
import kotlin.math.roundToInt

/** The scrubber's side padding; a preview card lines up with a marker using it. */
val ScrubberPadding = 16.dp

private val Speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

/**
 * Playback for the session: the recording's waveform to scrub along, with a marker for every note,
 * ★ and photo in it, and the play controls. Dragging along the waveform previews that moment
 * ([onScrub], so the timeline follows) and plays from it when you let go. Tapping a marker, or
 * pressing on the markers and sliding along them, previews that note ([onPeek]) without going there.
 */
@Composable
fun Scrubber(
    session: Session,
    rec: Recording,
    playable: List<Recording>,
    player: PlayerController.State,
    levels: ByteArray?,
    markers: List<Note>,
    focusId: String?,
    peekId: String?,
    scrubMs: Long?,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    onPeek: (String?) -> Unit,
    onSelectRec: (String) -> Unit,
    onToggle: () -> Unit,
    onSkip: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onRecordMore: () -> Unit,
) {
    val loaded = player.sessionId == session.id && player.recId == rec.id
    val position = if (loaded) player.positionMs else 0L
    val duration = (if (loaded && player.durationMs > 0) player.durationMs else rec.durationMs).coerceAtLeast(1L)
    val shown = scrubMs ?: position
    val timelineColors = timelineColors()

    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(start = ScrubberPadding, end = ScrubberPadding, top = 4.dp, bottom = 2.dp)) {
            if (playable.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    playable.forEach { r ->
                        FilterChip(
                            selected = r.id == rec.id,
                            onClick = { onSelectRec(r.id) },
                            label = { Text("Rec ${session.recordingNumber(r.id)} · ${formatTime(r.durationMs)}") },
                        )
                    }
                }
            }
            MarkerRow(session, markers, duration, focusId, peekId, timelineColors, onPeek)
            WaveformBar(levels, duration, shown, onScrub = onScrub, onSeek = onSeek)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
                Text(formatTime(shown), style = timeStyle)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), style = timeStyle)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { onSpeed(Speeds[(Speeds.indexOf(player.speed) + 1) % Speeds.size]) }) {
                    Text(speedLabel(player.speed), modifier = Modifier.semantics { contentDescription = "Playback speed ${speedLabel(player.speed)}" })
                }
                SkipButton(back = true) { onSkip(-10_000) }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(56.dp)) {
                    val playing = loaded && player.isPlaying
                    Icon(
                        if (playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
                SkipButton(back = false) { onSkip(10_000) }
                IconButton(onClick = onRecordMore) {
                    Icon(AppIcons.Mic, contentDescription = "Record more", tint = RecordRed)
                }
            }
        }
    }
}

/**
 * The markers above the waveform. A tap previews the nearest one within reach; pressing and sliding
 * previews each in turn, with a tick as it moves to the next. Each can also be previewed with TalkBack.
 */
@Composable
private fun MarkerRow(
    session: Session,
    markers: List<Note>,
    duration: Long,
    focusId: String?,
    peekId: String?,
    colors: TimelineColors,
    onPeek: (String?) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentPeek by rememberUpdatedState(peekId)
    val latestMarkers by rememberUpdatedState(markers)
    BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        fun xOf(note: Note) = ((note.offsetMs ?: 0L).toFloat() / duration).coerceIn(0f, 1f) * widthPx
        fun nearest(x: Float, reachPx: Float): Note? =
            latestMarkers.minByOrNull { abs(xOf(it) - x) }?.takeIf { abs(xOf(it) - x) <= reachPx }
        val tapReach = with(density) { 28.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(duration, widthPx) {
                    detectTapGestures(onTap = { onPeek(nearest(it.x, tapReach)?.id) })
                }
                .pointerInput(duration, widthPx) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            nearest(start.x, Float.MAX_VALUE)?.let {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPeek(it.id)
                            }
                        },
                        onDrag = { change, _ ->
                            val next = nearest(change.position.x, Float.MAX_VALUE)
                            if (next != null && next.id != currentPeek) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPeek(next.id)
                            }
                        },
                    )
                },
        ) {
            // Drawn in order of importance, so the one in focus or being previewed is on top.
            val ordered = markers.sortedBy { if (it.id == peekId) 2 else if (it.id == focusId) 1 else 0 }
            for (note in ordered) {
                val highlighted = note.id == peekId || note.id == focusId
                val description = markerDescription(session, note)
                val semantics = Modifier.semantics {
                    contentDescription = description
                    onClick(label = "Preview") {
                        onPeek(note.id)
                        true
                    }
                }
                if (note.photo != null) {
                    val w = 30.dp
                    val h = 21.dp
                    val ring = when (note.id) {
                        peekId -> MaterialTheme.colorScheme.primary
                        focusId -> colors.photoRing
                        else -> MaterialTheme.colorScheme.surface
                    }
                    val image = rememberPhoto(SessionRepository.photoFile(session.id, note.photo), 120)
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset { IntOffset((xOf(note) - w.toPx() / 2).roundToInt(), -3.dp.roundToPx()) }
                            .size(w, h)
                            // After the offset, so TalkBack (and tests) find it where it's drawn.
                            .then(semantics)
                            .graphicsLayer { if (highlighted) { scaleX = 1.25f; scaleY = 1.25f } }
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1C2033))
                            .border(2.dp, ring, RoundedCornerShape(4.dp)),
                    ) {
                        if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                } else {
                    val dot: Dp = if (highlighted) 13.dp else 9.dp
                    val kind = colors.of(note)
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset { IntOffset((xOf(note) - dot.toPx() / 2).roundToInt(), -(3.dp + (21.dp - dot) / 2).roundToPx()) }
                            .size(dot)
                            .then(semantics)
                            .border(2.dp, if (note.id == peekId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(kind.dot),
                    )
                }
            }
        }
    }
}

private fun markerDescription(session: Session, note: Note): String {
    val at = noteLabel(session, note)?.let { " at $it" }.orEmpty()
    return when {
        note.photo != null -> "Photo$at" + (photoTitle(note)?.let { ": $it" } ?: "")
        note.isMark -> "Marked moment$at"
        else -> (if (note.spoken) "Spoken note" else "Note") + "$at: ${note.text}"
    }
}

/** The recording's loudness as bars, played part in the theme's colour; drag along it to scrub, or tap a spot. */
@Composable
private fun WaveformBar(levels: ByteArray?, duration: Long, positionMs: Long, onScrub: (Long?) -> Unit, onSeek: (Long) -> Unit) {
    val played = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.outlineVariant
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .semantics { contentDescription = "Position in the recording, ${formatTime(positionMs)} of ${formatTime(duration)}" }
            .pointerInput(duration) {
                fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * duration).toLong()
                detectTapGestures(onTap = { onSeek(at(it.x)) })
            }
            .pointerInput(duration) {
                fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * duration).toLong()
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragMs = at(it.x)
                        onScrub(dragMs)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragMs = at(change.position.x)
                        onScrub(dragMs)
                    },
                    onDragEnd = {
                        dragMs?.let(onSeek)
                        dragMs = null
                        onScrub(null)
                    },
                    onDragCancel = {
                        dragMs = null
                        onScrub(null)
                    },
                )
            },
    ) {
        val barWidth = with(density) { 3.dp.toPx() }
        val gap = with(density) { 1.5.dp.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val count = ((size.width + gap) / (barWidth + gap)).toInt().coerceAtLeast(1)
            val heights = levels?.let { bars(it, count, duration) }
            val playX = (positionMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
            val minH = 5.dp.toPx()
            val maxH = size.height - 3.dp.toPx()
            for (i in 0 until count) {
                val x = i * (barWidth + gap)
                val h = if (heights != null) minH + heights[i] * (maxH - minH) else minH + 0.2f * (maxH - minH)
                val color = when {
                    heights == null -> unplayed.copy(alpha = 0.5f)
                    x + barWidth / 2 <= playX -> played
                    else -> unplayed
                }
                drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(barWidth, h), CornerRadius(barWidth / 2))
            }
            val head = 3.dp.toPx()
            drawRoundRect(played, Offset(playX - head / 2, -4.dp.toPx()), Size(head, size.height + 8.dp.toPx()), CornerRadius(head / 2))
        }
    }
}

/**
 * [count] bar heights (0..1) from levels at [Waveform.STEP_MS] apiece: each bar the average over its
 * stretch (a long recording gives each bar seconds of audio, whose loudest moment is nearly always
 * loud), scaled to the loudest bar.
 */
internal fun bars(levels: ByteArray, count: Int, durationMs: Long): FloatArray {
    val out = FloatArray(count)
    if (levels.isEmpty()) return out
    for (i in 0 until count) {
        val from = (durationMs * i / count / Waveform.STEP_MS).toInt().coerceAtMost(levels.size - 1)
        val to = (durationMs * (i + 1) / count / Waveform.STEP_MS).toInt().coerceIn(from + 1, levels.size)
        var sum = 0
        for (j in from until to) sum += levels[j].toInt() and 0xFF
        out[i] = sum / (to - from) / 255f
    }
    val top = out.maxOrNull()?.takeIf { it > 0f } ?: return out
    for (i in out.indices) out[i] = out[i] / top
    return out
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

/** For laying out a preview card over a marker: its x across a scrubber [width] wide. */
fun markerX(fraction: Float, width: Dp): Dp = ScrubberPadding + (width - ScrubberPadding * 2) * fraction.coerceIn(0f, 1f)
