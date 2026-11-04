package es.empresa.tabletsetup.updater

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.engine.StatusCalculator
import es.empresa.tabletsetup.core.model.AppState
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.model.Resolution
import es.empresa.tabletsetup.core.model.RuleResult
import es.empresa.tabletsetup.core.platform.DeviceInfo
import es.empresa.tabletsetup.core.platform.SuiteSettings
import es.empresa.tabletsetup.core.policy.CompatibilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Registro de la tarea periódica con WorkManager (Robolectric, framework Android real). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateSchedulerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        context.getSharedPreferences("tablet_suite_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun infos(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(UpdateScheduler.WORK_NAME).get()

    @Test
    fun `registra la comprobacion periodica cada 24 horas por defecto`() {
        UpdateScheduler.schedule(context, replace = false)
        val list = infos()
        assertEquals(1, list.size)
        assertEquals(WorkInfo.State.ENQUEUED, list[0].state)
        assertEquals(TimeUnit.HOURS.toMillis(24), list[0].periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.UNMETERED, list[0].constraints.requiredNetworkType)
        assertTrue(list[0].tags.contains(UpdateScheduler.TAG))
    }

    @Test
    fun `abrir de nuevo la app no duplica el trabajo`() {
        UpdateScheduler.schedule(context, replace = false)
        val first = infos().single().id
        UpdateScheduler.schedule(context, replace = false)
        assertEquals(first, infos().single().id)
    }

    @Test
    fun `cambiar la frecuencia y la red actualiza el mismo trabajo`() {
        UpdateScheduler.schedule(context, replace = false)
        val settings = SuiteSettings(context)
        settings.checkIntervalHours = 12
        settings.wifiOnly = false
        UpdateScheduler.schedule(context, replace = true)
        val info = infos().single()
        assertEquals(TimeUnit.HOURS.toMillis(12), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }
}

/** Regla de Waze evaluada con el Build.VERSION real de cada versión de Android (Robolectric). */
@RunWith(AndroidJUnit4::class)
class DeviceRuleRobolectricTest {
    private val waze = AppCatalog.find(AppCatalog.WAZE)!!

    @Test
    @Config(sdk = [28])
    fun `en Android 9 Waze se omite con el mensaje exigido`() {
        val device = DeviceInfo.current()
        assertEquals(28, device.sdkInt)
        val rule = CompatibilityPolicy.deviceRule(waze.packageName, device, waze.minDeviceSdk)
        assertEquals("No compatible con Android 9", (rule as RuleResult.Blocked).reason)
        val status = StatusCalculator.compute(waze, device, null, Resolution.NoSource("x"), Mode.INSTALLER)
        assertEquals(AppState.OMITIDA, status.state)
    }

    @Test
    @Config(sdk = [34])
    fun `en Android 14 Waze queda sujeto a la version concreta`() {
        val device = DeviceInfo.current()
        assertEquals(34, device.sdkInt)
        assertEquals(RuleResult.Allowed, CompatibilityPolicy.deviceRule(waze.packageName, device, waze.minDeviceSdk))
    }
}
