package com.sumclartech.paperlane.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sumclartech.paperlane.*
import com.sumclartech.paperlane.pdf.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable fun EditorScreen(vm: EditorViewModel) {
    val document = vm.current ?: return
    val detail = vm.info ?: return
    var zoom by remember(document.id, vm.page) { mutableStateOf(1f) }
    var offset by remember(document.id, vm.page) { mutableStateOf(Offset.Zero) }
    var confirmTrash by remember(document.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Outlined.ArrowBack, "Back to desk", !vm.busy, vm::closeDocument)
            Column(Modifier.weight(1f).clickable { vm.panel = Panel.RENAME }) {
                Text(document.title.removeSuffix(".pdf"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp)); Text("Saved on this device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconAction(Icons.Outlined.Undo, "Undo last edit", !vm.busy, vm::undo)
            IconAction(Icons.Outlined.IosShare, "Save or share PDF", !vm.busy) { vm.clearExport(); vm.panel = Panel.EXPORT }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
                val bitmap by produceState<Bitmap?>(null, document.filePath, vm.page) {
                    value = withContext(Dispatchers.IO) { runCatching { vm.engine.render(File(document.filePath), vm.page, 1800) }.getOrNull() }
                }
                val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (detail.pages[vm.page].width / detail.pages[vm.page].height)
                val width = minOf(maxWidth, maxHeight * ratio)
                val height = width / ratio
                Box(Modifier.fillMaxSize().pointerInput(vm.tool) {
                    if (vm.tool == EditorTool.MOVE) detectTransformGestures { _, pan, factor, _ ->
                        zoom = (zoom * factor).coerceIn(1f, 4f)
                        offset = if (zoom == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2), (offset.y + pan.y).coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2))
                    }
                }, contentAlignment = Alignment.Center) {
                    PageCanvas(vm, bitmap, Modifier.size(width, height).graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                        shadowElevation = 7.dp.toPx()
                    })
                }
            }
            if (vm.tool != EditorTool.MOVE) Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), shadowElevation = 2.dp) {
                Text(when (vm.tool) {
                    EditorTool.EDIT_TEXT -> if (vm.editableTextRuns.isEmpty()) "No editable text on this page" else "Tap an outlined text block"
                    EditorTool.TEXT -> "Tap to place your text"
                    EditorTool.SIGN -> "Tap to place your signature"
                    EditorTool.PEN -> "Draw on the page"
                    EditorTool.HIGHLIGHT -> "Drag across an area"
                    EditorTool.REDACT -> "Drag over content to remove"
                    EditorTool.CROP -> "Drag around the area to keep"
                    EditorTool.CORRECT -> "Drag over the text to replace"
                    else -> ""
                }, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium)
            }
            if (zoom > 1f) FilledTonalButton(onClick = { zoom = 1f; offset = Offset.Zero }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), contentPadding = PaddingValues(10.dp)) {
                Icon(Icons.Outlined.FitScreen, "Fit page", Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Fit")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.panel = Panel.PAGES }, label = { Text("Pages") }, leadingIcon = { Icon(Icons.Outlined.Dashboard, null, Modifier.size(17.dp)) })
            AssistChip(onClick = { vm.panel = Panel.FORM }, label = { Text("Fill form") }, leadingIcon = { Icon(Icons.Outlined.Checklist, null, Modifier.size(17.dp)) })
            AssistChip(onClick = { vm.panel = Panel.SEARCH }, label = { Text("Find") }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(17.dp)) })
            AssistChip(onClick = { vm.read() }, label = { Text("Read") }, leadingIcon = { Icon(Icons.Outlined.MenuBook, null, Modifier.size(17.dp)) })
            AssistChip(onClick = { vm.panel = Panel.HISTORY }, label = { Text("History") }, leadingIcon = { Icon(Icons.Outlined.History, null, Modifier.size(17.dp)) })
            AssistChip(onClick = { confirmTrash = true }, label = { Text("Trash") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(17.dp)) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconAction(Icons.Outlined.ChevronLeft, "Previous page", vm.page > 0) { vm.goToPage(vm.page - 1) }
            TextButton(onClick = { vm.panel = Panel.PAGES }) { Text("${vm.page + 1} / ${detail.pageCount}", style = MaterialTheme.typography.labelLarge) }
            IconAction(Icons.Outlined.ChevronRight, "Next page", vm.page + 1 < detail.pageCount) { vm.goToPage(vm.page + 1) }
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Outlined.Remove, "Zoom out", zoom > 1f) { zoom = max(1f, zoom - .5f); if (zoom == 1f) offset = Offset.Zero }
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            IconAction(Icons.Outlined.Add, "Zoom in", zoom < 4f) { zoom = min(4f, zoom + .5f) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val items = listOf(Triple(EditorTool.MOVE, "Move", Icons.Outlined.PanToolAlt), Triple(EditorTool.EDIT_TEXT, "Edit text", Icons.Outlined.EditNote), Triple(EditorTool.TEXT, "Add text", Icons.Outlined.Title),
                Triple(EditorTool.PEN, "Draw", Icons.Outlined.Edit), Triple(EditorTool.HIGHLIGHT, "Highlight", Icons.Outlined.BorderColor),
                Triple(EditorTool.SIGN, "Sign", Icons.Outlined.Draw), Triple(EditorTool.CORRECT, "Replace area", Icons.Outlined.FindReplace), Triple(EditorTool.REDACT, "Redact", Icons.Outlined.VisibilityOff), Triple(EditorTool.CROP, "Crop", Icons.Outlined.Crop))
            items.forEach { (tool, label, icon) ->
                Surface(onClick = {
                    if (tool != EditorTool.MOVE && !vm.billing.canEditNow()) vm.panel = Panel.UNLOCK
                    else if (tool == EditorTool.EDIT_TEXT) vm.beginTextEditing()
                    else { vm.selection = null; vm.tool = tool; when (tool) { EditorTool.TEXT -> vm.panel = Panel.TEXT; EditorTool.SIGN -> vm.panel = Panel.SIGNATURE; else -> Unit } }
                }, shape = RoundedCornerShape(16.dp), color = if (vm.tool == tool) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Column(Modifier.widthIn(min = 56.dp).padding(horizontal = 9.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(icon, label, Modifier.size(23.dp), tint = if (vm.tool == tool) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = if (vm.tool == tool) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    if (confirmTrash) AlertDialog(onDismissRequest = { confirmTrash = false },
        title = { Text("Move to trash?") },
        text = { Text("Move ${document.title} to Trash? You can restore it from Settings.") },
        confirmButton = { TextButton(onClick = { confirmTrash = false; vm.moveToTrash(document) }) { Text("Move to trash") } },
        dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } })
}

@Composable private fun PageCanvas(vm: EditorViewModel, bitmap: Bitmap?, modifier: Modifier) {
    var points by remember(vm.current?.filePath, vm.page, vm.tool) { mutableStateOf<List<NormalizedPoint>>(emptyList()) }
    val touch = when (vm.tool) {
        EditorTool.EDIT_TEXT -> Modifier.pointerInput(vm.editableTextRuns) {
            detectTapGestures { p -> vm.selectExistingTextAt(p.x / size.width, p.y / size.height) }
        }
        EditorTool.TEXT, EditorTool.SIGN -> Modifier.pointerInput(vm.tool, vm.textToPlace, vm.signature) { detectTapGestures { p -> vm.placeAt((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f)) } }
        EditorTool.PEN, EditorTool.HIGHLIGHT, EditorTool.REDACT, EditorTool.CROP, EditorTool.CORRECT -> Modifier.pointerInput(vm.tool, vm.current?.filePath, vm.page) {
            fun point(p: Offset) = NormalizedPoint((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
            detectDragGestures(onDragStart = { points = listOf(point(it)) }, onDrag = { change, _ ->
                change.consume(); if (points.size < 3000) points = points + point(change.position)
            }, onDragCancel = { points = emptyList() }, onDragEnd = {
                if (points.size > 1) {
                    if (vm.tool == EditorTool.PEN) vm.addAnnotation(PageAnnotation.Ink(points, color = vm.inkColor))
                    else {
                        val a = points.first(); val b = points.last()
                        if (abs(a.x - b.x) > .004f && abs(a.y - b.y) > .004f) {
                            val rect = NormalizedRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                            when (vm.tool) {
                                EditorTool.HIGHLIGHT -> vm.addAnnotation(PageAnnotation.Highlight(rect))
                                EditorTool.REDACT -> { vm.selection = rect; vm.panel = Panel.REDACT_CONFIRM }
                                EditorTool.CROP -> { vm.selection = rect; vm.panel = Panel.CROP_CONFIRM }
                                EditorTool.CORRECT -> { vm.selection = rect; vm.panel = Panel.CORRECT }
                                else -> Unit
                            }
                        }
                    }
                }
                points = emptyList()
            })
        }
        else -> Modifier
    }
    Box(modifier.testTag("pdf_page").background(Color.White).then(touch)) {
        if (bitmap == null) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.Center), strokeWidth = 2.dp)
        else Image(bitmap.asImageBitmap(), "Document page ${vm.page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            if (points.size > 1) {
                if (vm.tool == EditorTool.PEN) {
                    val path = Path().apply { points.forEachIndexed { index, p -> if (index == 0) moveTo(p.x * size.width, p.y * size.height) else lineTo(p.x * size.width, p.y * size.height) } }
                    drawPath(path, Color(vm.inkColor), style = Stroke(width = size.width * .004f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    val a = points.first(); val b = points.last()
                    drawRect(if (vm.tool == EditorTool.REDACT) Color.Black.copy(alpha = .8f) else if (vm.tool == EditorTool.HIGHLIGHT) Color(0x66FFD54F) else Green.copy(alpha = .25f),
                        Offset(min(a.x, b.x) * size.width, min(a.y, b.y) * size.height), androidx.compose.ui.geometry.Size(abs(a.x - b.x) * size.width, abs(a.y - b.y) * size.height))
                }
            }
            vm.selection?.let { r ->
                drawRect(if (vm.tool == EditorTool.REDACT) Color.Black.copy(alpha = .8f) else Green.copy(alpha = .2f), Offset(r.left * size.width, r.top * size.height), androidx.compose.ui.geometry.Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height))
            }
        }
        if (vm.tool == EditorTool.EDIT_TEXT && bitmap != null) BoxWithConstraints(Modifier.fillMaxSize()) {
            vm.editableTextRuns.forEach { run ->
                val r = run.rect
                val tint = if (run.editable) Green else Color(0xFF94633F)
                Box(Modifier.offset(x = maxWidth * r.left, y = maxHeight * r.top)
                    .size(maxWidth * (r.right - r.left), maxHeight * (r.bottom - r.top))
                    .background(tint.copy(alpha = .07f)).border(1.dp, tint.copy(alpha = .7f))
                    .clickable { vm.selectExistingText(run) }
                    .semantics { contentDescription = "${if (run.editable) "Edit text" else "Unavailable text"}: ${run.text.take(160)}" })
            }
        }
    }
}
