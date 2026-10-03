package com.sumclartech.paperlane.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sumclartech.paperlane.EditorTool
import com.sumclartech.paperlane.EditorViewModel
import com.sumclartech.paperlane.Panel
import com.sumclartech.paperlane.data.UserProfile
import com.sumclartech.paperlane.pdf.FormFieldInfo
import com.sumclartech.paperlane.pdf.FormFieldKind
import java.io.File

@Composable fun PagesPanel(vm: EditorViewModel) {
    val record = vm.current ?: return
    val info = vm.info ?: return
    var order by remember(record.revisionId) { mutableStateOf(info.pages.indices.toList()) }
    var selected by remember(record.revisionId) { mutableStateOf(emptySet<Int>()) }
    var rotations by remember(record.revisionId) { mutableStateOf(emptyMap<Int, Int>()) }
    val changed = order != info.pages.indices.toList() || rotations.values.any { it != 0 }

    Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (selected.isEmpty()) "${order.size} pages" else "${selected.size} selected", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { selected = if (selected.size == order.size) emptySet() else order.toSet() }) {
                Text(if (selected.size == order.size) "Clear selection" else "Select all")
            }
        }
        if (info.formFields.isNotEmpty()) {
            Text("Saving pages makes form answers permanent; fields become noninteractive.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(order, key = { _, pageIndex -> pageIndex }) { position, pageIndex ->
                val checked = pageIndex in selected
                val rotation = rotations[pageIndex] ?: 0
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(if (checked) 2.dp else 1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(.7f).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(Color.White).clickable {
                            selected = if (checked) selected - pageIndex else selected + pageIndex
                        }) {
                            PdfThumbnail(vm.engine, File(record.filePath), pageIndex,
                                Modifier.fillMaxSize().padding(top = 32.dp, bottom = 8.dp, start = 6.dp, end = 6.dp).graphicsLayer {
                                    rotationZ = rotation.toFloat()
                                    if (rotation % 180 != 0) { scaleX = .66f; scaleY = .66f }
                                })
                            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween) {
                                Checkbox(checked = checked, onCheckedChange = { selected = if (it) selected + pageIndex else selected - pageIndex })
                                IconButton(onClick = { rotations = rotations + (pageIndex to ((rotation + 90) % 360)) }) {
                                    Icon(Icons.Outlined.RotateRight, "Rotate page ${pageIndex + 1} clockwise", Modifier.size(20.dp), tint = Green)
                                }
                            }
                        }
                        Text("Page ${pageIndex + 1}", modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp), style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = {
                                order = order.toMutableList().apply { add(position - 1, removeAt(position)) }
                            }, enabled = position > 0, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Move page ${pageIndex + 1} earlier", Modifier.size(19.dp))
                            }
                            IconButton(onClick = {
                                order = order.toMutableList().apply { add(position + 1, removeAt(position)) }
                            }, enabled = position < order.lastIndex, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Move page ${pageIndex + 1} later", Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                vm.extract(order.filter { it in selected }, rotations)
            }, enabled = selected.isNotEmpty() && !vm.busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Extract")
            }
            OutlinedButton(onClick = {
                order = order.filterNot { it in selected }
                rotations = rotations.filterKeys { it in order }
                selected = emptySet()
            }, enabled = selected.isNotEmpty() && selected.size < order.size && !vm.busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove")
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { order = info.pages.indices.toList(); rotations = emptyMap(); selected = emptySet() }, enabled = changed) { Text("Reset") }
            Button(onClick = { vm.arrange(order, rotations) }, enabled = changed && !vm.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Save pages")
            }
        }
    }
}

@Composable fun FormPanel(vm: EditorViewModel) {
    val record = vm.current ?: return
    val info = vm.info ?: return
    val fields = remember(record.revisionId, info) {
        info.formFields.filter { field ->
            !field.readOnly && (field.kind == FormFieldKind.TEXT || field.kind == FormFieldKind.CHECKBOX ||
                ((field.kind == FormFieldKind.CHOICE || field.kind == FormFieldKind.RADIO) && field.options.isNotEmpty()))
        }
    }
    if (info.hasXfa || fields.isEmpty() || info.encrypted || info.signed) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Assignment, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
            Text(when {
                info.hasXfa -> "This form needs a compatible desktop app."
                info.signed -> "This document has a digital signature. Use an unsigned copy to fill it."
                info.encrypted -> "Use an unlocked copy to fill this PDF."
                else -> "No editable fields found"
            }, style = MaterialTheme.typography.titleMedium)
            if (!info.hasXfa && !info.signed && !info.encrypted) {
                Text("Place your answers anywhere on the page.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.tool = EditorTool.TEXT; vm.panel = Panel.TEXT }) {
                    Icon(Icons.Outlined.TextFields, null); Spacer(Modifier.width(8.dp)); Text("Add text")
                }
            }
        }
        return
    }

    if (vm.formDraftRevisionId != "${record.id}:${record.revisionId}") {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    DisposableEffect(record.id, record.revisionId) { onDispose { vm.flushFormDraft() } }
    val values = vm.formDraft
    val index by vm.formDraftQuestion
    var error by remember(record.revisionId) { mutableStateOf<String?>(null) }
    var profileNotice by remember(record.revisionId) { mutableStateOf<String?>(null) }
    val field = fields[index.coerceIn(fields.indices)]
    val value = values[field.name].orEmpty()
    val profile = vm.profile
    val hasProfile = listOf(profile.name, profile.email, profile.phone, profile.address, profile.company).any { it.isNotBlank() }
    val dirty = fields.any { values[it.name].orEmpty() != it.value }

    Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1} / ${fields.size}", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                if (!hasProfile) vm.panel = Panel.PROFILE
                else {
                    var matches = 0
                    fields.filter { it.kind == FormFieldKind.TEXT && values[it.name].isNullOrBlank() }.forEach { candidate ->
                        profileMatch(candidate, profile)?.takeIf { it.isNotBlank() }?.let { vm.updateFormAnswer(candidate.name, it); matches++ }
                    }
                    profileNotice = if (matches > 0) "$matches answers added. Review before saving." else "No empty fields matched your saved details."
                }
            }) {
                Icon(Icons.Outlined.PersonOutline, null, Modifier.size(19.dp)); Spacer(Modifier.width(4.dp)); Text(if (hasProfile) "Use my details" else "My details")
            }
        }
        LinearProgressIndicator(progress = { (index + 1f) / fields.size }, modifier = Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            profileNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Text(field.label + if (field.required) " *" else "", style = MaterialTheme.typography.headlineMedium)
            if (field.pageIndex != null) Text("Page ${field.pageIndex + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (field.kind) {
                FormFieldKind.TEXT -> OutlinedTextField(
                    value = value,
                    onValueChange = { vm.updateFormAnswer(field.name, it); error = null },
                    modifier = Modifier.fillMaxWidth().testTag("form_answer"),
                    placeholder = { Text("Your answer") },
                    minLines = if (field.multiline) 4 else 1,
                    maxLines = if (field.multiline) 8 else 1,
                    singleLine = !field.multiline,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = when {
                        field.label.contains("email", true) || field.name.contains("email", true) -> KeyboardType.Email
                        field.label.contains("phone", true) || field.name.contains("phone", true) -> KeyboardType.Phone
                        else -> KeyboardType.Text
                    }),
                    shape = RoundedCornerShape(16.dp),
                )
                FormFieldKind.CHECKBOX -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().clickable { vm.updateFormAnswer(field.name, (!value.equals("true", true)).toString()); error = null }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = value.equals("true", true), onCheckedChange = { vm.updateFormAnswer(field.name, it.toString()); error = null })
                        Text(if (value.equals("true", true)) "Checked" else "Not checked", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                FormFieldKind.CHOICE, FormFieldKind.RADIO -> field.options.forEach { option ->
                    Surface(shape = RoundedCornerShape(16.dp), color = if (value == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.fillMaxWidth().clickable { vm.updateFormAnswer(field.name, option); error = null }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = value == option, onClick = { vm.updateFormAnswer(field.name, option); error = null })
                            Text(field.optionLabels[option] ?: option, modifier = Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                else -> Unit
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (info.formFields.size > fields.size) Text("Some field types are read-only here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.updateFormQuestion(index - 1); error = null }, enabled = index > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous question")
            }
            Button(onClick = {
                val missing = fields.indexOfFirst { requiredMissing(it, values[it.name].orEmpty()) }
                if (index < fields.lastIndex) {
                    if (requiredMissing(field, value)) error = "This answer is required."
                    else { vm.updateFormQuestion(index + 1); error = null }
                } else if (missing >= 0) {
                    vm.updateFormQuestion(missing); error = "This answer is required."
                } else {
                    val changed = fields.filter { values[it.name].orEmpty() != it.value }.associate { it.name to values[it.name].orEmpty() }
                    if (changed.isEmpty()) { vm.flushFormDraft(); vm.panel = Panel.NONE; vm.notice = "Your form is already saved." }
                    else vm.fill(changed)
                }
            }, enabled = !vm.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (index == fields.lastIndex) "Save answers" else "Next")
                Spacer(Modifier.width(8.dp))
                Icon(if (index == fields.lastIndex) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
            }
        }
        TextButton(onClick = { vm.panel = Panel.SIGNATURE }, enabled = !dirty, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)) {
            Icon(Icons.Outlined.Draw, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (dirty) "Save answers before signing" else "Draw a signature")
        }
    }
}

private fun requiredMissing(field: FormFieldInfo, value: String): Boolean =
    field.required && (value.isBlank() || (field.kind == FormFieldKind.CHECKBOX && !value.equals("true", true)))

/** Only clear, whole field labels match. Never infer first/last names or overwrite an existing answer. */
private fun profileMatch(field: FormFieldInfo, profile: UserProfile): String? {
    val labels = listOf(field.name, field.label).map { it.lowercase().replace(Regex("[^a-z]"), "") }
    return when {
        labels.any { it in setOf("name", "fullname", "yourname", "applicantname") } -> profile.name
        labels.any { it in setOf("email", "emailaddress", "youremail") } -> profile.email
        labels.any { it in setOf("phone", "phonenumber", "telephone", "mobile", "mobilenumber") } -> profile.phone
        labels.any { it in setOf("address", "mailingaddress", "postaladdress", "youraddress") } -> profile.address
        labels.any { it in setOf("company", "companyname", "organization", "organisation") } -> profile.company
        else -> null
    }
}
