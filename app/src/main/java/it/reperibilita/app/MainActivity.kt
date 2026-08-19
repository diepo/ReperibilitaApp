package it.reperibilita.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import it.reperibilita.app.ui.screens.DashboardScreen
import it.reperibilita.app.ui.screens.LogsScreen
import it.reperibilita.app.ui.screens.OverrideScreen
import it.reperibilita.app.ui.screens.SettingsScreen
import it.reperibilita.app.ui.screens.SplashScreen
import it.reperibilita.app.ui.theme.ReperibilitaTheme

private sealed class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Destination("dashboard", "Stato", Icons.Filled.Dashboard)
    object Settings : Destination("settings", "Impostazioni", Icons.Filled.Settings)
    object Override : Destination("override", "Override", Icons.AutoMirrored.Filled.PhoneForwarded)
    object Logs : Destination("logs", "Log", Icons.AutoMirrored.Filled.List)
}

private val destinations = listOf(Destination.Dashboard, Destination.Settings, Destination.Override, Destination.Logs)

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Va chiamato PRIMA di super.onCreate(): installa lo splash di sistema (tema
        // Theme.Reperibilita.Starting), che copre l'istante altrimenti bianco/nero tra il
        // lancio del processo e il primo frame disegnato da Compose.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        val app = application as App
        setContent {
            ReperibilitaTheme {
                AppScaffold(app)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(permissions.toTypedArray())
    }
}

@Composable
private fun AppScaffold(app: App) {
    // Mostrata una sola volta per ogni avvio a freddo dell'Activity (non ad ogni cambio di tab):
    // remember senza chiavi la ricrea solo se AppScaffold viene ricomposto da zero, cioe' ad ogni
    // vero riavvio dell'app, non durante la navigazione normale tra le schede.
    var showSplash by remember { mutableStateOf(true) }
    if (showSplash) {
        SplashScreen(onFinished = { showSplash = false })
        return
    }

    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                destinations.forEach { dest ->
                    NavigationBarItem(
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destination.Dashboard.route) { DashboardScreen(app) }
            composable(Destination.Settings.route) { SettingsScreen(app) }
            composable(Destination.Override.route) { OverrideScreen(app) }
            composable(Destination.Logs.route) { LogsScreen(app) }
        }
    }
}
