package es.empresa.tabletsetup.core.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import es.empresa.tabletsetup.core.apk.ApkFileInfo
import es.empresa.tabletsetup.core.apk.ApkInspector
import es.empresa.tabletsetup.core.apk.ApkSetValidator
import es.empresa.tabletsetup.core.apk.ValidatedApkSet
import es.empresa.tabletsetup.core.apk.ValidationContext
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.engine.TaskOutcome
import es.empresa.tabletsetup.core.engine.TaskPhase
import es.empresa.tabletsetup.core.engine.versionLabel
import es.empresa.tabletsetup.core.model.AppRelease
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.ManagedApp
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.model.SourceKind
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.net.DownloadException
import es.empresa.tabletsetup.core.net.Downloader
import es.empresa.tabletsetup.core.net.FailureKind
import es.empresa.tabletsetup.core.net.ResourceFingerprint
import es.empresa.tabletsetup.core.policy.ExcludedApps
import es.empresa.tabletsetup.core.util.Hashing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Puente entre el receptor de PackageInstaller y la interfaz/cola de instalación. */
object InstallEvents {
    data class PendingConfirmation(
        val sessionId: Int,
        val packageName: String?,
        val intent: Intent,
        val launched: Boolean = false,
    )

    data class SessionResult(val sessionId: Int, val packageName: String?, val status: Int, val message: String?) {
        val success: Boolean get() = status == PackageInstaller.STATUS_SUCCESS
    }

    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<SessionResult>>()
    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    /** Lo actualizan las actividades (onStart/onStop) para saber si hay que notificar. */
    @Volatile
    var isUiVisible: Boolean = false

    fun register(sessionId: Int): CompletableDeferred<SessionResult> =
        CompletableDeferred<SessionResult>().also { waiters[sessionId] = it }

    fun hasWaiter(sessionId: Int): Boolean = waiters.containsKey(sessionId)

    fun onPendingUserAction(sessionId: Int, packageName: String?, intent: Intent) {
        _pending.value = PendingConfirmation(sessionId, packageName, intent)
    }

    fun markLaunched(sessionId: Int) {
        _pending.update { if (it?.sessionId == sessionId) it.copy(launched = true) else it }
    }

    /** Permite volver a mostrar la confirmación si el técnico la cerró sin responder. */
    fun relaunch(sessionId: Int) {
        _pending.update { if (it?.sessionId == sessionId) it.copy(launched = false) else it }
    }

    fun onResult(sessionId: Int, packageName: String?, status: Int, message: String?) {
        _pending.update { if (it?.sessionId == sessionId) null else it }
        waiters.remove(sessionId)?.complete(SessionResult(sessionId, packageName, status, message))
    }

    fun cancel(sessionId: Int) {
        onResult(sessionId, null, PackageInstaller.STATUS_FAILURE_ABORTED, "Cancelada por el técnico")
    }

    fun describe(status: Int, detail: String?): String {
        val base = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Instalada correctamente"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalación cancelada en la confirmación de Android"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android bloqueó la instalación (política del dispositivo)"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicto con una app instalada (firma distinta o versión superior)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Android indica que la APK es incompatible con esta tablet"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Android rechazó la APK por no ser válida"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "No hay espacio de almacenamiento suficiente"
            else -> "Error de instalación (código $status)"
        }
        val extra = detail?.takeIf { it.isNotBlank() && status != PackageInstaller.STATUS_SUCCESS }?.take(140)
        return if (extra != null) "$base: $extra" else base
    }
}

/** Recibe los resultados de las sesiones de PackageInstaller (no exportado). */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm == null) {
                InstallEvents.onResult(sessionId, packageName, PackageInstaller.STATUS_FAILURE, "Android no mostró la confirmación")
                return
            }
            InstallEvents.onPendingUserAction(sessionId, packageName, confirm)
            if (!InstallEvents.isUiVisible) Notifications.confirmationNeeded(context, sessionId, packageName, confirm)
            return
        }
        val tracked = InstallEvents.hasWaiter(sessionId)
        InstallEvents.onResult(sessionId, packageName, status, message)
        Notifications.cancelConfirmation(context, sessionId)
        if (!tracked && packageName != null) {
            // La app se cerró durante la instalación: se registra igualmente el resultado.
            HistoryStore(context).append(
                HistoryEvent(
                    System.currentTimeMillis(), packageName, AppCatalog.find(packageName)?.label ?: packageName,
                    "Instalación", status == PackageInstaller.STATUS_SUCCESS, InstallEvents.describe(status, message),
                )
            )
        }
    }

    companion object {
        const val ACTION = "es.empresa.tabletsetup.action.INSTALL_STATUS"
        const val EXTRA_TARGET_PACKAGE = "es.empresa.tabletsetup.extra.TARGET_PACKAGE"
    }
}

/** Sesiones de instalación nativas de Android (PackageInstaller), siempre con confirmación del sistema. */
class PackageInstallerGateway(context: Context) {
    private val appContext = context.applicationContext
    private val installer: PackageInstaller get() = appContext.packageManager.packageInstaller

    /** Abandona sesiones propias que quedaron abiertas (p. ej. si la app se cerró a mitad). */
    fun abandonStaleSessions() {
        installer.mySessions.forEach { info ->
            try {
                installer.abandonSession(info.sessionId)
            } catch (e: Exception) {
                // Sesión ya cerrada.
            }
        }
    }

    /** Crea la sesión y copia las APK (base primero). Devuelve el id de sesión. */
    fun prepare(set: ValidatedApkSet, source: SourceKind): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(set.packageName)
            setSize(set.totalBytes)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) {
                // No se prometen instalaciones silenciosas: Android pide confirmación al técnico.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(
                    if (source == SourceKind.LOCAL_FILE) PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                    else PackageInstaller.PACKAGE_SOURCE_STORE
                )
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                set.files.forEachIndexed { index, info ->
                    val name = if (index == 0) "base.apk" else "split_$index.apk"
                    info.file.inputStream().use { input ->
                        session.openWrite(name, 0, info.sizeBytes).use { output ->
                            input.copyTo(output, 128 * 1024)
                            session.fsync(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            abandon(sessionId)
            throw ValidationException("No se pudo preparar la instalación en Android (${e.javaClass.simpleName})")
        }
        return sessionId
    }

    fun commit(sessionId: Int, packageName: String) {
        val intent = Intent(appContext, InstallStatusReceiver::class.java)
            .setAction(InstallStatusReceiver.ACTION)
            .setPackage(appContext.packageName)
            .putExtra(InstallStatusReceiver.EXTRA_TARGET_PACKAGE, packageName)
        // FLAG_MUTABLE es obligatorio en Android 12+ para que el sistema añada el resultado al Intent explícito.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
        try {
            installer.openSession(sessionId).use { it.commit(callback.intentSender) }
        } catch (e: Exception) {
            abandon(sessionId)
            throw ValidationException("Android no aceptó la sesión de instalación (${e.javaClass.simpleName})")
        }
    }

    fun abandon(sessionId: Int) {
        try {
            installer.abandonSession(sessionId)
        } catch (e: Exception) {
            // Ya finalizada.
        }
    }
}

/**
 * Flujo completo de una instalación o actualización: obtener archivos (descarga HTTPS con
 * progreso, APK embebida o archivo local) → inspeccionar → validar → sesión PackageInstaller
 * → esperar la confirmación/resultado de Android → historial.
 */
class AppInstaller(
    context: Context,
    private val downloader: Downloader,
    private val packages: PackageStateReader,
    private val direct: DirectSourceRepository,
    private val embedded: EmbeddedUpdater,
    private val gateway: PackageInstallerGateway,
    private val history: HistoryStore,
) {
    private val workRoot = File(context.applicationContext.cacheDir, "trabajo")

    suspend fun install(
        app: ManagedApp,
        release: AppRelease,
        mode: Mode,
        device: DeviceProfile,
        onPhase: (TaskPhase) -> Unit,
        isCancelled: () -> Boolean,
    ): TaskOutcome {
        val action = if (packages.isInstalled(app.packageName)) "Actualización" else "Instalación"
        val outcome = guarded {
            val installed = packages.installed(app.packageName)
            if (mode == Mode.UPDATER && installed == null) {
                return@guarded TaskOutcome(false, "El Updater no instala aplicaciones nuevas", skipped = true)
            }
            val dir = File(workRoot, "${app.packageName}-${System.nanoTime()}").apply { mkdirs() }
            try {
                onPhase(TaskPhase.Resolving)
                val obtained = obtain(app, release, dir, onPhase, isCancelled)
                onPhase(TaskPhase.Verifying)
                val infos = obtained.files.map { ApkInspector.inspect(it, device.sdkInt) }
                if (release.origin == SourceKind.OFFICIAL_DIRECT) rememberDirect(app, infos, obtained.fingerprint)
                // Fuente directa: si la versión publicada ya está instalada, no hay nada que hacer.
                val base = infos.firstOrNull { it.manifest.splitName == null }
                if (release.versionCode == null && installed != null && base != null && base.manifest.versionCode <= installed.versionCode) {
                    return@guarded TaskOutcome(true, "Al día: la versión publicada (${versionLabel(base.manifest.versionName, base.manifest.versionCode)}) ya está instalada", skipped = true)
                }
                val validated = ApkSetValidator.validate(
                    infos,
                    ValidationContext(
                        expectedPackage = app.packageName,
                        expectedVersionCode = release.versionCode,
                        device = device,
                        installed = installed,
                        pinnedSigners = release.expectedSigners,
                        appMinDeviceSdk = app.minDeviceSdk,
                        expectedFileHashes = obtained.expectedHashes,
                    ),
                )
                if (isCancelled()) return@guarded TaskOutcome(false, "Cancelada", skipped = true)
                commitAndWait(validated, release.origin, onPhase)
            } finally {
                dir.deleteRecursively()
            }
        }
        history.append(HistoryEvent(System.currentTimeMillis(), app.packageName, app.label, action, outcome.success, outcome.message))
        return outcome
    }

    /**
     * Fuente directa sin versión anunciada: descarga y verifica la APK para conocer su versión real.
     * La APK verificada se guarda para no repetir la descarga al instalar.
     */
    fun verifyDirect(app: ManagedApp, release: AppRelease, device: DeviceProfile): AppRelease? = try {
        val dir = File(workRoot, "verificar-${app.packageName}").apply { mkdirs() }
        try {
            val obtained = obtain(app, release, dir, { }, { false })
            val info = ApkInspector.inspect(obtained.files.single(), device.sdkInt)
            if (info.manifest.packageName != app.packageName) throw ValidationException("La descarga oficial no corresponde a ${app.packageName}")
            if (release.expectedSigners.isNotEmpty() && info.signerLineage.intersect(release.expectedSigners).isEmpty()) {
                throw ValidationException("Certificado de firma no autorizado en la descarga oficial")
            }
            rememberDirect(app, listOf(info), obtained.fingerprint)
            obtained.files.single().copyTo(direct.prefetched(app.packageName), overwrite = true)
            release.copy(
                versionCode = info.manifest.versionCode,
                versionName = info.manifest.versionName,
                minSdk = info.manifest.minSdk,
                abis = info.nativeAbis,
                assets = release.assets.map { it.copy(sha256 = info.sha256) },
            )
        } finally {
            dir.deleteRecursively()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Importación manual de APK (una o varias) o de paquetes .apks/.xapk/.zip con APK divididas. */
    suspend fun installLocal(
        selected: List<File>,
        device: DeviceProfile,
        knownPins: (String) -> Set<String>,
        confirmSigner: suspend (packageName: String, signer: String) -> Boolean,
        onPhase: (TaskPhase) -> Unit,
    ): TaskOutcome {
        var label = "Importación"
        var pkg = "importacion"
        val outcome = guarded {
            val dir = File(workRoot, "importar-${System.nanoTime()}").apply { mkdirs() }
            try {
                onPhase(TaskPhase.Verifying)
                val apks = expandBundles(selected, dir)
                val infos = apks.map { ApkInspector.inspect(it, device.sdkInt) }
                val packageNames = infos.map { it.manifest.packageName }.toSet()
                if (packageNames.size != 1) {
                    throw ValidationException("Los archivos seleccionados son de aplicaciones distintas: ${packageNames.joinToString()}")
                }
                pkg = packageNames.single()
                if (ExcludedApps.isExcluded(pkg)) throw ValidationException("Aplicación excluida de esta suite: $pkg")
                val app = AppCatalog.find(pkg) ?: throw ValidationException("La aplicación $pkg no pertenece al catálogo de la suite")
                label = app.label
                val installed = packages.installed(pkg)
                val pins = knownPins(pkg)
                val base = infos.firstOrNull { it.manifest.splitName == null }
                    ?: throw ValidationException("Falta la APK base: selecciona también el archivo base.apk")
                if (pins.isEmpty() && installed == null) {
                    // Sin certificado de referencia: el técnico confirma la procedencia viendo la huella.
                    if (!confirmSigner(pkg, base.signers.first())) {
                        return@guarded TaskOutcome(false, "Importación cancelada: procedencia no confirmada", skipped = true)
                    }
                }
                val validated = ApkSetValidator.validate(
                    infos,
                    ValidationContext(pkg, null, device, installed, pins, app.minDeviceSdk),
                )
                commitAndWait(validated, SourceKind.LOCAL_FILE, onPhase)
            } finally {
                dir.deleteRecursively()
            }
        }
        history.append(HistoryEvent(System.currentTimeMillis(), pkg, label, "Importación local", outcome.success, outcome.message))
        return outcome
    }

    private data class Obtained(val files: List<File>, val expectedHashes: Map<String, String>, val fingerprint: String?)

    private fun obtain(app: ManagedApp, release: AppRelease, dir: File, onPhase: (TaskPhase) -> Unit, isCancelled: () -> Boolean): Obtained {
        if (release.origin == SourceKind.EMBEDDED) {
            val file = File(dir, EmbeddedUpdater.ASSET_NAME)
            embedded.extract().copyTo(file, overwrite = true)
            return Obtained(listOf(file), release.assets.mapNotNull { a -> a.sha256?.let { a.fileName to it } }.toMap(), null)
        }
        // APK oficial ya verificada en segundo plano por el Updater.
        val single = release.assets.singleOrNull()
        val prefetched = direct.prefetched(app.packageName)
        if (release.origin == SourceKind.OFFICIAL_DIRECT && single?.sha256 != null && prefetched.exists() &&
            Hashing.sha256Hex(prefetched) == single.sha256
        ) {
            val file = File(dir, single.fileName)
            prefetched.copyTo(file, overwrite = true)
            return Obtained(listOf(file), mapOf(single.fileName to single.sha256), direct.cached(app.packageName)?.fingerprint)
        }
        val total = release.assets.sumOf { it.sizeBytes ?: 0L }.takeIf { it > 0 }
        var completed = 0L
        var fingerprint: String? = null
        val files = release.assets.map { asset ->
            val file = File(dir, asset.fileName)
            val outcome = downloader.download(
                url = asset.url,
                destination = file,
                expectedSha256 = asset.sha256,
                onProgress = { bytes, assetTotal ->
                    onPhase(TaskPhase.Downloading(completed + bytes, total ?: assetTotal?.plus(completed)))
                },
                isCancelled = isCancelled,
            )
            completed += outcome.bytes
            fingerprint = ResourceFingerprint(outcome.bytes, outcome.etag, outcome.lastModified).encode()
            file
        }
        return Obtained(files, release.assets.mapNotNull { a -> a.sha256?.let { a.fileName to it } }.toMap(), fingerprint)
    }

    private fun rememberDirect(app: ManagedApp, infos: List<ApkFileInfo>, fingerprint: String?) {
        val info = infos.firstOrNull { it.manifest.splitName == null } ?: return
        if (info.manifest.packageName != app.packageName) return
        direct.remember(
            app.packageName,
            DirectCacheEntry(
                fingerprint = fingerprint,
                versionCode = info.manifest.versionCode,
                versionName = info.manifest.versionName,
                sha256 = info.sha256,
                minSdk = info.manifest.minSdk,
                abis = info.nativeAbis,
                signers = info.signers,
                checkedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun commitAndWait(set: ValidatedApkSet, source: SourceKind, onPhase: (TaskPhase) -> Unit): TaskOutcome {
        onPhase(TaskPhase.Installing)
        val sessionId = gateway.prepare(set, source)
        val waiter = InstallEvents.register(sessionId)
        gateway.commit(sessionId, set.packageName)
        onPhase(TaskPhase.WaitingUser)
        val result = try {
            waiter.await()
        } catch (e: CancellationException) {
            gateway.abandon(sessionId)
            InstallEvents.cancel(sessionId)
            throw e
        }
        if (result.status == PackageInstaller.STATUS_FAILURE_ABORTED) gateway.abandon(sessionId)
        val text = InstallEvents.describe(result.status, result.message)
        val warnings = set.warnings.joinToString(" ").takeIf { it.isNotEmpty() }
        return TaskOutcome(
            success = result.success,
            message = if (result.success) "$text · ${versionLabel(set.versionName, set.versionCode)}" + (warnings?.let { " · $it" } ?: "") else text,
        )
    }

    private inline fun guarded(block: () -> TaskOutcome): TaskOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ValidationException) {
        TaskOutcome(false, e.message ?: "Validación fallida")
    } catch (e: DownloadException) {
        if (e.kind == FailureKind.CANCELLED) TaskOutcome(false, "Cancelada", skipped = true) else TaskOutcome(false, e.message ?: "Error de descarga")
    } catch (e: Exception) {
        TaskOutcome(false, "Error inesperado (${e.javaClass.simpleName}): ${e.message.orEmpty().take(120)}")
    }

    /** Extrae las APK de paquetes .apks/.xapk/.zip; las .apk se usan tal cual. */
    private fun expandBundles(selected: List<File>, dir: File): List<File> {
        if (selected.isEmpty()) throw ValidationException("No se ha seleccionado ningún archivo")
        val result = mutableListOf<File>()
        selected.forEach { file ->
            val name = file.name.lowercase()
            when {
                name.endsWith(".apkm") -> throw ValidationException("Formato .apkm no admitido: extrae sus APK y selecciónalas")
                name.endsWith(".apk") -> result += file
                name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".zip") -> {
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
                            .forEachIndexed { i, entry ->
                                if (i >= 40) throw ValidationException("El paquete contiene demasiadas APK")
                                if (entry.size > Downloader.DEFAULT_MAX_APK_BYTES) throw ValidationException("APK demasiado grande dentro del paquete")
                                val out = File(dir, "bundle_${i}_" + entry.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9_.-]"), "_"))
                                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                                result += out
                            }
                    }
                }
                else -> throw ValidationException("Archivo no admitido: ${file.name} (usa .apk, .apks, .xapk o .zip)")
            }
        }
        if (result.isEmpty()) throw ValidationException("No se encontraron APK en los archivos seleccionados")
        return result
    }
}
