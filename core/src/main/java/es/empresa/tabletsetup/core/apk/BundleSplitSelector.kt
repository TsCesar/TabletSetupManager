package es.empresa.tabletsetup.core.apk

import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.ValidationException
import java.util.Locale
import kotlin.math.abs

/**
 * Elige del ZIP APKM/APKS/XAPK las piezas apropiadas para esta tablet.
 * APKs y certificados siguen siendo verificados por ApkInspector/ApkSetValidator.
 */
object BundleSplitSelector {
    private val knownAbi = setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64")
    private val density = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213,
        "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
    private enum class Kind { ABI, DENSITY, LANGUAGE, OTHER }
    private data class Config(val name: String, val group: String, val token: String, val kind: Kind)

    fun select(files: List<String>, device: DeviceProfile): List<String> {
        if (files.isEmpty()) throw ValidationException("El contenedor no contiene archivos APK")
        if (files.size > 160) throw ValidationException("Demasiadas APK en el contenedor")
        if (files.distinct().size != files.size) throw ValidationException("Nombres de APK duplicados")
        val preferred = files.filter { filename(it) in setOf("base.apk", "base-master.apk", "split_base.apk") }
        val main = when {
            preferred.size == 1 -> preferred.single()
            preferred.size > 1 -> throw ValidationException("Hay varias APK base")
            files.size == 1 -> files.single()
            else -> files.filter { parse(it) == null && !filename(it).startsWith("split_") }
                .singleOrNull() ?: throw ValidationException("No se puede identificar la APK base")
        }
        val configurations = files.filter { it != main }.mapNotNull(::parse)
        val result = mutableListOf(main)
        result += files.filter { it != main && parse(it) == null }
        val deviceAbis = device.supportedAbis.map { it.lowercase(Locale.ROOT) }
        val locales = device.preferredLocales.map(::normalizeLocale)
        configurations.groupBy { it.group }.forEach { (_, configs) ->
            val cpu = configs.filter { it.kind == Kind.ABI }
            if (cpu.isNotEmpty()) {
                val matched = deviceAbis.firstNotNullOfOrNull { abi ->
                    cpu.firstOrNull { normalizeAbi(it.token) == abi }
                } ?: throw ValidationException(
                    "No hay APK compatible con la arquitectura " + device.supportedAbis.joinToString()
                )
                result += matched.name
            }
            val dpi = configs.filter { it.kind == Kind.DENSITY }
            if (dpi.isNotEmpty()) {
                if (device.densityDpi <= 0) throw ValidationException("Densidad de la tablet desconocida")
                result += dpi.minBy { abs(density.getValue(it.token) - device.densityDpi) }.name
            }
            val languages = configs.filter { it.kind == Kind.LANGUAGE }
            if (languages.isNotEmpty()) {
                val matched = locales.firstNotNullOfOrNull { locale ->
                    languages.firstOrNull { normalizeLocale(it.token) == locale } ?:
                        languages.firstOrNull { normalizeLocale(it.token) == locale.substringBefore('-') }
                } ?: languages.firstOrNull { normalizeLocale(it.token) == "en" }
                if (matched != null) result += matched.name
            }
            result += configs.filter { it.kind == Kind.OTHER }.map { it.name }
        }
        if (result.size > 40) throw ValidationException("Demasiadas APK seleccionadas para la sesión de instalación")
        return result.distinct()
    }

    private fun filename(path: String) = path.substringAfterLast('/').lowercase(Locale.ROOT)

    private fun parse(path: String): Config? {
        val stem = filename(path).removeSuffix(".apk").removePrefix("split_")
        val group: String
        val token: String
        when {
            stem.startsWith("config.") -> {
                group = "config"
                token = stem.removePrefix("config.")
            }
            stem.contains(".config.") -> {
                group = stem.substringBeforeLast(".config.") + ".config"
                token = stem.substringAfterLast(".config.")
            }
            else -> return null
        }
        val type = when {
            normalizeAbi(token) in knownAbi -> Kind.ABI
            token in density -> Kind.DENSITY
            normalizeLocale(token).matches(Regex("[a-z]{2,3}(?:-[a-z]{2})?")) -> Kind.LANGUAGE
            else -> Kind.OTHER
        }
        return Config(path, group, token, type)
    }

    private fun normalizeAbi(s: String) = when (s.lowercase(Locale.ROOT)) {
        "arm64_v8a" -> "arm64-v8a"
        "armeabi_v7a" -> "armeabi-v7a"
        "x86-64" -> "x86_64"
        else -> s.lowercase(Locale.ROOT)
    }

    private fun normalizeLocale(s: String) = s.lowercase(Locale.ROOT)
        .removePrefix("b+").replace("+", "-").replace("-r", "-").replace('_', '-')
}
