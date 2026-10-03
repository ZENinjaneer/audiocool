package com.kjwindham.audiocool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.speakers.KnownVoices
import com.kjwindham.audiocool.speakers.VoicePrints
import com.kjwindham.audiocool.speakers.Voices

/** Colors told apart easily, light or dark, one per voice. */
private val VoiceColors = listOf(
    Color(0xFF5468C4), Color(0xFFC0542F), Color(0xFF2E8B57), Color(0xFF9C4DCC),
    Color(0xFFB07A0B), Color(0xFF1E88A8), Color(0xFFD0457A), Color(0xFF6D7F2B),
)

fun voiceColor(id: Int): Color = VoiceColors[Math.floorMod(id, VoiceColors.size)]


/** Who's speaking, at the top of their paragraph: tap to name them. */
@Composable
fun SpeakerLabel(session: Session, voiceId: Int, onClick: () -> Unit) {
    val voice = session.voices.firstOrNull { it.id == voiceId }
    val color = voiceColor(voiceId)
    val name = Voices.name(session, voiceId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (voice?.name == null) "$name. Who's this?" else "$name. Rename" }
            .padding(end = 8.dp),
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(voice?.name?.first()?.uppercase() ?: "?", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(6.dp))
        Text(name, color = color, style = MaterialTheme.typography.labelLarge)
        if (voice?.name == null) {
            Text(" · Who's this?", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Naming a voice: how much they said and a line of it (tap to hear it), names used before to pick
 * from, and whether to know the voice again in later recordings. The voiceprint stays on the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NameVoiceDialog(session: Session, voiceId: Int, onPlay: (recId: String, atMs: Long) -> Unit, onDismiss: () -> Unit) {
    val voice = session.voices.firstOrNull { it.id == voiceId }
    if (voice == null) {
        // Gone meanwhile (made one with another voice, say).
        LaunchedEffect(voiceId) { onDismiss() }
        return
    }
    val turns = session.recordings.flatMap { r -> r.speakers.orEmpty().filter { it.voice == voiceId } }
    val minutes = turns.sumOf { it.endMs - it.startMs } / 60_000.0
    // Their longest line, to recognise them by.
    val line = remember(session, voiceId) {
        session.recordings.flatMap { r ->
            r.transcript.orEmpty().flatMap { Voices.splitBySpeaker(it, r.speakers) }
                .filter { Voices.voiceAt(r.speakers, it.startMs, it.endMs) == voiceId }
                .map { Triple(r.id, it.startMs, it.text) }
        }.maxByOrNull { it.third.length }
    }
    val suggestions = remember(session, voiceId) {
        val known = KnownVoices.voices.value.sortedByDescending { it.updatedAt }.map { it.name }
        val elsewhere = SessionRepository.sessions.value.sortedByDescending { it.updatedAt }.flatMap { s -> s.voices.mapNotNull { it.name } }
        (known + elsewhere).distinctBy { it.lowercase() }.filterNot { it.equals(voice.name, ignoreCase = true) }.take(8)
    }
    val voiceprint = remember(session.id, voiceId) { VoicePrints.of(session.id)[voiceId] }
    var name by rememberSaveable(voiceId) { mutableStateOf(voice.name.orEmpty()) }
    var remember by rememberSaveable(voiceId) { mutableStateOf(voice.name != null && KnownVoices.isKnown(voice.name)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Sized here rather than by the platform: a text field in a platform-width dialog never settles
        // under Robolectric (the UI tests). On a phone it looks the same.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        title = { Text(if (voice.name == null) "Who's this?" else "Rename ${voice.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val talked = if (minutes < 1) "under a minute" else "about ${minutes.toInt().coerceAtLeast(1)} minute${if (minutes >= 2) "s" else ""}"
                Text(
                    "${Voices.name(session, voiceId)} · ${turns.size} turn${if (turns.size == 1) "" else "s"}, $talked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                line?.let { (recId, at, text) ->
                    Text(
                        "▶ “$text”",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPlay(recId, at) }.padding(4.dp),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (KnownVoices.isKnown(it.trim())) remember = true
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Text("From earlier meetings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { s ->
                            SuggestionChip(onClick = {
                                name = s
                                if (KnownVoices.isKnown(s)) remember = true
                            }, label = { Text(s) })
                        }
                    }
                }
                if (voiceprint != null) {
                    // The whole row switches it, not just the switch.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(value = remember, role = Role.Switch, onValueChange = { remember = it }),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Recognize this voice next time", style = MaterialTheme.typography.bodyMedium)
                            Text("The voiceprint stays on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = remember, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen = name.trim()
                    // The same name as another voice makes them one; that voice's voiceprint stands.
                    if (session.voices.any { it.id != voiceId && it.name.equals(chosen, ignoreCase = true) }) VoicePrints.set(session.id, VoicePrints.of(session.id) - voiceId)
                    SessionRepository.nameVoice(session.id, voiceId, chosen)
                    // Switched off for someone known: their voiceprint is forgotten.
                    if (remember) voiceprint?.let { KnownVoices.remember(chosen, it) } else if (KnownVoices.isKnown(chosen)) KnownVoices.forget(chosen)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
