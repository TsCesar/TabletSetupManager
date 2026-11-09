package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.sources.FirefoxArchiveSource
import org.junit.Assert.*
import org.junit.Test

class FirefoxArchiveSourceTest {
    @Test fun soloVersionesEstablesOrdenadas() {
        val html = """<a href="148.0/">148.0</a>
<a href="149.0.1/">149.0.1</a><a href="149.0b1/">beta</a>
<a href="149.0.2/">149.0.2</a><a href="147.0.4/">147.0.4</a>"""
        assertEquals(listOf("149.0.2", "149.0.1", "148.0", "147.0.4"),
            FirefoxArchiveSource.stableVersions(html))
    }

    @Test fun seleccionDeArquitectura() {
        assertEquals("x86_64", FirefoxArchiveSource.supportedAbi(listOf("x86_64", "x86")))
        assertEquals("arm64-v8a", FirefoxArchiveSource.supportedAbi(listOf("arm64-v8a", "armeabi-v7a")))
        assertNull(FirefoxArchiveSource.supportedAbi(listOf("mips")))
    }

    @Test fun urlOficialPorArquitectura() {
        assertEquals(
            "https://archive.mozilla.org/pub/fenix/releases/149.0.2/android/fenix-149.0.2-android-x86_64/fenix-149.0.2.multi.android-x86_64.apk",
            FirefoxArchiveSource.apkUrl("149.0.2", "x86_64")
        )
    }
}
