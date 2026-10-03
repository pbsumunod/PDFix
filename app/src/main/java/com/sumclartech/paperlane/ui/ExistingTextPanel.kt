package com.sumclartech.paperlane.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sumclartech.paperlane.EditorViewModel
import java.util.Locale

@Composable fun ExistingTextPanel(vm: EditorViewModel) {
    val run = vm.selectedTextRun ?: return
    var value by rememberSaveable(run.id) { mutableStateOf(run.text) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.TextFields, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(run.fontName.substringAfter('+'), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Original style · ${String.format(Locale.getDefault(), "%.1f", run.fontSize)} pt",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        OutlinedTextField(value, {
            if (!vm.busy && it.length <= 2000 && it != value) {
                value = it
                vm.existingTextError = null
            }
        }, modifier = Modifier.fillMaxWidth().testTag("existing_text_value"),
            label = { Text("Existing text") }, readOnly = !run.editable || vm.busy,
            minLines = 2, maxLines = 6, isError = vm.existingTextError != null,
            shape = RoundedCornerShape(16.dp))
        val explanation = vm.existingTextError ?: if (!run.editable) run.reason else null
        if (explanation != null) Text(explanation, Modifier.testTag("existing_text_error"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (run.editable) {
            Text("Keeps the original font, size and color. Your new text must fit this block; surrounding text stays in place.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.replaceExistingText(value) }, enabled = value != run.text && !vm.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(if (value.isEmpty()) Icons.Outlined.DeleteOutline else Icons.Outlined.Check, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text(if (value.isEmpty()) "Delete text" else "Save text")
            }
            Text("Text stays selectable. Your original remains in History.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}
