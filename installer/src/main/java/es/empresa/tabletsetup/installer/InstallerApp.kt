package es.empresa.tabletsetup.installer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.engine.versionLabel
import es.empresa.tabletsetup.core.model.InstalledInfo
import es.empresa.tabletsetup.core.platform.HistoryEvent
import es.empresa.tabletsetup.core.platform.QuickSupportProbe
import es.empresa.tabletsetup.core.platform.QuickSupportState
import es.empresa.tabletsetup.core.platform.SuiteEngine
import es.empresa.tabletsetup.core.ui.BatchBar
import es.empresa.tabletsetup.core.ui.HistoryScreen
import es.empresa.tabletsetup.core.ui.LocalStatusColors
import es.empresa.tabletsetup.core.ui.SectionHeader
import es.empresa.tabletsetup.core.ui.SuiteContract
import es.empresa.tabletsetup.core.ui.SuiteScreen
import es.empresa.tabletsetup.core.ui.SuiteViewModel
import es.empresa.tabletsetup.core.ui.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Screen { MAIN, QUICKSUPPORT, HISTORY }

/** Estado de Tablet Updater visto desde el Installer. */
private data class UpdaterCompanion(
    val installed: InstalledInfo?,
    val sameSigner: Boolean,
    val stopped: Boolean,
    val configuredAt: Long,
) {
    val installedOk: Boolean get() = installed != null && sameSigner
    /** Activado = instalado con nuestra firma y abierto al menos una vez (sus comprobaciones periódicas están registradas). */
    val ready: Boolean get() = installedOk && !stopped
}

private fun readCompanion(context: Context): UpdaterCompanion {
    val engine = SuiteEngine.get(context)
    val installed = engine.packages.installed(AppCatalog.UPDATER)
    return UpdaterCompanion(
        installed = installed,
        sameSigner = installed != null && engine.packages.signaturesMatchOwn(AppCatalog.UPDATER),
        stopped = installed != null && engine.packages.isStopped(AppCatalog.UPDATER),
        configuredAt = engine.settings.updaterConfiguredAt,
    )
}

@Composable
fun InstallerApp(vm: SuiteViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Mantener la pantalla encendida mientras se instala por lotes.
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }
    when (screen) {
        Screen.MAIN -> InstallerMain(vm, onQuickSupport = { screen = Screen.QUICKSUPPORT }, onHistory = { screen = Screen.HISTORY })
        Screen.QUICKSUPPORT -> QuickSupportScreen(vm, onBack = { screen = Screen.MAIN })
        Screen.HISTORY -> HistoryScreen(vm, onBack = { screen = Screen.MAIN })
    }
}

@Composable
private fun InstallerMain(vm: SuiteViewModel, onQuickSupport: () -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val companion by produceState<UpdaterCompanion?>(null, resumeTick, state.rows) {
        value = withContext(Dispatchers.IO) { readCompanion(context) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importFiles(uris)
    }
    val configureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            SuiteEngine.get(context).settings.updaterConfiguredAt = System.currentTimeMillis()
        }
        resumeTick++
    }
    var confirmUninstall by remember { mutableStateOf(false) }

    SuiteScreen(
        vm = vm,
        title = "Tablet Setup Installer",
        subtitle = "Preparación inicial · FuelSoft se instala con su propio procedimiento",
        selectable = true,
        actions = {
            IconButton(onClick = onHistory) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Historial") }
        },
        header = {
            BatchBar(vm, allLabel = "Instalar todas las compatibles", selectedLabel = "Instalar seleccionadas") {
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/vnd.android.package-archive", "application/zip", "application/x-zip-compressed", "application/octet-stream")
                        )
                    },
                    enabled = !state.running && !state.importing,
                ) { Text("Importar APK desde archivos") }
            }
        },
        footer = {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Finalizar la preparación", "Instala y activa Tablet Updater, comprueba QuickSupport y retira el instalador.")
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                UpdaterCard(companion, onActivate = {
                    val settings = SuiteEngine.get(context).settings
                    val intent = Intent(SuiteContract.ACTION_CONFIGURE)
                        .setClassName(AppCatalog.UPDATER, SuiteContract.CONFIGURE_ACTIVITY)
                        .putExtra(SuiteContract.EXTRA_CATALOG_URL, settings.catalogUrl)
                        .putExtra(SuiteContract.EXTRA_CATALOG_KEY, settings.catalogPublicKey)
                    try {
                        configureLauncher.launch(intent)
                    } catch (e: Exception) {
                        SuiteEngine.get(context).packages.launchIntent(AppCatalog.UPDATER)?.let { context.startActivity(it) }
                    }
                }, onInstall = { vm.installOne(AppCatalog.UPDATER) }, busy = state.running)
            }
            item(span = { GridItemSpan(maxLineSpan) }) { QuickSupportSummaryCard(state.rows.any { it.packageName == AppCatalog.QUICKSUPPORT && it.status.installed != null }, onQuickSupport) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FinishCard(companion, failed = state.failedIds.size, onUninstall = { confirmUninstall = true })
            }
        },
    )

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Desinstalar Tablet Setup Installer") },
            text = { Text("Tablet Updater está instalado y activado, así que la tablet seguirá recibiendo comprobaciones de actualización. Android pedirá confirmación.") },
            confirmButton = {
                Button(onClick = {
                    confirmUninstall = false
                    context.startActivity(Intent(Intent.ACTION_DELETE, "package:${context.packageName}".toUri()))
                }) { Text("Solicitar desinstalación") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) LocalStatusColors.current.success else LocalStatusColors.current.warning,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UpdaterCard(companion: UpdaterCompanion?, onActivate: () -> Unit, onInstall: () -> Unit, busy: Boolean) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Text("Tablet Updater", style = MaterialTheme.typography.titleMedium)
            Text(
                "Actualizador permanente: comprueba cada 24 h aprox. y avisa de nuevas versiones. No necesita este instalador.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (companion == null) {
                Text("Comprobando…")
                return@Column
            }
            CheckLine(companion.installed != null, companion.installed?.let { "Instalado: " + versionLabel(it.versionName, it.versionCode) } ?: "No instalado")
            if (companion.installed != null) {
                CheckLine(companion.sameSigner, if (companion.sameSigner) "Firmado con la misma clave que el Installer" else "Firma distinta: reinstala el Updater de esta suite")
                CheckLine(!companion.stopped, if (companion.stopped) "Pendiente de activar (no se ha abierto nunca)" else "Activado")
                if (companion.configuredAt > 0) CheckLine(true, "Configuración del catálogo enviada el ${formatDate(companion.configuredAt)}")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (companion.installed == null) {
                    Button(onClick = onInstall, enabled = !busy) { Text("Instalar Tablet Updater") }
                } else {
                    FilledTonalButton(onClick = onActivate, enabled = !busy && companion.sameSigner) { Text("Activar y configurar Updater") }
                }
            }
        }
    }
}

@Composable
private fun QuickSupportSummaryCard(installed: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Text("TeamViewer QuickSupport", style = MaterialTheme.typography.titleMedium)
            Text(
                "Instalar la APK no garantiza el control remoto: comprueba el complemento, los permisos y haz una prueba de conexión.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onOpen) { Text(if (installed) "Comprobar funcionamiento" else "Abrir asistente de QuickSupport") }
        }
    }
}

@Composable
private fun FinishCard(companion: UpdaterCompanion?, failed: Int, onUninstall: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Text("Retirar el instalador", style = MaterialTheme.typography.titleMedium)
            val ready = companion?.ready == true
            CheckLine(companion?.installedOk == true, "Tablet Updater instalado con la firma de la suite")
            CheckLine(ready, "Tablet Updater activado")
            CheckLine(failed == 0, if (failed == 0) "Sin instalaciones fallidas" else "$failed instalación(es) fallida(s): revisa o reintenta")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onUninstall, enabled = ready) { Text("Desinstalar Tablet Setup Installer") }
            if (!ready) {
                Text(
                    "No se permite desinstalar el Installer hasta que Tablet Updater esté instalado y activado.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSupportScreen(vm: SuiteViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { SuiteEngine.get(context) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val qs by produceState<QuickSupportState?>(null, tick, state.rows) {
        value = withContext(Dispatchers.IO) { QuickSupportProbe.probe(context) }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comprobación de QuickSupport") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") } },
            )
        },
    ) { padding ->
        val s = qs
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s == null) {
                Text("Comprobando…")
                return@Column
            }
            StepCard("1. QuickSupport instalado") {
                CheckLine(s.quickSupport != null, s.quickSupport?.let { "Instalado: " + versionLabel(it.versionName, it.versionCode) } ?: "No instalado")
                if (s.quickSupport == null) {
                    Button(onClick = { vm.installOne(AppCatalog.QUICKSUPPORT) }, enabled = !state.running) { Text("Instalar QuickSupport") }
                }
            }
            StepCard("2. Comprobar que funciona") {
                Text("Ábrelo y verifica que muestra un ID de TeamViewer. Acepta sus permisos si los pide.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { launch(context, AppCatalog.QUICKSUPPORT) }, enabled = s.quickSupport != null) { Text("Abrir QuickSupport") }
                    Spacer(Modifier.width(12.dp))
                    var confirmed by remember(s.idConfirmed) { mutableStateOf(s.idConfirmed) }
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it; engine.settings.quickSupportIdConfirmed = it }, enabled = s.quickSupport != null)
                    Text("Muestra un ID", style = MaterialTheme.typography.bodyMedium)
                }
            }
            StepCard("3. Complemento para control remoto") {
                Text(
                    "Fabricante detectado: ${s.manufacturer} ${s.model}. Según TeamViewer, QuickSupport indica al abrirse si existe un " +
                        "complemento específico del fabricante; si no existe, propone el Universal Add-On (Android 7 o superior). " +
                        "Instálalo solo si QuickSupport lo solicita.",
                    style = MaterialTheme.typography.bodySmall,
                )
                CheckLine(s.universalAddon != null, s.universalAddon?.let { "Universal Add-On instalado: " + versionLabel(it.versionName, it.versionCode) } ?: "Universal Add-On no instalado")
                CheckLine(s.addonAccessibilityOn, if (s.addonAccessibilityOn) "Servicio de accesibilidad del complemento ACTIVADO" else "Servicio de accesibilidad del complemento sin activar")
                if (s.accessibilityEnabled.isNotEmpty()) {
                    Text("Servicios detectados: ${s.accessibilityEnabled.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.universalAddon == null) {
                        FilledTonalButton(onClick = { vm.installOne(AppCatalog.QS_UNIVERSAL_ADDON) }, enabled = !state.running) { Text("Instalar Universal Add-On") }
                    }
                }
            }
            StepCard("4. Permisos en Ajustes") {
                Text(
                    "Activa el servicio de accesibilidad del complemento («Universal add-on accessibility service») y acepta la captura de pantalla cuando QuickSupport la pida.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.sdkInt >= 33) {
                    Text(
                        "Android 13+: si Ajustes indica «Ajuste restringido», abre Información de la app del complemento → menú ⋮ → «Permitir ajustes restringidos» y vuelve a activarlo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Ajustes de accesibilidad") }
                    OutlinedButton(onClick = { appDetails(context, AppCatalog.QS_UNIVERSAL_ADDON) }, enabled = s.universalAddon != null) { Text("Info. del complemento") }
                    OutlinedButton(onClick = { appDetails(context, AppCatalog.QUICKSUPPORT) }, enabled = s.quickSupport != null) { Text("Info. de QuickSupport") }
                }
            }
            StepCard("5. Prueba manual de conexión remota") {
                Text(
                    "Desde un equipo con TeamViewer, conéctate con el ID que muestra QuickSupport. Comprueba que ves la pantalla y que puedes tocar y escribir en la tablet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val status = when {
                    s.remoteTestAt == 0L -> "Control remoto NO verificado (la instalación por sí sola no lo garantiza)"
                    s.remoteTestOk -> "Control remoto verificado el ${formatDate(s.remoteTestAt)}"
                    else -> "Última prueba FALLIDA el ${formatDate(s.remoteTestAt)}"
                }
                CheckLine(s.remoteVerified, status)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { recordTest(context, true); tick++ }, enabled = s.quickSupport != null) { Text("La prueba ha funcionado") }
                    OutlinedButton(onClick = { recordTest(context, false); tick++ }, enabled = s.quickSupport != null) { Text("La prueba ha fallado") }
                }
            }
        }
    }
}

@Composable
private fun StepCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun recordTest(context: Context, ok: Boolean) {
    val engine = SuiteEngine.get(context)
    engine.settings.remoteTestAt = System.currentTimeMillis()
    engine.settings.remoteTestOk = ok
    engine.history.append(
        HistoryEvent(
            System.currentTimeMillis(), AppCatalog.QUICKSUPPORT, "TeamViewer QuickSupport", "Prueba de control remoto", ok,
            if (ok) "Prueba manual de conexión remota correcta" else "Prueba manual de conexión remota fallida",
        )
    )
}

private fun launch(context: Context, packageName: String) {
    SuiteEngine.get(context).packages.launchIntent(packageName)?.let { open(context, it) }
}

private fun appDetails(context: Context, packageName: String) =
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Ajuste no disponible en este fabricante.
    }
}
