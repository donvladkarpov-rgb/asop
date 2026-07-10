package ru.asop.terminal.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.asop.terminal.ui.screen.MainScreen
import ru.asop.terminal.ui.screen.ProvisioningScreen
import ru.asop.terminal.ui.screen.RegistrationScreen

@Composable
fun TerminalNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "provisioning") {
        composable("provisioning") {
            ProvisioningScreen(
                onProvisioned = { navController.navigate("registration") }
            )
        }
        composable("registration") {
            RegistrationScreen(
                onRegistered = { navController.navigate("main") }
            )
        }
        composable("main") {
            MainScreen()
        }
    }
}
