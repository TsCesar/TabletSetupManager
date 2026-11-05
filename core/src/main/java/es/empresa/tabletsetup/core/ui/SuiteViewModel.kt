package es.empresa.tabletsetup.core.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.catalog.CatalogSignature
import es.empresa.tabletsetup.core.catalog.EnterpriseCatalogParser
import es.empresa.tabletsetup.core.engine.BatchPlanner
import es.empresa.tabletsetup.core.engine.SequentialRunner
import es.empresa.tabletsetup.core.engine.TaskOutcome
import es.empresa.tabletsetup.core.engine.TaskPhase
import es.empresa.tabletsetup.core.model.AppState
import es.empresa.tabletsetup.core.model.AppStatus
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.platform.DeviceInfo
import es.empresa.tabletsetup.core.platform.HistoryEvent
import es.empresa.tabletsetup.core.platform.InstallEvents
import es.empresa.tabletsetup.core.platform.Notifications
import es.empresa.tabletsetup.core.platform.SuiteEngine
import es.empresa.tabletsetup.core.policy.CompatibilityPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Fila de la lista: estado calculado + progreso de la tarea en curso + último resultado. */
data class AppRowState(
    val status: AppStatus,
    val phase: TaskPhase? = null,
    val outcome: TaskOutcome? = null,
) {
    val packageName: String get() = status.app.packageName
}

data class UiMessage(val text: String, val isError: Boolean = false)

data class SignerRequest(val packageName: String, val label: String, val signerSha256: String)

data class SuiteUiState(
    val mode: Mode,
    val device: DeviceProfile,
    val online: Boolean = true,
    val canInstall: Boolean = true,
    val checking: Boolean = false,
    val rows: List<AppRowState> = emptyList(),
    val selected: Set<String> = emptySet(),
    val running: Boolean = false,
    val current: String? = null,
    val message: UiMessage? = null,
    val catalogInfo: String = "",
    val fdroidInfo: String? = null,
    val lastCheckAt: Long = 0L,
    val signerRequest: SignerRequest? = null,
    val importing: Boolean = false,
    val history: List<HistoryEvent> = emptyList(),
) {
    val statuses: List<AppStatus> get() = rows.map { it.status }
    val failedIds: List<String>
        get() = rows.filter { r -> r.outcome?.let { !it.success && !it.skipped } == true }.map { it.packageName }
    val plannedAll: List<String> get() = BatchPlanner.plan(statuses, mode)
    val plannedSelected: List<String> get() = if (selected.isEmpty()) emptyList() else BatchPlanner.plan(statuses, mode, selected)
    fun row(packageName: String): AppRowState? = rows.firstOrNull { it.packageName == packageName }
}

/**
 * ViewModel común a Installer y Updater. Toda la lógica de seguridad vive en el motor (core);
 * aquí solo se orquestan comprobaciones, la cola secuencial y los diálogos.
 */
class SuiteViewModel(application: Application, val mode: Mode) : AndroidViewModel(application) {
    private val engine = SuiteEngine.get(application)
    private val _state = MutableStateFlow(SuiteUiState(mode = mode, device = DeviceInfo.current()))
    val state: StateFlow<SuiteUiState> = _state.asStateFlow()
    private var batchJob: Job? = null
    @Volatile
    private var cancelRequested = false
    private var signerAnswer: CompletableDeferred<Boolean>? = null
    @Volatile
    private var batchAfterRefresh = false

    val settings get() = engine.settings

    init {
        engine.gateway.abandonStaleSessions()
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        if (_state.value.checking) return
        viewModelScope.launch(Dispatchers.IO) { refreshNow(force) }
    }

    private suspend fun refreshNow(force: Boolean) {
        _state.update {
            it.copy(checking = true, canInstall = engine.packages.canRequestInstalls(), online = DeviceInfo.isOnline(getApplication()))
        }
        try {
            // Las descargas de verificación (fuentes oficiales sin versión) respetan «solo Wi-Fi».
            val verifyDirect = mode == Mode.UPDATER && (!engine.settings.wifiOnly || DeviceInfo.isUnmetered(getApplication()))
            val result = engine.check(mode, forceRefresh = force, verifyDirect = verifyDirect)
            if (mode == Mode.UPDATER) {
                engine.settings.lastCheckAt = result.checkedAt
                val updates = BatchPlanner.updatesAvailable(result.statuses)
                engine.settings.lastCheckSummary = if (updates.isEmpty()) "Sin actualizaciones" else "${updates.size} actualización(es) disponible(s)"
            }
            Log.i(TAG, "comprobacion terminada modo=$mode online=${result.online} apps=${result.statuses.size}")
            result.statuses.forEach { Log.i(TAG, "estado ${it.app.packageName} ${it.state} | ${it.message}") }
            _state.update { old ->
                val previous = old.rows.associateBy { it.packageName }
                old.copy(
                    device = result.device,
                    online = result.online,
                    checking = false,
                    // En el Updater se muestran primero las aplicaciones instaladas (las únicas que actualiza).
                    rows = (if (mode == Mode.UPDATER) result.statuses.sortedBy { if (it.installed != null) 0 else 1 } else result.statuses).map { status ->
                        AppRowState(status, phase = previous[status.app.packageName]?.phase?.takeIf { old.running }, outcome = previous[status.app.packageName]?.outcome)
                    },
                    selected = old.selected.filter { pkg -> result.statuses.any { it.app.packageName == pkg } }.toSet(),
                    catalogInfo = result.catalogInfo,
                    fdroidInfo = result.fdroidInfo,
                    lastCheckAt = result.checkedAt,
                    message = if (!result.online) UiMessage("Sin conexión a Internet: se muestran datos locales y no se pueden descargar APK", isError = true) else old.message,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(checking = false, message = UiMessage("No se pudo completar la comprobación: ${e.message}", isError = true)) }
        }
        if (batchAfterRefresh) {
            batchAfterRefresh = false
            installAll()
        }
    }

    /**
     * Lanza «Instalar/Actualizar todo» tras la siguiente comprobación (botón «Actualizar» de la
     * notificación del Updater). Cada instalación sigue necesitando la confirmación de Android.
     */
    fun requestBatchAfterRefresh() {
        batchAfterRefresh = true
        refresh(force = false)
    }

    fun toggleSelection(packageName: String) = _state.update {
        it.copy(selected = if (packageName in it.selected) it.selected - packageName else it.selected + packageName)
    }

    fun selectAllEligible() = _state.update { s ->
        s.copy(selected = BatchPlanner.plan(s.statuses, mode, s.rows.map { it.packageName }.toSet()).toSet())
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun installAll() = runBatch(_state.value.plannedAll, emptyText = "No hay aplicaciones compatibles pendientes")

    fun installSelected() = runBatch(_state.value.plannedSelected, emptyText = "Ninguna de las aplicaciones seleccionadas puede instalarse ahora")

    fun retryFailed() {
        val failed = _state.value.failedIds.toSet()
        runBatch(BatchPlanner.plan(_state.value.statuses, mode, failed), emptyText = "No hay instalaciones fallidas que reintentar")
    }

    fun installOne(packageName: String) =
        runBatch(BatchPlanner.plan(_state.value.statuses, mode, setOf(packageName)), emptyText = "Esta aplicación no se puede instalar ahora")

    fun cancelBatch() {
        cancelRequested = true
        InstallEvents.pending.value?.let { pending ->
            engine.gateway.abandon(pending.sessionId)
            InstallEvents.cancel(pending.sessionId)
        }
    }

    private fun runBatch(ids: List<String>, emptyText: String) {
        val s = _state.value
        if (s.running || s.importing) return
        if (ids.isEmpty()) {
            _state.update { it.copy(message = UiMessage(emptyText)) }
            return
        }
        if (!engine.packages.canRequestInstalls()) {
            _state.update { it.copy(canInstall = false, message = UiMessage("Autoriza «Instalar aplicaciones desconocidas» para esta app y vuelve a intentarlo", isError = true)) }
            return
        }
        cancelRequested = false
        batchJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { st ->
                st.copy(
                    running = true, message = null,
                    rows = st.rows.map { if (it.packageName in ids) it.copy(phase = TaskPhase.Queued, outcome = null) else it },
                )
            }
            val summary = SequentialRunner.run(
                ids = ids,
                task = { id -> runOne(id) },
                onResult = { id, outcome ->
                    Log.i(TAG, "resultado $id ok=${outcome.success} omitida=${outcome.skipped} | ${outcome.message}")
                    // Updater recién (re)instalado: hay que volver a activarlo antes de retirar el Installer.
                    if (mode == Mode.INSTALLER && id == AppCatalog.UPDATER && outcome.success && !outcome.skipped) {
                        engine.settings.updaterConfiguredAt = 0L
                    }
                    updateRow(id) { it.copy(phase = null, outcome = outcome) }
                },
                isCancelled = { cancelRequested },
            )
            val text = buildString {
                append("Proceso terminado: ${summary.succeeded.size} correcta(s)")
                if (summary.failed.isNotEmpty()) append(", ${summary.failed.size} con error (puedes reintentarlas)")
                if (summary.skipped.isNotEmpty()) append(", ${summary.skipped.size} omitida(s)")
            }
            Log.i(TAG, "lote terminado correctas=${summary.succeeded.size} fallos=${summary.failed.size} omitidas=${summary.skipped.size}")
            _state.update { it.copy(running = false, current = null, message = UiMessage(text, isError = summary.failed.isNotEmpty())) }
            if (mode == Mode.UPDATER && summary.failed.isEmpty()) Notifications.clearUpdates(getApplication())
            refreshNow(force = false)
        }
    }

    private suspend fun runOne(packageName: String): TaskOutcome {
        val row = _state.value.row(packageName) ?: return TaskOutcome(false, "Aplicación desconocida")
        val release = row.status.release ?: return TaskOutcome(false, row.status.message, skipped = true)
        if (row.status.state == AppState.OMITIDA) return TaskOutcome(false, row.status.message, skipped = true)
        _state.update { it.copy(current = packageName) }
        return engine.installer.install(
            app = row.status.app,
            release = release,
            mode = mode,
            device = DeviceInfo.current(),
            onPhase = { phase -> updateRow(packageName) { it.copy(phase = phase) } },
            isCancelled = { cancelRequested },
        )
    }

    private fun updateRow(packageName: String, change: (AppRowState) -> AppRowState) = _state.update { st ->
        st.copy(rows = st.rows.map { if (it.packageName == packageName) change(it) else it })
    }

    /** Importación manual (Installer): APK sueltas, APK divididas o paquetes .apks/.xapk/.zip. */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.running || _state.value.importing) return
        if (!engine.packages.canRequestInstalls()) {
            _state.update { it.copy(canInstall = false, message = UiMessage("Autoriza «Instalar aplicaciones desconocidas» para esta app", isError = true)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(importing = true, message = UiMessage("Importando ${uris.size} archivo(s)…")) }
            val app = getApplication<Application>()
            val dir = File(app.cacheDir, "importados-${System.nanoTime()}").apply { mkdirs() }
            val outcome = try {
                val files = uris.mapIndexed { index, uri ->
                    val name = displayName(uri) ?: "archivo_$index.apk"
                    val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)
                    val target = File(dir, "${index}_$safe")
                    app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: throw ValidationException("No se puede abrir $name")
                    if (target.length() == 0L) throw ValidationException("Archivo vacío: $name")
                    target
                }
                engine.installer.installLocal(
                    selected = files,
                    device = DeviceInfo.current(),
                    knownPins = { pkg -> _state.value.row(pkg)?.status?.release?.expectedSigners.orEmpty() },
                    confirmSigner = { pkg, signer -> askSigner(pkg, signer) },
                    onPhase = { },
                )
            } catch (e: ValidationException) {
                TaskOutcome(false, e.message ?: "Importación no válida")
            } catch (e: Exception) {
                TaskOutcome(false, "Importación cancelada: ${e.message}")
            } finally {
                dir.deleteRecursively()
            }
            Log.i(TAG, "importacion ok=${outcome.success} | ${outcome.message}")
            _state.update { it.copy(importing = false, message = UiMessage(outcome.message, isError = !outcome.success)) }
            refreshNow(force = false)
        }
    }

    private fun displayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun askSigner(packageName: String, signer: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        signerAnswer = answer
        _state.update { it.copy(signerRequest = SignerRequest(packageName, AppCatalog.find(packageName)?.label ?: packageName, signer)) }
        return try {
            answer.await()
        } finally {
            _state.update { it.copy(signerRequest = null) }
        }
    }

    fun answerSigner(accept: Boolean) {
        signerAnswer?.complete(accept)
    }

    /** Guarda la configuración del catálogo; devuelve un mensaje de error o null si es válida. */
    fun saveCatalogConfig(url: String, publicKey: String): String? {
        val u = url.trim()
        val k = publicKey.trim()
        try {
            if (u.isNotEmpty()) EnterpriseCatalogParser.requireHttps(u)
            if (u.isNotEmpty() && k.isEmpty()) return "Introduce la clave pública: sin ella no se puede verificar la firma del catálogo"
            if (k.isNotEmpty()) CatalogSignature.parsePublicKey(k)
        } catch (e: ValidationException) {
            return e.message
        }
        if (u != engine.settings.catalogUrl || k != engine.settings.catalogPublicKey) {
            engine.settings.catalogUrl = u
            engine.settings.catalogPublicKey = k
            engine.catalogRepository.reset()
        }
        refresh(force = true)
        return null
    }

    /** Importa un archivo de configuración {"catalogUrl": "...", "publicKey": "..."}. */
    fun importCatalogConfig(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val error = try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > 64 * 1024) throw ValidationException("Archivo de configuración demasiado grande")
                    String(bytes, Charsets.UTF_8)
                } ?: throw ValidationException("No se puede abrir el archivo")
                val json = JsonParser.parseString(text).asJsonObject
                saveCatalogConfig(json.get("catalogUrl")?.asString.orEmpty(), json.get("publicKey")?.asString.orEmpty())
            } catch (e: Exception) {
                "Archivo de configuración no válido: ${e.message}"
            }
            _state.update { it.copy(message = UiMessage(error ?: "Configuración del catálogo importada", isError = error != null)) }
        }
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(history = engine.history.list()) } }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.history.clear()
            _state.update { it.copy(history = emptyList()) }
        }
    }

    fun onResume() {
        _state.update { it.copy(canInstall = engine.packages.canRequestInstalls(), online = DeviceInfo.isOnline(getApplication())) }
    }

    companion object {
        /** Etiqueta de logcat para diagnóstico en campo y para las pruebas en emulador. */
        const val TAG = "TabletSuite"
    }

    fun androidLabel(): String {
        val d = _state.value.device
        return "Android ${CompatibilityPolicy.androidName(d.sdkInt)} (API ${d.sdkInt})"
    }
}
