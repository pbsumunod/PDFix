package com.sumclartech.paperlane.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sumclartech.paperlane.*
import com.sumclartech.paperlane.data.*
import com.sumclartech.paperlane.pdf.NormalizedPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Panels(vm: EditorViewModel, onSave: () -> Unit, onShare: () -> Unit, onBuy: () -> Unit) {
    if (vm.panel == Panel.NONE) return
    if (vm.panel == Panel.REDACT_CONFIRM || vm.panel == Panel.CROP_CONFIRM) {
        val redact = vm.panel == Panel.REDACT_CONFIRM
        AlertDialog(onDismissRequest = { vm.panel = Panel.NONE; vm.selection = null },
            icon = { Icon(if (redact) Icons.Outlined.VisibilityOff else Icons.Outlined.Crop, null) },
            title = { Text(if (redact) "Remove this content?" else "Crop this page?") },
            text = { Text(if (redact) "The selected content will be removed from the exported PDF. All pages become images; text, forms and links stop being interactive. Your original stays in local History." else "Only the visible page area changes. Hidden content remains in the PDF. Use Redact to permanently remove content.") },
            confirmButton = { TextButton(onClick = { if (redact) vm.applyRedaction() else vm.applyCrop() }) { Text(if (redact) "Remove content" else "Crop") } },
            dismissButton = { TextButton(onClick = { vm.panel = Panel.NONE; vm.selection = null }) { Text("Cancel") } })
        return
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { vm.panel = Panel.NONE }, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 820.dp).imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when (vm.panel) {
                    Panel.PAGES -> "Every page in place."
                    Panel.FORM -> "Fill it in."
                    Panel.TEXT -> "Make your mark."
                    Panel.EDIT_TEXT -> "Edit existing text."
                    Panel.SIGNATURE -> "Your signature."
                    Panel.EXPORT -> "Ready to send."
                    Panel.SEARCH -> "Find in document."
                    Panel.READ -> "Room to read."
                    Panel.HISTORY -> "Your safety net."
                    Panel.UNLOCK -> "Yours for good."
                    Panel.PROFILE -> "Your details."
                    Panel.PRIVACY -> "Your files. Your choice."
                    Panel.TRASH -> "A second chance."
                    Panel.RENAME -> "Name your document."
                    Panel.CORRECT -> "Replace this area."
                    Panel.LICENSES -> "Built with care."
                    else -> ""
                }, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconAction(Icons.Outlined.Close, "Close panel") { vm.panel = Panel.NONE }
            }
            Spacer(Modifier.height(14.dp))
            vm.notice?.let { message ->
                Text(message, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (vm.panel) {
                Panel.PAGES -> PagesPanel(vm)
                Panel.FORM -> FormPanel(vm)
                Panel.TEXT -> TextPanel(vm)
                Panel.EDIT_TEXT -> ExistingTextPanel(vm)
                Panel.SIGNATURE -> SignaturePanel(vm)
                Panel.EXPORT -> ExportPanel(vm, onSave, onShare)
                Panel.SEARCH -> SearchPanel(vm)
                Panel.READ -> ReadPanel(vm)
                Panel.HISTORY -> HistoryPanel(vm)
                Panel.UNLOCK -> UnlockPanel(vm, onBuy)
                Panel.PROFILE -> ProfilePanel(vm)
                Panel.PRIVACY -> PrivacyPanel()
                Panel.TRASH -> TrashPanel(vm)
                Panel.RENAME -> RenamePanel(vm)
                Panel.CORRECT -> CorrectionPanel(vm)
                Panel.LICENSES -> LicensePanel()
                else -> Unit
            }
        }
    }
}

@Composable private fun LicensePanel() {
    val context = LocalContext.current
    val notices by produceState<List<Pair<String, String>>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            context.assets.list("licenses").orEmpty().sorted().map { name ->
                name to context.assets.open("licenses/$name").bufferedReader().use { it.readText().trim() }
            }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Libraries and licenses", style = MaterialTheme.typography.titleLarge)
        Text("These notices cover the third-party components included with PDFix.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        notices.forEach { (name, content) ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name.removeSuffix(".txt").replace('-', ' '), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(content, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun TextPanel(vm: EditorViewModel) {
    var text by rememberSaveable { mutableStateOf(vm.textToPlace) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(text, { if (it.length <= 2000) text = it }, Modifier.fillMaxWidth().testTag("annotation_text"), placeholder = { Text("Type your text") }, minLines = 2, maxLines = 6, shape = RoundedCornerShape(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", fontSize = 14.sp); Slider(vm.textSize, { vm.textSize = it }, valueRange = .012f.. .065f, modifier = Modifier.weight(1f).semantics { contentDescription = "Text size" }); Text("A", fontSize = 26.sp)
        }
        ColorPicker(vm.inkColor) { vm.inkColor = it }
        FilledTonalButton(onClick = { text = vm.profile.name }, enabled = vm.profile.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PersonOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use my name") }
        Button(onClick = { vm.textToPlace = text.trim(); vm.tool = EditorTool.TEXT; vm.panel = Panel.NONE }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Place on page"); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.TouchApp, null, Modifier.size(20.dp)) }
        Text("Adds new text. Existing PDF text is unchanged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable private fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    val colors = listOf("Ink" to 0xFF263C34.toInt(), "Blue" to 0xFF275DB7.toInt(), "Red" to 0xFFC34C3A.toInt(), "Black" to 0xFF000000.toInt())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { (name, color) ->
            Box(Modifier.size(48.dp).border(if (selected == color) 2.dp else 0.dp, if (selected == color) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape).clickable { onSelect(color) }.semantics { contentDescription = "$name text color" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).background(Color(color), CircleShape))
                if (selected == color) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = Color.White)
            }
        }
    }
}

@Composable private fun SignaturePanel(vm: EditorViewModel) {
    var strokes by remember { mutableStateOf<List<List<NormalizedPoint>>>(emptyList()) }
    var active by remember { mutableStateOf<List<NormalizedPoint>>(emptyList()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Draw with your finger or pen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(190.dp).background(Color.White, RoundedCornerShape(16.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).semantics { contentDescription = "Signature drawing area" }.pointerInput(Unit) {
            fun point(p: Offset) = NormalizedPoint((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
            detectDragGestures(onDragStart = { active = listOf(point(it)) }, onDrag = { change, _ -> change.consume(); if (active.size < 2500) active = active + point(change.position) },
                onDragEnd = { if (active.size > 1 && strokes.size < 40) strokes = strokes + listOf(active); active = emptyList() }, onDragCancel = { active = emptyList() })
        }) {
            drawLine(Color(0xFFE4E8DF), Offset(size.width * .08f, size.height * .78f), Offset(size.width * .92f, size.height * .78f), strokeWidth = 2f)
            (strokes + listOf(active)).forEach { stroke ->
                if (stroke.size > 1) drawPath(Path().apply { stroke.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * size.width, p.y * size.height) else lineTo(p.x * size.width, p.y * size.height) } }, Ink, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { strokes = emptyList(); active = emptyList() }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Clear") }
            Button(onClick = { vm.signature = strokes; vm.tool = EditorTool.SIGN; vm.panel = Panel.NONE }, enabled = strokes.isNotEmpty(), modifier = Modifier.weight(2f).height(52.dp)) { Text("Place signature") }
        }
        Text("A handwritten mark, without certificate verification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun ExportPanel(vm: EditorViewModel, onSave: () -> Unit, onShare: () -> Unit) {
    val document = vm.current ?: return
    var target by remember { mutableStateOf<Int?>(null) }
    var clean by remember { mutableStateOf(false) }
    var secure by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    val prepared = vm.export
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.testTag("export_preview"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PdfThumbnail(vm.engine, prepared?.previewFile ?: File(document.filePath), modifier = Modifier.size(66.dp, 90.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant).clickable(enabled = prepared != null) { preview = true })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(document.title, style = MaterialTheme.typography.titleMedium)
                Text("${document.pageCount} pages · ${humanSize(prepared?.file?.length() ?: File(document.filePath).length())}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (prepared != null) Pill(if (prepared.targetMet) "Ready" else "Above target size", if (prepared.targetMet) Icons.Outlined.Check else Icons.Outlined.Info)
            }
        }
        Text("File size", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null, 1, 2, 5, 10).forEach { value -> FilterChip(selected = target == value, onClick = { target = value; vm.clearExport() }, label = { Text(if (value == null) "Original" else "$value MB") }) }
        }
        if (target != null) Text("Smaller exports turn pages into images. Selectable text, form fields and links are removed. Review the result before sending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
            Text("Remove author & metadata", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(clean, { clean = it; vm.clearExport() })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
            Text("Require a password", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(secure, { secure = it; vm.clearExport() })
        }
        if (secure) OutlinedTextField(password, { password = it; vm.clearExport() }, Modifier.fillMaxWidth(), label = { Text("Opening password (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(16.dp))
        if (prepared == null) Button(onClick = { vm.prepareExport(target, clean, if (secure) password else "") }, enabled = !secure || password.length >= 8, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(Icons.Outlined.TaskAlt, null, Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Text("Prepare PDF")
        } else {
            OutlinedButton(onClick = { preview = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Preview, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Preview every page") }
            if (!prepared.targetMet) Text("This PDF could not fit the selected limit. Actual size: ${humanSize(prepared.file.length())}. Try fewer pages or a higher limit.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f).height(54.dp)) { Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save to Files", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(onClick = onShare, modifier = Modifier.weight(1f).height(54.dp)) { Icon(Icons.Outlined.IosShare, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Share") }
            }
        }
        Text("Your original stays safe in the app. Exported copies stay available after the trial.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
    if (preview && prepared != null) Dialog(onDismissRequest = { preview = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Export preview", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconAction(Icons.Outlined.Close, "Close export preview") { preview = false }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(document.pageCount) { index ->
                        val pageInfo = vm.info?.pages?.getOrNull(index)
                        val aspect = if (pageInfo == null) .707f else pageInfo.width / pageInfo.height
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PdfThumbnail(vm.engine, prepared.previewFile, index, modifier = Modifier.fillMaxWidth().aspectRatio(aspect), maxEdge = 1800)
                            Text("${index + 1} / ${document.pageCount}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SearchPanel(vm: EditorViewModel) {
    var value by remember { mutableStateOf(vm.query) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Find words") }, singleLine = true, trailingIcon = { IconAction(Icons.Outlined.Search, "Search PDF", value.isNotBlank()) { vm.search(value) } }, shape = RoundedCornerShape(16.dp))
        Button(onClick = { vm.search(value) }, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Search") }
        if (vm.searchHits.isEmpty()) Text(if (vm.query.isBlank()) "Search selectable text. Scanned pages may need OCR first." else "No results. Try another word, or recognize a scanned page.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.searchHits) { hit ->
                Surface(onClick = { vm.goToPage(hit.pageIndex); vm.panel = Panel.NONE }, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Page ${hit.pageIndex + 1}", style = MaterialTheme.typography.labelMedium); Text(hit.excerpt, maxLines = 3, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun ReadPanel(vm: EditorViewModel) {
    var size by remember { mutableStateOf(19f) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", fontSize = 14.sp); Slider(size, { size = it }, valueRange = 14f..30f, modifier = Modifier.weight(1f).semantics { contentDescription = "Reading text size" }); Text("A", fontSize = 28.sp)
        }
        SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth()) {
            Text(vm.readText.ifBlank { "No selectable text on this page. Recognize it with on-device OCR." }, fontSize = size.sp, lineHeight = (size * 1.6f).sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.read(true) }, modifier = Modifier.weight(1f)) { Text("Recognize page") }
            Button(onClick = vm::makeSearchable, modifier = Modifier.weight(1f)) { Text("Make searchable", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Text("OCR supports Latin text. Review recognition before relying on it. Reading view does not change the page layout.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable private fun HistoryPanel(vm: EditorViewModel) {
    val current = vm.current ?: return
    val revisions by produceState<List<PdfRevision>>(emptyList(), current.filePath) { value = withContext(Dispatchers.IO) { vm.store.history(current.id) } }
    Column(Modifier.fillMaxWidth().heightIn(max = 530.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your original and up to 10 saved versions stay on this device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = vm::restoreOriginal, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.RestorePage, null); Spacer(Modifier.width(8.dp)); Text("Restore untouched original") }
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(revisions.reversed(), key = { it.id }) { revision ->
                Surface(onClick = { if (revision.id != current.revisionId) vm.restoreVersion(revision.id) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (revision.id == current.revisionId) Icons.Outlined.CheckCircle else Icons.Outlined.History, null)
                        Column(Modifier.weight(1f)) { Text(SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()).format(Date(revision.createdAt)), style = MaterialTheme.typography.titleSmall); Text("${revision.pageCount} pages", style = MaterialTheme.typography.bodySmall) }
                        if (revision.id == current.revisionId) Text("Current", style = MaterialTheme.typography.labelSmall) else Icon(Icons.Outlined.Restore, "Restore version")
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun UnlockPanel(vm: EditorViewModel, onBuy: () -> Unit) {
    val state by vm.billing.state.collectAsState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PaperIllustration(Modifier.size(146.dp, 132.dp))
        Text(if (state.isEntitled) "Lifetime access is yours." else "One purchase. Every tool.", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("No ads", Icons.Outlined.Check); Pill("No renewal", Icons.Outlined.Check); Pill("Offline tools", Icons.Outlined.Check) }
        if (!state.isEntitled) {
            Text(if (state.expired) "Your trial has ended. Your PDFs are safe: you can still read, recover and export your work." else "${state.trialDaysRemaining} days left in your 30-day trial. No automatic charge when it ends.", style = MaterialTheme.typography.bodyMedium)
            Text(state.priceLabel ?: "Price available from Google Play", style = if (state.priceLabel != null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyMedium)
            Button(onClick = onBuy, enabled = state.billingReady && state.priceLabel != null && !state.pending, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Outlined.LockOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Text(if (state.pending) "Payment pending" else "Unlock forever")
            }
            if (!state.billingReady || state.priceLabel == null) Text("Purchases are available when this app and its product are active on Google Play.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = vm.billing::restore) { Text("Restore purchase") }
        Text("The purchase is linked to your Google Play account. Your documents remain stored on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun ProfilePanel(vm: EditorViewModel) {
    var name by rememberSaveable { mutableStateOf(vm.profile.name) }
    var email by rememberSaveable { mutableStateOf(vm.profile.email) }
    var phone by rememberSaveable { mutableStateOf(vm.profile.phone) }
    var address by rememberSaveable { mutableStateOf(vm.profile.address) }
    var company by rememberSaveable { mutableStateOf(vm.profile.company) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reuse your details in forms. Stored only here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true)
        OutlinedTextField(email, { email = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(phone, { phone = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(address, { address = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Address") }, maxLines = 3)
        OutlinedTextField(company, { company = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Company") }, singleLine = true)
        Button(onClick = { vm.saveProfile(UserProfile(name, email, phone, address, company)) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save details") }
        TextButton(onClick = {
            name = ""; email = ""; phone = ""; address = ""; company = ""
            vm.saveProfile(UserProfile())
        }, modifier = Modifier.fillMaxWidth()) { Text("Clear saved details") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun PrivacyPanel() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your PDFs stay local.", style = MaterialTheme.typography.titleLarge)
        Text("Editing and text recognition run on your device. We do not upload your documents to a PDF service. Sharing or saving to a cloud provider sends only the file you choose.")
        Text("No ads. No account required.", style = MaterialTheme.typography.titleMedium)
        Text("Google Play handles purchases and may download the document scanner on first use. Google's recognition and scanner SDKs can collect device, usage and diagnostic information. There is no advertising SDK.")
        Text("You control your copies.", style = MaterialTheme.typography.titleMedium)
        Text("Originals and edit history are kept in app storage. Trash lets you restore files or permanently delete them. Copies you export elsewhere remain there. Uninstalling deletes local files, so export anything you want to keep.")
        if (BuildConfig.PRIVACY_URL.startsWith("https://")) OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL))) } }, modifier = Modifier.fillMaxWidth()) { Text("Full privacy policy") }
        Text("Support: ${BuildConfig.SUPPORT_EMAIL}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun TrashPanel(vm: EditorViewModel) {
    var deleting by remember { mutableStateOf<DocumentRecord?>(null) }
    var deleteAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (vm.trash.isEmpty()) "Trash is empty." else "Restore a file, or delete its local copies and history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.trash.isNotEmpty()) OutlinedButton(onClick = { deleteAll = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.DeleteForever, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Delete all")
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(vm.trash, key = { it.id }) { record ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Description, null); Spacer(Modifier.width(12.dp)); Text(record.title, Modifier.weight(1f), maxLines = 2)
                    IconAction(Icons.Outlined.RestoreFromTrash, "Restore ${record.title}") { vm.restoreTrash(record) }
                    IconAction(Icons.Outlined.DeleteForever, "Permanently delete ${record.title}") { deleting = record }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete permanently?") }, text = { Text("${record.title}, its original and all local edit history will be deleted. Copies you exported elsewhere are unchanged.") },
        confirmButton = { TextButton(onClick = { deleting = null; vm.deletePermanently(record) }) { Text("Delete permanently") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    if (deleteAll) AlertDialog(onDismissRequest = { deleteAll = false },
        title = { Text("Delete all from Trash?") },
        text = { Text("Permanently delete all ${vm.trash.size} trashed documents, their originals and local edit history? Copies exported elsewhere are unchanged. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { deleteAll = false; vm.emptyTrash() }) { Text("Delete all permanently") } },
        dismissButton = { TextButton(onClick = { deleteAll = false }) { Text("Cancel") } })
}

@Composable private fun RenamePanel(vm: EditorViewModel) {
    val current = vm.current ?: return
    var title by remember { mutableStateOf(current.title.removeSuffix(".pdf")) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(title, { title = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Document name") }, suffix = { Text(".pdf") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Button(onClick = { vm.rename(title) }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save name") }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun CorrectionPanel(vm: EditorViewModel) {
    var replacement by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.FindReplace, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        OutlinedTextField(replacement, { replacement = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text("Replacement text") }, minLines = 2, maxLines = 6, shape = RoundedCornerShape(16.dp))
        Text("Replaces the selected area on a white background. All pages are flattened into images; existing searchable text, form fields and links are removed. Your original stays in History.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.correctRegion(replacement) }, enabled = replacement.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Replace selected area") }
        Spacer(Modifier.height(20.dp))
    }
}
