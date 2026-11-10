package es.empresa.tabletsetup.core.sources

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.model.ApkAsset
import es.empresa.tabletsetup.core.model.AppRelease
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.SourceKind

/**
 * Waze original firmado por Waze, en formato APKM.
 * El binario remoto tiene el mismo SHA-256 que el APKM aportado y revisado,
 * no es un APK modificado. Se rechaza cualquier cambio de un solo byte.
 * Distribución alternativa de terceros; no es una descarga oficial de Waze.
 */
object WazeVerifiedApkm {
    const val VERSION_CODE = 1030744L
    const val SHA256 = "6c42f73b0404445884b4861e976adeeabf1331fd8a310ca22145f75015bc0437"
    const val SIGNER_SHA256 = "03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"
    const val URL = "https://github.com/aaayd/waze-custom-patches/releases/download/" +
        "waze-5.26.5.0-1030744-patches-1.16/waze-5.26.5.0-original-arm64.apkm"

    fun releaseFor(device: DeviceProfile): AppRelease? {
        if (device.sdkInt < 29 ||
            device.supportedAbis.none { it.equals("arm64-v8a", ignoreCase = true) }
        ) return null

        return AppRelease(
            packageName = AppCatalog.WAZE,
            versionCode = VERSION_CODE,
            versionName = "5.26.5.0",
            minSdk = 29,
            abis = setOf("arm64-v8a"),
            assets = listOf(ApkAsset(
                url = URL,
                sha256 = SHA256,
                fileName = "waze-5.26.5.0-original-arm64.apkm",
                sizeBytes = 101_019_309L
            )),
            expectedSigners = setOf(SIGNER_SHA256),
            origin = SourceKind.VERIFIED_BUNDLE,
            originLabel = "APKM original Waze: SHA-256 y firma verificados (espejo externo)"
        )
    }
}
