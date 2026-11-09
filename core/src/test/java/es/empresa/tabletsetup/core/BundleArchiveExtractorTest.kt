package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.apk.ApkInspector
import es.empresa.tabletsetup.core.apk.ApkSetValidator
import es.empresa.tabletsetup.core.apk.BundleArchiveExtractor
import es.empresa.tabletsetup.core.apk.ValidationContext
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.testing.TestApks
import es.empresa.tabletsetup.core.testing.TestKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleArchiveExtractorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val device = DeviceProfile(29, "10", listOf("arm64-v8a", "armeabi-v7a"),
        densityDpi = 420, preferredLocales = listOf("es-ES", "en-US"))

    private fun archive(name: String, contents: Map<String, ByteArray>): File {
        val output = File(tmp.root, name)
        ZipOutputStream(output.outputStream()).use { z ->
            contents.forEach { (entry, bytes) ->
                z.putNextEntry(ZipEntry(entry))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return output
    }

    @Test fun apkmExtraeDosApksFirmadasEIgnoraX86() {
        val base = TestApks.signedApk(tmp.root, "real-base.apk", AppCatalog.WAZE, 200)
        val arm = TestApks.signedApk(tmp.root, "real-arm.apk", AppCatalog.WAZE, 200,
            split = "config.arm64_v8a", nativeAbis = listOf("arm64-v8a"))
        val intel = TestApks.signedApk(tmp.root, "real-x86.apk", AppCatalog.WAZE, 200,
            split = "config.x86_64", nativeAbis = listOf("x86_64"))
        val zipped = archive("waze.apkm", linkedMapOf(
            "base.apk" to base.readBytes(),
            "splits/split_config.arm64_v8a.apk" to arm.readBytes(),
            "splits/split_config.x86_64.apk" to intel.readBytes(),
            "info.json" to "{}".toByteArray()
        ))
        val files = BundleArchiveExtractor.expand(listOf(zipped), File(tmp.root, "extraidas"), device)
        assertEquals(2, files.size)
        val apkInfos = files.map { ApkInspector.inspect(it, device.sdkInt) }
        val validated = ApkSetValidator.validate(apkInfos, ValidationContext(
            expectedPackage = AppCatalog.WAZE, expectedVersionCode = 200,
            device = device, installed = null, pinnedSigners = setOf(TestKeys.signerA.sha256),
            appMinDeviceSdk = 29
        ))
        assertEquals(AppCatalog.WAZE, validated.packageName)
        assertTrue(validated.signerPinned)
        assertEquals(2, validated.files.size)
    }

    @Test fun rechazaApkmConRutaPeligrosa() {
        val zipped = archive("rutas.apkm", mapOf(
            "base.apk" to byteArrayOf(1, 2),
            "../fuera.apk" to byteArrayOf(1, 3)
        ))
        try {
            BundleArchiveExtractor.expand(listOf(zipped), File(tmp.root, "rutas"), device)
            fail("Debería rechazar la ruta")
        } catch (ex: ValidationException) {
            assertTrue(ex.message.orEmpty().contains("rutas"))
        }
    }

    @Test fun rechazaSplitDeArquitecturaIncompatible() {
        val zipped = archive("x86.apkm", mapOf(
            "base.apk" to byteArrayOf(1, 2),
            "split_config.arm64_v8a.apk" to byteArrayOf(1, 3)
        ))
        try {
            BundleArchiveExtractor.expand(listOf(zipped), File(tmp.root, "x86"),
                device.copy(supportedAbis = listOf("x86_64")))
            fail("Debería rechazar CPU incompatible")
        } catch (ex: ValidationException) {
            assertTrue(ex.message.orEmpty().contains("arquitectura"))
        }
    }
}
