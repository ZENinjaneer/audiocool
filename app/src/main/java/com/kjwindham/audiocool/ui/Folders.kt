package com.kjwindham.audiocool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kjwindham.audiocool.data.FolderRepository
import com.kjwindham.audiocool.data.FolderSummary
import com.kjwindham.audiocool.summarize.FolderSuggestion

/** "1 session", "12 sessions". */
fun sessionCount(n: Int) = if (n == 1) "1 session" else "$n sessions"

/** The main screen's folders: All, each folder, and + for a new one. Only shown once there's a folder. */
@Composable
fun FolderChips(folders: List<FolderSummary>, shown: String?, onShow: (String?) -> Unit, onNew: () -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") { FilterChip(selected = shown == null, onClick = { onShow(null) }, label = { Text("All") }) }
        items(folders, key = { it.name }) { f ->
            FilterChip(selected = shown == f.name, onClick = { onShow(f.name) }, label = { Text(f.name) })
        }
        item(key = "new") {
            AssistChip(onClick = onNew, label = { Icon(Icons.Filled.Add, contentDescription = "New folder", modifier = Modifier.size(18.dp)) })
        }
    }
}

/** Under the chips, for the folder shown: how many sessions it holds, and renaming or deleting it. */
@Composable
fun FolderBar(folder: FolderSummary, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            sessionCount(folder.sessions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Folder options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename folder") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete folder") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** Picks the folder for a session that's in [current]: one of [folders], none, or a new one. */
@Composable
fun MoveToFolderDialog(current: String?, folders: List<String>, onMove: (String?) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(
            title = "New folder",
            initial = "",
            onConfirm = { onMove(FolderRepository.create(it)) },
            onDismiss = { creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (name in folders) FolderChoice(name, selected = name == current) { onMove(name) }
                FolderChoice("No folder", selected = current == null) { onMove(null) }
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New folder")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** On the main screen: the summary model has folders to suggest (or is looking for some). */
@Composable
fun OrganizeCard(suggestions: List<FolderSuggestion>?, looking: Boolean, sessionCount: Int, onReview: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
            Text("✦ Organize your sessions?", style = MaterialTheme.typography.titleSmall)
            if (suggestions == null) {
                Text("Looking at your sessions…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp))
                return@Column
            }
            val names = suggestions.map { it.name }
            Text(
                "${sessionCount(sessionCount).replaceFirstChar { it.uppercase() }} fall into ${groupCount(names.size)}: ${listed(names)}.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = onReview) { Text("See the folders") }
                TextButton(onClick = onDismiss, enabled = !looking) { Text("Not now") }
            }
        }
    }
}

/**
 * The folders the summary model suggests, to check before anything moves: untick a folder or rename it,
 * then Organize. Sessions that fit nowhere stay where they are. [leftOver]: how many that is.
 */
@Composable
fun OrganizeReviewDialog(
    suggestions: List<FolderSuggestion>,
    titles: Map<String, String>,
    leftOver: Int,
    onApply: (List<FolderSuggestion>, keepOrganized: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var names by remember { mutableStateOf(suggestions.map { it.name }) }
    var picked by remember { mutableStateOf(suggestions.map { true }) }
    var keepOrganized by remember { mutableStateOf(true) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    val count = suggestions.indices.filter { picked[it] }.sumOf { i -> suggestions[i].sessionIds.count { it in titles } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Column(Modifier.weight(1f)) {
                        Text("Suggested folders", style = MaterialTheme.typography.titleLarge)
                        Text("From your sessions' summaries, made on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(suggestions.size) { i ->
                        val f = suggestions[i]
                        val inIt = f.sessionIds.mapNotNull { titles[it] }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = picked[i],
                                        onCheckedChange = { on -> picked = picked.toMutableList().also { it[i] = on } },
                                        modifier = Modifier.semantics { contentDescription = "Include ${names[i]}" },
                                    )
                                    Text(names[i], style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { renaming = i }) { Icon(Icons.Filled.Edit, contentDescription = "Rename ${names[i]}") }
                                }
                                val about = buildList {
                                    add(sessionCount(inIt.size))
                                    f.description?.let { add(it) }
                                    if (f.existing) add("already a folder")
                                }.joinToString(" · ")
                                Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp))
                                Text(listed(inIt, most = 3), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp, top = 4.dp))
                            }
                        }
                    }
                    if (leftOver > 0) {
                        item {
                            Text(
                                if (leftOver == 1) "1 session doesn't fit any of these, so it stays where it is."
                                else "$leftOver sessions don't fit any of these, so they stay where they are.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Keep new sessions organized", style = MaterialTheme.typography.titleSmall)
                                Text("Each new session is filed once its summary is ready. You can always move it.", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = keepOrganized, onCheckedChange = { keepOrganized = it })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = count > 0,
                        onClick = { onApply(suggestions.indices.filter { picked[it] }.map { suggestions[it].copy(name = names[it]) }, keepOrganized) },
                    ) { Text(if (count == 1) "Organize 1 session" else "Organize $count sessions") }
                }
            }
        }
    }
    renaming?.let { i ->
        TextInputDialog(
            title = "Folder name",
            initial = names[i],
            onConfirm = {
                names = names.toMutableList().also { list -> list[i] = it }
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

/** ⋮ › Folders: suggestions from the summary model, keeping new sessions organized, and filing by calendar. */
@Composable
fun FolderSettingsDialog(
    canSuggest: Boolean,
    keepOrganized: Boolean,
    byCalendar: Boolean,
    onSuggest: () -> Unit,
    onKeepOrganized: (Boolean) -> Unit,
    onByCalendar: (Boolean) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    OutlinedButton(onClick = onSuggest, enabled = canSuggest) { Text("✦ Suggest folders") }
                    Text(
                        if (canSuggest) "The summary model groups your sessions that aren't in a folder, for you to check."
                        else "Needs summaries on this phone (⋮ › Summaries).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingSwitch(
                    "Keep new sessions organized",
                    "Each new session is filed in the folder that fits once its summary is ready.",
                    keepOrganized,
                    onKeepOrganized,
                )
                SettingSwitch(
                    "File by calendar",
                    "A session recorded during an event on your calendar goes in a folder named after it. Asks to read your calendar.",
                    byCalendar,
                    onByCalendar,
                )
                TextButton(onClick = onNewFolder) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New folder")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SettingSwitch(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

private fun groupCount(n: Int) = when (n) {
    1 -> "one group"
    2 -> "two groups"
    3 -> "three groups"
    else -> "$n groups"
}

/** "A", "A and B", "A, B and C", or with [most], "A, B, C and 2 more". */
private fun listed(items: List<String>, most: Int = Int.MAX_VALUE): String {
    val shown = items.take(most)
    val rest = items.size - shown.size
    val parts = if (rest > 0) shown + "$rest more" else shown
    return when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}
