package es.empresa.tabletsetup.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Contrato entre Installer y Updater (configuración y activación del Updater). */
object SuiteContract {
    const val ACTION_CONFIGURE = "es.empresa.tabletsetup.updater.action.CONFIGURE"
    const val CONFIGURE_ACTIVITY = "es.empresa.tabletsetup.updater.ConfigureActivity"
    const val EXTRA_CATALOG_URL = "es.empresa.tabletsetup.extra.CATALOG_URL"
    const val EXTRA_CATALOG_KEY = "es.empresa.tabletsetup.extra.CATALOG_KEY"
}

/** Pantalla de historial básico de resultados. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: SuiteViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadHistory() }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de resultados") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    IconButton(onClick = { vm.clearHistory() }) { Icon(Icons.Filled.Delete, contentDescription = "Borrar historial") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        ) {
            HistoryList(state.history)
        }
    }
}
