package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.engine.BrowserFallbackPlanner
import es.empresa.tabletsetup.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserFallbackPlannerTest {
    private fun st(pkg: String, s: AppState, installed: InstalledInfo? = null) =
        AppStatus(AppCatalog.find(pkg)!!, s, installed, null, "")
    private fun existing() = InstalledInfo(1L, "1", false, emptySet())

    @Test fun firefoxVaDespuesDeChromeAntesDelUpdater() {
        val statuses = listOf(st(AppCatalog.CHROME, AppState.NO_INSTALADA), st(AppCatalog.FIREFOX, AppState.NO_INSTALADA))
        assertEquals(listOf(AppCatalog.CHROME, AppCatalog.FIREFOX, AppCatalog.UPDATER),
            BrowserFallbackPlanner.plan(statuses, Mode.INSTALLER, listOf(AppCatalog.CHROME, AppCatalog.UPDATER)))
    }

    @Test fun firefoxCuandoChromeNoTieneFuente() {
        val statuses = listOf(st(AppCatalog.CHROME, AppState.ERROR), st(AppCatalog.FIREFOX, AppState.NO_INSTALADA))
        assertEquals(listOf(AppCatalog.FIREFOX, AppCatalog.UPDATER),
            BrowserFallbackPlanner.plan(statuses, Mode.INSTALLER, listOf(AppCatalog.UPDATER)))
    }

    @Test fun noFirefoxSiChromeYaInstalado() {
        val statuses = listOf(st(AppCatalog.CHROME, AppState.AL_DIA, existing()), st(AppCatalog.FIREFOX, AppState.NO_INSTALADA))
        assertEquals(listOf(AppCatalog.UPDATER),
            BrowserFallbackPlanner.plan(statuses, Mode.INSTALLER, listOf(AppCatalog.UPDATER)))
    }

    @Test fun noFirefoxAlSeleccionarSoloMapsNiEnUpdater() {
        val statuses = listOf(st(AppCatalog.CHROME, AppState.ERROR), st(AppCatalog.FIREFOX, AppState.NO_INSTALADA))
        val ids = listOf(AppCatalog.MAPS)
        assertEquals(ids, BrowserFallbackPlanner.plan(statuses, Mode.UPDATER, ids))
        assertEquals(ids, BrowserFallbackPlanner.plan(statuses, Mode.INSTALLER, ids, setOf(AppCatalog.MAPS)))
    }
}
