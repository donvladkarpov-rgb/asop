package ru.asop.terminal.network

import okhttp3.Interceptor
import okhttp3.Response

// Промпт 012: OkHttp interceptor что пробрасывает per-event X-Event-Seq HTTP header
// к /api/v1/sync/ requests.
//
// Использование: SyncWorker устанавливает SeqHeaderHolder.set перед каждым
// вызовом syncApi.X; interceptor читает значение из ThreadLocal и добавляет
// header. Gateway пробрасывает дальше в Kafka X-Terminal-Seq.
//
// После запроса caller должен сбросить значение в 0L (SeqHeaderHolder.clear).

object SeqHeaderHolder : ThreadLocal<Long>() {
    fun setSeq(seq: Long): Unit = set(seq)
    fun clear(): Unit = remove()
}

class SeqHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val seq = SeqHeaderHolder.get() ?: 0L
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
