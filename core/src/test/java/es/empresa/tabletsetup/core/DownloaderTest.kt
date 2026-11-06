package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.net.DownloadException
import es.empresa.tabletsetup.core.net.Downloader
import es.empresa.tabletsetup.core.net.FailureKind
import es.empresa.tabletsetup.core.net.ResourceFingerprint
import es.empresa.tabletsetup.core.testing.TestHttpsServer
import es.empresa.tabletsetup.core.testing.TestKeys
import es.empresa.tabletsetup.core.util.Hashing
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.random.Random

/** Pruebas de descarga contra un servidor HTTPS local real (certificado de prueba). */
class DownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TestHttpsServer
    private lateinit var downloader: Downloader
    private val payload = Random(42).nextBytes(700_000)
    private val payloadSha = Hashing.sha256Hex(payload)
    private val hits = AtomicInteger()
    private val etag = "\"v1-abc\""

    private val base get() = "https://localhost:${server.port}"

    @Before
    fun setUp() {
        val ks = TestKeys.keyStore("tls-localhost.p12")
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, TestKeys.password) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        server = TestHttpsServer(serverContext) { req, resp -> route(req, resp) }
        downloader = Downloader(sslSocketFactory = clientContext.socketFactory, retryDelaysMs = listOf(1, 1, 1), sleep = { })
    }

    @After
    fun tearDown() = server.close()

    private fun route(req: TestHttpsServer.Request, resp: TestHttpsServer.Response) {
        when (req.path) {
            "/ok.apk" -> serveRange(req, resp)
            "/flaky500.apk" -> if (hits.incrementAndGet() == 1) resp.send(500) else serveRange(req, resp)
            "/missing.apk" -> resp.send(404, "no".toByteArray())
            "/to-http" -> {
                resp.headers["Location"] = "http://localhost:${server.port}/ok.apk"
                resp.send(302)
            }
            "/to-https" -> {
                resp.headers["Location"] = "/ok.apk"
                resp.send(301)
            }
            // Primera petición: anuncia el tamaño completo pero corta la conexión a mitad.
            "/cut.apk" -> if (hits.incrementAndGet() == 1) {
                resp.headers["ETag"] = etag
                resp.headers["Accept-Ranges"] = "bytes"
                resp.send(200, payload, declaredLength = payload.size.toLong(), sendBytes = payload.size / 2)
            } else {
                serveRange(req, resp)
            }
            "/conditional" -> if (req.header("If-None-Match") == etag) resp.send(304) else serveRange(req, resp)
            "/nohead.apk" -> if (req.method == "HEAD") resp.send(405) else serveRange(req, resp)
            else -> resp.send(404)
        }
    }

    private fun serveRange(req: TestHttpsServer.Request, resp: TestHttpsServer.Response) {
        resp.headers["ETag"] = etag
        resp.headers["Accept-Ranges"] = "bytes"
        val range = req.header("Range")
        val ifRange = req.header("If-Range")
        if (req.method != "HEAD" && range != null && (ifRange == null || ifRange == etag)) {
            val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: (payload.size - 1)
            resp.headers["Content-Range"] = "bytes $start-$end/${payload.size}"
            resp.send(206, payload.copyOfRange(start, end + 1))
        } else {
            resp.send(200, payload)
        }
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
        expectFailure(FailureKind.INSECURE) { downloader.download("http://localhost:${server.port}/ok.apk", File(tmp.root, "h.apk")) }
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
    fun `sondeo contra un servidor que no responde falla pronto y sin reintentos`() {
        val accepted = AtomicInteger()
        val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { blackhole ->
            thread(isDaemon = true) {
                try {
                    while (true) {
                        sockets += blackhole.accept() // acepta TCP pero nunca responde (red «colgada»)
                        accepted.incrementAndGet()
                    }
                } catch (e: IOException) {
                    // servidor cerrado
                }
            }
            val quick = Downloader(probeConnectTimeoutMs = 500, probeReadTimeoutMs = 500, sleep = { fail("El sondeo no debe reintentar") })
            val start = System.nanoTime()
            try {
                quick.probe("https://127.0.0.1:${blackhole.localPort}/QuickSupport.apk")
                fail("Debía fallar")
            } catch (e: DownloadException) {
                assertTrue(e.kind.name, e.kind == FailureKind.TIMEOUT || e.kind == FailureKind.TLS)
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("tardó $elapsedMs ms", elapsedMs < 5_000)
            assertEquals(1, accepted.get())
            sockets.forEach { it.close() }
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
        val a = ResourceFingerprint(100, "\"x\"", null)
        val decoded = ResourceFingerprint.decode(a.encode())
        assertEquals(a, decoded)
        assertTrue(a.matches(decoded))
        assertTrue(a.matches(a.copy(length = null)))
        assertFalse(a.matches(a.copy(etag = "\"y\"")))
        assertFalse(a.matches(a.copy(length = 101)))
        // Solo el tamaño no basta para afirmar que es el mismo archivo.
        val sizeOnly = ResourceFingerprint(100, null, null)
        assertFalse(sizeOnly.matches(sizeOnly))
        val byDate = ResourceFingerprint(null, null, "Wed, 04 Nov 2026 10:00:00 GMT")
        assertTrue(byDate.matches(byDate.copy(length = 5)))
        assertNull(ResourceFingerprint.decode("1|2|3"))
    }

    @Test
    fun `fetch en memoria con limite`() {
        assertArrayEquals(payload, downloader.fetch("$base/ok.apk", maxBytes = 1_000_000))
        expectFailure(FailureKind.TOO_LARGE) { downloader.fetch("$base/ok.apk", maxBytes = 10) }
    }

    @Test
    fun `subdominios CDN autorizados sin aceptar imitadores`() {
        val allowed = setOf(
            "url-provider.aptoide.com",
            "pool.apk.aptoide.com",
            "play.googleapis.com",
            "*.gvt1.com"
        )
        assertTrue(Downloader.hostMatchesAllowList("url-provider.aptoide.com", allowed))
        assertTrue(Downloader.hostMatchesAllowList("play.googleapis.com", allowed))
        assertTrue(Downloader.hostMatchesAllowList("rr2---sn-p5qs7nsk.gvt1.com", allowed))
        assertTrue(Downloader.hostMatchesAllowList("RR4---SN-p5qs7n6y.GVT1.COM", allowed))
        assertFalse(Downloader.hostMatchesAllowList("gvt1.com", allowed))
        assertFalse(Downloader.hostMatchesAllowList("evilgvt1.com", allowed))
        assertFalse(Downloader.hostMatchesAllowList("gvt1.com.ejemplo.org", allowed))
        assertFalse(Downloader.hostMatchesAllowList("play.googleapis.com.ejemplo.org", allowed))
    }

}
