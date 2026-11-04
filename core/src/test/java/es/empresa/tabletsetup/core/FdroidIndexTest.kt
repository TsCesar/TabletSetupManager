package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.fdroid.FdroidIndexReader
import es.empresa.tabletsetup.core.fdroid.FdroidRepo
import es.empresa.tabletsetup.core.fdroid.FdroidSelector
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.SourceKind
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.testing.TestApks
import es.empresa.tabletsetup.core.testing.TestKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FdroidIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val mupdf = AppCatalog.MUPDF
    private val arm = DeviceProfile(28, "9", listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
    private val x86 = DeviceProfile(30, "11", listOf("x86_64", "x86"))
    private val signer = "e".repeat(64)

    private fun pkg(code: Long, abis: List<String>, minSdk: Int = 21, hash: String = "f".repeat(64)) = """
        {"added": 1, "apkName": "${mupdf}_$code.apk", "hash": "$hash", "hashType": "sha256",
         "minSdkVersion": $minSdk, "nativecode": [${abis.joinToString { "\"$it\"" }}], "packageName": "$mupdf",
         "sig": "00", "signer": "$signer", "size": 16000000, "targetSdkVersion": 34,
         "uses-permission": [["android.permission.INTERNET", null]], "versionCode": $code, "versionName": "1.$code"}
    """.trimIndent()

    /** Estructura real de index-v1.json: "apps" es una lista y "packages" un objeto por paquete. */
    private fun index(packagesFirst: Boolean = false, suggested: String = "\"270\""): String {
        val apps = """"apps": [{"packageName": "org.otro", "suggestedVersionCode": "5"},
            {"packageName": "$mupdf", "name": "MuPDF viewer", "suggestedVersionCode": $suggested, "localized": {"en-US": {"summary": "x"}}}]"""
        val packages = """"packages": {
            "org.otro": [{"apkName": "o.apk", "hash": "${"1".repeat(64)}", "versionCode": 5}],
            "$mupdf": [${pkg(280, listOf("arm64-v8a", "armeabi-v7a", "x86"))}, ${pkg(270, listOf("arm64-v8a", "armeabi-v7a", "x86"))},
                       ${pkg(260, listOf("x86_64"))}, ${pkg(250, listOf(), minSdk = 99)}, ${pkg(240, listOf(), hash = "mal")}]}"""
        val repo = """"repo": {"name": "F-Droid", "timestamp": 1, "version": 21001}"""
        return if (packagesFirst) "{$repo, $packages, $apps, \"requests\": {\"install\": [], \"uninstall\": []}}"
        else "{$repo, \"requests\": {\"install\": [], \"uninstall\": []}, $apps, $packages}"
    }

    @Test
    fun `analiza index-v1 en streaming con packages como objeto`() {
        listOf(false, true).forEach { packagesFirst ->
            val lookup = FdroidIndexReader.parse(index(packagesFirst).byteInputStream(), setOf(mupdf)).getValue(mupdf)
            assertTrue(lookup.packageFound)
            assertEquals(270L, lookup.suggestedVersionCode)
            // La entrada con hash inválido se descarta.
            assertEquals(listOf(280L, 270L, 260L, 250L), lookup.candidates.map { it.versionCode })
            assertEquals(signer, lookup.candidates.first().signer)
        }
    }

    @Test
    fun `elige la version sugerida compatible y no las beta`() {
        val lookup = FdroidIndexReader.parse(index().byteInputStream(), setOf(mupdf)).getValue(mupdf)
        val selection = FdroidSelector.choose(lookup, mupdf, arm) as FdroidSelector.Selection.Found
        assertEquals(270L, selection.release.versionCode) // 280 es superior a la sugerida (beta)
        assertEquals(SourceKind.FDROID, selection.release.origin)
        assertEquals(FdroidRepo.REPO_URL + "${mupdf}_270.apk", selection.release.assets.single().url)
        assertEquals(setOf(signer), selection.release.expectedSigners)
    }

    @Test
    fun `respeta arquitectura y minSdk`() {
        val lookup = FdroidIndexReader.parse(index(suggested = "999").byteInputStream(), setOf(mupdf)).getValue(mupdf)
        // En x86_64 la 280 también vale (incluye x86), pero sin sugerida restrictiva gana la mayor.
        assertEquals(280L, (FdroidSelector.choose(lookup, mupdf, x86) as FdroidSelector.Selection.Found).release.versionCode)
        val onlyX8664 = lookup.copy(candidates = lookup.candidates.filter { it.versionCode == 260L })
        assertTrue(FdroidSelector.choose(onlyX8664, mupdf, arm) is FdroidSelector.Selection.Unavailable)
        val onlyHighMin = lookup.copy(candidates = lookup.candidates.filter { it.versionCode == 250L })
        assertTrue(FdroidSelector.choose(onlyHighMin, mupdf, arm) is FdroidSelector.Selection.Unavailable)
    }

    @Test
    fun `paquete inexistente en F-Droid`() {
        val lookup = FdroidIndexReader.parse(index().byteInputStream(), setOf("com.no.existe")).getValue("com.no.existe")
        assertFalse(lookup.packageFound)
        assertTrue(FdroidSelector.choose(lookup, "com.no.existe", arm) is FdroidSelector.Selection.Unavailable)
    }

    @Test
    fun `verifica la firma JAR y el certificado fijado`() {
        val jar = TestApks.signedJar(tmp.root, "index-v1.jar", mapOf("index-v1.json" to index().toByteArray()), TestKeys.signerA)
        val result = FdroidIndexReader.read(jar, setOf(mupdf), pinnedSignerSha256 = TestKeys.signerA.sha256)
        assertEquals(270L, result.getValue(mupdf).suggestedVersionCode)
        // Otro certificado: se rechaza aunque la firma sea criptográficamente válida.
        expectValidation("oficial") { FdroidIndexReader.read(jar, setOf(mupdf), pinnedSignerSha256 = TestKeys.signerB.sha256) }
    }

    @Test
    fun `rechaza indice sin firma o alterado`() {
        val unsigned = File(tmp.root, "unsigned.jar")
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("index-v1.json")); zip.write(index().toByteArray()); zip.closeEntry()
        }
        expectValidation("sin firma") { FdroidIndexReader.read(unsigned, setOf(mupdf), TestKeys.signerA.sha256) }

        val signed = TestApks.signedJar(tmp.root, "signed.jar", mapOf("index-v1.json" to index().toByteArray()), TestKeys.signerA)
        val tampered = File(tmp.root, "tampered.jar")
        ZipFile(signed).use { src ->
            ZipOutputStream(tampered.outputStream()).use { out ->
                src.entries().asSequence().forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    val bytes = src.getInputStream(entry).readBytes()
                    out.write(if (entry.name == "index-v1.json") String(bytes).replace("\"270\"", "\"280\"").toByteArray() else bytes)
                    out.closeEntry()
                }
            }
        }
        expectValidation("alterado") { FdroidIndexReader.read(tampered, setOf(mupdf), TestKeys.signerA.sha256) }
        expectValidation("") { FdroidIndexReader.read(File(tmp.root, "no-existe.jar"), setOf(mupdf), TestKeys.signerA.sha256) }
    }

    private fun expectValidation(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("Se esperaba ValidationException ($fragment)")
        } catch (e: ValidationException) {
            assertTrue("Mensaje inesperado: ${e.message}", e.message!!.contains(fragment))
        }
    }
}
