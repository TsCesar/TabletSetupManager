package es.empresa.tabletsetup.core.ui

import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import es.empresa.tabletsetup.core.platform.InstallEvents
import es.empresa.tabletsetup.core.platform.Notifications
import kotlinx.coroutines.launch

/**
 * Actividad base de Installer y Updater: muestra la confirmación de instalación que pide
 * Android (PackageInstaller STATUS_PENDING_USER_ACTION) en cuanto la app está en primer plano.
 */
abstract class SuiteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                InstallEvents.pending.collect { pending ->
                    if (pending != null && !pending.launched) {
                        InstallEvents.markLaunched(pending.sessionId)
                        Notifications.cancelConfirmation(this@SuiteActivity, pending.sessionId)
                        try {
                            startActivity(pending.intent)
                        } catch (e: Exception) {
                            InstallEvents.onResult(
                                pending.sessionId, pending.packageName, PackageInstaller.STATUS_FAILURE,
                                "No se pudo abrir la confirmación de Android",
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        InstallEvents.isUiVisible = true
    }

    override fun onStop() {
        InstallEvents.isUiVisible = false
        super.onStop()
    }
}
