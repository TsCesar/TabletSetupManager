package es.empresa.tabletsetup.core.testing

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * Servidor HTTPS/1.1 mínimo para pruebas, basado solo en javax.net.ssl (disponible tanto en el
 * JDK como en el classpath de pruebas de Android). Una petición por conexión («Connection: close»).
 */
class TestHttpsServer(context: SSLContext, private val handler: (Request, Response) -> Unit) : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response internal constructor(private val socket: Socket, private val method: String) {
        val headers = linkedMapOf<String, String>()

        /**
         * Envía la respuesta. [declaredLength] permite anunciar más bytes de los que se envían
         * ([sendBytes]) para simular un corte de red a mitad de la descarga.
         */
        fun send(status: Int, body: ByteArray? = null, declaredLength: Long? = null, sendBytes: Int? = null) {
            val out: OutputStream = socket.getOutputStream()
            val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            val length = declaredLength ?: body?.size?.toLong() ?: 0L
            if (status != 304 && !headers.keys.any { it.equals("Content-Length", ignoreCase = true) }) {
                head.append("Content-Length: ").append(length).append("\r\n")
            }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null && method != "HEAD") out.write(body, 0, sendBytes ?: body.size)
            out.flush()
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 301 -> "Moved Permanently"; 302 -> "Found"
            304 -> "Not Modified"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 500 -> "Internal Server Error"
            else -> "Status"
        }
    }

    private val server = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "test-https-accept") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true, name = "test-https-conn") { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                handler(Request(parts[0], parts[1], headers), Response(s, parts[0]))
            }
        } catch (e: IOException) {
            // Conexión cerrada por el cliente o corte simulado.
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return buffer.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            buffer.write(b)
            if (buffer.size() > 16 * 1024) throw IOException("Cabecera demasiado larga")
        }
    }

    override fun close() {
        server.close()
    }
}
