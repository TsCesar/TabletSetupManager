package es.empresa.tabletsetup.core.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import es.empresa.tabletsetup.core.R
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.model.AppStatus

/** Notificaciones del sistema (solo si el usuario las ha permitido). */
object Notifications {
    const val CHANNEL_INSTALLS = "instalaciones"
    const val CHANNEL_UPDATES = "actualizaciones"
    const val UPDATES_ID = 110
    const val EXTRA_START_ACTION = "es.empresa.tabletsetup.extra.START_ACTION"
    const val ACTION_UPDATE_ALL = "actualizar_todo"

    private fun manager(context: Context): NotificationManager? = context.getSystemService(NotificationManager::class.java)

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(context: Context) {
        val nm = manager(context) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INSTALLS, "Confirmaciones de instalación", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisos cuando Android necesita que confirmes una instalación"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "Actualizaciones disponibles", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Nuevas versiones compatibles de las aplicaciones de la tablet"
            }
        )
    }

    private fun confirmationId(sessionId: Int) = 1000 + (sessionId and 0x0FFFFFFF)

    /** Android necesita confirmación y la app no está en primer plano. */
    fun confirmationNeeded(context: Context, sessionId: Int, packageName: String?, confirm: Intent) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val label = packageName?.let { AppCatalog.find(it)?.label ?: it } ?: "aplicación"
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val action = PendingIntent.getActivity(
            context, confirmationId(sessionId), confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_INSTALLS)
            .setSmallIcon(R.drawable.ic_stat_suite)
            .setContentTitle("Confirma la instalación")
            .setContentText("Pulsa para continuar con $label")
            .setContentIntent(action)
            .setAutoCancel(true)
            .build()
        manager(context)?.notify(confirmationId(sessionId), notification)
    }

    fun cancelConfirmation(context: Context, sessionId: Int) {
        manager(context)?.cancel(confirmationId(sessionId))
    }

    /** Aviso de nuevas versiones compatibles, con botón «Actualizar». */
    fun updatesAvailable(context: Context, updates: List<AppStatus>) {
        if (!canNotify(context) || updates.isEmpty()) return
        ensureChannels(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val open = PendingIntent.getActivity(
            context, 1, launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val updateIntent = Intent(launch).putExtra(EXTRA_START_ACTION, ACTION_UPDATE_ALL)
        val update = PendingIntent.getActivity(
            context, 2, updateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val names = updates.joinToString { it.app.label }
        val notification = Notification.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_suite)
            .setContentTitle(if (updates.size == 1) "1 actualización disponible" else "${updates.size} actualizaciones disponibles")
            .setContentText(names)
            .setStyle(Notification.BigTextStyle().bigText(names))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Actualizar", update).build())
            .build()
        manager(context)?.notify(UPDATES_ID, notification)
    }

    fun clearUpdates(context: Context) {
        manager(context)?.cancel(UPDATES_ID)
    }
}
