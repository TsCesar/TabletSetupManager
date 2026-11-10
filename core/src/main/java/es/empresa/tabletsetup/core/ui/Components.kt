package es.empresa.tabletsetup.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import es.empresa.tabletsetup.core.engine.TaskPhase
import es.empresa.tabletsetup.core.engine.versionLabel
import es.empresa.tabletsetup.core.model.AppState
import es.empresa.tabletsetup.core.model.ManagedApp
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.util.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val SPANISH: Locale = Locale.forLanguageTag("es-ES")

fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) String.format(SPANISH, "%.1f MB", mb) else String.format(SPANISH, "%d KB", bytes / 1024)
}

fun formatDate(millis: Long): String = if (millis <= 0) "nunca" else
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, SPANISH).format(Date(millis))

/** Icono real de la app instalada o avatar con iniciales. */
@Composable
fun AppAvatar(app: ManagedApp, installed: Boolean, size: Dp = 44.dp) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, app.packageName, installed) {
        value = if (!installed) null else withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)))
    } else {
        Box(
            modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color(app.avatarColor)),
            contentAlignment = Alignment.Center,
        ) {
            Text(app.initials, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }
    }
}

private data class BadgeStyle(val text: String, val container: Color, val content: Color)

@Composable
private fun badgeStyle(state: AppState): BadgeStyle {
    val s = LocalStatusColors.current
    val c = MaterialTheme.colorScheme
    return when (state) {
        AppState.AL_DIA -> BadgeStyle("Al día", s.successContainer, s.success)
        AppState.ACTUALIZACION -> BadgeStyle("Actualización", s.infoContainer, s.info)
        AppState.NO_INSTALADA -> BadgeStyle("No instalada", c.primaryContainer, c.onPrimaryContainer)
        AppState.POR_VERIFICAR -> BadgeStyle("Por verificar", s.infoContainer, s.info)
        AppState.OMITIDA -> BadgeStyle("Omitida", s.neutralContainer, c.onSurfaceVariant)
        AppState.NO_COMPATIBLE -> BadgeStyle("No compatible", s.warningContainer, s.warning)
        AppState.FUENTE_PENDIENTE -> BadgeStyle("Fuente pendiente", s.warningContainer, s.warning)
        AppState.INSTALADA_SIN_FUENTE -> BadgeStyle("Instalada · sin fuente", s.neutralContainer, c.onSurfaceVariant)
        AppState.ERROR -> BadgeStyle("Error", c.errorContainer, c.onErrorContainer)
    }
}

@Composable
fun StatusBadge(state: AppState) {
    val style = badgeStyle(state)
    Surface(color = style.container, contentColor = style.content, shape = RoundedCornerShape(50)) {
        Text(style.text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1)
    }
}

@Composable
fun PhaseLine(phase: TaskPhase) {
    Column(Modifier.fillMaxWidth()) {
        val text = when (phase) {
            TaskPhase.Queued -> "En cola"
            TaskPhase.Resolving -> "Preparando la descarga…"
            is TaskPhase.Downloading -> "Descargando " + formatBytes(phase.bytes) + (phase.total?.let { " de " + formatBytes(it) } ?: "")
            TaskPhase.Verifying -> "Verificando paquete, versión, arquitectura y firma…"
            TaskPhase.Installing -> "Preparando la sesión de instalación…"
            TaskPhase.WaitingUser -> "Esperando la confirmación de Android…"
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        val fraction = (phase as? TaskPhase.Downloading)?.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else if (phase != TaskPhase.Queued) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Tarjeta de una aplicación del catálogo. */
@Composable
fun AppCard(
    row: AppRowState,
    mode: Mode,
    selectable: Boolean,
    selected: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onAction: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val status = row.status
    val app = status.app
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectable) {
                    Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !busy)
                }
                AppAvatar(app, installed = status.installed != null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label + if (app.optional) " · opcional" else "",
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(app.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(status.state)
            }
            Spacer(Modifier.height(10.dp))
            Text(status.message, style = MaterialTheme.typography.bodyMedium)
            val installed = status.installed?.let { versionLabel(it.versionName, it.versionCode) } ?: "—"
            val available = status.release?.let { versionLabel(it.versionName, it.versionCode) } ?: "—"
            val source = status.release?.originLabel ?: "sin fuente"
            Text(
                "Instalada: $installed  ·  Disponible: $available  ·  Fuente: $source",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.phase?.let {
                Spacer(Modifier.height(8.dp))
                PhaseLine(it)
            }
            row.outcome?.let { outcome ->
                Spacer(Modifier.height(6.dp))
                val color = if (outcome.success) LocalStatusColors.current.success else if (outcome.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (outcome.success) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null, tint = color, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(outcome.message, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
            extra()
            val actionText = actionLabel(status.state, mode, status.installed != null, failed = row.outcome?.let { !it.success && !it.skipped } == true)
            if (actionText != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = onAction, enabled = !busy) { Text(actionText) }
                }
            }
        }
    }
}

fun actionLabel(state: AppState, mode: Mode, installed: Boolean, failed: Boolean): String? = when {
    mode == Mode.UPDATER && !installed -> null
    state == AppState.ACTUALIZACION -> if (failed) "Reintentar" else "Actualizar"
    state == AppState.NO_INSTALADA -> if (failed) "Reintentar" else "Instalar"
    state == AppState.POR_VERIFICAR -> if (failed) "Reintentar" else "Comprobar y actualizar"
    else -> null
}

/** Resumen del dispositivo: versión de Android, arquitectura, red y permisos. */
@Composable
fun DeviceCard(state: SuiteUiState, onGrantInstall: () -> Unit, extra: @Composable () -> Unit = {}) {
    val d = state.device
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Android ${es.empresa.tabletsetup.core.policy.CompatibilityPolicy.androidName(d.sdkInt)} (API ${d.sdkInt}) · ${d.manufacturer} ${d.model}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("Arquitecturas: ${d.supportedAbis.joinToString()}", style = MaterialTheme.typography.bodySmall)
            Text(
                (if (state.online) "Conexión a Internet: sí" else "Conexión a Internet: NO") +
                    " · Última comprobación: " + formatDate(state.lastCheckAt),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(state.catalogInfo, style = MaterialTheme.typography.bodySmall)
            state.fdroidInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!state.canInstall) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Falta autorizar «Instalar aplicaciones desconocidas» para esta app.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onGrantInstall) { Text("Autorizar") }
                }
            }
            extra()
        }
    }
}

@Composable
fun MessageBanner(message: UiMessage, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (message.isError) colors.errorContainer else LocalStatusColors.current.infoContainer,
        contentColor = if (message.isError) colors.onErrorContainer else LocalStatusColors.current.info,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (message.isError) Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(message.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Confirmación de procedencia de una APK local sin certificado de referencia. */
@Composable
fun SignerDialog(request: SignerRequest, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Confirmar procedencia") },
        text = {
            Column {
                Text("No hay un certificado de referencia para ${request.label}. Comprueba que la huella SHA-256 del certificado de firma es la del desarrollador oficial:")
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(Hashing.pretty(request.signerSha256), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("Instala solo APK legítimas obtenidas de una fuente autorizada.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text("Confirmo la procedencia") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancelar") } },
    )
}

/** Configuración del catálogo empresarial (URL HTTPS + clave pública de verificación). */
@Composable
fun CatalogDialog(
    initialUrl: String,
    initialKey: String,
    onSave: (String, String) -> String?,
    onImportFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catálogo empresarial") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "El catálogo es opcional. Debe servirse por HTTPS y estar firmado (archivo <url>.sig). " +
                        "Sin una firma válida con esta clave no se usa.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL HTTPS del catalog.json") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Clave pública (Base64 o PEM)") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onImportFile) { Text("Importar desde archivo de configuración") }
            }
        },
        confirmButton = {
            Button(onClick = {
                error = onSave(url, key)
                if (error == null) onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Historial básico de resultados. */
@Composable
fun HistoryList(events: List<es.empresa.tabletsetup.core.platform.HistoryEvent>) {
    if (events.isEmpty()) {
        Text("Todavía no hay resultados registrados.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        events.forEach { e ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (e.success) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (e.success) LocalStatusColors.current.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${formatDate(e.time)} · ${e.action} · ${e.label}", style = MaterialTheme.typography.labelLarge)
                    Text(e.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}
