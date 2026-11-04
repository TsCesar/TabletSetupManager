package es.empresa.tabletsetup.core.engine

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.model.AppRelease
import es.empresa.tabletsetup.core.model.AppState
import es.empresa.tabletsetup.core.model.AppStatus
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.InstalledInfo
import es.empresa.tabletsetup.core.model.ManagedApp
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.model.Resolution
import es.empresa.tabletsetup.core.model.RuleResult
import es.empresa.tabletsetup.core.policy.CompatibilityPolicy
import es.empresa.tabletsetup.core.util.Hashing
import kotlinx.coroutines.CancellationException

/** Texto «1.2.3 (123)» para mostrar versiones. */
fun versionLabel(name: String?, code: Long?): String = when {
    name.isNullOrBlank() && code == null -> "desconocida"
    name.isNullOrBlank() -> "$code"
    code == null -> name
    else -> "$name ($code)"
}

/** Cálculo del estado de cada aplicación. Kotlin puro. */
object StatusCalculator {
    fun compute(
        app: ManagedApp,
        device: DeviceProfile,
        installed: InstalledInfo?,
        resolution: Resolution,
        mode: Mode,
    ): AppStatus {
        val rule = CompatibilityPolicy.deviceRule(app.packageName, device, app.minDeviceSdk)
        if (rule is RuleResult.Blocked) {
            return AppStatus(app, AppState.OMITIDA, installed, null, rule.reason)
        }
        val installedText = installed?.let { "Instalada " + versionLabel(it.versionName, it.versionCode) + if (it.isSystemApp) " (preinstalada)" else "" }
        return when (resolution) {
            is Resolution.Failed -> AppStatus(app, AppState.ERROR, installed, null, resolution.message)
            is Resolution.NoSource -> if (installed != null) {
                AppStatus(app, AppState.INSTALADA_SIN_FUENTE, installed, null, "$installedText · ${resolution.reason}")
            } else {
                AppStatus(app, AppState.FUENTE_PENDIENTE, null, null, resolution.reason)
            }
            is Resolution.Available -> available(app, device, installed, installedText, resolution.release, mode)
        }
    }

    private fun available(
        app: ManagedApp,
        device: DeviceProfile,
        installed: InstalledInfo?,
        installedText: String?,
        release: AppRelease,
        mode: Mode,
    ): AppStatus {
        val compatibility = CompatibilityPolicy.releaseRule(release, device)
        if (compatibility is RuleResult.Blocked) {
            return AppStatus(app, AppState.NO_COMPATIBLE, installed, release, compatibility.reason)
        }
        // Firma fijada por la fuente distinta de la instalada: Android no permitiría actualizar.
        if (installed != null && release.expectedSigners.isNotEmpty() && installed.signers.isNotEmpty() &&
            release.expectedSigners.intersect(installed.signers).isEmpty()
        ) {
            return AppStatus(
                app, AppState.NO_COMPATIBLE, installed, release,
                "$installedText · instalada desde otra fuente (firma ${Hashing.short(installed.signers.first())}); " +
                    "${release.originLabel} no puede actualizarla",
            )
        }
        val available = versionLabel(release.versionName, release.versionCode)
        if (installed == null) {
            val message = when {
                mode == Mode.UPDATER -> "No instalada · el Updater solo actualiza aplicaciones ya instaladas"
                release.versionCode == null -> "Disponible en ${release.originLabel} (versión se comprueba al descargar)"
                else -> "Disponible $available · ${release.originLabel}"
            }
            return AppStatus(app, AppState.NO_INSTALADA, null, release, message)
        }
        if (release.versionCode == null) {
            return AppStatus(app, AppState.POR_VERIFICAR, installed, release, "$installedText · ${release.originLabel} no anuncia versión: se comprobará al descargar")
        }
        return if (CompatibilityPolicy.needsUpdate(installed.versionCode, release.versionCode)) {
            AppStatus(app, AppState.ACTUALIZACION, installed, release, "${versionLabel(installed.versionName, installed.versionCode)} → $available")
        } else {
            AppStatus(app, AppState.AL_DIA, installed, release, "$installedText · al día")
        }
    }
}

/** Selección de aplicaciones para los procesos por lotes. Kotlin puro. */
object BatchPlanner {
    /**
     * Installer: instala las no instaladas y actualiza las desactualizadas.
     * Updater: SOLO actualiza aplicaciones ya instaladas (nunca instala nuevas).
     * Las opcionales (add-on de QuickSupport) solo entran si se seleccionan expresamente.
     */
    fun plan(statuses: List<AppStatus>, mode: Mode, selected: Set<String>? = null): List<String> {
        val eligible = statuses.filter { status ->
            when (mode) {
                // «Por verificar» (instalada, fuente sin versión anunciada) solo si se elige expresamente.
                Mode.INSTALLER -> status.state == AppState.NO_INSTALADA || status.state == AppState.ACTUALIZACION ||
                    (status.state == AppState.POR_VERIFICAR && selected != null)
                Mode.UPDATER -> status.installed != null &&
                    (status.state == AppState.ACTUALIZACION || status.state == AppState.POR_VERIFICAR)
            }
        }.filter { status ->
            if (selected != null) status.app.packageName in selected else !status.app.optional
        }
        // El Updater se instala/actualiza al final para no interrumpir el lote.
        return eligible.sortedBy { if (it.app.packageName == AppCatalog.UPDATER) 1 else 0 }.map { it.app.packageName }
    }

    /** Aplicaciones que pueden actualizarse en modo Updater (para notificaciones). */
    fun updatesAvailable(statuses: List<AppStatus>): List<AppStatus> =
        statuses.filter { it.installed != null && it.state == AppState.ACTUALIZACION }
}

/** Fase visible de una tarea de instalación. */
sealed interface TaskPhase {
    data object Queued : TaskPhase
    data object Resolving : TaskPhase
    data class Downloading(val bytes: Long, val total: Long?) : TaskPhase {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }
    data object Verifying : TaskPhase
    data object WaitingUser : TaskPhase
    data object Installing : TaskPhase
}

/** Resultado de una tarea. */
data class TaskOutcome(val success: Boolean, val message: String, val skipped: Boolean = false)

/** Resumen de un lote. */
data class BatchSummary(val results: Map<String, TaskOutcome>) {
    val succeeded: List<String> get() = results.filterValues { it.success }.keys.toList()
    val failed: List<String> get() = results.filterValues { !it.success && !it.skipped }.keys.toList()
    val skipped: List<String> get() = results.filterValues { it.skipped }.keys.toList()
}

/**
 * Ejecuta tareas de una en una. Un fallo no bloquea las siguientes; la cancelación
 * detiene las pendientes. Kotlin puro (corrutinas).
 */
object SequentialRunner {
    suspend fun run(
        ids: List<String>,
        task: suspend (String) -> TaskOutcome,
        onResult: (String, TaskOutcome) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): BatchSummary {
        val results = linkedMapOf<String, TaskOutcome>()
        for (id in ids.distinct()) {
            if (isCancelled()) {
                results[id] = TaskOutcome(false, "Cancelada antes de empezar", skipped = true)
                onResult(id, results.getValue(id))
                continue
            }
            val outcome = try {
                task(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TaskOutcome(false, e.message ?: "Error inesperado")
            }
            results[id] = outcome
            onResult(id, outcome)
        }
        return BatchSummary(results)
    }
}
