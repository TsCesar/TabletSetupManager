
package es.empresa.tabletsetup.core.sources

import es.empresa.tabletsetup.core.model.ValidationException
import java.util.Locale
import kotlin.math.abs

/**
 * Selecciona las APK divididas compatibles
 * con las características del dispositivo.
 *
 * No descarga ni instala archivos.
 */
object AptoideSplitSelector {

    private val densityValues = mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640
    )

    fun select(
        apk: AptoideApkInfo,
        supportedAbis: List<String>,
        densityDpi: Int,
        preferredLocales: List<String>
    ): List<AptoideSplitInfo> {

        val selected = mutableListOf<AptoideSplitInfo>()

        // 1. Arquitectura del procesador.
        val abiSplits = apk.splits.filter {
            it.type == "ABI"
        }

        if (abiSplits.isNotEmpty()) {
            val chosenAbi = supportedAbis
                .asSequence()
                .mapNotNull { abi ->
                    abiSplits.firstOrNull { split ->
                        normalizeAbi(split.name) == abi.lowercase(
                            Locale.ROOT
                        )
                    }
                }
                .firstOrNull()
                ?: throw ValidationException(
                    "No hay una APK compatible con la arquitectura del dispositivo"
                )

            selected.add(chosenAbi)
        }

        // 2. Densidad de pantalla.
        val densitySplits = apk.splits.filter {
            it.type == "DENSITY"
        }

        if (densitySplits.isNotEmpty()) {

            if (densityDpi <= 0) {
                throw ValidationException(
                    "Densidad de pantalla no válida"
                )
            }

            val chosenDensity = densitySplits
                .mapNotNull { split ->
                    val qualifier = split.name
                        .removePrefix("config.")
                        .lowercase(Locale.ROOT)

                    val dpi = densityValues[qualifier]

                    if (dpi == null) {
                        null
                    } else {
                        split to abs(dpi - densityDpi)
                    }
                }
                .minByOrNull { it.second }
                ?.first
                ?: throw ValidationException(
                    "No hay una APK de densidad reconocida"
                )

            selected.add(chosenDensity)
        }

        // 3. Idioma de la tablet.
        val localeSplits = apk.splits.filter {
            it.type == "LOCALE"
        }

        if (localeSplits.isNotEmpty()) {

            val chosenLocale = preferredLocales
                .asSequence()
                .map { normalizeLocale(it) }
                .mapNotNull { preferred ->
                    localeSplits.firstOrNull {
                        normalizeLocale(it.name) == preferred
                    } ?: localeSplits.firstOrNull {
                        normalizeLocale(it.name) ==
                            preferred.substringBefore("-")
                    }
                }
                .firstOrNull()
                // Inglés como alternativa.
                ?: localeSplits.firstOrNull {
                    normalizeLocale(it.name) == "en"
                }

            if (chosenLocale != null) {
                selected.add(chosenLocale)
            }
        }

        // 4. Verificar que todos los tipos
        // obligatorios han sido seleccionados.
        val selectedTypes = selected
            .map { it.type }
            .toSet()

        val missingTypes =
            apk.requiredSplitTypes - selectedTypes

        if (missingTypes.isNotEmpty()) {
            throw ValidationException(
                "No se pudieron seleccionar las APK obligatorias: " +
                    missingTypes.joinToString(", ")
            )
        }

        return selected.distinctBy { it.name }
    }

    private fun normalizeAbi(name: String): String? {
        return when (
            name.removePrefix("config.")
                .lowercase(Locale.ROOT)
        ) {
            "arm64_v8a" -> "arm64-v8a"
            "armeabi_v7a" -> "armeabi-v7a"
            "armeabi" -> "armeabi"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            else -> null
        }
    }

    private fun normalizeLocale(value: String): String {
        return value
            .removePrefix("config.")
            .lowercase(Locale.ROOT)
            .replace('_', '-')
            .replace("-r", "-")
    }
}
