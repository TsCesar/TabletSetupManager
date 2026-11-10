package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.engine.PdfFallbackPlanner
import es.empresa.tabletsetup.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfFallbackPlannerTest {
    private fun st(pkg: String, state: AppState, installed: InstalledInfo? = null) =
        AppStatus(AppCatalog.find(pkg)!!, state, installed, null, "")
    private fun installed() = InstalledInfo(10L, "10", false, emptySet())

    @Test fun adobeSigueAMupdfCuandoSeIntentaInstalar() {
        val statuses = listOf(
            st(AppCatalog.MUPDF, AppState.NO_INSTALADA),
            st(AppCatalog.ADOBE_READER, AppState.NO_INSTALADA)
        )
        assertEquals(
            listOf(AppCatalog.MUPDF, AppCatalog.ADOBE_READER, AppCatalog.UPDATER),
            PdfFallbackPlanner.plan(statuses, Mode.INSTALLER, listOf(AppCatalog.MUPDF, AppCatalog.UPDATER))
        )
    }

    @Test fun adobeSeIntentaCuandoMupdfNoEsCompatible() {
        val statuses = listOf(
            st(AppCatalog.MUPDF, AppState.NO_COMPATIBLE),
            st(AppCatalog.ADOBE_READER, AppState.NO_INSTALADA)
        )
        assertEquals(
            listOf(AppCatalog.ADOBE_READER, AppCatalog.UPDATER),
            PdfFallbackPlanner.plan(statuses, Mode.INSTALLER, listOf(AppCatalog.UPDATER))
        )
    }

    @Test fun adobeNoSePlanificaCuandoMupdfYaEstaInstalado() {
        val statuses = listOf(
            st(AppCatalog.MUPDF, AppState.AL_DIA, installed()),
            st(AppCatalog.ADOBE_READER, AppState.NO_INSTALADA)
        )
        assertEquals(
            listOf(AppCatalog.UPDATER),
            PdfFallbackPlanner.plan(statuses, Mode.INSTALLER, listOf(AppCatalog.UPDATER))
        )
    }

    @Test fun adobeNoInterfiereConOtrasAplicacionesNiConUpdater() {
        val statuses = listOf(
            st(AppCatalog.MUPDF, AppState.NO_COMPATIBLE),
            st(AppCatalog.ADOBE_READER, AppState.NO_INSTALADA)
        )
        val ids = listOf(AppCatalog.MAPS)
        assertEquals(ids, PdfFallbackPlanner.plan(statuses, Mode.UPDATER, ids))
        assertEquals(ids, PdfFallbackPlanner.plan(statuses, Mode.INSTALLER, ids, setOf(AppCatalog.MAPS)))
    }
}
