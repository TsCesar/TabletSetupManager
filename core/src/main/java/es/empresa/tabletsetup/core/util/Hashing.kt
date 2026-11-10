package es.empresa.tabletsetup.core.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Utilidades de hash. Kotlin puro. */
object Hashing {
    private val SHA256_HEX = Regex("^[A-Fa-f0-9]{64}$")

    fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return toHex(digest.digest())
    }

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(chars)
    }

    fun fromHex(hex: String): ByteArray {
        val clean = normalize(hex)
        require(clean.length % 2 == 0) { "Hexadecimal de longitud impar" }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Acepta «AB:CD:...», con espacios o en minúsculas, y devuelve 64 hex en minúsculas. */
    fun normalize(fingerprint: String): String = fingerprint.replace(":", "").replace(" ", "").trim().lowercase()

    fun isSha256Hex(value: String?): Boolean = value != null && SHA256_HEX.matches(normalize(value))

    /** Formato legible «AB:CD:EF...». */
    fun pretty(hex: String): String = normalize(hex).uppercase().chunked(2).joinToString(":")

    /** Formato corto para la interfaz: primeros y últimos bytes. */
    fun short(hex: String): String {
        val p = pretty(hex)
        return if (p.length <= 23) p else p.take(11) + "…" + p.takeLast(11)
    }
}
