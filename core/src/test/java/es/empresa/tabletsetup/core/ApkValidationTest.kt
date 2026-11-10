package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.apk.ApkFileInfo
import es.empresa.tabletsetup.core.apk.ApkInspector
import es.empresa.tabletsetup.core.apk.ApkSetValidator
import es.empresa.tabletsetup.core.apk.ManifestReader
import es.empresa.tabletsetup.core.apk.ValidationContext
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.InstalledInfo
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.testing.AxmlWriter
import es.empresa.tabletsetup.core.testing.TestApks
import es.empresa.tabletsetup.core.testing.TestKeys
import es.empresa.tabletsetup.core.util.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class ApkValidationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val tablet10 = DeviceProfile(29, "10", listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
    private val tablet9 = DeviceProfile(28, "9", listOf("arm64-v8a", "armeabi-v7a", "armeabi"))

    private fun inspect(file: File, sdk: Int = 29): ApkFileInfo = ApkInspector.inspect(file, sdk)

    private fun ctx(
        pkg: String = AppCatalog.WHATSAPP,
        expectedVersion: Long? = null,
        device: DeviceProfile = tablet10,
        installed: InstalledInfo? = null,
        pins: Set<String> = emptySet(),
        minDeviceSdk: Int = 28,
        hashes: Map<String, String> = emptyMap(),
    ) = ValidationContext(pkg, expectedVersion, device, installed, pins, minDeviceSdk, hashes)

    private fun expectRejected(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("Se esperaba rechazo: $fragment")
        } catch (e: ValidationException) {
            assertTrue("Mensaje «${e.message}» no contiene «$fragment»", e.message!!.contains(fragment, ignoreCase = true))
        }
    }

    @Test
    fun `lee el manifiesto binario`() {
        val info = ManifestReader.read(AxmlWriter.manifest("com.whatsapp", 241_000, "2.24", 21, 34, split = null))
        assertEquals("com.whatsapp", info.packageName)
        assertEquals(241_000L, info.versionCode)
        assertEquals("2.24", info.versionName)
        assertEquals(21, info.minSdk)
        assertEquals(34, info.targetSdk)
        assertNull(info.splitName)
        val split = ManifestReader.read(AxmlWriter.manifest("com.whatsapp", 241_000, null, 21, 34, split = "config.arm64_v8a"))
        assertEquals("config.arm64_v8a", split.splitName)
    }

    @Test
    fun `inspecciona APK firmada con apksig`() {
        val apk = TestApks.signedApk(tmp.root, "base.apk", AppCatalog.WHATSAPP, 100, nativeAbis = listOf("arm64-v8a"))
        val info = inspect(apk)
        assertEquals(AppCatalog.WHATSAPP, info.manifest.packageName)
        assertEquals(100L, info.manifest.versionCode)
        assertEquals(setOf("arm64-v8a"), info.nativeAbis)
        assertEquals(setOf(TestKeys.signerA.sha256), info.signers)
        // En API 28+ apksig valida con el esquema más fuerte disponible (v3).
        assertTrue(info.schemes, info.schemes.contains("v3") || info.schemes.contains("v2"))
        assertEquals(Hashing.sha256Hex(apk), info.sha256)
    }

    @Test
    fun `rechaza archivos corruptos, sin manifiesto, sin firma o alterados`() {
        val notZip = File(tmp.root, "falsa.apk").apply { writeText("esto no es una apk") }
        expectRejected("corrupto") { inspect(notZip) }

        val noManifest = File(tmp.root, "sin-manifiesto.apk")
        java.util.zip.ZipOutputStream(noManifest.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("classes.dex")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
        }
        expectRejected("AndroidManifest") { inspect(noManifest) }

        val unsigned = TestApks.unsignedApk(tmp.root, "sin-firma.apk", AxmlWriter.manifest(AppCatalog.WHATSAPP, 1))
        expectRejected("no está firmada") { inspect(unsigned) }

        val signed = TestApks.signedApk(tmp.root, "alterada.apk", AppCatalog.WHATSAPP, 5, nativeAbis = listOf("arm64-v8a"))
        // Alterar un byte del contenido de la biblioteca nativa (entrada sin comprimir).
        val bytes = signed.readBytes()
        val marker = ByteArray(16) { it.toByte() }
        val pos = indexOf(bytes, marker)
        assertTrue(pos > 0)
        RandomAccessFile(signed, "rw").use { it.seek(pos.toLong() + 3); it.write(0x7F) }
        expectRejected("alterado") { inspect(signed) }
    }

    @Test
    fun `acepta conjunto base mas split del mismo paquete, version y firma`() {
        val base = inspect(TestApks.signedApk(tmp.root, "base.apk", AppCatalog.WHATSAPP, 200))
        val split = inspect(TestApks.signedApk(tmp.root, "split.apk", AppCatalog.WHATSAPP, 200, split = "config.arm64_v8a", nativeAbis = listOf("arm64-v8a")))
        val result = ApkSetValidator.validate(listOf(split, base), ctx(expectedVersion = 200, pins = setOf(TestKeys.signerA.sha256)))
        assertEquals(base.file, result.files.first().file)
        assertEquals(2, result.files.size)
        assertTrue(result.signerPinned)
    }

    @Test
    fun `rechaza splits incoherentes`() {
        val base = inspect(TestApks.signedApk(tmp.root, "base.apk", AppCatalog.WHATSAPP, 200))
        val otherVersion = inspect(TestApks.signedApk(tmp.root, "s1.apk", AppCatalog.WHATSAPP, 201, split = "config.es"))
        expectRejected("otra versión") { ApkSetValidator.validate(listOf(base, otherVersion), ctx()) }
        val otherPackage = inspect(TestApks.signedApk(tmp.root, "s2.apk", AppCatalog.CHROME, 200, split = "config.es"))
        expectRejected("otro paquete") { ApkSetValidator.validate(listOf(base, otherPackage), ctx()) }
        val otherSigner = inspect(TestApks.signedApk(tmp.root, "s3.apk", AppCatalog.WHATSAPP, 200, signer = TestKeys.signerB, split = "config.es"))
        expectRejected("otro certificado") { ApkSetValidator.validate(listOf(base, otherSigner), ctx()) }
        val onlySplit = inspect(TestApks.signedApk(tmp.root, "s4.apk", AppCatalog.WHATSAPP, 200, split = "config.fr"))
        expectRejected("Falta la APK base") { ApkSetValidator.validate(listOf(onlySplit), ctx()) }
        val base2 = inspect(TestApks.signedApk(tmp.root, "base2.apk", AppCatalog.WHATSAPP, 200))
        expectRejected("varias APK base") { ApkSetValidator.validate(listOf(base, base2), ctx()) }
    }

    @Test
    fun `paquete, version publicada, hash y certificado fijado`() {
        val apk = inspect(TestApks.signedApk(tmp.root, "base.apk", AppCatalog.WHATSAPP, 300))
        expectRejected("otro paquete") { ApkSetValidator.validate(listOf(apk), ctx(pkg = AppCatalog.GMAIL)) }
        expectRejected("no coincide con la publicada") { ApkSetValidator.validate(listOf(apk), ctx(expectedVersion = 301)) }
        expectRejected("SHA-256") { ApkSetValidator.validate(listOf(apk), ctx(hashes = mapOf("base.apk" to "0".repeat(64)))) }
        expectRejected("no autorizado") { ApkSetValidator.validate(listOf(apk), ctx(pins = setOf(TestKeys.signerB.sha256))) }
        ApkSetValidator.validate(listOf(apk), ctx(hashes = mapOf("base.apk" to apk.sha256), pins = setOf(TestKeys.signerA.sha256)))
    }

    @Test
    fun `firma y version respecto a la app instalada`() {
        val apk = inspect(TestApks.signedApk(tmp.root, "base.apk", AppCatalog.WHATSAPP, 300))
        val installedSame = InstalledInfo(299, "x", false, setOf(TestKeys.signerA.sha256))
        ApkSetValidator.validate(listOf(apk), ctx(installed = installedSame))
        val installedOther = InstalledInfo(299, "x", false, setOf(TestKeys.signerB.sha256))
        expectRejected("no coincide con la aplicación instalada") { ApkSetValidator.validate(listOf(apk), ctx(installed = installedOther)) }
        expectRejected("ya está instalada") { ApkSetValidator.validate(listOf(apk), ctx(installed = installedSame.copy(versionCode = 300))) }
        expectRejected("Bajada de versión") { ApkSetValidator.validate(listOf(apk), ctx(installed = installedSame.copy(versionCode = 400))) }
    }

    @Test
    fun `Waze bloqueado en Android 9 tambien con APK local`() {
        val waze = inspect(TestApks.signedApk(tmp.root, "waze.apk", AppCatalog.WAZE, 10, minSdk = 21), sdk = 28)
        expectRejected("No compatible con Android 9") {
            ApkSetValidator.validate(listOf(waze), ctx(pkg = AppCatalog.WAZE, device = tablet9, minDeviceSdk = 29))
        }
        ApkSetValidator.validate(listOf(inspect(waze.file)), ctx(pkg = AppCatalog.WAZE, device = tablet10, minDeviceSdk = 29))
        // Android 10 con una versión de Waze que exige Android 11: rechazada.
        val waze30 = inspect(TestApks.signedApk(tmp.root, "waze30.apk", AppCatalog.WAZE, 11, minSdk = 30))
        expectRejected("Android 11") { ApkSetValidator.validate(listOf(waze30), ctx(pkg = AppCatalog.WAZE, minDeviceSdk = 29)) }
    }

    @Test
    fun `arquitectura y targetSdk`() {
        val x86 = inspect(TestApks.signedApk(tmp.root, "x86.apk", AppCatalog.WHATSAPP, 10, nativeAbis = listOf("x86", "x86_64")))
        expectRejected("Arquitectura no compatible") { ApkSetValidator.validate(listOf(x86), ctx()) }
        val noNative = inspect(TestApks.signedApk(tmp.root, "java.apk", AppCatalog.WHATSAPP, 10))
        ApkSetValidator.validate(listOf(noNative), ctx())
        val old = inspect(TestApks.signedApk(tmp.root, "old.apk", AppCatalog.WHATSAPP, 10, targetSdk = 22), sdk = 34)
        expectRejected("demasiado antigua") {
            ApkSetValidator.validate(listOf(old), ctx(device = DeviceProfile(34, "14", listOf("arm64-v8a"))))
        }
    }

    @Test
    fun `FuelSoft rechazada aunque se importe manualmente`() {
        val fuel = inspect(TestApks.signedApk(tmp.root, "fuel.apk", "com.empresa.fuelsoft", 10))
        expectRejected("excluida") { ApkSetValidator.validate(listOf(fuel), ctx(pkg = "com.empresa.fuelsoft")) }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
