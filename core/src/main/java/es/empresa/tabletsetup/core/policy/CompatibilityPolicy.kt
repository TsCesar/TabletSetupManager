package es.empresa.tabletsetup.core.policy

import es.empresa.tabletsetup.core.model.AppRelease
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.RuleResult

/**
 * Reglas de compatibilidad comunes a Installer y Updater, a descargas online,
 * APK locales y actualizaciones. Kotlin puro.
 */
object CompatibilityPolicy {
    const val MIN_SUPPORTED_SDK = 28 // Android 9
    const val WAZE_PACKAGE = "com.waze"
    const val WAZE_MIN_SDK = 29 // Android 10: requisito interno expreso
    const val MSG_WAZE_ANDROID9 = "No compatible con Android 9"

    /** Nombre comercial de la versión de Android para un nivel de API. */
    fun androidName(sdk: Int): String = when (sdk) {
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        else -> if (sdk > 36) "API $sdk" else "API $sdk (no soportada)"
    }

    /** Regla del dispositivo para un paquete, independiente de la versión publicada. */
    fun deviceRule(packageName: String, device: DeviceProfile, appMinDeviceSdk: Int = MIN_SUPPORTED_SDK): RuleResult {
        if (ExcludedApps.isExcluded(packageName)) {
            return RuleResult.Blocked("Aplicación excluida de esta suite (se instala con su propio procedimiento)")
        }
        if (device.sdkInt < MIN_SUPPORTED_SDK) {
            return RuleResult.Blocked("Android ${androidName(device.sdkInt)} no está soportado (mínimo Android 9)")
        }
        if (packageName == WAZE_PACKAGE && device.sdkInt < WAZE_MIN_SDK) {
            return RuleResult.Blocked(MSG_WAZE_ANDROID9)
        }
        if (device.sdkInt < appMinDeviceSdk) {
            return RuleResult.Blocked("Requiere Android ${androidName(appMinDeviceSdk)} o superior")
        }
        return RuleResult.Allowed
    }

    /** Compatibilidad de una versión publicada concreta con el dispositivo. */
    fun releaseRule(release: AppRelease, device: DeviceProfile): RuleResult {
        val rule = deviceRule(release.packageName, device)
        if (rule is RuleResult.Blocked) return rule
        release.minSdk?.let { min ->
            if (device.sdkInt < min) return RuleResult.Blocked("La versión publicada requiere Android ${androidName(min)} o superior")
        }
        release.maxSdk?.let { max ->
            if (device.sdkInt > max) return RuleResult.Blocked("La versión publicada solo admite hasta Android ${androidName(max)}")
        }
        if (!abiCompatible(release.abis, device.supportedAbis)) {
            return RuleResult.Blocked(
                "Arquitectura no compatible (APK: ${release.abis.sorted().joinToString()}; " +
                    "tablet: ${device.supportedAbis.joinToString()})"
            )
        }
        return RuleResult.Allowed
    }

    /** Sin código nativo (conjunto vacío) se considera compatible con cualquier arquitectura. */
    fun abiCompatible(apkAbis: Set<String>, deviceAbis: List<String>): Boolean =
        apkAbis.isEmpty() || apkAbis.any { it in deviceAbis }

    /**
     * Posición de la mejor ABI soportada (0 = ABI preferida de la tablet).
     * Int.MAX_VALUE si no es compatible; -1 si no tiene código nativo.
     */
    fun abiRank(apkAbis: Set<String>, deviceAbis: List<String>): Int {
        if (apkAbis.isEmpty()) return -1
        val ranks = apkAbis.mapNotNull { abi -> deviceAbis.indexOf(abi).takeIf { it >= 0 } }
        return ranks.minOrNull() ?: Int.MAX_VALUE
    }

    /** Hay actualización solo si la versión publicada es estrictamente mayor. */
    fun needsUpdate(installed: Long?, available: Long?): Boolean =
        available != null && available > 0 && (installed == null || available > installed)

    /**
     * Comprueba que se pueda instalar una versión respecto a la instalada.
     * Android no permite bajar de versión aplicaciones normales: se bloquea antes.
     */
    fun versionRule(installed: Long?, candidate: Long): RuleResult = when {
        installed == null -> RuleResult.Allowed
        candidate > installed -> RuleResult.Allowed
        candidate == installed -> RuleResult.Blocked("Esta versión ($candidate) ya está instalada")
        else -> RuleResult.Blocked("Bajada de versión no autorizada (instalada $installed, APK $candidate)")
    }

    /**
     * Android 14 rechaza apps con targetSdk < 23 y Android 15 con targetSdk < 24
     * (INSTALL_FAILED_DEPRECATED_SDK_VERSION). Se avisa antes de instalar.
     */
    fun targetSdkRule(targetSdk: Int?, deviceSdk: Int): RuleResult {
        if (targetSdk == null) return RuleResult.Allowed
        val minimum = when {
            deviceSdk >= 35 -> 24
            deviceSdk >= 34 -> 23
            else -> 0
        }
        return if (targetSdk < minimum) {
            RuleResult.Blocked("La APK es demasiado antigua para Android ${androidName(deviceSdk)} (targetSdk $targetSdk)")
        } else {
            RuleResult.Allowed
        }
    }
}

/**
 * Aplicaciones que la suite no debe instalar ni actualizar nunca.
 * FuelSoft tiene su propio procedimiento: se rechaza aunque aparezca en un catálogo
 * empresarial o se intente importar manualmente.
 */
object ExcludedApps {
    private val excludedKeywords = listOf("fuelsoft")

    fun isExcluded(packageName: String, label: String? = null): Boolean {
        val haystack = (packageName + " " + (label ?: "")).lowercase()
        return excludedKeywords.any { it in haystack }
    }
}
