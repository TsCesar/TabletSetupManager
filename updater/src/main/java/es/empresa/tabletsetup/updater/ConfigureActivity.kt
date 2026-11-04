package es.empresa.tabletsetup.updater

import android.app.Activity
import android.content.pm.PackageManager
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
 */
class ConfigureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setResult(if (ok) RESULT_OK else RESULT_FIRST_USER)
        finish()
    }
}
