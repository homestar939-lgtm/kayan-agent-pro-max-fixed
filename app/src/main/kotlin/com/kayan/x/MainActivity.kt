package com.kayan.x

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kayan.x.ui.MainViewModel
import com.kayan.x.ui.screens.ChatScreen
import com.kayan.x.ui.screens.ModelScreen
import com.kayan.x.ui.screens.SettingsScreen
import com.kayan.x.ui.theme.KayanTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KayanTheme {
                KayanApp()
            }
        }
    }
}

@Composable
private fun KayanApp() {
    val navController = rememberNavController()
    val vm: MainViewModel = viewModel()

    val navItems = listOf(
        NavItem("chat",     "المحادثة",  Icons.Default.Chat),
        NavItem("model",    "النموذج",   Icons.Default.Storage),
        NavItem("settings", "الإعدادات", Icons.Default.Settings)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current   = backStack?.destination?.route
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = current == item.route,
                        onClick  = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon  = { Icon(item.icon, item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = "chat",
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable("chat")     { ChatScreen(vm) }
            composable("model")    { ModelScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
