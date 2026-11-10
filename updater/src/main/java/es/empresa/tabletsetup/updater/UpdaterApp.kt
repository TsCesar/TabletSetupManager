package es.empresa.tabletsetup.updater

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.empresa.tabletsetup.core.model.AppState
import es.empresa.tabletsetup.core.platform.SuiteSettings
import es.empresa.tabletsetup.core.ui.BatchBar
import es.empresa.tabletsetup.core.ui.HistoryScreen
import es.empresa.tabletsetup.core.ui.SectionHeader
import es.empresa.tabletsetup.core.ui.SuiteScreen
import es.empresa.tabletsetup.core.ui.SuiteViewModel
import es.empresa.tabletsetup.core.ui.formatDate

private enum class UpdaterScreen { MAIN, SETTINGS, HISTORY }

@Composable
fun UpdaterApp(vm: SuiteViewModel, onScheduleChanged: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(UpdaterScreen.MAIN) }
    when (screen) {
        UpdaterScreen.MAIN -> UpdaterMain(vm, onSettings = { screen = UpdaterScreen.SETTINGS }, onHistory = { screen = UpdaterScreen.HISTORY })
        UpdaterScreen.SETTINGS -> UpdaterSettings(vm, onScheduleChanged, onBack = { screen = UpdaterScreen.MAIN }, onHistory = { screen = UpdaterScreen.HISTORY })
        UpdaterScreen.HISTORY -> HistoryScreen(vm, onBack = { screen = UpdaterScreen.MAIN })
    }
}

@Composable
private fun UpdaterMain(vm: SuiteViewModel, onSettings: () -> Unit, onHistory: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = vm.settings
    val updates = state.rows.count { it.status.installed != null && it.status.state == AppState.ACTUALIZACION }
    SuiteScreen(
        vm = vm,
        title = "Tablet Updater",
        subtitle = if (updates == 0) "Consulta, revisa e instala actualizaciones autorizadas" else "$updates actualización(es) compatible(s) disponible(s)",
        selectable = true,
        actions = {
            IconButton(onClick = onHistory) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Historial") }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Notifications, contentDescription = "Frecuencia y avisos") }
        },
        header = {
            Column {
                Text(
                    "Comprobación automática aprox. cada ${settings.checkIntervalHours} h" +
                        (if (settings.wifiOnly) " (solo Wi-Fi)" else "") +
                        " · Avisos " + (if (settings.notificationsEnabled) "activados" else "desactivados") +
                        " · Última: " + formatDate(settings.lastCheckAt) +
                        (settings.lastCheckSummary.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                BatchBar(vm, allLabel = "Actualizar todo", selectedLabel = "Actualizar seleccionadas")
            }
        },
        footer = {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    "Solo se actualizan aplicaciones ya instaladas",
                    "El Updater no instala aplicaciones nuevas y siempre usa la confirmación que exige Android.",
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdaterSettings(vm: SuiteViewModel, onScheduleChanged: () -> Unit, onBack: () -> Unit, onHistory: () -> Unit) {
    val settings = vm.settings
    var interval by remember { mutableIntStateOf(settings.checkIntervalHours) }
    var notify by remember { mutableStateOf(settings.notificationsEnabled) }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Frecuencia y avisos") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Frecuencia de comprobación", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "WorkManager ejecuta la comprobación de forma aproximada, según batería y red; no a una hora exacta.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SuiteSettings.INTERVAL_OPTIONS.forEach { hours ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = interval == hours, role = Role.RadioButton, onClick = {
                                interval = hours
                                settings.checkIntervalHours = hours
                                onScheduleChanged()
                            }).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = interval == hours, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (hours) {
                                    24 -> "Cada 24 horas (recomendado)"
                                    48, 72 -> "Cada ${hours / 24} días"
                                    else -> "Cada $hours horas"
                                }
                            )
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitch("Notificar nuevas versiones compatibles", notify) {
                        notify = it
                        settings.notificationsEnabled = it
                    }
                    SettingSwitch("Comprobar solo con Wi-Fi (sin datos móviles)", wifiOnly) {
                        wifiOnly = it
                        settings.wifiOnly = it
                        onScheduleChanged()
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Última comprobación: ${formatDate(settings.lastCheckAt)}", style = MaterialTheme.typography.bodyMedium)
                    if (settings.lastCheckSummary.isNotBlank()) Text(settings.lastCheckSummary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(onClick = onHistory) { Text("Ver historial de resultados") }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
