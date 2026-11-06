package es.empresa.tabletsetup.core.catalog

import es.empresa.tabletsetup.core.model.BuiltInSource
import es.empresa.tabletsetup.core.model.ManagedApp
import es.empresa.tabletsetup.core.policy.ExcludedApps

/**
 * Catálogo fijo de aplicaciones gestionadas por la suite.
 *
 * FuelSoft NO forma parte del catálogo: tiene su propio procedimiento de instalación.
 * Las fuentes por defecto solo usan orígenes verificados:
 *  - MuPDF: repositorio oficial F-Droid (índice firmado).
 *  - TeamViewer QuickSupport y Universal Add-On: APK oficiales publicadas por TeamViewer
 *    en su base de conocimiento.
 *  - Chrome, Maps, Gmail y Waze: descarga desde Aptoide con certificado del
 *    desarrollador fijado y validación de APK/splits antes de instalar.
 *  - WhatsApp: descarga APK oficial desde la página del desarrollador.
 */
object AppCatalog {
    const val MUPDF = "com.artifex.mupdf.viewer.app"
    const val QUICKSUPPORT = "com.teamviewer.quicksupport.market"
    const val QS_UNIVERSAL_ADDON = "com.teamviewer.quicksupport.addon.universal"
    const val QS_ADDON_PREFIX = "com.teamviewer.quicksupport.addon."
    const val CHROME = "com.android.chrome"
    const val MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"
    const val WHATSAPP = "com.whatsapp"
    const val GMAIL = "com.google.android.gm"
    const val UPDATER = "es.empresa.tabletsetup.updater"
    const val INSTALLER = "es.empresa.tabletsetup.installer"
    const val PLAY_STORE = "com.android.vending"
    const val PLAY_SERVICES = "com.google.android.gms"

    /** Base de conocimiento de TeamViewer donde se publican las descargas directas. */
    const val TEAMVIEWER_KB =
        "https://www.teamviewer.com/en/global/support/knowledge-base/teamviewer-classic/mobile/android/universal-add-on-for-android/"

    val apps: List<ManagedApp> = listOf(
        ManagedApp(
            packageName = MUPDF,
            label = "MuPDF",
            description = "Visor de documentos PDF",
            builtInSource = BuiltInSource.FDroid,
            initials = "Mu", avatarColor = 0xFFB3261E,
        ),
        ManagedApp(
            packageName = QUICKSUPPORT,
            label = "TeamViewer QuickSupport",
            description = "Asistencia remota",
            builtInSource = BuiltInSource.OfficialDirect(
                url = "https://dl.teamviewer.com/mobile/TeamViewerQS.apk",
                publisher = "TeamViewer",
                reference = TEAMVIEWER_KB,
            ),
            initials = "QS", avatarColor = 0xFF0E5FD8,
        ),
        ManagedApp(
            packageName = QS_UNIVERSAL_ADDON,
            label = "TeamViewer Universal Add-On",
            description = "Control remoto en dispositivos sin complemento del fabricante (solo si QuickSupport lo pide)",
            builtInSource = BuiltInSource.OfficialDirect(
                url = "https://download.teamviewer.com/mobile/addon/TeamViewerAddon-Universal.apk",
                publisher = "TeamViewer",
                reference = TEAMVIEWER_KB,
            ),
            optional = true,
            initials = "UA", avatarColor = 0xFF3A7BD5,
        ),
        ManagedApp(
            packageName = CHROME,
            label = "Google Chrome",
            description = "Navegador web",
            builtInSource = BuiltInSource.Aptoide(
                reference = "https://en.aptoide.com/",
            ),
            typicallyPreinstalled = true,
            initials = "Ch", avatarColor = 0xFF1E8E3E,
        ),
        ManagedApp(
            packageName = MAPS,
            label = "Google Maps",
            description = "Mapas y navegación",
            builtInSource = BuiltInSource.Aptoide(
                reference = "https://en.aptoide.com/",
            ),
            typicallyPreinstalled = true,
            initials = "Ma", avatarColor = 0xFF188038,
        ),
        ManagedApp(
            packageName = WAZE,
            label = "Waze",
            description = "Navegación GPS (no compatible con Android 9)",
            builtInSource = BuiltInSource.Aptoide(
                reference = "https://waze-gps-maps-traffic-alerts-sat-nav.es.aptoide.com/app",
            ),
            minDeviceSdk = 29,
            initials = "Wz", avatarColor = 0xFF33CCFF,
        ),
        ManagedApp(
            packageName = WHATSAPP,
            label = "WhatsApp",
            description = "Mensajería (solo instalación; no se configuran cuentas)",
            builtInSource = BuiltInSource.OfficialPage(
                pageUrl = "https://www.whatsapp.com/download/android",
                publisher = "WhatsApp",
                reference = "https://www.whatsapp.com/download/android",
            ),
            initials = "Wa", avatarColor = 0xFF25A244,
        ),
        ManagedApp(
            packageName = GMAIL,
            label = "Gmail",
            description = "Correo electrónico (solo instalación; no se configuran cuentas)",
            builtInSource = BuiltInSource.Aptoide(
                reference = "https://en.aptoide.com/",
            ),
            typicallyPreinstalled = true,
            initials = "Gm", avatarColor = 0xFFC5221F,
        ),
        ManagedApp(
            packageName = UPDATER,
            label = "Tablet Updater",
            description = "Actualizador permanente de la suite",
            builtInSource = BuiltInSource.Embedded,
            initials = "TU", avatarColor = 0xFF00696B,
        ),
    )

    val packageNames: Set<String> = apps.map { it.packageName }.toSet()

    fun find(packageName: String): ManagedApp? = apps.firstOrNull { it.packageName == packageName }

    /** Paquetes de complementos de QuickSupport conocidos (para detección). */
    val knownQuickSupportAddons: Set<String> = setOf(QS_UNIVERSAL_ADDON)

    init {
        check(apps.none { ExcludedApps.isExcluded(it.packageName, it.label) }) {
            "El catálogo no puede incluir aplicaciones excluidas"
        }
        check(packageNames.size == apps.size) { "Paquetes duplicados en el catálogo" }
    }
}
