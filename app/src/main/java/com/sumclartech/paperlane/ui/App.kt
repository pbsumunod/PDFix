package com.sumclartech.paperlane.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.sumclartech.paperlane.*
import com.sumclartech.paperlane.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PaperlaneApp(vm: EditorViewModel, onOpen: (Boolean) -> Unit, onScan: () -> Unit, onImages: () -> Unit, onMerge: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onBuy: () -> Unit) {
    EzTheme {
        val billing by vm.billing.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm.notice) { vm.notice?.let { snackbar.showSnackbar(it); vm.notice = null } }
        LaunchedEffect(billing.message) { billing.message?.let { snackbar.showSnackbar(it); vm.billing.clearMessage() } }
        BackHandler(enabled = vm.current != null || vm.panel != Panel.NONE) {
            if (vm.panel != Panel.NONE) vm.panel = Panel.NONE else vm.closeDocument()
        }
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = { if (vm.current == null) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.pdfix_logo), "PDFix logo", Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
                    Spacer(Modifier.width(10.dp)); Text("PDFix", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Pill(if (billing.isEntitled) "Lifetime" else if (billing.expired) "Unlock" else "${billing.trialDaysRemaining} days left", onClick = { vm.panel = Panel.UNLOCK })
                }
            } },
            bottomBar = { if (vm.current == null) NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                listOf(Triple("Desk", Icons.Outlined.SpaceDashboard, 0), Triple("Tools", Icons.Outlined.GridView, 1), Triple("Settings", Icons.Outlined.Tune, 2)).forEach { (label, icon, index) ->
                    NavigationBarItem(selected = vm.tab == index, onClick = { vm.tab = index }, icon = { Icon(icon, label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                }
            } }, snackbarHost = {
                SnackbarHost(snackbar, Modifier.padding(bottom = if (vm.current != null) 128.dp else 0.dp))
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (vm.current != null) EditorScreen(vm)
                else when (vm.tab) {
                    0 -> DeskScreen(vm, onOpen, onScan, onImages, onMerge)
                    1 -> ToolsScreen(onOpen, onScan, onImages, onMerge, vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
        Panels(vm, onSave, onShare, onBuy)
        if (vm.busy) Dialog(onDismissRequest = {}) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    Text(vm.busyLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
