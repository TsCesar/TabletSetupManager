package es.empresa.tabletsetup.core.platform

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import es.empresa.tabletsetup.core.BuildConfig
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.engine.StatusCalculator
import es.empresa.tabletsetup.core.model.AppState
import es.empresa.tabletsetup.core.model.AppStatus
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.InstalledInfo
import es.empresa.tabletsetup.core.model.ManagedApp
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.model.Resolution
import es.empresa.tabletsetup.core.model.SourceKind
import es.empresa.tabletsetup.core.net.Downloader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resultado de una comprobación completa. */
data class CheckResult(
    val device: DeviceProfile,
    val statuses: List<AppStatus>,
    val catalogInfo: String,
    val fdroidInfo: String?,
    val online: Boolean,
    val checkedAt: Long,
)

/**
 * Fachada única usada por la interfaz (Installer y Updater) y por el trabajo periódico.
 * No depende de Google Play Services.
 */
class SuiteEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    val settings = SuiteSettings(appContext)
    val history = HistoryStore(appContext)
    val packages = PackageStateReader(appContext)
    val downloader = Downloader(userAgent = "TabletSetupManager/${BuildConfig.SUITE_VERSION} (Android)")
    val catalogRepository = EnterpriseCatalogRepository(appContext, settings, downloader)
    val fdroidRepository = FdroidRepository(appContext, downloader)
    val directRepository = DirectSourceRepository(appContext, downloader)
    val embedded = EmbeddedUpdater(appContext)
    val gateway = PackageInstallerGateway(appContext)
    val resolver = ReleaseResolver(catalogRepository, fdroidRepository, directRepository, embedded, packages)
    val installer = AppInstaller(appContext, downloader, packages, directRepository, embedded, gateway, history)
    private val checkMutex = Mutex()

    /**
     * Comprueba todas las aplicaciones. En modo Updater, [verifyDirect] descarga y verifica las
     * APK de fuentes oficiales directas para conocer su versión real (solo de apps instaladas).
     */
    suspend fun check(mode: Mode, forceRefresh: Boolean, verifyDirect: Boolean): CheckResult = checkMutex.withLock {
        val device = DeviceInfo.current()
        val online = DeviceInfo.isOnline(appContext)
        val apps = AppCatalog.apps
        val maxAge = if (forceRefresh) 30L * 60 * 1000 else 6L * 60 * 60 * 1000
        val snapshot = resolver.snapshot(apps, device, allowNetwork = online, fdroidMaxAgeMs = maxAge)
        val statuses = apps.map { app ->
            val installed = packages.installed(app.packageName)
            var resolution = resolver.resolve(app, snapshot, device, mode)
            if (!online && resolution is Resolution.Available && resolution.release.origin == SourceKind.OFFICIAL_DIRECT) {
                resolution = Resolution.Failed("Sin conexión a Internet: no se puede consultar la descarga oficial")
            }
            var status = StatusCalculator.compute(app, device, installed, resolution, mode)
            if (verifyDirect && online && installed != null && status.state == AppState.POR_VERIFICAR && status.release != null) {
                val verified = installer.verifyDirect(app, status.release!!, device)
                if (verified != null) status = StatusCalculator.compute(app, device, installed, Resolution.Available(verified), mode)
            }
            status
        }
        val catalogInfo = when (val c = snapshot.catalog) {
            CatalogState.NotConfigured -> "Catálogo empresarial: no configurado"
            is CatalogState.Failed -> c.message
            is CatalogState.Loaded -> "Catálogo empresarial verificado (secuencia ${c.catalog.sequence}" +
                (if (c.fromCache) ", copia local" else "") + ")" +
                (if (c.catalog.warnings.isNotEmpty()) " · ${c.catalog.warnings.size} aviso(s)" else "")
        }
        val fdroidInfo = when (val f = snapshot.fdroid) {
            null -> null
            is FdroidState.Failed -> f.message
            is FdroidState.Loaded -> "Índice F-Droid firmado verificado" + if (f.fromCache) " (copia local)" else ""
        }
        CheckResult(device, statuses, catalogInfo, fdroidInfo, online, System.currentTimeMillis())
    }

    fun app(packageName: String): ManagedApp? = AppCatalog.find(packageName)

    companion object {
        @Volatile
        private var instance: SuiteEngine? = null

        fun get(context: Context): SuiteEngine =
            instance ?: synchronized(this) { instance ?: SuiteEngine(context).also { instance = it } }
    }
}

/** Estado de TeamViewer QuickSupport y sus complementos en esta tablet. */
data class QuickSupportState(
    val quickSupport: InstalledInfo?,
    val universalAddon: InstalledInfo?,
    /** Paquetes de complementos detectados con el servicio de accesibilidad activado. */
    val accessibilityEnabled: List<String>,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val idConfirmed: Boolean,
    val remoteTestAt: Long,
    val remoteTestOk: Boolean,
) {
    /** El control remoto solo se considera comprobado tras una prueba manual satisfactoria. */
    val remoteVerified: Boolean get() = remoteTestAt > 0 && remoteTestOk
    val addonAccessibilityOn: Boolean get() = accessibilityEnabled.any { it.startsWith(AppCatalog.QS_ADDON_PREFIX) }
}

object QuickSupportProbe {
    fun probe(context: Context): QuickSupportState {
        val engine = SuiteEngine.get(context)
        val device = DeviceInfo.current()
        val enabled = mutableSetOf<String>()
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')
                ?.mapNotNull { it.substringBefore('/').takeIf { pkg -> pkg.isNotBlank() } }
                ?.filter { it.startsWith(AppCatalog.QS_ADDON_PREFIX) || it == AppCatalog.QUICKSUPPORT }
                ?.let { enabled += it }
        } catch (e: Exception) {
            // Algunos fabricantes restringen la lectura: se usa AccessibilityManager.
        }
        try {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                ?.filter { it.startsWith(AppCatalog.QS_ADDON_PREFIX) || it == AppCatalog.QUICKSUPPORT }
                ?.let { enabled += it }
        } catch (e: Exception) {
            // Sin información adicional.
        }
        return QuickSupportState(
            quickSupport = engine.packages.installed(AppCatalog.QUICKSUPPORT),
            universalAddon = engine.packages.installed(AppCatalog.QS_UNIVERSAL_ADDON),
            accessibilityEnabled = enabled.sorted(),
            manufacturer = device.manufacturer,
            model = device.model,
            sdkInt = device.sdkInt,
            idConfirmed = engine.settings.quickSupportIdConfirmed,
            remoteTestAt = engine.settings.remoteTestAt,
            remoteTestOk = engine.settings.remoteTestOk,
        )
    }
}
