package ru.asop.fiscal.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import ru.asop.fiscal.adapter.FiscalContext
import ru.asop.fiscal.adapter.FiscalizerAdapter
import ru.asop.fiscal.adapter.FiscalizerAdapterRegistry
import ru.asop.fiscal.model.CarrierFiscalizer
import ru.asop.fiscal.model.DistributorFiscalizer
import ru.asop.fiscal.model.FiscalReceiptEntity
import ru.asop.fiscal.model.FiscalReceiptAttempt
import ru.asop.fiscal.repository.CarrierFiscalizerRepository
import ru.asop.fiscal.repository.DistributorFiscalizerRepository
import ru.asop.fiscal.repository.FiscalizerRepository
import ru.asop.fiscal.repository.FiscalReceiptRepository
import ru.asop.fiscal.repository.FiscalizerTokenRepository
import ru.asop.fiscal.util.TokenCipher
import ru.asop.kafka.events.fiscal.FiscalReceiptRequestedEvent
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.UUID

@Service
class FiscalReceiptService(
    private val receiptRepository: FiscalReceiptRepository,
    private val carrierFiscalizerRepository: CarrierFiscalizerRepository,
    private val distributorFiscalizerRepository: DistributorFiscalizerRepository,
    private val fiscalizerRepository: FiscalizerRepository,
    private val tokenRepository: FiscalizerTokenRepository,
    private val adapterRegistry: FiscalizerAdapterRegistry,
    private val contextResolver: FiscalContextResolver,
    private val tokenCipher: TokenCipher,
    private val objectMapper: ObjectMapper,
    private val webClient: WebClient,
    private val template: R2dbcEntityTemplate,
    @Value("\${fiscal.max-attempts:10}") private val maxAttempts: Int,
    @Value("\${fiscal.retry-interval-ms:30000}") private val retryIntervalMs: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Идемпотентная обработка запроса фискализации (промпт 017 §5/§7).
     *
     * Дедуп по трём ключам в порядке приоритета: receiptId (клиентский UUIDv7, для офлайн-QR)
     * → transactionId → acqReference. acqReference — общий ключ с авто-контуром
     * [ru.asop.fiscal.kafka.PaymentReceiptConsumer] (payment-событие), поэтому банковская
     * оплата НЕ фискализируется дважды, кто бы ни пришёл первым.
     */
    fun handleRequested(event: FiscalReceiptRequestedEvent): Mono<FiscalReceiptEntity> {
        return receiptRepository.findById(event.receiptId)
            .switchIfEmpty(event.transactionId?.let { receiptRepository.findByTransactionId(it) } ?: Mono.empty())
            .switchIfEmpty(event.acqReference?.let { receiptRepository.findByAcqReference(it) } ?: Mono.empty())
            .switchIfEmpty(
                resolveContext(event)
                    .flatMap { ctx -> insertReceipt(event, ctx) }
                    .flatMap { send(it) }
            )
    }

    private fun resolveContext(event: FiscalReceiptRequestedEvent): Mono<Pair<UUID?, UUID?>> {
        if (event.carrierId != null || event.cardsDistributorId != null) {
            return Mono.just(Pair(event.carrierId, event.carrierFiscalizerId))
        }
        val sessionId = event.sessionId
        if (sessionId == null || event.source == "DISTRIBUTOR") {
            return Mono.just(Pair<UUID?, UUID?>(null, null))
        }
        return contextResolver.resolve(sessionId)
            .map { ctx -> Pair<UUID?, UUID?>(ctx.carrierId, ctx.carrierFiscalizerId) }
            .defaultIfEmpty(Pair<UUID?, UUID?>(null, null))
    }

    private fun insertReceipt(
        event: FiscalReceiptRequestedEvent,
        ctx: Pair<UUID?, UUID?>
    ): Mono<FiscalReceiptEntity> {
        val entity = FiscalReceiptEntity(
            receiptId = event.receiptId,
            transactionId = event.transactionId,
            acqReference = event.acqReference,
            amount = event.amount,
            carrierId = ctx.first,
            cardsDistributorId = event.cardsDistributorId,
            carrierFiscalizerId = ctx.second,
            source = event.source,
            // RECEIPT_DATA — JSON-объект (description + amount), а не строка description:
            // иначе сумма терялась и уходила в ОФД пустым payload'ом.
            receiptData = Json.of(objectMapper.writeValueAsString(
                mapOf(
                    "description" to (event.description ?: ""),
                    "amount" to event.amount
                )
            )),
            status = "PENDING",
            maxAttempts = maxAttempts
        )
        return template.insert(entity)
    }

    fun send(receipt: FiscalReceiptEntity): Mono<FiscalReceiptEntity> {
        return resolveAdapter(receipt).flatMap { resolved ->
            val ctx = FiscalContext(
                receiptId = receipt.receiptId,
                transactionId = receipt.transactionId,
                total = receipt.amount,
                fiscalContext = parseReceiptData(receipt.receiptData?.asString())
            )
            val payload = resolved.adapter.buildRequest(ctx)
            val payloadJson = objectMapper.writeValueAsString(payload)
            resolved.tokenMono.flatMap { (tokenId, token) ->
                post(resolved.adapter, resolved.baseUrl, payloadJson, token, tokenId, receipt.receiptId)
            }
        }.onErrorResume { e ->
            fail(receipt, e.message ?: "error")
        }
    }

    private data class Resolved(
        val adapter: FiscalizerAdapter,
        val linkId: UUID,
        val baseUrl: String?,
        // (TOKEN_ID, расшифрованные байты токена). TOKEN_ID нужен для FK attempts.TOKEN_ID.
        val tokenMono: Mono<Pair<UUID?, ByteArray>>
    )

    private fun resolveAdapter(receipt: FiscalReceiptEntity): Mono<Resolved> {
        return carrierMono(receipt).switchIfEmpty(distributorMono(receipt))
            .switchIfEmpty(Mono.error(IllegalStateException("No active fiscalizer for receipt")))
    }

    private fun carrierMono(receipt: FiscalReceiptEntity): Mono<Resolved> {
        val carrierId = receipt.carrierId ?: return Mono.empty()
        return carrierFiscalizerRepository.findActive(carrierId)
            .flatMap { cf -> buildResolved(cf.fiscalizerId, cf.carrierFiscalizerId, cf.customApiUrl) }
    }

    private fun distributorMono(receipt: FiscalReceiptEntity): Mono<Resolved> {
        val distributorId = receipt.cardsDistributorId ?: return Mono.empty()
        return distributorFiscalizerRepository.findActive(distributorId)
            .flatMap { df -> buildResolved(df.fiscalizerId, df.distributorFiscalizerId, df.customApiUrl) }
    }

    private fun buildResolved(fiscalizerId: UUID, linkId: UUID, customUrl: String?): Mono<Resolved> {
        return fiscalizerRepository.findById(fiscalizerId).flatMap { fiscalizer ->
            val adapter = adapterRegistry.get(fiscalizer.fiscalizerCode)
                ?: return@flatMap Mono.error<Resolved>(IllegalStateException("No adapter for ${fiscalizer.fiscalizerCode}"))
            val baseUrl = customUrl ?: fiscalizer.baseApiUrl
            val tokenMono = tokenRepository.findActiveForLink(linkId)
                .map { it.tokenId as UUID? to tokenCipher.decrypt(it.tokenEncrypted) }
                .defaultIfEmpty(Pair<UUID?, ByteArray>(null, ByteArray(0)))
            Mono.just(Resolved(adapter, linkId, baseUrl, tokenMono))
        }
    }

    private fun post(
        adapter: FiscalizerAdapter,
        baseUrl: String?,
        payloadJson: String,
        token: ByteArray,
        tokenId: UUID?,
        receiptId: UUID
    ): Mono<FiscalReceiptEntity> {
        val base = baseUrl?.trimEnd('/') ?: "https://fp.bifit.com"
        val uri = base + adapter.registrationPath()
        val startedAt = System.currentTimeMillis()
        return webClient.post()
            .uri(uri)
            .header("Connector-Token", String(token, Charsets.UTF_8))
            .header("Idempotency-Key", receiptId.toString())
            .header("Content-Type", "application/json")
            .bodyValue(payloadJson)
            .retrieve()
            .toEntity(String::class.java)
            .flatMap { resp ->
                val result = adapter.parseResponse(resp.statusCode.value(), resp.body)
                finishAttempt(receiptId, tokenId, payloadJson, result.success, resp.statusCode.value(),
                    result.responseData, result.errorMessage, result.receiptNumber, result.fiscalSign,
                    adapter.receiptUrl(receiptId, baseUrl, null), startedAt)
            }
            .onErrorResume { e ->
                finishAttempt(receiptId, tokenId, payloadJson, false, null, null,
                    e.message ?: "error", null, null, null, startedAt)
            }
    }

    @Suppress("LongParameterList")
    private fun finishAttempt(
        receiptId: UUID,
        tokenId: UUID?,
        payloadJson: String,
        success: Boolean,
        httpStatus: Int?,
        responseData: String?,
        errorMessage: String?,
        receiptNumber: String?,
        fiscalSign: String?,
        fiscalUrl: String?,
        startedAt: Long
    ): Mono<FiscalReceiptEntity> {
        return receiptRepository.findById(receiptId).flatMap { r ->
            val attemptNumber = r.attemptCount + 1
            val exhausted = attemptNumber >= r.maxAttempts
            val now = OffsetDateTime.now()
            val updated = when {
                success -> r.copy(
                    status = "CONFIRMED",
                    attemptCount = attemptNumber,
                    receiptNumber = receiptNumber ?: r.receiptNumber,
                    fiscalSign = fiscalSign ?: r.fiscalSign,
                    responseData = responseData?.let { Json.of(it) },
                    fiscalUrl = fiscalUrl,
                    confirmedAt = now,
                    lastAttemptAt = now,
                    nextRetryAt = null,
                    lastErrorMessage = null,
                    updatedAt = now
                )
                exhausted -> r.copy(
                    status = "CANCELLED",
                    attemptCount = attemptNumber,
                    lastAttemptAt = now,
                    nextRetryAt = null,
                    lastErrorMessage = (errorMessage ?: "error").take(1000),
                    updatedAt = now
                )
                else -> r.copy(
                    status = "RETRY",
                    attemptCount = attemptNumber,
                    lastAttemptAt = now,
                    nextRetryAt = now.plusSeconds(backoffSeconds(attemptNumber)),
                    lastErrorMessage = (errorMessage ?: "error").take(1000),
                    updatedAt = now
                )
            }
            val attempt = FiscalReceiptAttempt(
                attemptId = UUID.randomUUID(),
                receiptId = receiptId,
                attemptNumber = attemptNumber,
                tokenId = tokenId,
                requestPayload = Json.of(payloadJson),
                responsePayload = responseData?.let { Json.of(it) },
                httpStatus = httpStatus,
                success = success,
                errorMessage = errorMessage?.take(1000),
                durationMs = (System.currentTimeMillis() - startedAt).toInt()
            )
            template.insert(attempt).then(receiptRepository.save(updated))
        }
    }

    private fun fail(receipt: FiscalReceiptEntity, message: String): Mono<FiscalReceiptEntity> {
        val now = OffsetDateTime.now()
        val attemptNumber = receipt.attemptCount + 1
        val updated = if (attemptNumber >= receipt.maxAttempts) {
            receipt.copy(status = "CANCELLED", attemptCount = attemptNumber, lastAttemptAt = now,
                nextRetryAt = null, lastErrorMessage = message.take(1000), updatedAt = now)
        } else {
            receipt.copy(status = "RETRY", attemptCount = attemptNumber, lastAttemptAt = now,
                nextRetryAt = now.plusSeconds(backoffSeconds(attemptNumber)),
                lastErrorMessage = message.take(1000), updatedAt = now)
        }
        val attempt = FiscalReceiptAttempt(
            attemptId = UUID.randomUUID(),
            receiptId = receipt.receiptId,
            attemptNumber = attemptNumber,
            success = false,
            errorMessage = message.take(1000)
        )
        return template.insert(attempt).then(receiptRepository.save(updated))
    }

    private fun backoffSeconds(attemptNumber: Int): Long {
        val base = retryIntervalMs / 1000
        val shift = (attemptNumber - 1).coerceIn(0, 6)
        return (base * (1L shl shift)).coerceAtMost(3600L)
    }

    private fun parseReceiptData(data: String?): Map<String, Any?> {
        if (data.isNullOrBlank()) return emptyMap()
        return try {
            objectMapper.readValue(data, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun retryDue(): Mono<Int> {
        val now = OffsetDateTime.now()
        return receiptRepository.findDueForRetry(now, now.minusMinutes(1))
            .flatMap { r -> send(r).onErrorResume { Mono.just(r.copy(status = "RETRY")) } }
            .doOnNext { log.info("Retry receipt {} -> {}", it.receiptId, it.status) }
            .count()
            .map { it.toInt() }
            .defaultIfEmpty(0)
    }

    fun getStatus(receiptId: UUID): Mono<FiscalReceiptEntity> = receiptRepository.findById(receiptId)
        .switchIfEmpty(Mono.error(NoSuchElementException("Receipt not found")))
}
