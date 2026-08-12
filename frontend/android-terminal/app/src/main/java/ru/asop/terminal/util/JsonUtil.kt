package ru.asop.terminal.util

import com.squareup.moshi.Moshi

/**
 * Промпт 011: утилитный JSON-сериализатор для [Object] → String через singleton Moshi.
 * Используется SessionFlowViewModel и SyncWorker.
 */
object JsonUtil {
    private val moshi: Moshi = Moshi.Builder().build()

    fun encode(value: Any?): String {
        if (value == null) return ""
        val adapter = moshi.adapter(value.javaClass)
        return adapter.toJson(value) ?: ""
    }
}
