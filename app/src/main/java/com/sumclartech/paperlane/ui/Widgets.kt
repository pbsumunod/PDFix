package com.sumclartech.paperlane.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sumclartech.paperlane.pdf.PdfEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun IconAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, label, Modifier.size(23.dp)) }
}
@Composable fun Pill(text: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Surface(onClick = onClick ?: {}, enabled = onClick != null, color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(30.dp),
        modifier = Modifier.heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            icon?.let { Icon(it, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
@Composable fun PdfThumbnail(engine: PdfEngine, file: File, page: Int = 0, modifier: Modifier = Modifier, maxEdge: Int = 350) {
    val bitmap by produceState<Bitmap?>(null, file.absolutePath, page, maxEdge) {
        value = withContext(Dispatchers.IO) { runCatching { engine.render(file, page, maxEdge) }.getOrNull() }
    }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "Page ${page + 1} preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Icon(Icons.Outlined.Description, "PDF preview", tint = Green.copy(alpha = .3f), modifier = Modifier.size(36.dp))
    }
}
@Composable fun ToolTile(title: String, subtitle: String, icon: ImageVector, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, color = tint, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp).heightIn(min = 108.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Icon(icon, null, Modifier.size(32.dp), tint = Ink)
                Icon(Icons.Outlined.NorthEast, null, Modifier.size(17.dp), tint = Ink.copy(alpha = .5f))
            }
            Spacer(Modifier.height(20.dp))
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Ink, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable fun PaperIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xFFE5EDDB), radius = w * .44f, center = Offset(w * .51f, h * .55f))
        val paper = Size(w * .52f, h * .78f)
        drawRoundRect(Color(0xFFC4D4B8), Offset(w * .21f, h * .13f), paper, androidx.compose.ui.geometry.CornerRadius(12f))
        drawRoundRect(Color.White, Offset(w * .27f, h * .07f), paper, androidx.compose.ui.geometry.CornerRadius(12f))
        val left = w * .35f
        drawRoundRect(Color(0xFF355E49), Offset(left, h * .20f), Size(w * .17f, h * .045f), androidx.compose.ui.geometry.CornerRadius(4f))
        for (i in 0..3) drawLine(Color(0xFFDDE2D9), Offset(left, h * (.34f + i * .10f)), Offset(w * (if (i == 3) .59f else .70f), h * (.34f + i * .10f)), strokeWidth = 5f, cap = StrokeCap.Round)
        drawCircle(Color(0xFF355E49), w * .15f, Offset(w * .74f, h * .75f))
        val path = Path().apply { moveTo(w * .67f, h * .75f); lineTo(w * .72f, h * .81f); lineTo(w * .82f, h * .68f) }
        drawPath(path, Color.White, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
@Composable fun SectionLabel(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null) {
            if (onAction == null) Text(action, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else TextButton(onClick = onAction) { Text(action) }
        }
    }
}
@Composable fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}
fun humanSize(bytes: Long): String = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1048576.0) else "${(bytes / 1024).coerceAtLeast(1)} KB"
