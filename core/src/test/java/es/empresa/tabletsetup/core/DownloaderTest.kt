package es.empresa.tabletsetup.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import es.empresa.tabletsetup.core.net.DownloadException
import es.empresa.tabletsetup.core.net.Downloader
import es.empresa.tabletsetup.core.net.FailureKind
import es.empresa.tabletsetup.core.testing.TestKeys
import es.empresa.tabletsetup.core.util.Hashing
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.random.Random

/** Pruebas de descarga contra un servidor HTTPS local real (certificado de prueba). */
class DownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpsServer
    private lateinit var downloader: Downloader
    private val payload = Random(42).nextBytes(700_000)
    private val payloadSha = Hashing.sha256Hex(payload)
    private val hits = AtomicInteger()
    private val etag = "\"v1-abc\""

    private val base get() = "https://localhost:${server.address.port}"

    @Before
    fun setUp() {
        val ks = TestKeys.keyStore("tls-localhost.p12")
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, TestKeys.password) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(serverContext)
        server.createContext("/ok.apk") { ex -> serveRange(ex) }
        server.createContext("/flaky500.apk") { ex ->
            if (hits.incrementAndGet() == 1) respond(ex, 500, ByteArray(0)) else serveRange(ex)
        }
        server.createContext("/missing.apk") { ex -> respond(ex, 404, "no".toByteArray()) }
        server.createContext("/to-http") { ex ->
            ex.responseHeaders.add("Location", "http://localhost:${server.address.port}/ok.apk"); respond(ex, 302, ByteArray(0))
        }
        server.createContext("/to-https") { ex -> ex.responseHeaders.add("Location", "/ok.apk"); respond(ex, 301, ByteArray(0)) }
        server.createContext("/cut.apk") { ex ->
            // Primera petición: anuncia el tamaño completo pero corta la conexión a mitad.
            if (hits.incrementAndGet() == 1) {
                ex.responseHeaders.add("ETag", etag)
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.write(payload, 0, payload.size / 2)
                ex.responseBody.flush()
                ex.close()
            } else {
                serveRange(ex)
            }
        }
        server.createContext("/conditional") { ex ->
            if (ex.requestHeaders.getFirst("If-None-Match") == etag) respond(ex, 304, null) else serveRange(ex)
        }
        server.createContext("/nohead.apk") { ex ->
            if (ex.requestMethod == "HEAD") respond(ex, 405, null) else serveRange(ex)
        }
        server.start()
        downloader = Downloader(sslSocketFactory = clientContext.socketFactory, retryDelaysMs = listOf(1, 1, 1), sleep = { })
    }

    @After
    fun tearDown() = server.stop(0)

    private fun respond(ex: HttpExchange, code: Int, body: ByteArray?) {
        ex.sendResponseHeaders(code, if (body == null || body.isEmpty()) -1 else body.size.toLong())
        body?.takeIf { it.isNotEmpty() }?.let { ex.responseBody.write(it) }
        ex.close()
    }

    private fun serveRange(ex: HttpExchange) {
        ex.responseHeaders.add("ETag", etag)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        if (ex.requestMethod == "HEAD") {
            ex.responseHeaders.add("Content-Length", payload.size.toString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
            return
        }
        val range = ex.requestHeaders.getFirst("Range")
        val ifRange = ex.requestHeaders.getFirst("If-Range")
        if (range != null && (ifRange == null || ifRange == etag)) {
            val start = Regex("bytes=(\\d+)-(\\d*)").find(range)!!.groupValues[1].toInt()
            val endText = Regex("bytes=(\\d+)-(\\d*)").find(range)!!.groupValues[2]
            val end = if (endText.isEmpty()) payload.size - 1 else endText.toInt()
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            ex.sendResponseHeaders(206, (end - start + 1).toLong())
            ex.responseBody.write(payload, start, end - start + 1)
        } else {
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.write(payload)
        }
        ex.close()
    }

    private fun expectFailure(kind: FailureKind, block: () -> Unit) {
        try {
            block()
            fail("Se esperaba fallo $kind")
        } catch (e: DownloadException) {
            assertEquals(e.message, kind, e.kind)
        }
    }

    @Test
    fun `descarga HTTPS con SHA-256 correcto y progreso`() {
        val dest = File(tmp.root, "ok.apk")
        var lastProgress = 0L
        val outcome = downloader.download("$base/ok.apk", dest, payloadSha, onProgress = { b, _ -> lastProgress = b })
        assertArrayEquals(payload, dest.readBytes())
        assertEquals(payload.size.toLong(), lastProgress)
        assertEquals(etag, outcome.etag)
    }

    @Test
    fun `SHA-256 distinto rechaza el archivo y no deja restos`() {
        val dest = File(tmp.root, "bad.apk")
        expectFailure(FailureKind.INTEGRITY) { downloader.download("$base/ok.apk", dest, "0".repeat(64)) }
        assertFalse(dest.exists())
        assertFalse(File(tmp.root, "bad.apk.part").exists())
    }

    @Test
    fun `errores HTTP, reintentos y limite de tamano`() {
        expectFailure(FailureKind.HTTP) { downloader.download("$base/missing.apk", File(tmp.root, "m.apk")) }
        // Un 500 transitorio se reintenta.
        downloader.download("$base/flaky500.apk", File(tmp.root, "f.apk"), payloadSha)
        expectFailure(FailureKind.TOO_LARGE) { downloader.download("$base/ok.apk", File(tmp.root, "big.apk"), maxBytes = 1000) }
    }

    @Test
    fun `solo HTTPS tambien en redirecciones`() {
        expectFailure(FailureKind.INSECURE) { downloader.download("http://localhost:${server.address.port}/ok.apk", File(tmp.root, "h.apk")) }
        expectFailure(FailureKind.INSECURE) { downloader.download("$base/to-http", File(tmp.root, "r.apk")) }
        downloader.download("$base/to-https", File(tmp.root, "s.apk"), payloadSha)
    }

    @Test
    fun `reanuda tras un corte de red con Range`() {
        val dest = File(tmp.root, "cut.apk")
        downloader.download("$base/cut.apk", dest, payloadSha)
        assertArrayEquals(payload, dest.readBytes())
        assertTrue(hits.get() >= 2)
    }

    @Test
    fun `sin red devuelve error de conexion comprensible`() {
        val port = ServerSocket(0).use { it.localPort } // puerto cerrado
        val quick = Downloader(retryDelaysMs = emptyList(), sleep = { })
        try {
            quick.download("https://127.0.0.1:$port/x.apk", File(tmp.root, "n.apk"))
            fail("Debía fallar")
        } catch (e: DownloadException) {
            assertEquals(FailureKind.NO_NETWORK, e.kind)
            assertTrue(e.message!!.contains("conectar"))
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `descarga condicional y sondeo de cabeceras`() {
        val notModified = downloader.download("$base/conditional", File(tmp.root, "c.apk"), conditionalEtag = etag)
        assertTrue(notModified.notModified)
        val probe = downloader.probe("$base/ok.apk")
        assertEquals(payload.size.toLong(), probe.contentLength)
        assertEquals(etag, probe.etag)
        val fallback = downloader.probe("$base/nohead.apk")
        assertEquals(payload.size.toLong(), fallback.contentLength)
        assertTrue(probe.fingerprint.matches(fallback.fingerprint))
    }

    @Test
    fun `huella de recurso para URL oficiales sin version`() {
        val a = es.empresa.tabletsetup.core.net.ResourceFingerprint(100, "\"x\"", null)
        val decoded = es.empresa.tabletsetup.core.net.ResourceFingerprint.decode(a.encode())
        assertEquals(a, decoded)
        assertTrue(a.matches(decoded))
        assertTrue(a.matches(a.copy(length = null)))
        assertFalse(a.matches(a.copy(etag = "\"y\"")))
        assertFalse(a.matches(a.copy(length = 101)))
        // Solo el tamaño no basta para afirmar que es el mismo archivo.
        val sizeOnly = es.empresa.tabletsetup.core.net.ResourceFingerprint(100, null, null)
        assertFalse(sizeOnly.matches(sizeOnly))
        val byDate = es.empresa.tabletsetup.core.net.ResourceFingerprint(null, null, "Wed, 04 Nov 2026 10:00:00 GMT")
        assertTrue(byDate.matches(byDate.copy(length = 5)))
        assertEquals(null, es.empresa.tabletsetup.core.net.ResourceFingerprint.decode("1|2|3"))
    }

    @Test
    fun `fetch en memoria con limite`() {
        assertArrayEquals(payload, downloader.fetch("$base/ok.apk", maxBytes = 1_000_000))
        expectFailure(FailureKind.TOO_LARGE) { downloader.fetch("$base/ok.apk", maxBytes = 10) }
    }
}
