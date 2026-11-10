package es.empresa.tabletsetup.core.apk

import com.android.apksig.ApkVerifier
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.util.Hashing
import java.io.File
import java.security.cert.X509Certificate
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Resultado del análisis de un archivo APK. */
data class ApkFileInfo(
    val file: File,
    val manifest: ManifestInfo,
    /** ABI con bibliotecas nativas (entradas lib/ABI/archivo.so). */
    val nativeAbis: Set<String>,
    /** SHA-256 de los certificados firmantes actuales. */
    val signers: Set<String>,
    /** Certificados actuales + históricos (rotación de clave APK Signature Scheme v3). */
    val signerLineage: Set<String>,
    val schemes: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Inspección de APK independiente del fabricante:
 *  - integridad ZIP y presencia de AndroidManifest.xml;
 *  - lectura del manifiesto binario (paquete, versión, SDK, split);
 *  - ABI de código nativo;
 *  - verificación criptográfica de la firma (v1/v2/v3) con apksig, la misma
 *    biblioteca que usa `apksigner`, evaluada para el nivel de API de la tablet.
 */
object ApkInspector {
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private val LIB_ENTRY = Regex("^lib/([^/]+)/[^/]+\\.so$")

    fun inspect(file: File, deviceSdk: Int): ApkFileInfo {
        if (!file.isFile || file.length() == 0L) throw ValidationException("Archivo APK vacío o inexistente")
        val sha256 = Hashing.sha256Hex(file)
        val (manifestBytes, abis) = readZip(file)
        val manifest = ManifestReader.read(manifestBytes)
        val verification = verifySignature(file, deviceSdk)
        return ApkFileInfo(
            file = file,
            manifest = manifest,
            nativeAbis = abis,
            signers = verification.first,
            signerLineage = verification.second,
            schemes = verification.third,
            sha256 = sha256,
            sizeBytes = file.length(),
        )
    }

    private fun readZip(file: File): Pair<ByteArray, Set<String>> {
        try {
            ZipFile(file).use { zip ->
                val abis = mutableSetOf<String>()
                var manifest: ByteArray? = null
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/")) {
                        throw ValidationException("La APK contiene rutas no válidas")
                    }
                    LIB_ENTRY.matchEntire(name)?.let { abis += it.groupValues[1] }
                    if (name == "AndroidManifest.xml") {
                        if (entry.size > MAX_MANIFEST_BYTES) throw ValidationException("Manifiesto demasiado grande")
                        manifest = zip.getInputStream(entry).use { input -> input.readBytes() }
                    }
                }
                val bytes = manifest ?: throw ValidationException("El archivo no es una APK (falta AndroidManifest.xml)")
                return bytes to abis
            }
        } catch (e: ValidationException) {
            throw e
        } catch (e: ZipException) {
            throw ValidationException("Archivo corrupto: no es un ZIP/APK válido")
        } catch (e: Exception) {
            throw ValidationException("No se pudo leer la APK (${e.javaClass.simpleName})")
        }
    }

    /** Devuelve (firmantes actuales, linaje de firmantes, esquemas). */
    private fun verifySignature(file: File, deviceSdk: Int): Triple<Set<String>, Set<String>, String> {
        val result = try {
            ApkVerifier.Builder(file)
                .setMinCheckedPlatformVersion(deviceSdk)
                .setMaxCheckedPlatformVersion(deviceSdk)
                .build()
                .verify()
        } catch (e: Exception) {
            throw ValidationException("No se pudo verificar la firma de la APK (${e.javaClass.simpleName})")
        }
        if (!result.isVerified) {
            val unsigned = result.errors.any { it.issue.name == "JAR_SIG_NO_MANIFEST" } &&
                result.v2SchemeSigners.isEmpty() && result.v3SchemeSigners.isEmpty()
            if (unsigned) throw ValidationException("La APK no está firmada: Android no permitiría instalarla")
            val reason = result.errors.firstOrNull()?.toString()?.take(160) ?: "firma no válida"
            throw ValidationException("Firma de APK no válida o archivo alterado: $reason")
        }
        val current = result.signerCertificates.map { sha256(it) }.toSet()
        if (current.isEmpty()) throw ValidationException("La APK no tiene certificado de firma")
        val lineage = try {
            result.signingCertificateLineage?.certificatesInLineage?.map { sha256(it) }?.toSet().orEmpty()
        } catch (e: Exception) {
            emptySet()
        }
        val schemes = buildList {
            if (result.isVerifiedUsingV1Scheme) add("v1")
            if (result.isVerifiedUsingV2Scheme) add("v2")
            if (result.isVerifiedUsingV3Scheme) add("v3")
        }.joinToString("+")
        return Triple(current, current + lineage, schemes)
    }

    private fun sha256(certificate: X509Certificate): String = Hashing.sha256Hex(certificate.encoded)
}
