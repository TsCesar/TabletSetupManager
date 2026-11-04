package es.empresa.tabletsetup.core.catalog

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import es.empresa.tabletsetup.core.model.ApkAsset
import es.empresa.tabletsetup.core.model.AppRelease
import es.empresa.tabletsetup.core.model.SourceKind
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.policy.ExcludedApps
import es.empresa.tabletsetup.core.util.Hashing
import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Catálogo empresarial (schemaVersion 2).
 *
 * Se descarga por HTTPS y SOLO se acepta si su firma ECDSA P-256 (SHA256withECDSA),
 * publicada en `<url>.sig`, es válida con la clave pública configurada. Además se
 * impide reutilizar catálogos antiguos (número de secuencia creciente).
 * Puede modificarse sin recompilar las aplicaciones: basta con volver a firmarlo
 * con `tools/sign_catalog.py`.
 */
data class EnterpriseCatalog(
    val schemaVersion: Int,
    val catalogId: String?,
    val sequence: Long,
    val generatedAt: String?,
    val releases: Map<String, List<CatalogRelease>>,
    val directSources: Map<String, DirectSourceEntry>,
    val signerPins: Map<String, Set<String>>,
    val warnings: List<String>,
)

/** Versión concreta alojada en un servidor autorizado. */
data class CatalogRelease(val release: AppRelease, val override: Boolean)

/** URL oficial directa (sin versión anunciada) declarada por la empresa. */
data class DirectSourceEntry(
    val packageName: String,
    val url: String,
    val signers: Set<String>,
    val override: Boolean,
)

object EnterpriseCatalogParser {
    const val SCHEMA_VERSION = 2
    private val FILE_NAME = Regex("^[A-Za-z0-9_.-]{1,100}\\.apk$")
    private val ABI = Regex("^[a-z0-9_-]{2,20}$")

    /**
     * Analiza el JSON (ya verificado criptográficamente). Las entradas inválidas o ajenas
     * al catálogo fijo se descartan con aviso; los errores estructurales rechazan el catálogo.
     */
    fun parse(json: String, knownPackages: Set<String>): EnterpriseCatalog {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw ValidationException("El catálogo empresarial no es un JSON válido")
        }
        if (!root.isJsonObject) throw ValidationException("El catálogo empresarial debe ser un objeto JSON")
        val obj = root.asJsonObject
        val schema = obj.intOrNull("schemaVersion")
        if (schema == 1) {
            throw ValidationException("Catálogo v1 sin firma: regenéralo con tools/create_catalog.py y fírmalo con tools/sign_catalog.py")
        }
        if (schema != SCHEMA_VERSION) throw ValidationException("Versión de esquema de catálogo no soportada: $schema")
        val sequence = obj.longOrNull("sequence")
            ?: throw ValidationException("El catálogo no tiene número de secuencia («sequence»)")
        if (sequence < 1) throw ValidationException("Número de secuencia de catálogo inválido")
        val apps = obj.get("apps")
        if (apps == null || !apps.isJsonArray) throw ValidationException("El catálogo no contiene la lista «apps»")

        val warnings = mutableListOf<String>()
        val releases = linkedMapOf<String, MutableList<CatalogRelease>>()
        val direct = linkedMapOf<String, DirectSourceEntry>()

        apps.asJsonArray.forEachIndexed { index, element ->
            val where = "apps[$index]"
            if (!element.isJsonObject) {
                warnings += "$where: entrada ignorada (no es un objeto)"
                return@forEachIndexed
            }
            val item = element.asJsonObject
            val pkg = item.stringOrNull("packageName")
            if (pkg.isNullOrBlank()) {
                warnings += "$where: entrada ignorada (sin packageName)"
                return@forEachIndexed
            }
            if (ExcludedApps.isExcluded(pkg, item.stringOrNull("label"))) {
                warnings += "$where: $pkg ignorada: aplicación excluida de la suite"
                return@forEachIndexed
            }
            if (pkg !in knownPackages) {
                warnings += "$where: $pkg ignorada: no pertenece al catálogo de la suite"
                return@forEachIndexed
            }
            try {
                when (val type = item.stringOrNull("type") ?: "versioned") {
                    "versioned" -> {
                        val parsed = parseVersioned(pkg, item)
                        releases.getOrPut(pkg) { mutableListOf() } += parsed
                    }
                    "direct" -> direct[pkg] = parseDirect(pkg, item)
                    else -> warnings += "$where: $pkg ignorada: tipo «$type» desconocido"
                }
            } catch (e: ValidationException) {
                warnings += "$where: $pkg ignorada: ${e.message}"
            }
        }

        val pins = linkedMapOf<String, Set<String>>()
        obj.get("signerPins")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (pkg, value) ->
            if (pkg in knownPackages) {
                try {
                    pins[pkg] = parseSigners(value, required = true)
                } catch (e: ValidationException) {
                    warnings += "signerPins.$pkg ignorado: ${e.message}"
                }
            }
        }

        return EnterpriseCatalog(
            schemaVersion = schema,
            catalogId = obj.stringOrNull("catalogId"),
            sequence = sequence,
            generatedAt = obj.stringOrNull("generatedAt"),
            releases = releases,
            directSources = direct,
            signerPins = pins,
            warnings = warnings,
        )
    }

    private fun parseVersioned(pkg: String, item: JsonObject): CatalogRelease {
        val versionCode = item.longOrNull("versionCode") ?: throw ValidationException("falta versionCode")
        if (versionCode <= 0) throw ValidationException("versionCode inválido")
        val signers = parseSigners(item.get("signingCertSha256"), required = true)
        val assetsJson = item.get("assets")
        if (assetsJson == null || !assetsJson.isJsonArray || assetsJson.asJsonArray.size() == 0) {
            throw ValidationException("no hay APK («assets»)")
        }
        val assets = assetsJson.asJsonArray.mapIndexed { i, a ->
            if (!a.isJsonObject) throw ValidationException("asset $i inválido")
            val asset = a.asJsonObject
            val url = asset.stringOrNull("url") ?: throw ValidationException("asset $i sin URL")
            requireHttps(url)
            val sha = asset.stringOrNull("sha256")
            if (!Hashing.isSha256Hex(sha)) throw ValidationException("asset $i sin SHA-256 válido")
            val name = asset.stringOrNull("filename") ?: if (i == 0) "base.apk" else "split$i.apk"
            if (!FILE_NAME.matches(name)) throw ValidationException("nombre de APK no permitido: $name")
            val size = asset.longOrNull("size")
            if (size != null && size <= 0) throw ValidationException("tamaño inválido en asset $i")
            ApkAsset(url = url, sha256 = Hashing.normalize(sha!!), fileName = name, sizeBytes = size)
        }
        if (assets.map { it.fileName.lowercase() }.distinct().size != assets.size) {
            throw ValidationException("nombres de APK duplicados")
        }
        val minSdk = item.intOrNull("minSdk") ?: 28
        val maxSdk = item.intOrNull("maxSdk")
        if (maxSdk != null && maxSdk < minSdk) throw ValidationException("maxSdk menor que minSdk")
        val abis = item.get("abis")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString.trim() else null }
            ?.onEach { if (!ABI.matches(it)) throw ValidationException("ABI no válida: $it") }
            ?.toSet() ?: emptySet()
        val release = AppRelease(
            packageName = pkg,
            versionCode = versionCode,
            versionName = item.stringOrNull("versionName"),
            minSdk = minSdk,
            maxSdk = maxSdk,
            abis = abis,
            assets = assets,
            expectedSigners = signers,
            origin = SourceKind.ENTERPRISE_CATALOG,
        )
        return CatalogRelease(release, override = item.booleanOrFalse("override"))
    }

    private fun parseDirect(pkg: String, item: JsonObject): DirectSourceEntry {
        val url = item.stringOrNull("url") ?: throw ValidationException("falta url")
        requireHttps(url)
        return DirectSourceEntry(
            packageName = pkg,
            url = url,
            signers = parseSigners(item.get("signingCertSha256"), required = false),
            override = item.booleanOrFalse("override"),
        )
    }

    private fun parseSigners(element: JsonElement?, required: Boolean): Set<String> {
        val values = when {
            element == null || element.isJsonNull -> emptyList()
            element.isJsonArray -> element.asJsonArray.map { if (it.isJsonPrimitive) it.asString else "" }
            element.isJsonPrimitive -> listOf(element.asString)
            else -> throw ValidationException("certificado de firma con formato no válido")
        }
        if (required && values.isEmpty()) throw ValidationException("falta signingCertSha256 (certificado de firma autorizado)")
        values.forEach { if (!Hashing.isSha256Hex(it)) throw ValidationException("certificado de firma no es un SHA-256 válido") }
        return values.map { Hashing.normalize(it) }.toSet()
    }

    fun requireHttps(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw ValidationException("URL no válida: $url")
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw ValidationException("solo se admiten URL HTTPS: $url")
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
}

/** Verificación de la firma del catálogo (ECDSA P-256 + SHA-256). Kotlin puro. */
object CatalogSignature {
    const val ALGORITHM = "SHA256withECDSA"

    /** Acepta PEM («-----BEGIN PUBLIC KEY-----») o Base64 de la clave X.509/SPKI. */
    fun parsePublicKey(text: String): PublicKey {
        val body = text.trim()
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        if (body.isEmpty()) throw ValidationException("No hay clave pública de catálogo configurada")
        return try {
            val der = Base64.getDecoder().decode(body)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        } catch (e: Exception) {
            throw ValidationException("La clave pública del catálogo no es una clave EC válida (Base64 X.509)")
        }
    }

    /** Huella SHA-256 de la clave pública, para mostrarla y compararla. */
    fun fingerprint(key: PublicKey): String = Hashing.toHex(MessageDigest.getInstance("SHA-256").digest(key.encoded))

    /**
     * Admite firma DER, firma DER en Base64 (salida de `openssl dgst | base64`) o
     * firma «r||s» de 64 bytes en Base64.
     */
    fun decodeSignature(content: ByteArray): ByteArray {
        if (looksLikeDer(content)) return content
        val text = String(content, Charsets.US_ASCII).trim()
        val decoded = try {
            Base64.getMimeDecoder().decode(text)
        } catch (e: Exception) {
            throw ValidationException("La firma del catálogo no tiene un formato válido")
        }
        return when {
            looksLikeDer(decoded) -> decoded
            decoded.size == 64 -> rawToDer(decoded)
            else -> throw ValidationException("La firma del catálogo no tiene un formato válido")
        }
    }

    fun verify(data: ByteArray, signatureFile: ByteArray, publicKey: PublicKey): Boolean = try {
        val signature = decodeSignature(signatureFile)
        Signature.getInstance(ALGORITHM).run {
            initVerify(publicKey)
            update(data)
            verify(signature)
        }
    } catch (e: ValidationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun looksLikeDer(bytes: ByteArray): Boolean =
        bytes.size in 8..80 && bytes[0] == 0x30.toByte() && (bytes[1].toInt() and 0xFF) == bytes.size - 2 && bytes[2] == 0x02.toByte()

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(part: ByteArray): ByteArray {
            val value = BigInteger(1, part).toByteArray() // incluye 0x00 inicial si hace falta
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val r = integer(raw.copyOfRange(0, 32))
        val s = integer(raw.copyOfRange(32, 64))
        val body = r + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
