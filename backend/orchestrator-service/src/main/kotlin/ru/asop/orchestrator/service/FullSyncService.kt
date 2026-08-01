package ru.asop.orchestrator.service

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.Message
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import ru.asop.common.event.EventService
import ru.asop.kafka.events.delta.FullSyncCommand
import ru.asop.orchestrator.config.OrchestratorProperties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Полная выгрузка: все записи по таблицам → .pb файлы → ZIP → MinIO,
 * pre-signed URL → eventService.complete(resultData={"s3Url":...}).
 */
@Service
class FullSyncService(
    private val protoRowMapper: ProtoRowMapper,
    private val eventService: EventService,
    private val props: OrchestratorProperties,
    private val s3Client: S3Client,
    private val masterWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(command: FullSyncCommand): Mono<Void> {
        val eventId = command.eventId
        return Mono.defer {
            fetchAll(command).flatMap { files ->
                val zipBytes = buildZip(files)
                uploadAndPresign(eventId, zipBytes)
            }.flatMap { s3Url ->
                eventService.complete(eventId, "{\"s3Url\":\"$s3Url\"}")
            }
        }.onErrorResume { err ->
            log.error("Full sync failed for event {}", eventId, err)
            eventService.fail(eventId, err.message ?: "full sync failed").then(Mono.error(err))
        }
    }

    private fun fetchAll(command: FullSyncCommand): Mono<Map<String, List<Message>>> {
        return Mono.defer {
            // userIds каскад
            val usersEp = MasterRegistry.USER_TABLES["asop_users"]!!
            masterWebClient.get().uri { u ->
                val b = u.path("/api/v1/admin-users/delta")
                    .queryParam("includeDeleted", true)
                    .queryParam("limit", 10_000)
                command.carrierId?.let { b.queryParam("carrierId", it.toString()) }
                command.regionId?.let { b.queryParam("regionId", it.toString()) }
                b.build()
            }.retrieve().bodyToFlux(JsonNode::class.java)
                .map { it.get("userId")?.asText() ?: it.get("user_id")?.asText() }
                .filter { it != null }.map { it!! }
                .collectList()
                .flatMap { userIdList ->
                    val userIds = userIdList.toSet()
                    // все таблицы
                    val tableRows = MasterRegistry.ALL.entries.map { (table, ep) ->
                        val isUserCard = MasterRegistry.USER_TABLES.containsKey(table) || MasterRegistry.CARD_TABLES.containsKey(table)
                        val spec = if (table == "asop_users" || !isUserCard) {
                            masterWebClient.get().uri { u ->
                                val b = u.path("/api/v1/{resource}/delta")
                                    .queryParam("includeDeleted", true)
                                    .queryParam("limit", 10_000)
                                command.carrierId?.let { b.queryParam("carrierId", it.toString()) }
                                command.regionId?.let { b.queryParam("regionId", it.toString()) }
                                b.build(ep.resource)
                            }
                        } else {
                            masterWebClient.get().uri { u ->
                                val b = u.path("/api/v1/{resource}/delta")
                                    .queryParam("includeDeleted", true)
                                    .queryParam("limit", 10_000)
                                if (userIds.isNotEmpty()) b.queryParam("userIdsIn", userIds.joinToString(","))
                                b.build(ep.resource)
                            }
                        }
                        table to spec.retrieve().bodyToFlux(JsonNode::class.java)
                            .map { protoRowMapper.buildRowMessage(table, it) }
                            .onErrorResume { err ->
                                log.warn("Full fetch failed for {}: {}", table, err.message)
                                reactor.core.publisher.Flux.empty<Message>()
                            }
                    }
                    val tables = tableRows.map { it.first }
                    val fluxes = tableRows.map { it.second }
                    reactor.core.publisher.Flux.combineLatest(fluxes) { arrays ->
                        val map = LinkedHashMap<String, List<Message>>()
                        for (i in tables.indices) {
                            @Suppress("UNCHECKED_CAST")
                            map[tables[i]] = (arrays[i] as? List<*>)?.mapNotNull { it as? Message } ?: emptyList()
                        }
                        map
                    }.next()
                }
        }
    }

    private fun buildZip(files: Map<String, List<Message>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((table, rows) in files) {
                val clsName = "ru.asop.proto.v1.${camel(table)}File"
                val builderClass = Class.forName(clsName)
                val builder = builderClass.getMethod("newBuilder").invoke(null) as Message.Builder
                val fileDescriptor = builder.descriptorForType
                val rowsField = fileDescriptor.findFieldByName("rows")
                for (row in rows) {
                    builder.addRepeatedField(rowsField, row)
                }
                val entryName = "${table.removePrefix("asop_")}.pb"
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(builder.build().toByteArray())
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun uploadAndPresign(eventId: UUID, zipBytes: ByteArray): Mono<String> {
        val key = "full_$eventId.zip"
        val put = PutObjectRequest.builder()
            .bucket(props.s3.bucket)
            .key(key)
            .contentType("application/zip")
            .build()
        s3Client.putObject(put, RequestBody.fromBytes(zipBytes))

        // Bucket настроен на anonymous download (minio-init: mc anonymous set download).
        // Внутренний HTTP URL; наружу отдаёт gateway-прокси (HTTPS).
        val url = "${props.s3.endpoint}/${props.s3.bucket}/$key"
        log.info("Full dump uploaded, download URL: {}", url)
        return Mono.just(url)
    }

    private fun camel(table: String): String {
        return table.split("_").drop(1).joinToString("") { it.capitalize() }
    }
}
