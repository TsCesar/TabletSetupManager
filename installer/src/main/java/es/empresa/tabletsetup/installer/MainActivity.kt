package es.empresa.tabletsetup.installer

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.empresa.tabletsetup.core.model.Mode
import es.empresa.tabletsetup.core.platform.Notifications
import es.empresa.tabletsetup.core.ui.SuiteAccent
import es.empresa.tabletsetup.core.ui.SuiteActivity
import es.empresa.tabletsetup.core.ui.SuiteTheme
import es.empresa.tabletsetup.core.ui.SuiteViewModel

/** Tablet Setup Installer: instalador inicial de tablets nuevas. */
class MainActivity : SuiteActivity() {
    private val viewModel: SuiteViewModel by viewModels {
        viewModelFactory {
            initializer {
                SuiteViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, Mode.INSTALLER)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTestAction(intent)
        setContent {
            SuiteTheme(SuiteAccent.INSTALLER) {
                InstallerApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestAction(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    /**
     * Solo en compilaciones debug (pruebas automáticas en emulador): equivale a pulsar
     * «Instalar todas las compatibles». Android sigue pidiendo confirmación en cada instalación.
     */
    private fun handleTestAction(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.getStringExtra(Notifications.EXTRA_START_ACTION) == ACTION_INSTALL_ALL) {
            intent.removeExtra(Notifications.EXTRA_START_ACTION)
            viewModel.requestBatchAfterRefresh()
        }
    }

    companion object {
        const val ACTION_INSTALL_ALL = "instalar_todo"
    }
}
