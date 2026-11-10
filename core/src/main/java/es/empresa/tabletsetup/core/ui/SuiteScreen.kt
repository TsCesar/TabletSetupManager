package es.empresa.tabletsetup.core.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.platform.InstallEvents

/** Abre los ajustes para autorizar la instalación de apps desde esta aplicación. */
fun openUnknownSourcesSettings(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/**
 * Pantalla principal común: resumen del dispositivo, acciones por lotes, catálogo visual de
 * aplicaciones (rejilla adaptable a la resolución de la tablet) y secciones adicionales.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuiteScreen(
    vm: SuiteViewModel,
    title: String,
    subtitle: String,
    selectable: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
    header: @Composable () -> Unit = {},
    footer: LazyGridScope.() -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCatalog by remember { mutableStateOf(false) }
    val configPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCatalogConfig(uri)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh(force = true) }, enabled = !state.checking && !state.running) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Comprobar versiones")
                    }
                    IconButton(onClick = { showCatalog = true }, enabled = !state.running) {
                        Icon(Icons.Filled.Settings, contentDescription = "Catálogo empresarial")
                    }
                    actions()
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 360.dp),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DeviceCard(state, onGrantInstall = { openUnknownSourcesSettings(context) })
            }
            state.message?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) { MessageBanner(message) { vm.dismissMessage() } }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { PendingConfirmationCard(vm) }
            if (state.checking) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("Comprobando versiones y fuentes…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(state.rows, key = { it.packageName }) { row ->
                AppCard(
                    row = row,
                    mode = vm.mode,
                    selectable = selectable && row.packageName != AppCatalog.INSTALLER,
                    selected = row.packageName in state.selected,
                    busy = state.running || state.importing,
                    onToggle = { vm.toggleSelection(row.packageName) },
                    onAction = { vm.installOne(row.packageName) },
                )
            }
            footer()
        }
    }

    state.signerRequest?.let { request -> SignerDialog(request) { accepted -> vm.answerSigner(accepted) } }
    if (showCatalog) {
        CatalogDialog(
            initialUrl = vm.settings.catalogUrl,
            initialKey = vm.settings.catalogPublicKey,
            onSave = { url, key -> vm.saveCatalogConfig(url, key) },
            onImportFile = { configPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onDismiss = { showCatalog = false },
        )
    }
}

/** Botones de proceso por lotes (instalar/actualizar todas, seleccionadas, reintentar, cancelar). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchBar(
    vm: SuiteViewModel,
    allLabel: String,
    selectedLabel: String,
    extra: @Composable () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy = state.running || state.importing || state.checking
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.installAll() }, enabled = !busy && state.plannedAll.isNotEmpty()) {
                    Text("$allLabel (${state.plannedAll.size})")
                }
                OutlinedButton(onClick = { vm.installSelected() }, enabled = !busy && state.plannedSelected.isNotEmpty()) {
                    Text("$selectedLabel (${state.plannedSelected.size})")
                }
                if (state.failedIds.isNotEmpty()) {
                    OutlinedButton(onClick = { vm.retryFailed() }, enabled = !busy) { Text("Reintentar fallidas (${state.failedIds.size})") }
                }
                TextButton(onClick = { vm.selectAllEligible() }, enabled = !busy) { Text("Seleccionar disponibles") }
                if (state.selected.isNotEmpty()) TextButton(onClick = { vm.clearSelection() }, enabled = !busy) { Text("Quitar selección") }
                extra()
            }
            if (state.running) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = state.current?.let { AppCatalog.find(it)?.label } ?: "…"
                    Text("Procesando: $label (una aplicación cada vez)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.cancelBatch() }) { Text("Cancelar") }
                }
            }
        }
    }
}

/** Aviso cuando Android espera la confirmación del técnico (por si se cerró el diálogo). */
@Composable
fun PendingConfirmationCard(vm: SuiteViewModel) {
    val pending by InstallEvents.pending.collectAsStateWithLifecycle()
    val current = pending ?: return
    if (!current.launched) return
    val label = current.packageName?.let { AppCatalog.find(it)?.label ?: it } ?: "la aplicación"
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text("Android espera tu confirmación para instalar $label.", style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { InstallEvents.relaunch(current.sessionId) }) { Text("Mostrar de nuevo") }
                TextButton(onClick = { vm.cancelBatch() }) { Text("Cancelar instalación") }
            }
        }
    }
}
