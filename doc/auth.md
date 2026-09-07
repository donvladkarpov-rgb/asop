# Архитектура аутентификации и авторизации ASOP

**Версия:** 1.1
**Дата:** 2026-07-09
**Статус:** Утверждено как ЦЕЛЕВОЙ дизайн. Раздел 2.4 (водитель: DESFire EV3 ECC + PIN + CRL) — цель развития; фактическая реализация MVP описана в разделе 2.5
**Область:** Backend, Gateway, Terminal Android, NFC-персонализация, Keycloak, PKI

---

## 1. Обзор и архитектурные принципы

Система АСОП строится на **трёх независимых контурах аутентификации**, каждый из которых оптимизирован под свой тип клиента и условия работы:

| Клиент | Метод | Сетевое условие | Где применяется |
|--------|-------|-----------------|-----------------|
| **Веб-админка / Мобильное приложение пассажира** | JWT (OIDC через Keycloak) | Всегда онлайн | Управление системой, личные кабинеты, аналитика |
| **Терминал (Android/Linux)** | mTLS + сертификат устройства | Online ↔ Offline (до 12 ч) | Приём оплаты, валидация, открытие смен, GPS-трекинг |
| **Водитель** | MIFARE DESFire EV3 + PKI (ECDSA P-256) + PIN | 100% Offline | Аутентификация в терминале, открытие смены/рейса |

### Ключевые принципы
1. **Offline-first для терминалов**: терминал способен работать без связи часами, накапливая сессии и транзакции локально.
2. **Аппаратная защита ключей**: приватные ключи никогда не покидают защищённую среду (HSM для Root CA, Android Keystore/StrongBox для терминалов, чип DESFire для водителей).
3. **Zero-Trust на уровне устройств**: каждый терминал и каждая карта водителя имеют криптографический паспорт, проверяемый через Root CA.
4. **Gateway не пишет в БД**: принимает запросы, валидирует аутентификацию/авторизацию, пушит команды в Kafka, возвращает `202 Accepted`.
5. **Pass-through identity**: gateway проверяет JWT, извлекает `sub` (keycloakId), передаёт внутренним сервисам через заголовок `X-Keycloak-Id` без оригинального JWT.

---

## 2. Компоненты системы

### 2.1. Root CA (Корневой Центр Сертификации)
- **Назначение**: единая точка доверия. Подписывает сертификаты терминалов и сертификаты водителей (на MIFARE-картах).
- **Хранение приватного ключа**:
    - MVP: PKCS#12 файл + пароль
    - Production: HSM (Thales Luna, YubiHSM 2, AWS CloudHSM) с защитой от физического вскрытия
- **Управление доступом**: M-of-N (минимум 3 из 5 администраторов для подписи сертификата или отзыва)
- **Онлайн-операции**: выполняются через Intermediate CA, который подписывается Root CA. Root CA физически отключён от сети.
- **Выходные артефакты**: `asop-root-ca.pem` (публичный ключ), CRL (список отозванных сертификатов), Intermediate CA сертификаты.

### 2.2. Веб-клиенты и мобильные приложения (JWT + Keycloak)
- **Flow**: Authorization Code Flow (веб), Authorization Code + PKCE (мобайл)
- **Токены**:
    - `access_token`: JWT, TTL 5 мин, содержит `sub`, `realm_access.roles`, `resource_access.asop-backend.roles`
    - `refresh_token`: opaque, TTL 8 ч (веб) / 30 дн (мобайл), хранится в `httpOnly` cookie или Secure Storage
- **Валидация в Gateway**:
    - JWT декодируется и проверяется локально через JWKS (`/realms/asop/protocol/openid-connect/certs`)
    - JWKS кэшируется в Gateway, обновляется каждые 60 сек
    - Никаких сетевых вызовов к Keycloak на каждый запрос
- **Авторизация**: ролевая модель через Spring Security Reactive (`hasRole`, `hasAuthority`). Примеры ролей: `SUPER_ADMIN`, `CARRIER_ADMIN`, `DISPATCHER`, `PASSENGER`.

### 2.3. Терминалы (mTLS + сертификаты устройств)
- **Аутентификация устройства**: Mutual TLS при каждом соединении с Gateway
- **Хранение приватного ключа терминала**:
    - Android: `AndroidKeyStore` с флагом `setIsStrongBoxBacked(true)` (аппаратный SE, если доступен)
    - Linux/Embedded: TPM 2.0 или внешний YubiKey
- **Процесс регистрации терминала** (choreographed saga через Kafka — см. раздел 5.5):
    1. При первом включении терминал генерирует пару ECC P-256 в Keystore
    2. POST `POST /api/v1/terminals/cert-sign` (open HTTPS, без JWT/mTLS) на Gateway
    3. Gateway отправляет команду в `asop.terminal.cert.commands`
    4. crypto-service подписывает CSR через Intermediate CA, публикует `CertIssued` в `asop.terminal.cert.issued`
    5. terminal-service сохраняет сертификат в `ASOP_TERMINAL_CERTS` (`IS_CURRENT=true`), регистрирует терминал в `ASOP_TERMINALS` если нужно
    6. terminal-service публикует `CertStored` в `asop.terminal.cert.events`
    7. Gateway-consumer обновляет EventService (PENDING → COMPLETED + resultData)
    8. Android получает PEM-цепочку через polling `GET /api/v1/events/{eventId}`, сохраняет в Keystore
    9. Серийный номер сертификата фиксируется в `ASOP_TERMINALS.TERMINAL_SERIAL` (UNIQUE)
- **Синхронизация (online)**: терминал запрашивает у Gateway:
    - Обновлённый CRL
    - Изменения в льготах (`ASOP_USER_BENEFITS`)
    - Изменения в тарифах (`ASOP_TARIFF_RATES`)
    - Чёрный список карт (`ASOP_BLACKLISTS`)
- **Offline-работа**: терминал хранит локальные копии справочников. Сессии подписываются приватным ключом терминала и отправляются батчем при появлении связи.
- **Ключи ASOP_KEYS**: доставляются на терминал через дельта-синхронизацию (зашифрованные RSA-ключом сервера), **перешифровываются** локальным AES-256/GCM-ключом в Android Keystore (`TerminalKeyCryptor`, алиас `asop_terminal_keys_aes`, PURPOSE_ENCRYPT|DECRYPT). Этот AES-ключ **не зависит от mTLS-сертификата** — перевыпуск сертификата не затрагивает материал ключей.
- **Верификация подписей карт**: при регистрации терминал загружает публичный RSA-PSS ключ сервера (`GET /api/v1/keys/public`). При чтении зарегистрированной карты (CardReadScreen) терминал верифицирует RSA-PSS-SHA256 подпись identity из File 1 против canonical JSON, восстановленного из proto File 0. Результат отображается в UI. Не выполняется при активации новых карт.

### 2.3.1. Целевое устройство — Feitian F20

**Feitian F20** (FTSafe) — Android-POS терминал, целевое железо для `android-terminal`. 
NFC-чип поддерживает ISO/IEC 14443-4 (Type A), что полностью совместимо с DESFire EV1/EV2/EV3 на уровне радио/транспортного протокола — код `DesfireCardWriter` (IsoDep + transceive) будет работать без модификаций. 

Ограничение: PCI PTS 5.1 — NFC может быть зарезервирован за платёжным ядром (доступ через Feitian SDK). Требуется проверка на реальном устройстве.

### 2.4. Водители (MIFARE DESFire EV3 + PKI + Challenge-Response)
- **Криптография**: ECDSA на кривой NIST P-256. Приватный ключ генерируется и хранится **внутри чипа карты**, физически не извлекаем.
- **Структура приложения на карте (AID: `0xD2760000850101`)**:
    - `File 0x01`: X.509 сертификат водителя (подписан Root CA)
    - `File 0x02`: ECC Private Key (права: `Sign Only`, чтение запрещено)
    - `File 0x03`: Профиль водителя (`driver_id`, `carrier_id`, `valid_from`, `valid_until`)
- **Протокол аутентификации (NFC)**:
    1. Терминал генерирует случайный `challenge` (32 байта)
    2. Отправляет карте команду `SignData(challenge)`
    3. Карта подписывает challenge своим приватным ключом, возвращает подпись
    4. Терминал извлекает публичный ключ из сертификата (File 0x01)
    5. Терминал проверяет подпись локально
    6. Терминал проверяет срок действия сертификата и отсутствие UID в локальном CRL
    7. Водитель вводит PIN (второй фактор) → терминал сверяет хэш PIN с локальной базой
    8. При успехе → смена открыта
- **Персонализация карты (в депо)**: выполняется через NFC-ридер + ПК с ПО персонализации. Приватный ключ генерируется на карте, публичный отправляется на бэкенд для выпуска сертификата, сертификат записывается обратно на карту.

### 2.5. Реализация сейчас (MVP — фактическое состояние)

Чем MVP отличается от целевой схемы §2.4:

| Аспект | Целевой дизайн (§2.4) | Реализация сейчас |
|--------|----------------------|-------------------|
| Криптография карты | ECDSA P-256 внутри чипа DESFire EV3 | RSA-PSS-SHA256 подпись сервера (crypto-service); терминал верифицирует публичным ключом сервера (`GET /api/v1/keys/public`). ECC-on-card недоступна: клоны отвечают `0x1C` на GetCardCertificate (см. `doc/specifications/DESFireEV3.md`) |
| AID / файлы | `0xD2760000850101`, X.509 в File 01 | AID `0xA05A01`: File 0 = protobuf CardIdentity, File 1 = base64 RSA-PSS подпись canonical JSON |
| MIFARE Classic | не рассматривался | Форматы VCM1 (sector 1, bitmask ролей) и SAC1 (подписанный proto, sectors 1–15); без PKI — защита через server-side whitelist `(uid, cardId)` |
| PIN | второй фактор | не реализован |
| CRL | проверка отзыва на терминале | не реализована; схема хранит `REVOKED_AT` в `ASOP_TERMINAL_CERTS`. Для карт используется UID-whitelist |

Матрица авторизации закрытия смены (`SessionService.canClose`, промпт 011 §4) и аутентификация водителя картой-ключом (bitmask → роль DRIVER/CARRIER_DISPATCHER) реализованы.

---

## 3. Pass-Through Identity

Gateway проверяет JWT, извлекает `sub` (keycloakId), передаёт внутренним сервисам **без** оригинального JWT.

### Схема работы
```
Client                     Gateway                         Service
  │                         │                                │
  │ POST /password/change   │                                │
  │ Authorization: JWT      │                                │
  │────────────────────────>│                                │
  │                         │ JWT validation (JWKS, exp/nbf) │
  │                         │ extract sub (keycloakId)       │
  │                         │                                │
  │                         │ POST /password/change          │
  │                         │ X-Keycloak-Id: <sub>          │
  │                         │ (без Authorization)            │
  │                         │──────────────────────────────>│
  │                         │                                │
  │                         │ resolve keycloakId → userId   │
  │                         │ process request                │
  │                         │<──────────────────────────────│
  │<────────────────────────│                                │
  │ 204 / 202 / 4xx / 5xx   │                                │
```

### Мотивация
- **Безопасность**: backend сервисы не имеют доступа к JWT, не могут его украсть или переиспользовать
- **Упрощение**: сервисы не валидируют JWT, не нуждаются в JWKS, Keycloak connectivity
- **Аудит**: единая точка проверки identity — gateway

### Детали реализации

#### ProxyController.extractIdentity()
```kotlin
private fun extractIdentity(exchange: ServerWebExchange): Mono<String> =
    ReactiveSecurityContextHolder.getContext()
        .map { it.authentication }
        .filter { it.isAuthenticated }
        .map { it.name }  // sub из JWT
        .switchIfEmpty(Mono.just("anonymous"))
```

#### Kafka async writes
- `X-Keycloak-Id` передаётся в Kafka headers
- Пример: `CarrierCommandService.sendCreateCommand()`

#### Backend сервисы
- User-service: `SecurityConfig` с `permitAll`, без JWT
- terminal-service: `permitAll` для `/api/v1/terminals/**`, БЕЗ `.oauth2ResourceServer` (ранее leftover — удалён). Сервис внутри Docker доверяет gateway.
- Другие сервисы: либо `permitAll`, либо кастомный `JwtDecoderConfig` (без проверки issuer)

### JWT issuer workaround (Docker)

Keycloak в Docker (`KC_HOSTNAME=localhost`) выдаёт `iss: http://localhost:8180/realms/asop`, но сервисы обращаются к Keycloak по `http://keycloak:8080`.

**Решение:** Кастомный `ReactiveJwtDecoder` (`JwtDecoderConfig.kt`):
```kotlin
@Bean
fun reactiveJwtDecoder(): ReactiveJwtDecoder {
    val decoder = NimbusReactiveJwtDecoder(jwkSetUri)
    decoder.setJwtValidator { jwt ->
        val errors = mutableListOf<OAuth2Error>()
        // Проверка exp
        if (jwt.expiresAt?.isBefore(Instant.now()) == true)
            errors.add(OAuth2Error("token_expired"))
        // Проверка nbf
        if (jwt.notBefore?.isAfter(Instant.now()) == true)
            errors.add(OAuth2Error("token_not_before"))
        // НЕ проверяем iss
        Mono.just(OAuth2TokenValidatorResult.success())
    }
    return decoder
}
```

Применён в: gateway-service, user-service.

---

## 4. Gateway dual auth

### SecurityConfig
```kotlin
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean @Order(1)
    fun terminalSecurityFilterChain(http: ServerHttpSecurity) = http
        .securityMatcher(ServerWebExchangeMatchers.pathMatchers(
            "/api/v1/terminals/**", "/api/v1/sync/**"))
        .x509 { it.principalExtractor(TerminalPrincipalExtractor()) }
        .authorizeExchange { it.anyExchange().authenticated() }
        .build()

    @Bean @Order(2)
    fun webSecurityFilterChain(http: ServerHttpSecurity) = http
        .oauth2ResourceServer { it.jwt { } }
        .authorizeExchange { it.anyExchange().authenticated() }
        .build()
}
```

### TerminalPrincipalExtractor
```kotlin
class TerminalPrincipalExtractor : X509PrincipalExtractor {
    override fun extractPrincipal(x509Certificate: X509Certificate): Any {
        val cn = x509Certificate.subjectX500Principal.name
            .split(",").map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }
            ?.substringAfter("CN=")
        return cn ?: throw UsernameNotFoundException("CN not found")
    }
}
```

**⚠️ Важно:** `X509PrincipalExtractor` находится в пакете `org.springframework.security.web.authentication.preauth.x509`, а не `web.server.authentication`. Интерфейс синхронный — возвращает `Any`, не `Mono<Any>`.

---

## 5. Детальные сценарии (Flows)

### 5.1. Аутентификация vs Авторизация в Gateway
| Этап | Вопрос | Механизм | Результат |
|------|--------|----------|-----------|
| Аутентификация | Кто ты? | Проверка подписи JWT (JWKS) или валидация Client Cert (mTLS) | `Principal` / `X.509Certificate` |
| Авторизация | Что тебе можно? | Проверка ролей/скопов в токете, RBAC в Spring Security | `200 OK` / `403 Forbidden` |
| Аудит | Кто совершил действие? | `userId` из JWT или `terminal_serial` из mTLS → `X-Keycloak-Id` в запросе к сервису | `correlationId`, `userId` в event |

### 5.2. Смена пароля (pass-through identity)
1. Клиент шлёт `POST /api/v1/users/password/change` с JWT
2. Gateway проверяет JWT, извлекает `sub` (keycloakId)
3. Gateway проксирует запрос в user-service с `X-Keycloak-Id` header
4. User-service обновляет пароль в Keycloak Admin API
5. User-service обновляет `BOOTSTRAP_ADMIN_PASSWORD` в БД

### 5.3. Открытие смены терминалом (полный цикл)
1. **Синхронизация (online)**: терминал загружает CRL, льготы, тарифы, чёрный список карт.
2. **Выбор транспорта**:
   ```sql
   SELECT VEHICLE_ID, VEHICLE_NUMBER, VEHICLE_NAME,
     CASE WHEN CARRIER_ID = :terminal_carrier_id THEN 1
          WHEN CARRIER_ID IS NULL THEN 2 ELSE 3 END AS priority
   FROM ASOP_VEHICLES
   WHERE CARRIER_ID = :terminal_carrier_id OR CARRIER_ID IS NULL
   ORDER BY priority, VEHICLE_NUMBER;
   ```

### 5.3.a. Shift/Trip lifecycle (промпт 011)

Авторизация — двух-уровневая:
1. **Карта-ключ** на NFC tap → Android `SessionFlowViewModel.onCardTappedForAuth()`:
   - VCM1 card identity (`file 0`) даёт `cardId, userId, bitmask`.
   - Из `bitmask` через `CardActivationMatrix.rolesFromBitmask(...)` — список ordinal.
   - **Водительские операции (SHIFT/TRIP)** принимают `SHIFT_OPERATOR_ROLES` = DRIVER, CARRIER_DISPATCHER,
     KRS_DISPATCHER + админ-роли (SUPER_ADMIN, REGION_ADMIN, ORGANIZER_ADMIN, CARRIER_ADMIN); для водительских
     ролей carrier резолвится через `reference_rows` (`asop_user_carriers`), для админ-ролей линк не обязателен —
     используется `SyncPreferences.carrierId` (иначе NOT_DRIVER); root/SUPER_ADMIN — глобально.
   - Остальные роли → `CardStep.NOT_DRIVER`.
2. **Server `canClose(sessionId, requesterId)`** (matrix из промпт 011 §4, см. AGENTS.md/SQL там) — cascade scope:
   - DRIVER (открыватель ИЛИ другой_водитель_того_же_carrier_id) → OK.
   - CARRIER_DISPATCHER/KRS_DISPATCHER/CARRIER_ADMIN → OK если carrier_id matches.
   - ORGANIZER_ADMIN → OK cascade на all carriers организатора (JOIN ASOP_ORGANIZER_TERRITORIES).
   - KRS_ADMIN → OK cascade по auditServiceId.
   - REGION_ADMIN/ADMIN/SUPER_ADMIN → OK всегда (с фильтром scope для REGION).
   - **Реализация**: root (ADMIN/SUPER_ADMIN) — отдельным запросом по `ASOP_ROLES.ROLE_NAME` (колонки `role_code`
     нет), НЕ зависит от линков; carrier/region scope — вторым запросом через `requester_scope`
     (ASOP_USER_CARRIERS × ASOP_USER_REGIONS). Root-админ без линков иначе получал 0 строк → «not authorized».

**Race guards**:
- 1 shift per terminal max (UI client-side check + server-side).
- 1 active trip per shift (server-side 409 Conflict в `SessionCommandConsumer.handleSessionOpened`
  через подзапрос `EXISTS (IN_PROGRESS WHERE parent=shift)`).

**Offline-идемпотентность** (только для sessionId и paymentId — `TripPaymentEntity.id`):
- Client генерирует UUIDv7 (`UuidCreator.getTimeOrderedEpoch()`).
- Server `INSERT … ON CONFLICT (SESSION_ID) DO NOTHING` — повторный SyncWorker retry на reconnect безопасен.
- Tap карты → store → emit PendingEvent → SyncWorker → POST.

**GPS привязка к сменам**:
- `ASOP_GPS_TRACKING.SESSION_ID = shift.id` (НЕ trip.id) — отчёты согласованы от открытия shift до закрытия.
- `vehicleId/pathId` в GPS-отчёте = текущий открытый trip (или NULL, если trip закрыт).
- При offline: GPS буферизация in-memory (max ~100 точек) → flush при reconnect.



### 5.4. Создание перевозчика (async)
1. Клиент шлёт `POST /api/v1/carriers` с JWT
2. Gateway проверяет JWT, извлекает `sub`
3. Gateway отправляет команду в `asop.carrier.commands` Kafka topic с `X-Keycloak-Id` header
4. Gateway сохраняет EventStatus.PENDING
5. Gateway возвращает `202 Accepted` + `X-Event-Id`
6. Клиент поллит `GET /api/v1/events/{eventId}`

### 5.5. Выпуск X.509 сертификата терминала (choreographed saga, 4 hops)

Первая регистрация терминала — **открытый HTTPS endpoint без JWT/mTLS** (chicken-and-egg: mTLS требует сертификата, а первый сертификат ещё нужно выпустить). Дальше mTLS становится доступным.

**Android → Gateway → Kafka → crypto-service → Kafka → terminal-service → Kafka → Gateway → polling**

| Шаг | Действие | Топик / endpoint | Результат |
|-----|---------|-------------------|-----------|
| 1 | Android генерирует ECC P-256 ключевую пару в AndroidKeyStore (StrongBox если доступен), отправляет публичный ключ | `POST /api/v1/terminals/cert-sign` (open HTTPS, без auth) | 202 Accepted + `X-Event-Id` |
| 2 | Gateway пушит `CertSignRequested` в Kafka с `X-Event-Id` header (генерирует `eventId` = UUID v7, сохраняет EventStatus.PENDING) | `asop.terminal.cert.commands` | Команда в очереди |
| 3 | crypto-service `@KafkaListener` подписывает через Intermediate CA, достаёт CA chain (intermediate + root PEM bundle), публикует `CertIssued` с теми же `X-Event-Id` | `asop.terminal.cert.issued` | Подписанный X.509 |
| 4 | terminal-service `@KafkaListener` вызывает `ensureTerminal` (findByTerminalSerial → insert если новый). В `TransactionalOperator.transactional` блоке: `markAllAsNotCurrent(terminalId)` + `R2dbcEntityTemplate.insert(TerminalCertEntity(IS_CURRENT=true))`. UNIQUE partial index защищает от race condition. Публикует `CertStored` (или `CertSignFailed` при ошибке) | `asop.terminal.cert.events` | Сертификат в БД |
| 5 | Gateway `@KafkaListener` находит EventService по `X-Event-Id`, вызывает `complete(eventId, resultData=CertStoredResult JSON)` или `fail(eventId, reason)` | — | EventService COMPLETED/FAILED |
| 6 | Android polling `GET /api/v1/events/{eventId}` → 200 + `resultData` (JSON с `certificateBase64`, `caChain`, `terminalNumber`, `terminalId`) | — | PEM сертификат + цепочка |
| 7 | Android сохраняет цепочку через `MtlsManager.storeCertificateChain()`. Теперь доступен mTLS | — | Терминал работает |

**Гарантии консистентности:**
- **XA через UNIQUE partial index** `uq_tc_current_per_terminal ON ASOP_TERMINAL_CERTS (TERMINAL_ID) WHERE IS_CURRENT = true` — DB-уровневая защита от race condition
- **`TransactionalOperator.transactional(mono)`** оборачивает mark+insert в одну R2DBC-транзакцию (Spring `@Transactional` не работает в WebFlux)
- **`X-Event-Id` через Kafka headers** — корреляция request-response в асинхронной saga без сохранения state в продюсере

**`CertSignRequest` DTO** (от Android):
```json
{
  "terminalSerial": "E2E-001",
  "terminalNumber": "12345",
  "terminalModel": "PaxTest",
  "carrierId": "00000000-...",  // optional, null допустим
  "terminalId": "00000000-...",   // optional, null если новый
  "publicKeyBase64": "MFkwEw..."
}
```

**`resultData` (для Android)**:
```json
{
  "certId": "...",
  "terminalId": "...",
  "terminalNumber": "12345",
  "certSerial": "abc123...",
  "certificateBase64": "MII...",
  "validFrom": "2026-07-11T10:00:00Z",
  "validUntil": "2027-07-11T10:00:00Z",
  "caChain": "-----BEGIN CERTIFICATE-----\n..."
}
```

---

## 6. Keycloak 25.0.4 известные баги

### 6.1. Создание realm
```kotlin
// Обязательные поля:
resetPasswordAllowed = true
directGrantFlow = "direct grant"
registrationAllowed = false
verifyEmail = false
loginWithEmailAllowed = true
```

### 6.2. Создание пользователя
Credentials должны быть **inline**:
```kotlin
credentials = listOf(CredentialRepresentation().apply {
    type = CredentialRepresentation.PASSWORD
    value = password
    temporary = true
})
```

### 6.3. firstName + lastName
Оба поля обязательны. Отсутствие любого → `"Account is not fully set up"`.

---

## 7. Асинхронная архитектура Gateway

### Команды (async writes)
- POST/PUT/DELETE с явным контроллером отправляются в Kafka
- Gateway возвращает `202 Accepted` + `AcceptResponse { eventId, topic, acceptedAt }`
- Статус отслеживается через `EventService` (**Redis**, key `asop:event:{eventId}`, TTL 24 ч, реактивный `ReactiveStringRedisTemplate`)

### Прокси (sync proxy)
- GET и необработанные запросы через `ProxyController`
- Gateway добавляет `X-Keycloak-Id`, удаляет `Authorization`
- Маппинг ресурсов в `ServiceRegistry`

### Kafka topic naming
`asop.{domain}.{commands|events}`, например:
- `asop.carrier.commands`
- `asop.session.events`

---

## 8. Таблица атак и контрмер

| Атака | Вектор | Контрмера |
|-------|--------|-----------|
| Подделка JWT | Кража/утечка секрета Keycloak | JWKS, короткий TTL (5 мин), refresh token |
| Replay JWT | Перехват network traffic | HTTPS обязательно, короткий TTL |
| mTLS spoofing | Кража сертификата терминала | Аппаратное хранение (StrongBox, TPM), CRL |
| Offline атака на карту водителя | Физический доступ к чипу | ECDSA P-256, PIN (2-й фактор), файловая система DESFire |
| Gateway impersonation | MITM между клиентом и gateway | mTLS в обе стороны (client-cert на gateway) |
| Backend service spoofing | Фальшивый сервис в Docker сети | Внутренняя сеть (`asop-net`), без открытых портов |
| JWT на прямом запросе к сервису | Обход gateway | Сервисы слушают только на internal network; gateway — единственная точка входа |

---

## 9. Auth-зависимости

Для запуска pass-through identity требуется:
- **gateway-service**: JwtDecoderConfig, WebClient, ServiceRegistry
- **user-service**: SecurityConfig (permitAll), JwtDecoderConfig (для healthchecks etc.)

Для других сервисов требуется один из вариантов:
1. `JwtDecoderConfig.kt` (как в gateway) — если нужно различать сервисы
2. `SecurityConfig` с `permitAll` (как в user-service) — если сервис только внутренний

---

## 📎 Ссылки

- `doc/architecture.md` — архитектурные решения (English)
- `doc/context.md` — полный гайд проекта
- `backend/gateway-service/.../config/JwtDecoderConfig.kt` — кастомный JWT decoder
- `backend/gateway-service/.../controller/ProxyController.kt` — pass-through identity
- `backend/user-service/.../controller/UserController.kt` — пример сервиса с pass-through
- `backend/user-service/.../config/SecurityConfig.kt` — permitAll

---

**Конец документа.**
*Версия: 1.1 — добавлен pass-through identity, JWT issuer workaround*
