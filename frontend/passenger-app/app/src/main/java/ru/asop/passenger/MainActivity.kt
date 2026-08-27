package ru.asop.passenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import ru.asop.passenger.ui.MapScreen
import ru.asop.passenger.ui.theme.PassengerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PassengerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MapScreen()
                }
            }
        }
    }
}
