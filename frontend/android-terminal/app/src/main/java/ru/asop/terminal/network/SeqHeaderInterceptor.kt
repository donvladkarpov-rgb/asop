package ru.asop.terminal.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Промпт 014: per-event seq для X-Event-Seq HTTP header.
 *
 * Раньше был ThreadLocal<Long>, но OkHttp Interceptor бежит на другом потоке,
 * чем SyncWorker.setSeq() — ThreadLocal возвращал 0L, все seq уходили как legacy.
 * При том что SyncWorker обрабатывает события последовательно, используем обычную
 * переменную (setBeforeRequest / clearAfterRequest).
 */
object SeqHeaderHolder {
    @Volatile var currentSeq: Long = 0L
    fun setSeq(seq: Long) { currentSeq = seq }
    fun clear() { currentSeq = 0L }
}

class SeqHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val seq = SeqHeaderHolder.currentSeq
        val request = chain.request()
        if (seq > 0L && request.url.encodedPath.startsWith("/api/v1/sync/")) {
            val enriched = request.newBuilder()
                .addHeader("X-Event-Seq", seq.toString())
                .build()
            return chain.proceed(enriched)
        }
        return chain.proceed(request)
    }
}