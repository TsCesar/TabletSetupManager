package es.empresa.tabletsetup.core.testing

import com.android.apksig.ApkSigner
import es.empresa.tabletsetup.core.util.Hashing
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Claves de prueba (no son claves reales de producción). */
data class TestSigner(val name: String, val key: PrivateKey, val certificate: X509Certificate) {
    val sha256: String get() = Hashing.sha256Hex(certificate.encoded)
}

object TestKeys {
    private const val PASSWORD = "test1234"

    val signerA: TestSigner by lazy { load("signer-a.p12", "a") }
    val signerB: TestSigner by lazy { load("signer-b.p12", "b") }

    fun keyStore(resource: String): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        val stream = TestKeys::class.java.getResourceAsStream("/keys/$resource")
            ?: error("Recurso de prueba no encontrado: $resource")
        stream.use { ks.load(it, PASSWORD.toCharArray()) }
        return ks
    }

    val password: CharArray get() = PASSWORD.toCharArray()

    private fun load(resource: String, alias: String): TestSigner {
        val ks = keyStore(resource)
        return TestSigner(
            name = alias,
            key = ks.getKey(alias, PASSWORD.toCharArray()) as PrivateKey,
            certificate = ks.getCertificate(alias) as X509Certificate,
        )
    }
}

/**
 * Escritor mínimo de XML binario de Android para generar manifiestos de prueba.
 * apksig (biblioteca oficial de Google) también analiza estos manifiestos, lo que
 * sirve de comprobación cruzada del lector propio.
 */
object AxmlWriter {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private class Attr(val ns: Int, val name: Int, val raw: Int, val type: Int, val data: Int)

    fun manifest(
        packageName: String,
        versionCode: Int,
        versionName: String? = "1.0",
        minSdk: Int = 28,
        targetSdk: Int = 34,
        split: String? = null,
    ): ByteArray {
        // Cadenas con id de recurso primero (deben coincidir con el mapa de recursos).
        val strings = mutableListOf("versionCode", "versionName", "minSdkVersion", "targetSdkVersion")
        val resIds = intArrayOf(0x0101021b, 0x0101021c, 0x0101020c, 0x01010270)
        fun idx(value: String): Int {
            val i = strings.indexOf(value)
            if (i >= 0) return i
            strings += value
            return strings.size - 1
        }
        val sAndroid = idx("android")
        val sNs = idx(ANDROID_NS)
        val sManifest = idx("manifest")
        val sUsesSdk = idx("uses-sdk")
        val sPackage = idx("package")
        val sPkgValue = idx(packageName)
        val sSplit = idx("split")
        val sSplitValue = split?.let { idx(it) }
        val sVersionNameValue = versionName?.let { idx(it) }

        val manifestAttrs = mutableListOf(
            Attr(-1, sPackage, sPkgValue, 0x03, sPkgValue),
            Attr(sNs, 0, -1, 0x10, versionCode),
        )
        if (sVersionNameValue != null) manifestAttrs += Attr(sNs, 1, sVersionNameValue, 0x03, sVersionNameValue)
        if (sSplitValue != null) manifestAttrs += Attr(-1, sSplit, sSplitValue, 0x03, sSplitValue)
        val sdkAttrs = listOf(Attr(sNs, 2, -1, 0x10, minSdk), Attr(sNs, 3, -1, 0x10, targetSdk))

        val body = ByteArrayOutputStream()
        body.write(stringPool(strings))
        body.write(resourceMap(resIds))
        body.write(namespace(0x0100, sAndroid, sNs))
        body.write(startElement(sManifest, manifestAttrs))
        body.write(startElement(sUsesSdk, sdkAttrs))
        body.write(endElement(sUsesSdk))
        body.write(endElement(sManifest))
        body.write(namespace(0x0101, sAndroid, sNs))
        val content = body.toByteArray()
        val header = le(8).apply { putShort(0x0003); putShort(8); putInt(8 + content.size) }.array()
        return header + content
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        strings.forEach { s ->
            offsets += data.size()
            val b = le(2 + s.length * 2 + 2)
            b.putShort(s.length.toShort())
            s.forEach { b.putChar(it) }
            b.putShort(0)
            data.write(b.array())
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val total = stringsStart + data.size()
        val out = le(total)
        out.putShort(0x0001); out.putShort(headerSize.toShort()); out.putInt(total)
        out.putInt(strings.size); out.putInt(0); out.putInt(0); out.putInt(stringsStart); out.putInt(0)
        offsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        return out.array()
    }

    private fun resourceMap(ids: IntArray): ByteArray {
        val out = le(8 + ids.size * 4)
        out.putShort(0x0180); out.putShort(8); out.putInt(8 + ids.size * 4)
        ids.forEach { out.putInt(it) }
        return out.array()
    }

    private fun namespace(type: Int, prefix: Int, uri: Int): ByteArray {
        val out = le(24)
        out.putShort(type.toShort()); out.putShort(16); out.putInt(24)
        out.putInt(1); out.putInt(-1); out.putInt(prefix); out.putInt(uri)
        return out.array()
    }

    private fun startElement(name: Int, attrs: List<Attr>): ByteArray {
        val size = 16 + 20 + attrs.size * 20
        val out = le(size)
        out.putShort(0x0102); out.putShort(16); out.putInt(size)
        out.putInt(1); out.putInt(-1)
        out.putInt(-1); out.putInt(name)
        out.putShort(20); out.putShort(20); out.putShort(attrs.size.toShort())
        out.putShort(0); out.putShort(0); out.putShort(0)
        attrs.forEach { a ->
            out.putInt(a.ns); out.putInt(a.name); out.putInt(a.raw)
            out.putShort(8); out.put(0); out.put(a.type.toByte()); out.putInt(a.data)
        }
        return out.array()
    }

    private fun endElement(name: Int): ByteArray {
        val out = le(24)
        out.putShort(0x0103); out.putShort(16); out.putInt(24)
        out.putInt(1); out.putInt(-1); out.putInt(-1); out.putInt(name)
        return out.array()
    }
}

/** Generación de APK y JAR firmados para las pruebas. */
object TestApks {
    fun unsignedApk(
        dir: File,
        name: String,
        manifest: ByteArray,
        nativeAbis: List<String> = emptyList(),
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(entryName: String, bytes: ByteArray, stored: Boolean = false) {
                val entry = ZipEntry(entryName)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put("AndroidManifest.xml", manifest)
            put("classes.dex", "dex\n035\u0000prueba".toByteArray())
            nativeAbis.forEach { abi -> put("lib/$abi/libprueba.so", ByteArray(64) { it.toByte() }, stored = true) }
            extraEntries.forEach { (k, v) -> put(k, v) }
        }
        return file
    }

    fun sign(input: File, output: File, signer: TestSigner, v1: Boolean = false, minSdk: Int = 28): File {
        val config = ApkSigner.SignerConfig.Builder(signer.name.uppercase(), signer.key, listOf(signer.certificate)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(!v1 || minSdk >= 24)
            .setV3SigningEnabled(!v1)
            .build()
            .sign()
        return output
    }

    /** APK firmada lista para usar. */
    fun signedApk(
        dir: File,
        name: String,
        packageName: String,
        versionCode: Int,
        signer: TestSigner = TestKeys.signerA,
        minSdk: Int = 28,
        targetSdk: Int = 34,
        split: String? = null,
        nativeAbis: List<String> = emptyList(),
    ): File {
        val manifest = AxmlWriter.manifest(packageName, versionCode, "v$versionCode", minSdk, targetSdk, split)
        val unsigned = unsignedApk(dir, "$name.unsigned", manifest, nativeAbis)
        return sign(unsigned, File(dir, name), signer).also { unsigned.delete() }
    }

    /** JAR firmado (firma v1/JAR) con el contenido indicado, como el índice de F-Droid. */
    fun signedJar(dir: File, name: String, entries: Map<String, ByteArray>, signer: TestSigner): File {
        val unsigned = File(dir, "$name.unsigned")
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            entries.forEach { (k, v) ->
                zip.putNextEntry(ZipEntry(k))
                zip.write(v)
                zip.closeEntry()
            }
        }
        val out = File(dir, name)
        val config = ApkSigner.SignerConfig.Builder(signer.name.uppercase(), signer.key, listOf(signer.certificate)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(unsigned)
            .setOutputApk(out)
            .setMinSdkVersion(18)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(false)
            .setV3SigningEnabled(false)
            .build()
            .sign()
        unsigned.delete()
        return out
    }
}
