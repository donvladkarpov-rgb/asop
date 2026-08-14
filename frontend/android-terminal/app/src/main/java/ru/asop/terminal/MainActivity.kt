package ru.asop.terminal

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
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

    /**
     * Feitian F20 может игнорировать NfcAdapter.enableReaderMode() (PiccService binder
     * "never registered"). Foreground dispatch + ACTION_TAG_DISCOVERED через onNewIntent
     * — fallback. Drop Tag прямо в Bus, оттуда — в SessionFlowViewModel.onTagDiscovered.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            Log.i("MainActivity", "onNewIntent TAG: ${tag.id.joinToString("") { "%02X".format(it) }}")
            NfcTagBus.publish(tag)
        }
    }

    override fun onResume() {
        super.onResume()
        val tag: Tag? = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            Log.i("MainActivity", "onResume picking up TAG from launch intent")
            NfcTagBus.publish(tag)
        }
    }
}

/**
 * Thread-safe Singleton для передачи Tag из MainActivity.onNewIntent (foreground-dispatch
 * path) в SessionFlowViewModel (там работает только ReaderMode-listener). Каждый
 * подписчик из коллекции Flow может получить последний Tag.
 */
object NfcTagBus {
    private val mutex = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var pendingTag: Tag? = null

    // Ревью-фикс: single-owner — экран-владелец (TopUp) заявляет права на таги,
    // долгоживущий SessionFlowViewModel не съедает их из-под него.
    @Volatile private var claimedBy: String? = null

    fun claim(owner: String): Boolean {
        mutex.lock()
        return try {
            if (claimedBy == null) { claimedBy = owner; true } else false
        } finally { mutex.unlock() }
    }

    fun release(owner: String) {
        mutex.lock()
        try { if (claimedBy == owner) claimedBy = null } finally { mutex.unlock() }
    }

    fun isClaimed(): Boolean = claimedBy != null

    fun publish(tag: Tag) {
        mutex.lock()
        try {
            pendingTag = tag
        } finally {
            mutex.unlock()
        }
        // Очистим через небольшой промежуток, чтобы VM успел прочитать
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            mutex.lock()
            try { pendingTag = null } finally { mutex.unlock() }
        }, 500L)
    }

    fun consume(): Tag? {
        mutex.lock()
        return try {
            val t = pendingTag
            pendingTag = null
            t
        } finally {
            mutex.unlock()
        }
    }
}

