
package es.empresa.tabletsetup.core.sources

import java.net.URI

/**
 * Extrae el enlace APK desde la página oficial
 * de descarga de WhatsApp.
 */
object WhatsAppPageParser {

    private val hrefPattern =
        Regex("""(?is)\bhref\s*=\s*(['"])(.*?)\1""")

    fun extractDownloadUrl(html: String): String? {
        return hrefPattern.findAll(html)
            .map { match ->
                match.groupValues[2]
                    .replace("&amp;", "&")
                    .replace("&#38;", "&")
                    .replace("&#x26;", "&")
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
            }
            .firstOrNull { isOfficialApk(it) }
    }

    private fun isOfficialApk(url: String): Boolean {
        return try {
            val uri = URI(url)

            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(
                    "scontent.whatsapp.net",
                    ignoreCase = true
                ) &&
                uri.path.endsWith(
                    "/WhatsApp.apk",
                    ignoreCase = true
                )
        } catch (e: Exception) {
            false
        }
    }
}
