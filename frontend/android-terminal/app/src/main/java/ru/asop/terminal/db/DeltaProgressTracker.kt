package ru.asop.terminal.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeltaProgressTracker @Inject constructor() {

    private val _progress = MutableStateFlow<DeltaProgress?>(null)
    val progress: StateFlow<DeltaProgress?> = _progress.asStateFlow()

    fun startDelta(eventId: String, totalChunks: Int) {
        _progress.value = DeltaProgress(eventId, totalChunks, 0, System.currentTimeMillis())
    }

    fun reportChunk(eventId: String, currentChunk: Int) {
        val current = _progress.value
        if (current != null && current.eventId == eventId) {
            _progress.value = current.copy(currentChunk = currentChunk)
        }
    }

    fun finish(eventId: String) {
        val current = _progress.value
        if (current != null && current.eventId == eventId) {
            _progress.value = null
        }
    }

    fun clear() {
        _progress.value = null
    }

    data class DeltaProgress(
        val eventId: String,
        val totalChunks: Int,
        val currentChunk: Int,
        val startedAt: Long
    ) {
        val fraction: Float
            get() = if (totalChunks > 0) currentChunk.toFloat() / totalChunks.toFloat() else 0f
    }
}
