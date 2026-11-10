package es.empresa.tabletsetup.core.apk

import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.InstalledInfo
import es.empresa.tabletsetup.core.model.RuleResult
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.policy.CompatibilityPolicy
import es.empresa.tabletsetup.core.util.Hashing

/** Contexto para validar un conjunto de APK (base + splits) antes de instalarlo. */
data class ValidationContext(
    val expectedPackage: String,
    /** Versión anunciada por la fuente (null si la fuente no la publica). */
    val expectedVersionCode: Long?,
    val device: DeviceProfile,
    val installed: InstalledInfo?,
    /** Certificados autorizados (SHA-256). Vacío = no hay certificado fijado. */
    val pinnedSigners: Set<String>,
    /** Versión mínima de Android exigida por la política interna para esta app. */
    val appMinDeviceSdk: Int = CompatibilityPolicy.MIN_SUPPORTED_SDK,
    /** Hashes SHA-256 esperados por nombre de fichero (descargas del catálogo/F-Droid). */
    val expectedFileHashes: Map<String, String> = emptyMap(),
)

/** Conjunto validado, listo para la sesión de PackageInstaller. */
data class ValidatedApkSet(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int?,
    /** La APK base siempre en primera posición. */
    val files: List<ApkFileInfo>,
    val signers: Set<String>,
    val signerPinned: Boolean,
    val warnings: List<String>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/**
 * Aplica TODAS las comprobaciones de seguridad exigidas antes de instalar:
 *  1. packageName              6. certificado de firma (apksig)
 *  2. versionCode              7. compatibilidad con la firma instalada
 *  3. minSdkVersion/targetSdk  8. sin bajadas de versión
 *  4. arquitectura (ABI)       9. splits del mismo paquete y versión
 *  5. SHA-256 del archivo     10. archivos corruptos o inesperados (ApkInspector)
 */
object ApkSetValidator {
    fun validate(infos: List<ApkFileInfo>, ctx: ValidationContext): ValidatedApkSet {
        if (infos.isEmpty()) throw ValidationException("No hay APK para instalar")
        if (infos.size > 40) throw ValidationException("Demasiados archivos APK en el conjunto")

        // 9. Exactamente una APK base y splits únicos.
        val bases = infos.filter { it.manifest.splitName == null }
        if (bases.isEmpty()) throw ValidationException("Falta la APK base: solo se han recibido APK divididas (split)")
        if (bases.size > 1) throw ValidationException("Se han recibido varias APK base para la misma instalación")
        val base = bases.single()
        val splits = infos.filter { it !== base }
        val splitNames = splits.map { it.manifest.splitName!! }
        if (splitNames.distinct().size != splitNames.size) throw ValidationException("APK divididas duplicadas: ${splitNames.joinToString()}")

        // 1. Paquete.
        infos.forEach { info ->
            if (info.manifest.packageName != ctx.expectedPackage) {
                throw ValidationException(
                    "La APK pertenece a otro paquete (${info.manifest.packageName}); se esperaba ${ctx.expectedPackage}"
                )
            }
        }
        // 9. Misma versión en base y splits.
        infos.forEach { info ->
            if (info.manifest.versionCode != base.manifest.versionCode) {
                throw ValidationException("La APK dividida ${info.manifest.splitName ?: info.file.name} tiene otra versión (${info.manifest.versionCode})")
            }
        }
        val versionCode = base.manifest.versionCode
        // 2. Versión anunciada por la fuente.
        if (ctx.expectedVersionCode != null && ctx.expectedVersionCode != versionCode) {
            throw ValidationException("La versión descargada ($versionCode) no coincide con la publicada (${ctx.expectedVersionCode})")
        }
        // 5. SHA-256 (cuando la fuente lo publica).
        infos.forEach { info ->
            ctx.expectedFileHashes[info.file.name]?.let { expected ->
                if (Hashing.normalize(expected) != info.sha256) throw ValidationException("SHA-256 distinto al publicado en ${info.file.name}")
            }
        }
        // Regla del dispositivo (Waze en Android 9, FuelSoft excluida...).
        val deviceRule = CompatibilityPolicy.deviceRule(ctx.expectedPackage, ctx.device, ctx.appMinDeviceSdk)
        if (deviceRule is RuleResult.Blocked) throw ValidationException(deviceRule.reason)
        // 3. minSdk / maxSdk / targetSdk.
        if (ctx.device.sdkInt < base.manifest.minSdk) {
            throw ValidationException(
                if (base.manifest.minSdk >= ManifestReader.PREVIEW_SDK) "La APK es una versión preliminar no instalable"
                else "La APK requiere Android ${CompatibilityPolicy.androidName(base.manifest.minSdk)} o superior (minSdk ${base.manifest.minSdk})"
            )
        }
        base.manifest.maxSdk?.let { max ->
            if (ctx.device.sdkInt > max) throw ValidationException("La APK solo admite hasta Android ${CompatibilityPolicy.androidName(max)}")
        }
        val targetRule = CompatibilityPolicy.targetSdkRule(base.manifest.targetSdk, ctx.device.sdkInt)
        if (targetRule is RuleResult.Blocked) throw ValidationException(targetRule.reason)
        // 4. Arquitectura.
        val warnings = mutableListOf<String>()
        val allAbis = infos.flatMap { it.nativeAbis }.toSet()
        if (!CompatibilityPolicy.abiCompatible(allAbis, ctx.device.supportedAbis)) {
            throw ValidationException(
                "Arquitectura no compatible (APK: ${allAbis.sorted().joinToString()}; tablet: ${ctx.device.supportedAbis.joinToString()})"
            )
        }
        splits.filter { it.nativeAbis.isNotEmpty() && !CompatibilityPolicy.abiCompatible(it.nativeAbis, ctx.device.supportedAbis) }
            .forEach { warnings += "La APK dividida ${it.manifest.splitName} es para otra arquitectura y no se usará" }
        // 6. Firma: todas las APK del conjunto con el mismo firmante.
        val signers = base.signers
        infos.forEach { info ->
            if (info.signers != signers) throw ValidationException("La APK ${info.manifest.splitName ?: "base"} está firmada con otro certificado")
        }
        val pinned = ctx.pinnedSigners.map { Hashing.normalize(it) }.toSet()
        if (pinned.isNotEmpty() && base.signerLineage.intersect(pinned).isEmpty()) {
            throw ValidationException("Certificado de firma no autorizado (${Hashing.short(signers.first())})")
        }
        // 7 y 8. Respecto a la aplicación instalada.
        ctx.installed?.let { installed ->
            val versionRule = CompatibilityPolicy.versionRule(installed.versionCode, versionCode)
            if (versionRule is RuleResult.Blocked) throw ValidationException(versionRule.reason)
            if (installed.signers.isNotEmpty() && base.signerLineage.intersect(installed.signers).isEmpty()) {
                throw ValidationException(
                    "La firma no coincide con la aplicación instalada (procede de otra fuente). " +
                        "Android no permite actualizarla sin desinstalarla antes."
                )
            }
        }
        val ordered = listOf(base) + splits.sortedBy { it.manifest.splitName }
        return ValidatedApkSet(
            packageName = ctx.expectedPackage,
            versionCode = versionCode,
            versionName = base.manifest.versionName,
            minSdk = base.manifest.minSdk,
            targetSdk = base.manifest.targetSdk,
            files = ordered,
            signers = signers,
            signerPinned = pinned.isNotEmpty(),
            warnings = warnings,
        )
    }
}
