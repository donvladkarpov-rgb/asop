package ru.asop.fiscal.adapter

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class FiscalizerAdapterRegistry(adapters: List<FiscalizerAdapter>) {
    private val map = ConcurrentHashMap<String, FiscalizerAdapter>()
    init {
        adapters.forEach { map[it.providerCode().uppercase()] = it }
    }
    fun get(code: String?): FiscalizerAdapter? = code?.uppercase()?.let { map[it] }
}
