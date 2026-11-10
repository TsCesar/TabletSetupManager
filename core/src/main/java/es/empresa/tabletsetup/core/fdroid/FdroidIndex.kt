package es.empresa.tabletsetup.core.fdroid

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import es.empresa.tabletsetup.core.model.ApkAsset
import es.empresa.tabletsetup.core.model.AppRelease
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.SourceKind
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.policy.CompatibilityPolicy
import es.empresa.tabletsetup.core.util.Hashing
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.security.cert.Certificate
import java.util.jar.JarFile

/** Repositorio oficial F-Droid. */
object FdroidRepo {
    const val REPO_URL = "https://f-droid.org/repo/"
    const val INDEX_JAR = "index-v1.jar"
    const val INDEX_ENTRY = "index-v1.json"

    /**
     * SHA-256 del certificado que firma el índice del repositorio oficial
     * (no confundir con la firma de la app cliente de F-Droid).
     */
    const val PINNED_INDEX_SIGNER_SHA256 = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
    const val MAX_INDEX_BYTES = 128L * 1024 * 1024
}

/** Una APK publicada en el índice. */
data class FdroidCandidate(
    val versionCode: Long,
    val versionName: String?,
    val apkName: String,
    val sha256: String,
    val minSdk: Int,
    val maxSdk: Int?,
    val targetSdk: Int?,
    val abis: Set<String>,
    val signer: String?,
    val size: Long?,
)

data class FdroidLookup(
    val packageFound: Boolean,
    val suggestedVersionCode: Long?,
    val candidates: List<FdroidCandidate>,
)

/**
 * Lee `index-v1.jar`: verifica la firma JAR y el certificado fijado, y extrae en una sola
 * pasada (streaming, apto para tablets con poca memoria) las entradas de los paquetes pedidos.
 * Falla de forma cerrada: sin firma válida no se devuelve ningún dato.
 */
object FdroidIndexReader {
    private val APK_NAME = Regex("^[A-Za-z0-9_.-]{1,200}\\.apk$")

    fun read(
        jarFile: File,
        packages: Set<String>,
        pinnedSignerSha256: String = FdroidRepo.PINNED_INDEX_SIGNER_SHA256,
    ): Map<String, FdroidLookup> {
        val result: Map<String, FdroidLookup>
        val certificates: Array<Certificate>?
        try {
            JarFile(jarFile, true).use { jar ->
                val entry = jar.getJarEntry(FdroidRepo.INDEX_ENTRY)
                    ?: throw ValidationException("Índice F-Droid sin ${FdroidRepo.INDEX_ENTRY}")
                jar.getInputStream(entry).use { input ->
                    result = parse(input, packages)
                    drain(input) // Leer hasta el final es lo que dispara la comprobación de digest JAR.
                }
                certificates = entry.certificates
            }
        } catch (e: SecurityException) {
            throw ValidationException("Índice F-Droid alterado: la firma no coincide con el contenido")
        } catch (e: ValidationException) {
            throw e
        } catch (e: Exception) {
            throw ValidationException("Índice F-Droid corrupto o ilegible (${e.javaClass.simpleName})")
        }
        if (certificates.isNullOrEmpty()) throw ValidationException("Índice F-Droid sin firma")
        val pinned = Hashing.normalize(pinnedSignerSha256)
        val trusted = certificates.any { Hashing.sha256Hex(it.encoded) == pinned }
        if (!trusted) throw ValidationException("El certificado del índice F-Droid no es el oficial")
        return result
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (input.read(buffer) >= 0) Unit
    }

    /** Analiza el JSON del índice v1. Público para pruebas. */
    fun parse(input: InputStream, packages: Set<String>): Map<String, FdroidLookup> {
        val suggested = mutableMapOf<String, Long?>()
        val found = mutableMapOf<String, MutableList<FdroidCandidate>>()
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "apps" -> {
                    reader.beginArray()
                    while (reader.hasNext()) readApp(reader, packages, suggested)
                    reader.endArray()
                }
                "packages" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name in packages && reader.peek() == JsonToken.BEGIN_ARRAY) {
                            val list = found.getOrPut(name) { mutableListOf() }
                            reader.beginArray()
                            while (reader.hasNext()) readPackage(reader, name)?.let { list += it }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return packages.associateWith { pkg ->
            FdroidLookup(
                packageFound = found.containsKey(pkg) || suggested.containsKey(pkg),
                suggestedVersionCode = suggested[pkg],
                candidates = found[pkg].orEmpty(),
            )
        }
    }

    private fun readApp(reader: JsonReader, packages: Set<String>, suggested: MutableMap<String, Long?>) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue(); return
        }
        var pkg: String? = null
        var suggestedCode: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "packageName" -> pkg = nextStringOrNull(reader)
                "suggestedVersionCode" -> suggestedCode = nextLongOrNull(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (pkg != null && pkg in packages) suggested[pkg] = suggestedCode
    }

    private fun readPackage(reader: JsonReader, expected: String): FdroidCandidate? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue(); return null
        }
        var id: String? = null
        var apkName: String? = null
        var hash: String? = null
        var hashType: String? = null
        var versionCode: Long? = null
        var versionName: String? = null
        var minSdk: Long? = null
        var maxSdk: Long? = null
        var targetSdk: Long? = null
        var signer: String? = null
        var size: Long? = null
        val abis = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "packageName" -> id = nextStringOrNull(reader)
                "apkName" -> apkName = nextStringOrNull(reader)
                "hash" -> hash = nextStringOrNull(reader)
                "hashType" -> hashType = nextStringOrNull(reader)
                "versionCode" -> versionCode = nextLongOrNull(reader)
                "versionName" -> versionName = nextStringOrNull(reader)
                "minSdkVersion" -> minSdk = nextLongOrNull(reader)
                "maxSdkVersion" -> maxSdk = nextLongOrNull(reader)
                "targetSdkVersion" -> targetSdk = nextLongOrNull(reader)
                "signer" -> signer = nextStringOrNull(reader)
                "size" -> size = nextLongOrNull(reader)
                "nativecode" -> if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) nextStringOrNull(reader)?.let { abis += it }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (id != null && id != expected) return null
        if (apkName == null || !APK_NAME.matches(apkName)) return null
        if (hashType != null && !hashType.equals("sha256", ignoreCase = true)) return null
        if (!Hashing.isSha256Hex(hash)) return null
        val code = versionCode ?: return null
        if (code <= 0) return null
        return FdroidCandidate(
            versionCode = code,
            versionName = versionName,
            apkName = apkName,
            sha256 = Hashing.normalize(hash!!),
            minSdk = (minSdk ?: 1L).toInt(),
            maxSdk = maxSdk?.toInt(),
            targetSdk = targetSdk?.toInt(),
            abis = abis,
            signer = signer?.takeIf { Hashing.isSha256Hex(it) }?.let { Hashing.normalize(it) },
            size = size,
        )
    }

    private fun nextStringOrNull(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> { reader.skipValue(); null }
    }

    private fun nextLongOrNull(reader: JsonReader): Long? = when (reader.peek()) {
        JsonToken.NUMBER -> reader.nextString().toLongOrNull()
        JsonToken.STRING -> reader.nextString().trim().toLongOrNull()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> { reader.skipValue(); null }
    }
}

/** Elige la mejor APK del índice para la tablet. */
object FdroidSelector {
    sealed interface Selection {
        data class Found(val release: AppRelease, val candidate: FdroidCandidate) : Selection

        /**
         * Sin APK utilizable. [incompatible] = la app está publicada pero ninguna versión estable
         * sirve para esta tablet (Android o arquitectura): se muestra «No compatible», no «Fuente pendiente».
         */
        data class Unavailable(val reason: String, val incompatible: Boolean = false) : Selection
    }

    fun choose(lookup: FdroidLookup, packageName: String, device: DeviceProfile, repoUrl: String = FdroidRepo.REPO_URL): Selection {
        if (!lookup.packageFound || lookup.candidates.isEmpty()) {
            return Selection.Unavailable("La aplicación no está publicada en F-Droid")
        }
        val compatible = lookup.candidates.filter { c ->
            device.sdkInt >= c.minSdk &&
                (c.maxSdk == null || device.sdkInt <= c.maxSdk) &&
                CompatibilityPolicy.abiCompatible(c.abis, device.supportedAbis)
        }
        if (compatible.isEmpty()) {
            val abiOk = lookup.candidates.filter { CompatibilityPolicy.abiCompatible(it.abis, device.supportedAbis) }
            val detail = when {
                abiOk.isEmpty() -> "arquitecturas en F-Droid: ${lookup.candidates.flatMap { it.abis }.distinct().sorted().joinToString(", ")}; " +
                    "tablet: ${device.supportedAbis.joinToString(", ")}"
                device.sdkInt < abiOk.minOf { it.minSdk } ->
                    "requiere Android ${CompatibilityPolicy.androidName(abiOk.minOf { it.minSdk })} o superior"
                else -> "las APK publicadas no admiten Android ${CompatibilityPolicy.androidName(device.sdkInt)}"
            }
            return Selection.Unavailable("Ninguna versión publicada en F-Droid es compatible con esta tablet ($detail)", incompatible = true)
        }
        val suggested = lookup.suggestedVersionCode
        val stable = if (suggested != null && suggested > 0) compatible.filter { it.versionCode <= suggested } else compatible
        if (stable.isEmpty()) {
            return Selection.Unavailable("F-Droid solo ofrece versiones de prueba (beta) compatibles", incompatible = true)
        }
        val best = stable.maxWith(
            compareBy<FdroidCandidate>({ it.versionCode }, { -CompatibilityPolicy.abiRank(it.abis, device.supportedAbis) })
        )
        val release = AppRelease(
            packageName = packageName,
            versionCode = best.versionCode,
            versionName = best.versionName,
            minSdk = best.minSdk,
            maxSdk = best.maxSdk,
            abis = best.abis,
            assets = listOf(ApkAsset(repoUrl + best.apkName, best.sha256, best.apkName, best.size)),
            expectedSigners = setOfNotNull(best.signer),
            origin = SourceKind.FDROID,
            originLabel = "F-Droid",
        )
        return Selection.Found(release, best)
    }
}
