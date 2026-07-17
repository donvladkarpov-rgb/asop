package ru.asop.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.asop.terminal.ui.TerminalNavHost
import ru.asop.terminal.ui.theme.AsopTerminalTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AsopTerminalTheme {
                TerminalNavHost()
            }
        }
    }
}
