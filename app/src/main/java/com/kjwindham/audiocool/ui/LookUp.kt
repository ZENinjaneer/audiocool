package com.kjwindham.audiocool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kjwindham.audiocool.lookup.Explain
import com.kjwindham.audiocool.lookup.LookUp
import com.kjwindham.audiocool.summarize.Moment

/** A word looked up: what it means in this talk and where it's said, and what Wikipedia says. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LookUpSheet(state: LookUp.State, onMoment: (Moment) -> Unit, onAddNote: () -> Unit, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.word, style = MaterialTheme.typography.headlineSmall)
                if (state.acronym) {
                    Spacer(Modifier.width(10.dp))
                    Text("acronym", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
            }

            Text("In this talk", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when {
                state.working -> {
                    Text("Working out what it means here…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.meaning != null -> Text(state.meaning, style = MaterialTheme.typography.bodyLarge)
            }
            if (state.moments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    state.moments.forEach { m -> MomentChip(m, onMoment) }
                    Text(
                        if (state.count == 1) "said once" else "said ${state.count} times",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            } else if (!state.working && state.meaning == null) {
                Text("It isn't in what was said.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("From the web · Wikipedia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            val article = state.article
            when {
                state.searching -> Text("Looking it up…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.webProblem != null -> Text(state.webProblem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                article == null -> Text("Nothing found that fits.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(article.title, style = MaterialTheme.typography.titleSmall)
                    Text(firstSentences(article.extract, 3), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.others.isNotEmpty()) {
                Text(
                    "Other meanings: " + state.others.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)) {
                Button(onClick = onAddNote, enabled = state.meaning != null || article != null) { Text("Add as a note") }
                if (article != null) OutlinedButton(onClick = { onOpen(article.url) }) { Text("Open") }
            }
        }
    }
}

/** A slide explained: from its text and what was said while it was up, with its key terms to look up. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExplainSheet(state: Explain.State, onMoment: (Moment) -> Unit, onLookUp: (String) -> Unit, onAddNote: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Explain this slide", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            state.title?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
            when {
                state.working -> {
                    Text("Reading the slide and what was said…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.problem != null -> Text(state.problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(state.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }
            if (state.moments.isNotEmpty()) {
                Text("From what was said at", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.moments.forEach { m -> MomentChip(m, onMoment) } }
            }
            if (state.terms.isNotEmpty()) {
                Text("Look up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.terms.forEach { t -> SuggestionChip(onClick = { onLookUp(t) }, label = { Text(t) }) }
                }
            }
            Row(modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)) {
                Button(onClick = onAddNote, enabled = state.text != null) { Text("Add as a note") }
            }
        }
    }
}

@Composable
private fun MomentChip(m: Moment, onMoment: (Moment) -> Unit) {
    AssistChip(
        onClick = { onMoment(m) },
        label = { Text(m.label) },
        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.semantics { contentDescription = "Play from ${m.label}" },
    )
}

/** The first [n] sentences of [text]. */
private fun firstSentences(text: String, n: Int): String {
    var end = 0
    repeat(n) {
        val next = Regex("[.!?](\\s|$)").find(text, end)?.range?.last ?: return text.trim()
        end = next + 1
    }
    return text.substring(0, end).trim()
}
