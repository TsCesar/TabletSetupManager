package es.empresa.tabletsetup.core.sources

import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.net.Downloader
import es.empresa.tabletsetup.core.net.DownloadException
import java.util.Locale

/**
 * Firefox desde https://archive.mozilla.org, sin Google Play ni tiendas de terceros.
 * Solo se consideran versiones estables y APK completas de una ABI conocida.
 */
class FirefoxArchiveSource(private val downloader: Downloader) {
    companion object {
        const val RELEASES = "https://archive.mozilla.org/pub/fenix/releases/"
        private const val MAX_INDEX = 2L * 1024 * 1024
        private val STABLE_LINK = Regex("""href=["']([0-9]{2,3}\.[0-9]{1,2}(?:\.[0-9]{1,2})?)/["']""")

        internal fun supportedAbi(abis: List<String>): String? =
            abis.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }

        internal fun stableVersions(html: String): List<String> =
            STABLE_LINK.findAll(html).map { it.groupValues[1] }.distinct()
                .sortedWith(compareByDescending<String> { it.substringBefore('.').toInt() }
                    .thenByDescending { it.substringAfter('.').substringBefore('.').toInt() }
                    .thenByDescending { if (it.count { ch -> ch == '.' } == 2) it.substringAfterLast('.').toInt() else 0 })
                .toList()

        internal fun apkUrl(version: String, abi: String): String {
            require(Regex("""[0-9]{2,3}\.[0-9]{1,2}(?:\.[0-9]{1,2})?""").matches(version))
            require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
            return RELEASES + version + "/android/fenix-" + version + "-android-" + abi + "/" +
                "fenix-" + version + ".multi.android-" + abi + ".apk"
        }
    }

    fun resolveDownloadUrl(deviceAbis: List<String>): String {
        val abi = supportedAbi(deviceAbis.map { it.lowercase(Locale.ROOT) })
            ?: throw ValidationException("Firefox: ninguna arquitectura compatible")
        if (abi == "x86") {
            throw ValidationException("Firefox actual no admite x86 de 32 bits")
        }
        val html = String(downloader.fetch(RELEASES, maxBytes = MAX_INDEX), Charsets.UTF_8)
        val versions = stableVersions(html)
        if (versions.isEmpty()) throw ValidationException("Mozilla no devolvió versiones estables de Firefox")
        for (version in versions.take(12)) {
            val url = apkUrl(version, abi)
            try {
                val probe = downloader.probe(url)
                if (probe.contentLength == null || probe.contentLength > 1_000_000) return url
            } catch (e: DownloadException) {
                if (e.httpCode == 404 || e.httpCode == 403) continue
                throw e
            }
        }
        throw ValidationException("Mozilla no publica una APK reciente de Firefox para " + abi)
    }
}
