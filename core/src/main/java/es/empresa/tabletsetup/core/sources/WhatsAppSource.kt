
package es.empresa.tabletsetup.core.sources

import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.net.Downloader

/**
 * Obtiene el enlace APK vigente desde
 * la página oficial de WhatsApp.
 */
class WhatsAppSource(
    private val downloader: Downloader
) {

    companion object {
        const val OFFICIAL_PAGE =
            "https://www.whatsapp.com/download/android"

        private const val MAX_HTML_BYTES =
            2L * 1024 * 1024
    }

    /**
     * Consulta la web oficial y devuelve
     * la URL HTTPS del archivo APK.
     *
     * No descarga todavía la APK.
     */
    fun resolveDownloadUrl(
        pageUrl: String = OFFICIAL_PAGE
    ): String {

        if (pageUrl != OFFICIAL_PAGE) {
            throw ValidationException(
                "La página de WhatsApp no está autorizada"
            )
        }

        val htmlBytes = downloader.fetch(
            url = pageUrl,
            maxBytes = MAX_HTML_BYTES
        )

        val html = String(
            htmlBytes,
            Charsets.UTF_8
        )

        return WhatsAppPageParser.extractDownloadUrl(html)
            ?: throw ValidationException(
                "No se encontró un enlace APK válido " +
                    "en la página oficial de WhatsApp"
            )
    }
}
