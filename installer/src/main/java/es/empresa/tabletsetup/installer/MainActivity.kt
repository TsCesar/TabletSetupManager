package es.empresa.tabletsetup.installer

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.empresa.tabletsetup.core.model.Mode
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
        setContent {
            SuiteTheme(SuiteAccent.INSTALLER) {
                InstallerApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}
