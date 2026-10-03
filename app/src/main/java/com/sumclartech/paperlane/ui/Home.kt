package com.sumclartech.paperlane.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sumclartech.paperlane.*
import com.sumclartech.paperlane.data.DocumentRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun DeskScreen(vm: EditorViewModel, onOpen: (Boolean) -> Unit, onScan: () -> Unit, onImages: () -> Unit, onMerge: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var documentToTrash by remember { mutableStateOf<DocumentRecord?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("desk_list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your desk.", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("A little less paperwork.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                PaperIllustration(Modifier.size(106.dp))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolTile("Open PDF", "Edit · fill · sign", Icons.Outlined.NoteAdd, Sage, { onOpen(false) }, Modifier.weight(1f))
                    ToolTile("Scan pages", "Paper to PDF", Icons.Outlined.DocumentScanner, Peach, onScan, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolTile("Merge PDFs", "Bring files together", Icons.Outlined.Layers, Lavender, onMerge, Modifier.weight(1f))
                    ToolTile("Images to PDF", "Photos, neatly filed", Icons.Outlined.AddPhotoAlternate, Sand, onImages, Modifier.weight(1f))
                }
            }
        }
        item { SectionLabel("Recent documents", if (vm.documents.isNotEmpty()) "${vm.documents.size} ${if (vm.documents.size == 1) "file" else "files"}" else null) }
        if (vm.documents.size > 3) item {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find a document") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        }
        if (vm.documents.isEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Room for your next idea.", style = MaterialTheme.typography.titleMedium)
                        Text("Open a file, or try a practice PDF.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = vm::demo, contentPadding = PaddingValues(0.dp)) { Text("Try it out"); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(16.dp)) }
                    }
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (filter.isNotBlank() && vm.documents.isNotEmpty() && vm.documents.none { it.title.contains(filter, true) }) item {
            Text("No documents match “$filter”. Try another name.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
        }
        items(vm.documents.filter { it.title.contains(filter, true) }, key = { it.id }) { record ->
            Surface(onClick = { vm.open(record) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PdfThumbnail(vm.engine, File(record.filePath), modifier = Modifier.size(52.dp, 68.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(record.title.removeSuffix(".pdf"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text("${record.pageCount} ${if (record.pageCount == 1) "page" else "pages"}  ·  ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(record.modifiedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { documentToTrash = record }) {
                        Icon(Icons.Outlined.DeleteOutline, "Move ${record.title} to trash", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp)); Text("Your documents stay on your device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    documentToTrash?.let { record ->
        AlertDialog(onDismissRequest = { documentToTrash = null },
            title = { Text("Move to trash?") },
            text = { Text("Move ${record.title} to Trash? You can restore it from Settings.") },
            confirmButton = { TextButton(onClick = { documentToTrash = null; vm.moveToTrash(record) }) { Text("Move to trash") } },
            dismissButton = { TextButton(onClick = { documentToTrash = null }) { Text("Cancel") } })
    }
}

@Composable fun ToolsScreen(onOpen: (Boolean) -> Unit, onScan: () -> Unit, onImages: () -> Unit, onMerge: () -> Unit, vm: EditorViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Make it easy.", style = MaterialTheme.typography.displaySmall) }
        item { Text("Choose a task. Bring a PDF.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { ToolTile("Fill & sign", "A form, a signature, done.", Icons.Outlined.Draw, Sage, { onOpen(true) }, Modifier.fillMaxWidth()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile("Scan", "Capture & crop", Icons.Outlined.DocumentScanner, Peach, onScan, Modifier.weight(1f))
            ToolTile("Merge", "Combine PDFs", Icons.Outlined.Layers, Lavender, onMerge, Modifier.weight(1f))
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile("Photos", "Images to PDF", Icons.Outlined.PhotoLibrary, Sand, onImages, Modifier.weight(1f))
            ToolTile("Page tools", "Rotate · extract · crop", Icons.Outlined.Dashboard, Sage, { onOpen(false) }, Modifier.weight(1f))
        } }
        item { SettingsRow(Icons.Outlined.PersonOutline, "Your details", "Fill forms faster with a local profile", { vm.panel = Panel.PROFILE }) }
        item { SettingsRow(Icons.Outlined.School, "Practice PDF", "Explore tools with a sample document", vm::demo) }
    }
}

@Composable fun SettingsScreen(vm: EditorViewModel) {
    val billing by vm.billing.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Your space.", style = MaterialTheme.typography.displaySmall) }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp), onClick = { vm.panel = Panel.UNLOCK }) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (billing.isEntitled) Icons.Outlined.Verified else Icons.Outlined.HourglassTop, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(if (billing.isEntitled) "Yours for good." else if (billing.expired) "Keep creating." else "${billing.trialDaysRemaining} days to explore.", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("30-day trial. One purchase. No ads.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (!billing.isEntitled) Text("View lifetime access →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item { SettingsRow(Icons.Outlined.PersonOutline, "Your details", "Stored only on this device", { vm.panel = Panel.PROFILE }) }
        item { SettingsRow(Icons.Outlined.Restore, "Restore purchase", "Use the same Google Play account", vm.billing::restore) }
        item { SettingsRow(Icons.Outlined.DeleteOutline, "Trash", "Restore removed documents", vm::loadTrash) }
        item { SettingsRow(Icons.Outlined.Shield, "Privacy", "Your files, your control", { vm.panel = Panel.PRIVACY }) }
        item { SettingsRow(Icons.Outlined.Code, "Open source", "Libraries & licenses", { vm.panel = Panel.LICENSES }) }
        item { Text("PDFix · ${BuildConfig.VERSION_NAME}\nMade by SumClarTech", Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
