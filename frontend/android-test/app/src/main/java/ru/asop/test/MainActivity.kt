package ru.asop.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checker = EthalonChecker(contentResolver)
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("ASOP Test — Delta Sync") }) }
                ) { padding ->
                    TestMenu(
                        checker = checker,
                        context = this@MainActivity,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
private fun TestMenu(
    checker: EthalonChecker,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<EthalonChecker.Result?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Проверка данных reference_rows через ContentProvider " +
                "(content://ru.asop.terminal.provider) против ethalon JSON.",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = {
                result = null
                running = "expected-1.json"
                scope.launch {
                    result = checker.check(context, "expected-1.json")
                    running = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = running == null
        ) {
            Text(if (running == "expected-1.json") "Проверка..." else "Тест дельта инкремента 1")
        }

        Button(
            onClick = {
                result = null
                running = "expected-2.json"
                scope.launch {
                    result = checker.check(context, "expected-2.json")
                    running = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = running == null
        ) {
            Text(if (running == "expected-2.json") "Проверка..." else "Тест дельта инкремента 2")
        }

        Button(
            onClick = {
                result = null
                running = "expected-all.json"
                scope.launch {
                    result = checker.check(context, "expected-all.json")
                    running = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = running == null
        ) {
            Text(if (running == "expected-all.json") "Проверка..." else "Получить все данные")
        }

        OutlinedButton(
            onClick = { result = null },
            modifier = Modifier.fillMaxWidth(),
            enabled = running == null
        ) {
            Text("Сбросить")
        }

        result?.let { r ->
            Spacer(Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (r.ok) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = if (r.ok) "СОВПАДЕНИЕ: ${r.assetName} ✓" else "РАСХОЖДЕНИЕ: ${r.assetName} ✗",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    r.error?.let { Text("Ошибка: $it") }
                    for (t in r.tables) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${t.table}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (t.ok) "OK" else "MISMATCH",
                                color = if (t.ok) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "  room=${t.roomCount} ethalon=${t.ethalonCount} " +
                                "missing=${t.missingInRoomCount} extra=${t.extraInRoomCount} diff=${t.diffs.size}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        t.diffs.take(3).forEach {
                            Text(
                                "  DIFF\n    room: ${it.room}\n    eth:  ${it.ethalon}",
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
