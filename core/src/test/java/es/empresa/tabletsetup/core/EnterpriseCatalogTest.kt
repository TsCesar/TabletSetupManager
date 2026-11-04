package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.catalog.CatalogSignature
import es.empresa.tabletsetup.core.catalog.EnterpriseCatalogParser
import es.empresa.tabletsetup.core.model.ValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class EnterpriseCatalogTest {
    private val hashA = "a".repeat(64)
    private val certA = "b".repeat(64)

    private fun catalog(apps: String, extra: String = "") = """
        {"schemaVersion": 2, "catalogId": "pruebas", "sequence": 7, "generatedAt": "2026-11-04T10:00:00Z",
         "apps": [$apps] $extra}
    """.trimIndent()

    private val waze = """
        {"packageName": "com.waze", "versionCode": 1200, "versionName": "5.1", "minSdk": 29,
         "abis": ["arm64-v8a"], "signingCertSha256": "$certA",
         "assets": [{"url": "https://descargas.empresa.example/waze/base.apk", "sha256": "$hashA", "filename": "base.apk", "size": 1000},
                    {"url": "https://descargas.empresa.example/waze/split_config.arm64_v8a.apk", "sha256": "${"c".repeat(64)}", "filename": "split_config.arm64_v8a.apk"}]}
    """.trimIndent()

    @Test
    fun `analiza entradas validas con splits`() {
        val parsed = EnterpriseCatalogParser.parse(catalog(waze), AppCatalog.packageNames)
        assertEquals(7L, parsed.sequence)
        val release = parsed.releases.getValue(AppCatalog.WAZE).single().release
        assertEquals(1200L, release.versionCode)
        assertEquals(29, release.minSdk)
        assertEquals(setOf(certA), release.expectedSigners)
        assertEquals(2, release.assets.size)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `ignora FuelSoft y paquetes ajenos al catalogo`() {
        val fuel = """{"packageName": "com.empresa.fuelsoft", "versionCode": 1, "signingCertSha256": "$certA",
            "assets": [{"url": "https://x.example/f.apk", "sha256": "$hashA"}]}"""
        val other = """{"packageName": "com.desconocida.app", "versionCode": 1, "signingCertSha256": "$certA",
            "assets": [{"url": "https://x.example/o.apk", "sha256": "$hashA"}]}"""
        val parsed = EnterpriseCatalogParser.parse(catalog("$fuel, $other, $waze"), AppCatalog.packageNames)
        assertEquals(setOf(AppCatalog.WAZE), parsed.releases.keys)
        assertTrue(parsed.warnings.any { it.contains("excluida") })
        assertTrue(parsed.warnings.any { it.contains("no pertenece") })
    }

    @Test
    fun `rechaza HTTP, hashes invalidos y firmas ausentes por entrada`() {
        val http = """{"packageName": "com.whatsapp", "versionCode": 5, "signingCertSha256": "$certA",
            "assets": [{"url": "http://inseguro.example/w.apk", "sha256": "$hashA"}]}"""
        val badHash = """{"packageName": "com.android.chrome", "versionCode": 5, "signingCertSha256": "$certA",
            "assets": [{"url": "https://x.example/c.apk", "sha256": "1234"}]}"""
        val noSigner = """{"packageName": "com.google.android.gm", "versionCode": 5,
            "assets": [{"url": "https://x.example/g.apk", "sha256": "$hashA"}]}"""
        val parsed = EnterpriseCatalogParser.parse(catalog("$http, $badHash, $noSigner"), AppCatalog.packageNames)
        assertTrue(parsed.releases.isEmpty())
        assertEquals(3, parsed.warnings.size)
        assertTrue(parsed.warnings[0].contains("HTTPS"))
    }

    @Test
    fun `fuentes directas y certificados fijados`() {
        val direct = """{"packageName": "com.whatsapp", "type": "direct", "url": "https://oficial.example/app.apk", "signingCertSha256": "$certA"}"""
        val parsed = EnterpriseCatalogParser.parse(
            catalog(direct, """, "signerPins": {"com.teamviewer.quicksupport.market": ["${"d".repeat(64)}"]}"""),
            AppCatalog.packageNames,
        )
        assertEquals("https://oficial.example/app.apk", parsed.directSources.getValue(AppCatalog.WHATSAPP).url)
        assertEquals(setOf("d".repeat(64)), parsed.signerPins.getValue(AppCatalog.QUICKSUPPORT))
    }

    @Test
    fun `errores estructurales rechazan el catalogo`() {
        listOf(
            """{"schemaVersion": 1, "apps": []}""" to "v1",
            """{"schemaVersion": 3, "sequence": 1, "apps": []}""" to "esquema",
            """{"schemaVersion": 2, "apps": []}""" to "secuencia",
            """{"schemaVersion": 2, "sequence": 1}""" to "apps",
            """no es json""" to "JSON",
        ).forEach { (json, hint) ->
            try {
                EnterpriseCatalogParser.parse(json, AppCatalog.packageNames)
                fail("Debía rechazarse: $hint")
            } catch (e: ValidationException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun signDer(data: ByteArray, pair: KeyPair): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }

    @Test
    fun `firma ECDSA valida en DER, Base64 y r-s`() {
        val pair = keyPair()
        val data = catalog(waze).toByteArray()
        val der = signDer(data, pair)
        val publicB64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        val key = CatalogSignature.parsePublicKey(publicB64)
        assertTrue(CatalogSignature.verify(data, der, key))
        assertTrue(CatalogSignature.verify(data, Base64.getEncoder().encode(der), key))
        // PEM con saltos de línea.
        val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.public.encoded) + "\n-----END PUBLIC KEY-----\n"
        assertTrue(CatalogSignature.verify(data, der, CatalogSignature.parsePublicKey(pem)))
        // Formato r||s de 64 bytes (p. ej. de otras herramientas).
        val raw = derToRaw(der)
        assertTrue(CatalogSignature.verify(data, Base64.getEncoder().encode(raw), key))
    }

    @Test
    fun `firma invalida si el catalogo se altera o la clave es otra`() {
        val pair = keyPair()
        val data = catalog(waze).toByteArray()
        val der = signDer(data, pair)
        val key = CatalogSignature.parsePublicKey(Base64.getEncoder().encodeToString(pair.public.encoded))
        val tampered = catalog(waze).replace("1200", "1201").toByteArray()
        assertFalse(CatalogSignature.verify(tampered, der, key))
        val other = CatalogSignature.parsePublicKey(Base64.getEncoder().encodeToString(keyPair().public.encoded))
        assertFalse(CatalogSignature.verify(data, der, other))
        try {
            CatalogSignature.parsePublicKey("no-es-una-clave")
            fail("Clave inválida aceptada")
        } catch (e: ValidationException) {
            assertTrue(e.message!!.contains("clave"))
        }
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        fun readInt(): ByteArray {
            require(der[i] == 0x02.toByte())
            val len = der[i + 1].toInt()
            val value = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            val unsigned = BigInteger(1, value).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - unsigned.size) + unsigned
        }
        return readInt() + readInt()
    }
}
