package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.engine.FallbackInstallationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackManualSelectionTest {
    private fun reason(pkg: String, requested: Set<String>, installed: Set<String>): String? =
        FallbackInstallationPolicy.skipReason(pkg, requested, installed::contains)

    @Test fun instalacionAutomaticaOmiteFirefoxSiChromeYaExiste() {
        val why = reason(AppCatalog.FIREFOX, emptySet(), setOf(AppCatalog.CHROME))
        assertEquals(true, why?.contains("Chrome ya está instalado"))
    }

    @Test fun permiteFirefoxElegidoManualmenteAunqueChromeEsteInstalado() {
        assertNull(reason(
            AppCatalog.FIREFOX, setOf(AppCatalog.FIREFOX), setOf(AppCatalog.CHROME)
        ))
    }

    @Test fun instalacionAutomaticaOmiteAdobeSiMuPdfYaExiste() {
        val why = reason(AppCatalog.ADOBE_READER, emptySet(), setOf(AppCatalog.MUPDF))
        assertEquals(true, why?.contains("MuPDF ya está instalado"))
    }

    @Test fun permiteAdobeElegidoManualmenteAunqueMuPdfEsteInstalado() {
        assertNull(reason(
            AppCatalog.ADOBE_READER, setOf(AppCatalog.ADOBE_READER), setOf(AppCatalog.MUPDF)
        ))
    }

    @Test fun alternativasSeInstalanCuandoNoExisteLaPrincipal() {
        assertNull(reason(AppCatalog.FIREFOX, emptySet(), emptySet()))
        assertNull(reason(AppCatalog.ADOBE_READER, emptySet(), emptySet()))
    }

    @Test fun seleccionIndividualNoAfectaOtrasAplicaciones() {
        assertNull(reason(AppCatalog.GMAIL, emptySet(), setOf(AppCatalog.CHROME)))
        assertNull(reason(AppCatalog.MAPS, emptySet(), setOf(AppCatalog.MUPDF)))
    }
}
