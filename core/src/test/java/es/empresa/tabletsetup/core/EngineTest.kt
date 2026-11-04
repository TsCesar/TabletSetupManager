package es.empresa.tabletsetup.core

import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.engine.BatchPlanner
import es.empresa.tabletsetup.core.engine.SequentialRunner
import es.empresa.tabletsetup.core.engine.StatusCalculator
import es.empresa.tabletsetup.core.engine.TaskOutcome
import es.empresa.tabletsetup.core.model.ApkAsset
import es.empresa.tabletsetup.core.model.AppRelease
import es.empresa.tabletsetup.core.model.AppState
import es.empresa.tabletsetup.core.model.AppStatus
import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.InstalledInfo
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.model.Resolution
import es.empresa.tabletsetup.core.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val android9 = DeviceProfile(28, "9", listOf("arm64-v8a", "armeabi-v7a"))
    private val android12 = DeviceProfile(31, "12", listOf("arm64-v8a", "armeabi-v7a"))

    private fun app(pkg: String) = AppCatalog.find(pkg)!!

    private fun release(pkg: String, code: Long?, signers: Set<String> = emptySet(), minSdk: Int = 28) = AppRelease(
        packageName = pkg, versionCode = code, versionName = code?.let { "v$it" }, minSdk = minSdk,
        assets = listOf(ApkAsset("https://x.example/$pkg.apk", if (code == null) null else "a".repeat(64), "base.apk")),
        expectedSigners = signers, origin = SourceKind.ENTERPRISE_CATALOG,
    )

    private fun installed(code: Long, signer: String = "a".repeat(64)) = InstalledInfo(code, "v$code", false, setOf(signer))

    @Test
    fun `Waze omitido en Android 9 sin bloquear el resto`() {
        val waze = StatusCalculator.compute(app(AppCatalog.WAZE), android9, null, Resolution.Available(release(AppCatalog.WAZE, 5)), Mode.INSTALLER)
        assertEquals(AppState.OMITIDA, waze.state)
        assertEquals("No compatible con Android 9", waze.message)
        val maps = StatusCalculator.compute(app(AppCatalog.MAPS), android9, null, Resolution.Available(release(AppCatalog.MAPS, 5)), Mode.INSTALLER)
        assertEquals(AppState.NO_INSTALADA, maps.state)
        val plan = BatchPlanner.plan(listOf(waze, maps), Mode.INSTALLER)
        assertEquals(listOf(AppCatalog.MAPS), plan)
    }

    @Test
    fun `estados segun fuente y version instalada`() {
        val noSource = StatusCalculator.compute(app(AppCatalog.WHATSAPP), android12, null, Resolution.NoSource("Fuente pendiente"), Mode.INSTALLER)
        assertEquals(AppState.FUENTE_PENDIENTE, noSource.state)
        val installedNoSource = StatusCalculator.compute(app(AppCatalog.CHROME), android12, installed(10), Resolution.NoSource("sin fuente"), Mode.UPDATER)
        assertEquals(AppState.INSTALADA_SIN_FUENTE, installedNoSource.state)
        val update = StatusCalculator.compute(app(AppCatalog.GMAIL), android12, installed(10), Resolution.Available(release(AppCatalog.GMAIL, 11)), Mode.UPDATER)
        assertEquals(AppState.ACTUALIZACION, update.state)
        val same = StatusCalculator.compute(app(AppCatalog.GMAIL), android12, installed(11), Resolution.Available(release(AppCatalog.GMAIL, 11)), Mode.UPDATER)
        assertEquals(AppState.AL_DIA, same.state)
        val newerInstalled = StatusCalculator.compute(app(AppCatalog.GMAIL), android12, installed(12), Resolution.Available(release(AppCatalog.GMAIL, 11)), Mode.UPDATER)
        assertEquals(AppState.AL_DIA, newerInstalled.state)
        val incompatible = StatusCalculator.compute(app(AppCatalog.GMAIL), android12, installed(1), Resolution.Available(release(AppCatalog.GMAIL, 11, minSdk = 33)), Mode.UPDATER)
        assertEquals(AppState.NO_COMPATIBLE, incompatible.state)
        val error = StatusCalculator.compute(app(AppCatalog.MUPDF), android12, null, Resolution.Failed("Sin conexión a Internet"), Mode.INSTALLER)
        assertEquals(AppState.ERROR, error.state)
        val direct = StatusCalculator.compute(app(AppCatalog.QUICKSUPPORT), android12, installed(3), Resolution.Available(release(AppCatalog.QUICKSUPPORT, null)), Mode.UPDATER)
        assertEquals(AppState.POR_VERIFICAR, direct.state)
    }

    @Test
    fun `firma distinta a la instalada se detecta antes de descargar`() {
        val status = StatusCalculator.compute(
            app(AppCatalog.MUPDF), android12, installed(5, signer = "b".repeat(64)),
            Resolution.Available(release(AppCatalog.MUPDF, 9, signers = setOf("c".repeat(64)))), Mode.UPDATER,
        )
        assertEquals(AppState.NO_COMPATIBLE, status.state)
        assertTrue(status.message.contains("otra fuente"))
    }

    @Test
    fun `el Updater solo actualiza aplicaciones instaladas`() {
        val statuses = listOf(
            StatusCalculator.compute(app(AppCatalog.WHATSAPP), android12, null, Resolution.Available(release(AppCatalog.WHATSAPP, 9)), Mode.UPDATER),
            StatusCalculator.compute(app(AppCatalog.GMAIL), android12, installed(1), Resolution.Available(release(AppCatalog.GMAIL, 2)), Mode.UPDATER),
            StatusCalculator.compute(app(AppCatalog.MAPS), android12, installed(3), Resolution.Available(release(AppCatalog.MAPS, 3)), Mode.UPDATER),
        )
        assertEquals(AppState.NO_INSTALADA, statuses[0].state)
        assertTrue(statuses[0].message.contains("solo actualiza"))
        assertEquals(listOf(AppCatalog.GMAIL), BatchPlanner.plan(statuses, Mode.UPDATER))
        // Aunque se seleccione expresamente, una app no instalada no entra en modo Updater.
        assertEquals(listOf(AppCatalog.GMAIL), BatchPlanner.plan(statuses, Mode.UPDATER, setOf(AppCatalog.WHATSAPP, AppCatalog.GMAIL)))
        assertEquals(1, BatchPlanner.updatesAvailable(statuses).size)
    }

    @Test
    fun `instalar todas excluye opcionales y deja el Updater al final`() {
        val statuses: List<AppStatus> = listOf(AppCatalog.UPDATER, AppCatalog.QS_UNIVERSAL_ADDON, AppCatalog.MUPDF, AppCatalog.QUICKSUPPORT).map {
            StatusCalculator.compute(app(it), android12, null, Resolution.Available(release(it, 1)), Mode.INSTALLER)
        }
        assertEquals(listOf(AppCatalog.MUPDF, AppCatalog.QUICKSUPPORT, AppCatalog.UPDATER), BatchPlanner.plan(statuses, Mode.INSTALLER))
        // Selección individual: el add-on solo entra si se elige.
        assertEquals(listOf(AppCatalog.QS_UNIVERSAL_ADDON), BatchPlanner.plan(statuses, Mode.INSTALLER, setOf(AppCatalog.QS_UNIVERSAL_ADDON)))
    }

    @Test
    fun `la cola secuencial continua tras un fallo y admite cancelacion`() = runBlocking {
        val order = mutableListOf<String>()
        val summary = SequentialRunner.run(listOf("a", "b", "c"), task = { id ->
            order += id
            when (id) {
                "a" -> TaskOutcome(false, "Error de red")
                "b" -> throw IllegalStateException("fallo inesperado")
                else -> TaskOutcome(true, "Instalada")
            }
        })
        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(listOf("c"), summary.succeeded)
        assertEquals(listOf("a", "b"), summary.failed)
        var cancel = false
        val cancelled = SequentialRunner.run(listOf("x", "y"), task = { cancel = true; TaskOutcome(true, "ok") }, isCancelled = { cancel })
        assertEquals(listOf("x"), cancelled.succeeded)
        assertEquals(listOf("y"), cancelled.skipped)
    }
}
