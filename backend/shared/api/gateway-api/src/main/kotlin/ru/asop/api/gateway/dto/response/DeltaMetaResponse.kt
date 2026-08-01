package ru.asop.api.gateway.dto.response

/**
 * Метаданные дельта/полной выгрузки. Gateway читает Redis
 * `asop:event:{eventId}:meta`, заполненный оркестратором.
 *
 * @param totalChunks количество Protobuf-чанков в Redis (delta)
 * @param totalBytes суммарный размер чанков (delta)
 * @param createdAt когда выгрузка сгенерирована
 * @param s3Url URL ZIP-файла полной выгрузки в MinIO (full), null для delta
 */
data class DeltaMetaResponse(
    val totalChunks: Int? = null,
    val totalBytes: Long? = null,
    val createdAt: String? = null,
    val s3Url: String? = null
)
