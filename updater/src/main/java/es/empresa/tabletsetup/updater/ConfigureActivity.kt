package es.empresa.tabletsetup.updater

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import es.empresa.tabletsetup.core.catalog.AppCatalog
import es.empresa.tabletsetup.core.catalog.CatalogSignature
import es.empresa.tabletsetup.core.catalog.EnterpriseCatalogParser
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.platform.HistoryEvent
import es.empresa.tabletsetup.core.platform.SuiteEngine
import es.empresa.tabletsetup.core.ui.SuiteContract

/**
 * Recibe del Installer la configuración del catálogo y ACTIVA el Updater (registra la
 * comprobación periódica). Protegida por un permiso de firma y comprobando el llamante.
 *
 * En Android 13+ pide además el permiso de notificaciones: sin él, los avisos de
 * actualización no se mostrarían si el técnico no llega a abrir nunca el Updater.
 */
class ConfigureActivity : Activity() {
    private var result = RESULT_CANCELED
    private var waitingPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreada (p. ej. al girar la tablet) mientras se mostraba el diálogo de permisos.
            result = savedInstanceState.getInt(KEY_RESULT, RESULT_CANCELED)
            waitingPermission = savedInstanceState.getBoolean(KEY_WAITING, false)
            setResult(result)
            if (!waitingPermission) finish()
            return
        }
        val caller = callingPackage
        val trusted = caller != null && caller == AppCatalog.INSTALLER &&
            packageManager.checkSignatures(caller, packageName) == PackageManager.SIGNATURE_MATCH
        if (!trusted) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val engine = SuiteEngine.get(this)
        val url = intent.getStringExtra(SuiteContract.EXTRA_CATALOG_URL)?.trim().orEmpty()
        val key = intent.getStringExtra(SuiteContract.EXTRA_CATALOG_KEY)?.trim().orEmpty()
        var ok = true
        var detail = "Activado desde Tablet Setup Installer"
        if (url.isNotEmpty() || key.isNotEmpty()) {
            try {
                if (url.isNotEmpty()) EnterpriseCatalogParser.requireHttps(url)
                if (key.isNotEmpty()) CatalogSignature.parsePublicKey(key)
                if (url != engine.settings.catalogUrl || key != engine.settings.catalogPublicKey) {
                    engine.settings.catalogUrl = url
                    engine.settings.catalogPublicKey = key
                    engine.catalogRepository.reset()
                }
                detail += " · catálogo empresarial configurado"
            } catch (e: ValidationException) {
                ok = false
                detail += " · configuración de catálogo rechazada: ${e.message}"
            }
        }
        UpdateScheduler.schedule(this, replace = false)
        engine.history.append(HistoryEvent(System.currentTimeMillis(), packageName, "Tablet Updater", "Activación", ok, detail))
        result = if (ok) RESULT_OK else RESULT_FIRST_USER
        setResult(result)
        if (Build.VERSION.SDK_INT >= 33 && engine.settings.notificationsEnabled &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            waitingPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        waitingPermission = false
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        SuiteEngine.get(this).history.append(
            HistoryEvent(
                System.currentTimeMillis(), packageName, "Tablet Updater", "Permiso de notificaciones", granted,
                if (granted) "Concedido: se avisará de las actualizaciones"
                else "Denegado: no se mostrarán avisos (actívalo en Ajustes › Aplicaciones › Tablet Updater › Notificaciones)",
            )
        )
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_RESULT, result)
        outState.putBoolean(KEY_WAITING, waitingPermission)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 41
        const val KEY_RESULT = "resultado"
        const val KEY_WAITING = "esperando_permiso"
    }
}
