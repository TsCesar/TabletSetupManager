package es.empresa.tabletsetup.core.model

/*
 * Modelos de dominio compartidos por Installer y Updater.
 * Este paquete es Kotlin puro (sin APIs de Android) para poder probarse en la JVM.
 */

/** Tipo de origen de una versión concreta de una aplicación. */
enum class SourceKind(val label: String) {
    FDROID("F-Droid (índice firmado)"),
    OFFICIAL_DIRECT("Descarga oficial del desarrollador"),
    ENTERPRISE_CATALOG("Catálogo empresarial firmado"),
    EMBEDDED("Incluida en Tablet Setup Installer"),
    LOCAL_FILE("Archivo local importado"),
    /** Tienda oficial de Google: se abre la ficha de la app y el técnico pulsa «Instalar». */
    GOOGLE_PLAY("Google Play (tienda oficial)"),
}

/** Fuente predeterminada definida en el código para cada aplicación del catálogo. */
sealed interface BuiltInSource {
    /** Repositorio oficial F-Droid (índice JAR firmado y certificado fijado). */
    data object FDroid : BuiltInSource

    /** URL HTTPS publicada por el propio desarrollador (sin versión en el nombre). */
    data class OfficialDirect(
        val url: String,
        val publisher: String,
        val reference: String,
    ) : BuiltInSource

    /**
     * Página oficial de descarga del desarrollador que publica un enlace firmado y temporal a la APK
     * (WhatsApp). Se lee la página en cada comprobación para obtener el enlace vigente.
     */
    data class OfficialPage(
        val pageUrl: String,
        /** Expresión regular del enlace a la APK dentro de la página (grupo 0). */
        val linkPattern: String,
        /** Expresión regular con un grupo para la versión publicada en la página (opcional). */
        val versionPattern: String?,
        val publisher: String,
        val reference: String,
    ) : BuiltInSource

    /**
     * Apps que su desarrollador distribuye solo por Google Play (Chrome, Maps, Gmail, Waze):
     * si la tablet tiene Google Play se abre su ficha oficial; si no, catálogo empresarial o importación.
     */
    data object GooglePlay : BuiltInSource

    /** Sin fuente pública automatizable: catálogo empresarial o importación manual. */
    data object EnterpriseOnly : BuiltInSource

    /** APK incluida dentro del Installer (Tablet Updater). */
    data object Embedded : BuiltInSource
}

/** Aplicación gestionada por la suite. */
data class ManagedApp(
    val packageName: String,
    val label: String,
    val description: String,
    val builtInSource: BuiltInSource,
    /** Las opcionales no entran en «Instalar todas» (p. ej. el add-on de QuickSupport). */
    val optional: Boolean = false,
    /** Versión mínima de Android del dispositivo exigida por la política interna. */
    val minDeviceSdk: Int = 28,
    /** Suele venir preinstalada en tablets certificadas (GMS). */
    val typicallyPreinstalled: Boolean = false,
    /** Iniciales para el avatar cuando la app no está instalada. */
    val initials: String = label.take(2),
    /** Color ARGB del avatar. */
    val avatarColor: Long = 0xFF3F51B5,
    /** No funciona sin los servicios de Google Play (Maps, Gmail). */
    val requiresPlayServices: Boolean = false,
    /**
     * SHA-256 de los certificados de firma auténticos del desarrollador. Toda APK de esta app
     * (catálogo, descarga o importación) debe estar firmada con uno de ellos.
     */
    val knownSigners: Set<String> = emptySet(),
)

/** Datos del dispositivo relevantes para la compatibilidad. */
data class DeviceProfile(
    val sdkInt: Int,
    val release: String,
    val supportedAbis: List<String>,
    val manufacturer: String = "",
    val model: String = "",
    /** Google Play Store instalada y activada. */
    val hasPlayStore: Boolean = false,
    /** Servicios de Google Play instalados y activados (tablet con GMS). */
    val hasPlayServices: Boolean = false,
)

/** Fichero APK publicado por una fuente. */
data class ApkAsset(
    val url: String,
    /** SHA-256 esperado del archivo; null solo en descargas oficiales directas sin hash publicado. */
    val sha256: String?,
    val fileName: String,
    val sizeBytes: Long? = null,
)

/** Versión publicada de una aplicación. */
data class AppRelease(
    val packageName: String,
    /** null cuando la fuente no anuncia la versión (se conoce tras descargar y analizar la APK). */
    val versionCode: Long?,
    val versionName: String?,
    val minSdk: Int?,
    val maxSdk: Int? = null,
    /** ABI con código nativo; vacío = sin código nativo o desconocido. */
    val abis: Set<String> = emptySet(),
    val assets: List<ApkAsset>,
    /** SHA-256 de certificados de firma autorizados; vacío = sin certificado fijado. */
    val expectedSigners: Set<String> = emptySet(),
    val origin: SourceKind,
    val originLabel: String = origin.label,
)

/** Información de una aplicación instalada en la tablet. */
data class InstalledInfo(
    val versionCode: Long,
    val versionName: String?,
    val isSystemApp: Boolean,
    /** SHA-256 de los certificados actuales e históricos (rotación v3). */
    val signers: Set<String>,
    val installerPackage: String? = null,
    /** false si está desactivada en Ajustes (habitual en apps preinstaladas que no se usan). */
    val enabled: Boolean = true,
)

/** Estado calculado de cada aplicación en pantalla. */
enum class AppState {
    /** Omitida por regla del dispositivo (p. ej. Waze en Android 9). */
    OMITIDA,
    /** La versión publicada no es compatible (minSdk, ABI...). */
    NO_COMPATIBLE,
    /** No existe fuente autorizada configurada. */
    FUENTE_PENDIENTE,
    /** No instalada y con versión disponible. */
    NO_INSTALADA,
    /** Instalada con versión más reciente disponible. */
    ACTUALIZACION,
    /** Instalada y al día según la fuente. */
    AL_DIA,
    /** Instalada, pero sin fuente para actualizarla. */
    INSTALADA_SIN_FUENTE,
    /** Fuente directa: hay que descargar para conocer la versión publicada. */
    POR_VERIFICAR,
    /** Error al consultar la fuente. */
    ERROR,
    /** Instalada; sus actualizaciones las gestiona Google Play. */
    GOOGLE_PLAY,
    /** Instalada pero desactivada en Ajustes: hay que activarla. */
    DESACTIVADA,
}

data class AppStatus(
    val app: ManagedApp,
    val state: AppState,
    val installed: InstalledInfo? = null,
    val release: AppRelease? = null,
    val message: String,
)

/** Resultado de consultar las fuentes de una aplicación. */
sealed interface Resolution {
    data class Available(val release: AppRelease) : Resolution
    data class NoSource(val reason: String) : Resolution

    /** La fuente publica la app, pero ninguna versión sirve para esta tablet. */
    data class Incompatible(val reason: String) : Resolution
    data class Failed(val message: String) : Resolution
}

/** Modo de funcionamiento de la interfaz común. */
enum class Mode { INSTALLER, UPDATER }

/** Regla de compatibilidad evaluada. */
sealed interface RuleResult {
    data object Allowed : RuleResult
    data class Blocked(val reason: String) : RuleResult
}

/** Error de validación con mensaje comprensible para el técnico. */
class ValidationException(message: String) : Exception(message)
