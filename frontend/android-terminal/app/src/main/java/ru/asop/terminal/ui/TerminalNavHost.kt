package ru.asop.terminal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.ui.screen.MainScreen
import ru.asop.terminal.ui.screen.ProvisioningScreen
import ru.asop.terminal.ui.screen.RegistrationScreen
import ru.asop.terminal.ui.screen.TerminalViewModel

@Composable
fun TerminalNavHost() {
    val navController = rememberNavController()

    val terminalViewModel: TerminalViewModel = hiltViewModel()
    val terminalId by terminalViewModel.terminalId.collectAsState()

    LaunchedEffect(Unit) {
        val certReady = terminalViewModel.isCertificateReady()
        if (certReady && terminalId != null) {
            terminalViewModel.loadTerminal(terminalId!!)
            navController.navigate("main") {
                popUpTo(0) { inclusive = true }
            }
        } else if (certReady) {
            navController.navigate("registration") {
                popUpTo("provisioning") { inclusive = true }
            }
        }
    }

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
