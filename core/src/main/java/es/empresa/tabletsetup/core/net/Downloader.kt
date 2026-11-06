package es.empresa.tabletsetup.core.net

import es.empresa.tabletsetup.core.util.Hashing
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

enum class FailureKind { NO_NETWORK, TIMEOUT, TLS, HTTP, INSECURE, INTEGRITY, TOO_LARGE, CANCELLED, IO }

/** Error de red con mensaje comprensible para el técnico. */
class DownloadException(
    val kind: FailureKind,
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val retryable: Boolean
        get() = when (kind) {
            FailureKind.NO_NETWORK, FailureKind.TIMEOUT, FailureKind.IO -> true
            FailureKind.HTTP -> httpCode != null && (httpCode >= 500 || httpCode == 408 || httpCode == 429)
            else -> false
        }
}

/** Cabeceras de una URL sin descargarla (para detectar cambios en descargas oficiales directas). */
data class ProbeResult(
    val finalUrl: String,
    val contentLength: Long?,
    val etag: String?,
    val lastModified: String?,
) {
    val fingerprint: ResourceFingerprint get() = ResourceFingerprint(contentLength, etag, lastModified)
}

/**
 * Identidad de un archivo publicado en una URL sin versión. Solo se considera «el mismo archivo»
 * si coincide el ETag o la fecha Last-Modified (el tamaño por sí solo no basta).
 */
data class ResourceFingerprint(val length: Long?, val etag: String?, val lastModified: String?) {
    /** Las cabeceras HTTP no pueden contener saltos de línea: se usan como separador. */
    fun encode(): String = "len=${length ?: ""}\netag=${etag.orEmpty()}\nlm=${lastModified.orEmpty()}"

    fun matches(other: ResourceFingerprint?): Boolean {
        if (other == null) return false
        val sizeOk = length == null || other.length == null || length == other.length
        val e1 = etag?.removePrefix("W/")?.takeIf { it.isNotBlank() }
        val e2 = other.etag?.removePrefix("W/")?.takeIf { it.isNotBlank() }
        if (e1 != null && e2 != null) return e1 == e2 && sizeOk
        val m1 = lastModified?.takeIf { it.isNotBlank() }
        val m2 = other.lastModified?.takeIf { it.isNotBlank() }
        if (m1 != null && m2 != null) return m1 == m2 && sizeOk
        return false
    }

    companion object {
        fun decode(text: String?): ResourceFingerprint? {
            if (text.isNullOrBlank() || !text.startsWith("len=")) return null
            val parts = text.split("\n").associate { it.substringBefore('=') to it.substringAfter('=', "") }
            return ResourceFingerprint(
                length = parts["len"]?.toLongOrNull(),
                etag = parts["etag"]?.takeIf { it.isNotEmpty() },
                lastModified = parts["lm"]?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

data class DownloadOutcome(
    /** null si el servidor respondió 304 (sin cambios). */
    val file: File?,
    val etag: String?,
    val lastModified: String?,
    val bytes: Long,
) {
    val notModified: Boolean get() = file == null
}

/**
 * Descargas exclusivamente HTTPS (también en redirecciones), con límites de tamaño,
 * reintentos con espera, reanudación por rangos HTTP tras cortes de red, progreso,
 * cancelación, escritura atómica y comprobación de SHA-256. Kotlin/JVM puro.
 */
class Downloader(
    private val userAgent: String = "TabletSetupManager",
    private val sslSocketFactory: SSLSocketFactory? = null,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    /** El sondeo de cabeceras solo consulta: falla pronto (sin reintentos) para no retrasar la comprobación. */
    private val probeConnectTimeoutMs: Int = 8_000,
    private val probeReadTimeoutMs: Int = 10_000,
    private val retryDelaysMs: List<Long> = listOf(2_000L, 5_000L, 10_000L),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        const val MAX_REDIRECTS = 5
        const val DEFAULT_MAX_APK_BYTES = 600L * 1024 * 1024
    }

    /**
     * Descarga [url] en [destination]. Si [conditional] contiene ETag/Last-Modified previos y el
     * servidor responde 304, devuelve un resultado sin archivo.
     */
    fun download(
        url: String,
        destination: File,
        expectedSha256: String? = null,
        maxBytes: Long = DEFAULT_MAX_APK_BYTES,
        conditionalEtag: String? = null,
        conditionalLastModified: String? = null,
        onProgress: (bytes: Long, total: Long?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        allowedHosts: Set<String>? = null,
    ): DownloadOutcome {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        var validator: String? = null
        var etag: String? = null
        var lastModified: String? = null
        var attempt = 0
        var finished = false
        try {
            while (!finished) {
                try {
                    if (isCancelled()) throw DownloadException(FailureKind.CANCELLED, "Descarga cancelada")
                    val existing = if (partial.exists()) partial.length() else 0L
                    val headers = linkedMapOf<String, String>()
                    if (existing > 0 && validator != null) {
                        headers["Range"] = "bytes=$existing-"
                        headers["If-Range"] = validator!!
                    } else if (existing == 0L) {
                        conditionalEtag?.let { headers["If-None-Match"] = it }
                        conditionalLastModified?.let { headers["If-Modified-Since"] = it }
                    }
                    val connection = connect(
                        url,
                        "GET",
                        headers,
                        allowedHosts = allowedHosts
                    )
                    try {
                        val code = responseCode(connection)
                        if (code == HttpURLConnection.HTTP_NOT_MODIFIED && existing == 0L &&
                            (conditionalEtag != null || conditionalLastModified != null)
                        ) {
                            return DownloadOutcome(null, conditionalEtag, conditionalLastModified, 0)
                        }
                        val append = when (code) {
                            HttpURLConnection.HTTP_OK -> false
                            HttpURLConnection.HTTP_PARTIAL -> {
                                if (existing > 0 && contentRangeStart(connection) == existing) true
                                else {
                                    partial.delete()
                                    throw DownloadException(FailureKind.IO, "El servidor devolvió un rango inesperado")
                                }
                            }
                            416 -> {
                                partial.delete()
                                throw DownloadException(FailureKind.IO, "Rango no satisfacible; se reinicia la descarga")
                            }
                            else -> throw httpError(code)
                        }
                        etag = connection.getHeaderField("ETag") ?: etag
                        lastModified = connection.getHeaderField("Last-Modified") ?: lastModified
                        validator = etag?.takeIf { !it.startsWith("W/") } ?: lastModified
                        val total = if (code == HttpURLConnection.HTTP_PARTIAL) {
                            contentRangeTotal(connection) ?: connection.contentLengthLong.takeIf { it > 0 }?.plus(existing)
                        } else {
                            connection.contentLengthLong.takeIf { it > 0 }
                        }
                        if (total != null && total > maxBytes) {
                            throw DownloadException(FailureKind.TOO_LARGE, "El archivo supera el tamaño máximo permitido")
                        }
                        var written = if (append) existing else 0L
                        onProgress(written, total)
                        try {
                            FileOutputStream(partial, append).use { out ->
                                connection.inputStream.use { input ->
                                    written = copy(input, out, written, maxBytes, total, onProgress, isCancelled)
                                }
                            }
                        } catch (e: IOException) {
                            throw mapIo(e)
                        }
                        if (total != null && written < total) {
                            throw DownloadException(FailureKind.IO, "Descarga incompleta (conexión interrumpida)")
                        }
                        finished = true
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: DownloadException) {
                    if (!e.retryable || attempt >= retryDelaysMs.size || isCancelled()) throw e
                    sleep(retryDelaysMs[attempt++])
                }
            }
            if (!partial.exists() || partial.length() == 0L) {
                throw DownloadException(FailureKind.INTEGRITY, "Descarga vacía")
            }
            if (expectedSha256 != null) {
                val actual = Hashing.sha256Hex(partial)
                if (actual != Hashing.normalize(expectedSha256)) {
                    throw DownloadException(FailureKind.INTEGRITY, "SHA-256 distinto al publicado: archivo alterado o corrupto")
                }
            }
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
            }
            return DownloadOutcome(destination, etag, lastModified, destination.length())
        } finally {
            partial.delete()
        }
    }

    /** Descarga un recurso pequeño (catálogo, firma) en memoria. */
    fun fetch(url: String, maxBytes: Long = 2L * 1024 * 1024): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val connection = connect(url, "GET", emptyMap())
                try {
                    val code = responseCode(connection)
                    if (code != HttpURLConnection.HTTP_OK) throw httpError(code)
                    val declared = connection.contentLengthLong
                    if (declared > maxBytes) throw DownloadException(FailureKind.TOO_LARGE, "Respuesta demasiado grande")
                    return try {
                        connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            copy(input, out, 0, maxBytes, null, { _, _ -> }, { false })
                            out.toByteArray()
                        }
                    } catch (e: IOException) {
                        throw mapIo(e)
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: DownloadException) {
                if (!e.retryable || attempt >= retryDelaysMs.size) throw e
                sleep(retryDelaysMs[attempt++])
            }
        }
    }

    /** Consulta cabeceras (HEAD; si el servidor no lo admite, GET del primer byte). Sin reintentos. */
    fun probe(url: String): ProbeResult {
        val head = try {
            connect(url, "HEAD", emptyMap(), probeConnectTimeoutMs, probeReadTimeoutMs)
        } catch (e: DownloadException) {
            if (e.kind != FailureKind.HTTP) throw e
            null
        }
        head?.let { connection ->
            try {
                val code = responseCode(connection)
                if (code == HttpURLConnection.HTTP_OK) {
                    return ProbeResult(
                        finalUrl = connection.url.toString(),
                        contentLength = connection.contentLengthLong.takeIf { it > 0 },
                        etag = connection.getHeaderField("ETag"),
                        lastModified = connection.getHeaderField("Last-Modified"),
                    )
                }
                if (code != HttpURLConnection.HTTP_BAD_METHOD && code != 501 && code != HttpURLConnection.HTTP_FORBIDDEN) {
                    throw httpError(code)
                }
            } finally {
                connection.disconnect()
            }
        }
        val connection = connect(url, "GET", mapOf("Range" to "bytes=0-0"), probeConnectTimeoutMs, probeReadTimeoutMs)
        try {
            val code = responseCode(connection)
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw httpError(code)
            return ProbeResult(
                finalUrl = connection.url.toString(),
                contentLength = if (code == HttpURLConnection.HTTP_PARTIAL) contentRangeTotal(connection)
                else connection.contentLengthLong.takeIf { it > 0 },
                etag = connection.getHeaderField("ETag"),
                lastModified = connection.getHeaderField("Last-Modified"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun copy(
        input: InputStream,
        out: java.io.OutputStream,
        start: Long,
        maxBytes: Long,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var written = start
        var lastReport = 0L
        while (true) {
            if (isCancelled()) throw DownloadException(FailureKind.CANCELLED, "Descarga cancelada")
            val n = input.read(buffer)
            if (n < 0) break
            written += n
            if (written > maxBytes) throw DownloadException(FailureKind.TOO_LARGE, "El archivo supera el tamaño máximo permitido")
            out.write(buffer, 0, n)
            if (written - lastReport >= 256 * 1024) {
                lastReport = written
                onProgress(written, total)
            }
        }
        onProgress(written, total)
        return written
    }

    private fun connect(
        initialUrl: String,
        method: String,
        headers: Map<String, String>,
        connectTimeout: Int = connectTimeoutMs,
        readTimeout: Int = readTimeoutMs,
        allowedHosts: Set<String>? = null,
    ): HttpURLConnection {
        var url = try {
            URI(initialUrl).toURL()
        } catch (e: Exception) {
            throw DownloadException(FailureKind.INSECURE, "URL no válida")
        }
        // Normalizar la lista una sola vez antes de verificar la URL inicial y sus redirecciones.
        val normalizedAllowedHosts = allowedHosts?.map { it.lowercase(Locale.ROOT) }?.toSet()
        repeat(MAX_REDIRECTS + 1) {
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw DownloadException(FailureKind.INSECURE, "Solo se permiten conexiones HTTPS (${url.protocol} rechazado)")
            }
            if (normalizedAllowedHosts != null) {
                val currentUri = try {
                    url.toURI()
                } catch (e: Exception) {
                    throw DownloadException(FailureKind.INSECURE, "URL de descarga no válida")
                }
                val host = currentUri.host?.lowercase(Locale.ROOT)
                if (host == null || host !in normalizedAllowedHosts ||
                    currentUri.userInfo != null || currentUri.port != -1
                ) {
                    throw DownloadException(FailureKind.INSECURE, "La descarga redirige a un servidor no autorizado")
                }
            }
            val connection = try {
                url.openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw mapIo(e)
            }
            if (connection is HttpsURLConnection && sslSocketFactory != null) {
                connection.sslSocketFactory = sslSocketFactory
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = responseCode(connection)
            if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw DownloadException(FailureKind.HTTP, "Redirección sin destino", code)
                url = try {
                    url.toURI().resolve(location.trim()).toURL()
                } catch (e: Exception) {
                    throw DownloadException(FailureKind.HTTP, "Redirección con destino no válido", code)
                }
            } else {
                return connection
            }
        }
        throw DownloadException(FailureKind.HTTP, "Demasiadas redirecciones")
    }

    private fun responseCode(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (e: IOException) {
        connection.disconnect()
        throw mapIo(e)
    }

    private fun contentRangeStart(connection: HttpURLConnection): Long? =
        connection.getHeaderField("Content-Range")?.let { Regex("bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun contentRangeTotal(connection: HttpURLConnection): Long? =
        connection.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()

    private fun httpError(code: Int): DownloadException {
        val text = when (code) {
            401, 403 -> "Acceso denegado por el servidor (HTTP $code)"
            404, 410 -> "Archivo no encontrado en el servidor (HTTP $code)"
            408 -> "El servidor tardó demasiado en responder (HTTP 408)"
            429 -> "Demasiadas peticiones; el servidor pide esperar (HTTP 429)"
            in 500..599 -> "Error temporal del servidor (HTTP $code)"
            else -> "Respuesta inesperada del servidor (HTTP $code)"
        }
        return DownloadException(FailureKind.HTTP, text, code)
    }

    private fun mapIo(e: IOException): DownloadException = when (e) {
        is UnknownHostException -> DownloadException(FailureKind.NO_NETWORK, "Sin conexión a Internet o servidor no encontrado", cause = e)
        is ConnectException, is NoRouteToHostException ->
            DownloadException(FailureKind.NO_NETWORK, "No se puede conectar con el servidor (¿sin Internet?)", cause = e)
        is SocketTimeoutException -> DownloadException(FailureKind.TIMEOUT, "Tiempo de espera agotado en la conexión", cause = e)
        is SSLException -> DownloadException(FailureKind.TLS, "Error de seguridad HTTPS: certificado del servidor no válido", cause = e)
        else -> DownloadException(FailureKind.IO, "Conexión interrumpida durante la descarga", cause = e)
    }
}
