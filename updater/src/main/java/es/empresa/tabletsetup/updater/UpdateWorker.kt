package es.empresa.tabletsetup.updater

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import es.empresa.tabletsetup.core.engine.BatchPlanner
import es.empresa.tabletsetup.core.model.AppState
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.platform.DeviceInfo
import es.empresa.tabletsetup.core.platform.HistoryEvent
import es.empresa.tabletsetup.core.platform.Notifications
import es.empresa.tabletsetup.core.platform.SuiteEngine
import es.empresa.tabletsetup.core.platform.SuiteSettings
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Comprobación periódica (WorkManager). Solo CONSULTA y NOTIFICA: la instalación de
 * actualizaciones siempre la inicia el técnico y Android pide confirmación.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = SuiteEngine.get(applicationContext)
        val settings = engine.settings
        return try {
            // Las descargas de verificación (fuentes oficiales sin versión) solo en Wi-Fi si así se ha configurado.
            val allowVerifyDownloads = !settings.wifiOnly || DeviceInfo.isUnmetered(applicationContext)
            val result = engine.check(Mode.UPDATER, forceRefresh = true, verifyDirect = allowVerifyDownloads)
            val updates = BatchPlanner.updatesAvailable(result.statuses)
            val errors = result.statuses.count { it.state == AppState.ERROR && it.installed != null }
            val summary = buildString {
                append(if (updates.isEmpty()) "Sin actualizaciones" else "${updates.size} actualización(es): ${updates.joinToString { it.app.label }}")
                if (errors > 0) append(" · $errors fuente(s) con error")
            }
            settings.lastCheckAt = result.checkedAt
            settings.lastCheckSummary = summary
            engine.history.append(
                HistoryEvent(result.checkedAt, applicationContext.packageName, "Tablet Updater", "Comprobación automática", errors == 0, summary)
            )
            if (updates.isNotEmpty() && settings.notificationsEnabled) {
                val signature = updates.joinToString { "${it.app.packageName}:${it.release?.versionCode}" }
                val stale = System.currentTimeMillis() - lastNotifiedAt(applicationContext) > REMIND_AFTER_MS
                if (signature != settings.lastNotifiedSignature || stale) {
                    Notifications.updatesAvailable(applicationContext, updates)
                    settings.lastNotifiedSignature = signature
                    markNotified(applicationContext)
                }
            } else if (updates.isEmpty()) {
                Notifications.clearUpdates(applicationContext)
                settings.lastNotifiedSignature = ""
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun lastNotifiedAt(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("notified_at", 0L)

    private fun markNotified(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong("notified_at", System.currentTimeMillis()) }

    companion object {
        private const val PREFS = "updater_worker"
        private const val REMIND_AFTER_MS = 72L * 60 * 60 * 1000
    }
}

/** Registro de la comprobación periódica (intervalo inicial aproximado de 24 horas). */
object UpdateScheduler {
    const val WORK_NAME = "comprobacion-periodica-actualizaciones"
    const val TAG = "tablet-updater"

    /**
     * @param replace true al cambiar la configuración (frecuencia o Wi-Fi): actualiza el trabajo
     * existente; false al abrir la app: conserva la programación ya registrada.
     */
    fun schedule(context: Context, replace: Boolean) {
        val settings = SuiteSettings(context)
        val hours = settings.checkIntervalHours.coerceIn(1, 168).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS, minOf(2L, hours), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
