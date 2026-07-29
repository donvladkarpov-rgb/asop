package ru.asop.terminal.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import ru.asop.terminal.ui.screen.AssignCarrierScreen
import ru.asop.terminal.ui.screen.MainScreen
import ru.asop.terminal.ui.screen.ProvisioningScreen
import ru.asop.terminal.ui.screen.RegistrationScreen
import ru.asop.terminal.ui.screen.TerminalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalNavHost() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val terminalViewModel: TerminalViewModel = hiltViewModel()
    val terminalId by terminalViewModel.terminalId.collectAsState()
    val terminalInfo by terminalViewModel.terminalInfo.collectAsState()

    var showCertDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val tid = terminalViewModel.getStoredTerminalId()
        val certReady = terminalViewModel.isCertificateReady()
        if (certReady && tid != null) {
            terminalViewModel.loadTerminal(tid)
            navController.navigate("main") {
                popUpTo(0) { inclusive = true }
            }
        } else if (certReady) {
            navController.navigate("registration") {
                popUpTo("provisioning") { inclusive = true }
            }
        }
    }

    if (showCertDialog) {
        AlertDialog(
            onDismissRequest = { showCertDialog = false },
            title = { Text("Перевыпуск сертификата") },
            text = { Text("Текущий сертификат будет удалён. Продолжить?") },
            confirmButton = {
                TextButton(onClick = {
                    showCertDialog = false
                    terminalViewModel.regenerateCert()
                    navController.navigate("provisioning") {
                        popUpTo(0) { inclusive = true }
                    }
                }) {
                    Text("Да")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCertDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "ASOP Терминал",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                )
                Text(
                    text = "ID: ${terminalInfo?.id ?: terminalId ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
                Text(
                    text = "Серийный: ${terminalViewModel.androidId}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Сертификат") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showCertDialog = true
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Регистрация") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        if (terminalId == null) {
                            navController.navigate("provisioning")
                        } else {
                            navController.navigate("registration")
                        }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Привязать перевозчика") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("assign-carrier")
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Загрузить справочники") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text("Зарегистрировать карту водителя") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text("Открыть смену") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text("Закрыть смену") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text("Открыть рейс") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text("Закрыть рейс") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("ASOP") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Меню")
                        }
                    }
                )
            }
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = "provisioning",
                modifier = Modifier.padding(paddingValues)
            ) {
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
                composable("assign-carrier") {
                    AssignCarrierScreen(
                        onSaved = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
