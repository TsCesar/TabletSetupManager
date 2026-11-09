package es.empresa.tabletsetup.core.sources

import com.google.gson.JsonParser
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.net.Downloader
import es.empresa.tabletsetup.core.net.DownloadException
import java.net.URI
import java.util.Base64
import java.util.Locale

/**
 * Archivo APK adicional de un Android App Bundle.
 *
 * ABI: arquitectura del procesador.
 * DENSITY: densidad de pantalla.
 * LOCALE: idioma.
 */
data class AptoideSplitInfo(
    val name: String,
    val type: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
)

/**
 * Información de una aplicación obtenida desde Aptoide.
 * Incluye el APK base y los metadatos de sus splits.
 */
data class AptoideApkInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val downloadUrl: String,
    val minSdk: Int?,
    val requiredSplitTypes: Set<String> = emptySet(),
    val splits: List<AptoideSplitInfo> = emptyList(),
)

/**
 * Consulta versiones de aplicaciones en Aptoide
 * filtrando por Android, arquitectura y densidad del dispositivo.
 */
class AptoideSource(
    private val downloader: Downloader
) {
    companion object {
        private const val API_URL =
            "https://ws2.aptoide.com/api/7/app/getMeta"
        private const val MAX_RESPONSE_BYTES =
            2L * 1024 * 1024
        private val ALLOWED_PACKAGES = setOf(
            "com.android.chrome",
            "com.google.android.apps.maps",
            "com.google.android.gm",
            "com.waze",
            "com.adobe.reader"
        )
        private val PROVIDER_TOKEN_PATTERN =
            Regex("[A-Za-z0-9_-]+={0,2}")

        // Límite de consultas a versiones antiguas por perfil de dispositivo.
        private const val MAX_VERSION_ATTEMPTS = 6

        /**
         * Chrome 138 es la última versión para Android 9. El código 7204xxxx
         * corresponde a esa serie, documentada en sus variantes de APKMirror.
         * Es una preferencia de búsqueda, no una aceptación sin verificar.
         */
        internal fun initialMaxVersionCode(packageName: String, sdkInt: Int): Long? =
            when {
                // Última serie compatible de Chrome para Android 9.
                packageName == "com.android.chrome" && sdkInt == 28 -> 720_499_999L
                // APKMirror publica una variante Gmail para Android 9 con
                // versionCode 66019814 (septiembre 2026). Aptoide ofrece en
                // cambio ramas más nuevas que piden SDK 29. Empezar por un
                // código documentado; SDK/ABI/firma se comprueban igualmente.
                packageName == "com.google.android.gm" && sdkInt == 28 -> 66_019_814L
                else -> null
            }

        internal fun normalizeAbi(abi: String): String =
            when (val text = abi.trim().lowercase(Locale.ROOT)) {
                "x86-64" -> "x86_64"
                "arm-v7a" -> "armeabi-v7a"
                else -> text
            }

        /**
         * Estrategia progresiva para metadatos de Aptoide.
         * Se prioriza la variante específica; solo si esta no existe o no
         * es compatible se amplía la búsqueda. El APK real se valida después.
         */
        internal fun deviceQueryCandidates(
            sdkInt: Int,
            validAbis: List<String>,
            densityDpi: Int
        ): List<String> {
            val sdk = "maxSdk=$sdkInt"
            val cpu = if (validAbis.isEmpty()) sdk
                      else "$sdk&myCPU=${validAbis.joinToString(",")}"
            val full = if (densityDpi in 1..1000) "$cpu&myDensity=$densityDpi" else cpu
            return listOf(full, cpu, sdk, "").distinct()
        }

        /**
         * Comprueba las ABI declaradas en las APK divididas ANTES de seleccionar
         * una variante de Aptoide. Sin splits ABI, se deja decidir al validador.
         */
        internal fun supportsAbiSplits(
            splitNames: List<String>,
            validAbis: List<String>
        ): Boolean {
            if (splitNames.isEmpty() || validAbis.isEmpty()) return true
            return splitNames.any { name ->
                val value = name.substringAfterLast("config.").lowercase(Locale.ROOT)
                val abi = when (value) {
                    "arm64_v8a" -> "arm64-v8a"
                    "armeabi_v7a" -> "armeabi-v7a"
                    "armeabi" -> "armeabi"
                    "x86" -> "x86"
                    "x86_64" -> "x86_64"
                    else -> null
                }
                abi != null && abi in validAbis
            }
        }
    }

    /**
     * Obtiene los metadatos de la aplicación
     * para una versión de Android y, opcionalmente, ABI y densidad.
     *
     * sdkInt = 28 -> Android 9
     * sdkInt = 29 -> Android 10
     * sdkInt = 30 -> Android 11
     */
    fun lookup(
        packageName: String,
        sdkInt: Int,
        supportedAbis: List<String> = emptyList(),
        densityDpi: Int = 0
    ): AptoideApkInfo {
        // Solo aplicaciones autorizadas.
        if (packageName !in ALLOWED_PACKAGES) {
            throw ValidationException(
                "Aplicación no autorizada para Aptoide"
            )
        }
        // Tablet Setup Manager admite Android 9+.
        if (sdkInt < 28) {
            throw ValidationException(
                "Versión de Android no admitida por Tablet Setup Manager"
            )
        }
        // Filtro de compatibilidad de Aptoide: Android, CPU y densidad.
        val validAbis = supportedAbis
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter {
                it in setOf(
                    "arm64-v8a",
                    "armeabi-v7a",
                    "armeabi",
                    "x86",
                    "x86_64"
                )
            }
            .distinct()

        val queries = deviceQueryCandidates(sdkInt, validAbis, densityDpi)
        var candidateResponse: ByteArray? = null
        var lastUnavailable = "Aptoide no ofrece una variante compatible para este dispositivo"

        variantQueries@ for (deviceQuery in queries) {
            val qPart = if (deviceQuery.isEmpty()) {
                ""
            } else {
                val encodedQuery = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(deviceQuery.toByteArray(Charsets.UTF_8))
                "/q=$encodedQuery"
            }

            var maxVersionCode = initialMaxVersionCode(packageName, sdkInt)
            for (attempt in 0 until MAX_VERSION_ATTEMPTS) {
                val maxCodePart = maxVersionCode?.let { "/max_vercode=$it" }.orEmpty()
                val bytes = try {
                    downloader.fetch(
                        url = "$API_URL/package_name=$packageName$qPart$maxCodePart/aab=1",
                        maxBytes = MAX_RESPONSE_BYTES
                    )
                } catch (e: DownloadException) {
                    if (e.httpCode == 404) {
                        lastUnavailable = "Aptoide: no se encontró una variante (HTTP 404)"
                        break
                    }
                    throw e
                }

                // Comprobar metadatos antes de elegir la variante y descargarla.
                // La validación criptográfica y del manifiesto se realiza al instalar.
                val candidate = JsonParser.parseString(
                    String(bytes, Charsets.UTF_8)
                ).asJsonObject
                val status = candidate.getAsJsonObject("info")
                    ?.get("status")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                if (status != "OK") {
                    throw ValidationException("Aptoide no devolvió una respuesta válida")
                }

                val candidateData = candidate.getAsJsonObject("data")
                    ?: throw ValidationException("Aptoide no devolvió datos de la aplicación")
                if (candidateData.get("package")?.asString != packageName) {
                    throw ValidationException("El paquete devuelto por Aptoide no coincide")
                }
                val candidateFile = candidateData.getAsJsonObject("file")
                    ?: throw ValidationException("Aptoide no devolvió información del APK")
                val versionCode = candidateFile.get("vercode")
                    ?.takeIf { !it.isJsonNull }
                    ?.asLong
                    ?: throw ValidationException("Aptoide no devolvió un código de versión")
                if (versionCode <= 0L || (maxVersionCode != null && versionCode > maxVersionCode)) {
                    lastUnavailable = "Aptoide devolvió un código de versión fuera del límite solicitado"
                    break
                }
                maxVersionCode = (versionCode - 1L).takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                val hardware = candidateFile.getAsJsonObject("hardware")
                val minSdk = hardware?.get("sdk")?.takeIf { !it.isJsonNull }?.asInt
                if (minSdk != null && minSdk > sdkInt) {
                    lastUnavailable = "La variante de Aptoide requiere Android SDK $minSdk"
                    if (maxVersionCode == null) break
                    continue
                }
                val cpus = hardware?.get("cpus")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { it.takeIf { cpu -> cpu.isJsonPrimitive }?.asString?.let(::normalizeAbi) }
                    .orEmpty()
                if (validAbis.isNotEmpty() && cpus.isNotEmpty() &&
                    cpus.none { it in validAbis || it == "all" || it == "universal" }
                ) {
                    lastUnavailable = "La variante de Aptoide es para otra arquitectura"
                    if (maxVersionCode == null) break
                    continue
                }

                // La consulta general puede devolver AAB exclusivamente ARM
                // incluso cuando la tablet admite solo x86/x86_64.
                // Si el proveedor publica splits ABI incompatibles, probar el
                // siguiente conjunto de filtros en vez de aceptar esa variante.
                val aab = candidateData.get("aab")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                val abiSplitNames = aab?.get("splits")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { entry ->
                        if (!entry.isJsonObject) return@mapNotNull null
                        val split = entry.asJsonObject
                        if (!split.get("type")?.asString.equals("ABI", ignoreCase = true)) {
                            return@mapNotNull null
                        }
                        split.get("name")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                    }
                    .orEmpty()
                if (!supportsAbiSplits(abiSplitNames, validAbis)) {
                    lastUnavailable = "Aptoide no ofrece APK divididas compatibles con " +
                        validAbis.joinToString(", ")
                    if (maxVersionCode == null) break
                    continue
                }

                if (packageName == "com.android.chrome") {
                    val version = candidateFile.get("vername")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                    val major = version?.substringBefore(".")?.toIntOrNull()
                    if (major == null || major < 138) {
                        lastUnavailable = "Chrome ${version ?: "desconocido"} es anterior a la versión mínima 138"
                        if (maxVersionCode == null) break
                        continue
                    }
                }

                candidateResponse = bytes
                break@variantQueries
            }
        }

        val response = candidateResponse
            ?: throw ValidationException(lastUnavailable)

        // Interpretar respuesta JSON.
        val root = JsonParser.parseString(
            String(response, Charsets.UTF_8)
        ).asJsonObject

        // Verificar estado de la API.
        val status = root.getAsJsonObject("info")
            ?.get("status")
            ?.takeIf { !it.isJsonNull }
            ?.asString
        if (status != "OK") {
            throw ValidationException(
                "Aptoide no devolvió una respuesta válida"
            )
        }

        // Obtener metadatos.
        val data = root.getAsJsonObject("data")
            ?: throw ValidationException(
                "Aptoide no devolvió datos de la aplicación"
            )

        // Verificar paquete.
        val actualPackage = data.get("package")
            ?.takeIf { !it.isJsonNull }
            ?.asString
        if (actualPackage != packageName) {
            throw ValidationException(
                "El paquete devuelto por Aptoide no coincide"
            )
        }

        // Información del APK.
        val file = data.getAsJsonObject("file")
            ?: throw ValidationException(
                "Aptoide no devolvió información del APK"
            )
        val url = file.get("path")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw ValidationException(
                "Aptoide no devolvió una URL de descarga"
            )
        // Interpretar URL.
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw ValidationException(
                "La URL del APK no es válida"
            )
        }
        val path = uri.path.orEmpty()
        val host = uri.host.orEmpty()
        // APK directa en Aptoide.
        val isDirectApk =
            host.equals(
                "pool.apk.aptoide.com",
                ignoreCase = true
            ) &&
            path.endsWith(".apk", ignoreCase = true)
        // Enlace generado por el proveedor.
        val providerToken = path.removePrefix("/download/")
        val isProviderLink =
            host.equals(
                "url-provider.aptoide.com",
                ignoreCase = true
            ) &&
            path.startsWith("/download/") &&
            providerToken.isNotEmpty() &&
            providerToken.length <= 8192 &&
            PROVIDER_TOKEN_PATTERN.matches(providerToken)
        // Comprobar formato de URL y servidor inicial.
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.userInfo != null ||
            uri.port != -1 ||
            uri.rawQuery != null ||
            uri.rawFragment != null ||
            (!isDirectApk && !isProviderLink)
        ) {
            throw ValidationException(
                "La URL no pertenece a un servidor APK autorizado de Aptoide"
            )
        }
        // Código de versión.
        val versionCode = file.get("vercode")
            ?.takeIf { !it.isJsonNull }
            ?.asLong
            ?: throw ValidationException(
                "Aptoide no devolvió el código de versión"
            )
        if (versionCode <= 0L) {
            throw ValidationException(
                "Código de versión no válido"
            )
        }
        // Nombre de versión.
        val versionName = file.get("vername")
            ?.takeIf { !it.isJsonNull }
            ?.asString
        // Android mínimo requerido.
        val minSdk = file.getAsJsonObject("hardware")
            ?.get("sdk")
            ?.takeIf { !it.isJsonNull }
            ?.asInt
        // Comprobar compatibilidad básica.
        if (minSdk != null && minSdk > sdkInt) {
            throw ValidationException(
                "La versión disponible requiere Android SDK $minSdk; " +
                    "el dispositivo utiliza SDK $sdkInt"
            )
        }

        // Obtener los metadatos de Android App Bundle.
        val aabElement = data.get("aab")
        val aab = when {
            aabElement == null || aabElement.isJsonNull -> null
            aabElement.isJsonObject -> aabElement.asJsonObject
            else -> throw ValidationException(
                "Los metadatos AAB no son válidos"
            )
        }

        // Tipos de splits que requiere la aplicación.
        val requiredElement = aab?.get("required_split_types")
        if (
            requiredElement != null &&
            !requiredElement.isJsonNull &&
            !requiredElement.isJsonArray
        ) {
            throw ValidationException(
                "La lista de splits obligatorios no es válida"
            )
        }
        val requiredSplitTypes = requiredElement
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.map { it.asString.uppercase(Locale.ROOT) }
            ?.toSet()
            .orEmpty()

        // Lista de archivos APK adicionales.
        val splitsElement = aab?.get("splits")
        if (
            splitsElement != null &&
            !splitsElement.isJsonNull &&
            !splitsElement.isJsonArray
        ) {
            throw ValidationException(
                "La lista de APK divididas no es válida"
            )
        }
        val splitArray = splitsElement
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
        if (splitArray != null && splitArray.size() > 50) {
            throw ValidationException(
                "Aptoide devolvió demasiados archivos APK"
            )
        }
        val splits = splitArray?.map { element ->
            if (!element.isJsonObject) {
                throw ValidationException(
                    "Metadatos de APK dividida no válidos"
                )
            }
            val split = element.asJsonObject
            val name = split.get("name")
                ?.asString
                ?.takeIf {
                    it.isNotBlank() &&
                    it.length <= 100 &&
                    it.matches(Regex("[A-Za-z0-9._-]+"))
                }
                ?: throw ValidationException(
                    "Nombre de APK dividida no válido"
                )
            val type = split.get("type")
                ?.asString
                ?.uppercase(Locale.ROOT)
                ?: throw ValidationException(
                    "Tipo de APK dividida no disponible"
                )
            if (type !in setOf("ABI", "DENSITY", "LOCALE")) {
                throw ValidationException(
                    "Tipo de APK dividida no reconocido: $type"
                )
            }
            val splitUrl = split.get("path")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?: throw ValidationException(
                    "URL de APK dividida no disponible"
                )
            val splitUri = try {
                URI(splitUrl)
            } catch (e: Exception) {
                throw ValidationException(
                    "URL de APK dividida no válida"
                )
            }
            val splitHost = splitUri.host.orEmpty()
            val splitPath = splitUri.path.orEmpty()
            val isDirectSplit =
                splitHost.equals(
                    "pool.apk.aptoide.com",
                    ignoreCase = true
                ) &&
                splitPath.endsWith(".apk", ignoreCase = true)
            val splitToken = splitPath.removePrefix("/download/")
            val isProviderSplit =
                splitHost.equals(
                    "url-provider.aptoide.com",
                    ignoreCase = true
                ) &&
                splitPath.startsWith("/download/") &&
                splitToken.isNotEmpty() &&
                splitToken.length <= 8192 &&
                PROVIDER_TOKEN_PATTERN.matches(splitToken)
            if (
                !splitUri.scheme.equals("https", ignoreCase = true) ||
                splitUri.userInfo != null ||
                splitUri.port != -1 ||
                splitUri.rawQuery != null ||
                splitUri.rawFragment != null ||
                (!isDirectSplit && !isProviderSplit)
            ) {
                throw ValidationException(
                    "La APK dividida no tiene una URL autorizada"
                )
            }
            val sizeBytes = split.get("filesize")
                ?.takeIf { !it.isJsonNull }
                ?.asLong
            if (sizeBytes != null && sizeBytes <= 0L) {
                throw ValidationException(
                    "Tamaño de APK dividida no válido"
                )
            }
            AptoideSplitInfo(
                name = name,
                type = type,
                downloadUrl = splitUrl,
                sizeBytes = sizeBytes
            )
        }.orEmpty()

        // Comprobar que no existen APK divididas
        // con el mismo nombre.
        val splitNames = splits.map { it.name }
        if (splitNames.distinct().size != splitNames.size) {
            throw ValidationException(
                "Aptoide devolvió nombres de APK duplicados"
            )
        }

        // Tipos de splits admitidos por el instalador.
        val supportedSplitTypes = setOf(
            "ABI",
            "DENSITY",
            "LOCALE"
        )

        // Rechazar requisitos que aún no sabemos procesar.
        val unsupportedTypes =
            requiredSplitTypes - supportedSplitTypes
        if (unsupportedTypes.isNotEmpty()) {
            throw ValidationException(
                "Tipos de APK divididas no admitidos: " +
                    unsupportedTypes.joinToString(", ")
            )
        }

        // Comprobar que Aptoide proporciona los tipos obligatorios.
        val availableSplitTypes = splits
            .map { it.type }
            .toSet()
        val missingSplitTypes =
            requiredSplitTypes - availableSplitTypes
        if (missingSplitTypes.isNotEmpty()) {
            throw ValidationException(
                "Faltan APK divididas obligatorias: " +
                    missingSplitTypes.joinToString(", ")
            )
        }

        return AptoideApkInfo(
            packageName = actualPackage,
            versionCode = versionCode,
            versionName = versionName,
            downloadUrl = url,
            minSdk = minSdk,
            requiredSplitTypes = requiredSplitTypes,
            splits = splits
        )
    }
}
