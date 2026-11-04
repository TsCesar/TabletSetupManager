package es.empresa.tabletsetup.updater

import android.content.Intent
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

/** Tablet Updater: aplicación permanente que mantiene actualizadas las apps ya instaladas. */
class MainActivity : SuiteActivity() {
    private val viewModel: SuiteViewModel by viewModels {
        viewModelFactory {
            initializer {
                SuiteViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, Mode.UPDATER)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Abrir el Updater una vez basta para registrar la comprobación periódica.
        UpdateScheduler.schedule(this, replace = false)
        handleStartAction(intent)
        setContent {
            SuiteTheme(SuiteAccent.UPDATER) {
                UpdaterApp(viewModel, onScheduleChanged = { UpdateScheduler.schedule(this, replace = true) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartAction(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun handleStartAction(intent: Intent?) {
        if (intent?.getStringExtra(Notifications.EXTRA_START_ACTION) == Notifications.ACTION_UPDATE_ALL) {
            intent.removeExtra(Notifications.EXTRA_START_ACTION)
            viewModel.requestUpdateAllAfterRefresh()
        }
    }
}
