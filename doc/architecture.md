# Архитектура платформы ASOP

**Версия:** 1.1
**Дата:** 2026-07-16
**Статус:** MVP в разработке

---

## Содержание

1. [Технологический стек](#1-технологический-стек)
2. [Структура модулей](#2-структура-модулей)
3. [Архитектура Gateway](#3-архитектура-gateway)
4. [Аутентификация и авторизация](#4-аутентификация-и-авторизация)
5. [Pass-Through Identity](#5-pass-through-identity)
6. [Интеграция с Keycloak](#6-интеграция-с-keycloak)
7. [Kafka-сообщения](#7-kafka-сообщения)
8. [Криптография и PKI](#8-криптография-и-pki)
9. [База данных](#9-база-данных)
10. [Фронтенд](#10-фронтенд)
11. [Сборка и деплой](#11-сборка-и-деплой)
12. [Ключевые решения и обоснование](#12-ключевые-решения-и-обоснование)

---

## 1. Технологический стек

| Компонент | Версия | Назначение |
|-----------|--------|------------|
| Kotlin | 2.0.21 | Основной язык |
| Spring Boot | 3.3.5 (WebFlux) | Реактивный фреймворк |
| PostgreSQL | 14 + PostGIS | База данных |
| Kafka | 3.7.1 | Асинхронная шина |
| Keycloak | 25.0.4 | OIDC-провайдер |
| Bouncy Castle | 1.78.1 | Криптография (ECC P-256) |
| Gradle | 8.10.2 | Система сборки |
| React | 18 + Vite | Админка |
| oidc-client-ts | — | OIDC-клиент |

### Ограничения JVM (критично)
- Gradle daemon: `-Xmx4g`
- Kotlin daemon: `-Xmx2g`
- Без этих настроек сборка падает с OOM на 22 модулях
- `org.gradle.configuration-cache=false` — ломает компиляцию Kotlin (ClasspathSnapshotProperties)

---

## 2. Структура модулей

22 модуля в иерархии:

```
:backend:shared:asop-common           # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, утилиты
:backend:shared:asop-dto              # Пусто — DTO перенесены в API-модули
:backend:shared:asop-kafka-contracts  # Классы Kafka-событий
:backend:shared:api:{domain}-api      # 12 модулей: интерфейсы контроллеров + DTO (без реализации)
:backend:{domain}-service             # 12 Spring Boot приложений с реализацией
```

### Граф зависимостей
```
Сервис → API (implementation) → asop-common (api platform)
```

### Структура API-модуля
Пакет: `ru.asop.api.{domain}`
```
backend/shared/api/{name}-api/
├── build.gradle.kts
└── src/main/kotlin/ru/asop/api/{package}/
    ├── controller/          # Интерфейсы контроллеров (@RequestMapping)
    ├── dto/request/         # Request DTO
    ├── dto/response/        # Response DTO
    └── exception/           # Исключения API
```

### Структура сервисного модуля
Пакет: `ru.asop.{domain}` (например `ru.asop.gateway`, `ru.asop.crypto`)

### Порты сервисов

| Сервис | Порт | Роль |
|--------|------|------|
| gateway-service | 8080 | API Gateway: JWT + mTLS, Kafka producer |
| crypto-service | 8081 | Root CA, выпуск X.509 сертификатов |
| user-service | 8082 | Пользователи + Keycloak bootstrap |
| terminal-service | 8084 | Управление терминалами |
| session-service | 8085 | Сессии/смены (иерархия) |
| card-service | 8086 | Карты (MIFARE, банковские) |
| carrier-service | 8087 | Перевозчики, договоры (R2DBC) |
| debt-service | 8088 | Долги по картам |
| audit-service | 8089 | КРС (проверки) |
| fiscal-service | 8090 | Фискализация (ОФД) |
| admin-service | 8091 | Справочники (Regions, Territories, Organizers) |
| route-service | 8092 | Маршруты, тарифные зоны, остановки, ТС, расписание (R2DBC) |

---

## 3. Архитектура Gateway

**Основной принцип:** Gateway не пишет в БД. Валидирует аутентификацию, пушит команды в Kafka, возвращает `202 Accepted`.

### Паттерны маршрутизации

#### 1. Async writes (POST/PUT/DELETE с явным контроллером)
- Команда отправляется в Kafka
- Gateway возвращает `202 Accepted` + заголовок `X-Event-Id` + тело `AcceptedResponse`
- `keycloakId` передаётся в Kafka headers (`X-Keycloak-Id`)
- Пример: `CarrierController` / `CarrierCommandService`

#### 2. Sync proxy (GET + необработанные запросы)
- `ProxyController` пересылает запросы в backend-сервисы через `WebClient`
- Маппинг ресурса → base URL в `ServiceRegistry`:
  - `ASOP_ENV=local` (по умолчанию) → `localhost:{port}`
  - `ASOP_ENV=docker` → Docker hostname сервиса
- Gateway добавляет заголовок `X-Keycloak-Id`, **удаляет** `Authorization`

#### 3. Event tracking
- После отправки в Kafka `EventService` сохраняет статус `PENDING` (in-memory `ConcurrentHashMap`, TTL 30 мин)
- Фронт поллит `GET /api/v1/events/{eventId}`:
  - `202 Accepted` пока PENDING
  - `200 OK` с `resultData` (JSON) когда COMPLETED (cert saga — PEM)
  - `422 Unprocessable Entity` с `errorMessage` когда FAILED
  - `404 Not Found` если eventId неизвестен
- Для cert-sign saga (`asop.terminal.cert.events`) Gateway-consumer обновляет EventService (`COMPLETED`/`FAILED`). Для остальных команд пока сервисы не публикуют события — статус навсегда PENDING.

#### 4. Cert signing flow (choreographed saga, 4 hops)
Первая регистрация терминала — **открытый HTTPS endpoint без JWT/mTLS** (chicken-and-egg при первой регистрации):

```
Android → POST /api/v1/terminals/cert-sign (HTTPS plain)
       → 202 + X-Event-Id
       ↓
Gateway → Kafka asop.terminal.cert.commands (X-Event-Id header)
       ↓
crypto-service @KafkaListener → выпускает X.509 через Intermediate CA
                            → Kafka asop.terminal.cert.issued (X-Event-Id пробрасывается)
       ↓
terminal-service @KafkaListener → ensureTerminal (findByTerminalSerial → insert если новый)
                              → TransactionalOperator.transactional:
                                markAllAsNotCurrent(terminalId)
                                R2dbcEntityTemplate.insert(TerminalCertEntity IS_CURRENT=true)
                              → Kafka asop.terminal.cert.events (CertStored | CertSignFailed)
       ↓
Gateway CertEventConsumer → EventService.complete(eventId, resultData=CertStoredResult JSON)
                          или fail(eventId, reason)
       ↓
Android polling GET /api/v1/events/{eventId} → 200 + resultData → MtlsManager.storeCertificateChain()
```

XA-гарантии: UNIQUE partial index `uq_tc_current_per_terminal ON ASOP_TERMINAL_CERTS (TERMINAL_ID) WHERE IS_CURRENT = true` + `TransactionalOperator` для атомарности.

### Ключевые компоненты Gateway

| Файл | Назначение |
|------|------------|
| `config/ServiceRegistry.kt` | Маппинг resource → URL сервиса |
| `config/WebClientConfig.kt` | WebClient bean для proxy |
| `controller/ProxyController.kt` | Sync proxy (catch-all) |
| `controller/CertCommandController.kt` | POST /api/v1/terminals/cert-sign (async через Kafka) |
| `controller/EventController.kt` | Эндпоинт статуса события |
| `service/EventService.kt` | In-memory event store с методами complete/fail |
| `service/CertCommandService.kt` | Producer CertSignRequested в `asop.terminal.cert.commands` |
| `kafka/CertEventConsumer.kt` | Listener `asop.terminal.cert.events` → EventService.complete/fail |
| `model/EventStatus.kt` | EventState (PENDING, COMPLETED, FAILED) + `resultData: String?` |

---

## 4. Аутентификация и авторизация

### Двойная аутентификация в Gateway

**Chain 1** (`@Order(1)`): mTLS для терминалов
- Пути: `/api/v1/terminals/**`, `/api/v1/sync/**`
- Principal = `CN` из X.509 сертификата
- `X509PrincipalExtractor` из `org.springframework.security.web.authentication.preauth.x509` (синхронный, возвращает `Any`)
- **Исключение**: `POST /api/v1/terminals/cert-sign` → `permitAll` (open HTTPS, без JWT и mTLS — chicken-and-egg при первой регистрации терминала)

**Chain 2** (`@Order(2)`): JWT (Keycloak) для всего остального
- JWKS кэшируется локально, проверка каждые 60 сек
- Нет сетевых вызовов к Keycloak на каждый запрос

### Проблема JWT issuer

В Docker Keycloak (`KC_HOSTNAME=localhost`) выдаёт токены с `iss: http://localhost:8180/realms/asop`, но сервисы внутри Docker обращаются к Keycloak по `http://keycloak:8080`. Стандартный валидатор Spring Security отвергает несовпадающий issuer.

**Решение:** Кастомный `ReactiveJwtDecoder` (`JwtDecoderConfig.kt`):
- Использует JWKS (`jwk-set-uri`) для проверки подписи
- Не проверяет `iss` (принимает любой issuer)
- Проверяет `exp` и `nbf` вручную

Применён в: gateway-service, user-service.

---

## 5. Pass-Through Identity

Gateway проверяет JWT, извлекает `sub` (keycloakId), передаёт backend-сервисам **без** оригинального JWT.

### Схема
```
Клиент                      Gateway                         Сервис
  │                         │                                │
  │ POST /password/change   │                                │
  │ Authorization: JWT      │                                │
  │────────────────────────>│                                │
  │                         │ Проверка JWT (JWKS, exp/nbf)   │
  │                         │ Извлечение sub (keycloakId)    │
  │                         │                                │
  │                         │ POST /password/change          │
  │                         │ X-Keycloak-Id: <sub>          │
  │                         │ (без Authorization)            │
  │                         │──────────────────────────────>│
  │                         │                                │
  │                         │ keycloakId → userId            │
  │                         │ обработка запроса              │
  │                         │<──────────────────────────────│
  │<────────────────────────│                                │
  │ 204 / 202 / 4xx / 5xx   │                                │
```

### Реализация
- **Gateway**: `ProxyController.extractIdentity()` использует `ReactiveSecurityContextHolder` для получения JWT, извлекает `sub`
- **Kafka async**: `X-Keycloak-Id` в Kafka headers
- **Backend сервисы**: не валидируют JWT (user-service с `permitAll`)
- Backend доверяет `X-Keycloak-Id` от gateway (внутренняя сеть)

### Улучшение в будущем
Создать общий `UserResolver` в `asop-common` для resolve keycloakId → userId + роли из БД.

---

## 6. Интеграция с Keycloak

### Баги Keycloak 25.0.4

Три бага при создании realm/users через Admin API:

1. **Создание realm**
   - Голый `{realm: "asop", enabled: true}` ломает Direct Access Grant
   - Требуется: `resetPasswordAllowed=true`, `directGrantFlow="direct grant"`, `registrationAllowed=false`, `verifyEmail=false`, `loginWithEmailAllowed=true`

2. **Учётные данные пользователя**
   - `POST /users` без credentials + отдельный `PUT /reset-password` → "Account is not fully set up"
   - Фикс: credentials **inline** в `UserRepresentation`

3. **Имена пользователя**
   - Отсутствие `firstName` или `lastName` → "Account is not fully set up"
   - Оба поля обязательны

### Bootstrap

На `ApplicationReadyEvent`, если таблица `ASOP_USERS` пуста:
1. Создаёт realm `asop` с полной конфигурацией
2. Создаёт роли: `SUPER_ADMIN`, `CARRIER_ADMIN`, `DISPATCHER` и т.д.
3. Создаёт администратора `admin@asop.local` с inline credentials
4. Записывает в `ASOP_USERS` + `ASOP_USER_ROLES`

### Потоки аутентификации

| Окружение | Поток | Статус |
|-----------|-------|--------|
| MVP/dev | Direct Access Grant (password grant) | Активен |
| Production | OIDC Auth Code + PKCE (через oidc-client-ts) | Запланирован |

### Эндпоинты

| Метод | Путь | Сервис | Тип |
|-------|------|--------|-----|
| POST | `/api/v1/users/password/change` | user-service | sync (pass-through) |

---

## 7. Kafka-сообщения

### Именование топиков
Шаблон: `asop.{domain}.{commands|events}`

Определён в `KafkaTopic` в `asop-common`:
- `asop.carrier.commands` — команды записи перевозчиков
- `asop.session.events` — доменные события сессий
- `asop.terminal.cert.commands` — команды выпуска X.509 (gateway → crypto-service)
- `asop.terminal.cert.issued` — выпущенные сертификаты (crypto-service → terminal-service)
- `asop.terminal.cert.events` — сохранённые сертификаты + ошибки (terminal-service → gateway)

### Поток асинхронной команды
1. Gateway проверяет запрос, извлекает identity
2. Gateway отправляет команду в `asop.{domain}.commands`
3. Gateway возвращает `202 Accepted` + `X-Event-Id`
4. Backend-сервис потребляет команду, обрабатывает, публикует событие в `asop.{domain}.events`
5. Consumer обновляет статус события (`EventService.complete/fail`)
6. Клиент получает `200 OK` с `resultData` (cert saga) при следующем polling `GET /api/v1/events/{eventId}`

Для cert-sign saga поток расширен: gateway → crypto-service → terminal-service → gateway через 3 топика и проброс `X-Event-Id` через Kafka headers для корреляции.

### Заголовки
- `X-Keycloak-Id`: keycloakId аутентифицированного пользователя (трассировка)

---

## 8. Криптография и PKI

### Иерархия сертификатов
```
Root CA (self-signed, ECC P-256, 10 лет)
└── Intermediate CA (подписан Root CA, 5 лет)
    ├── Сертификаты терминалов (1 год)
    ├── Сертификаты смарт-карт (1 год)
    └── Сертификаты водителей (1 год)
```

### Реализация
- Root CA в PKCS#12 (`./data/root-ca.p12`), авто-генерация при первом старте
- ECC P-256 через Bouncy Castle
- Все подписи через Intermediate CA (пересоздаётся при каждом рестарте в MVP)
- `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` нет в Spring 6.1 — использовать `"application/x-pem-file"`

### Эндпоинты crypto-service
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/terminals/register` | Выпуск сертификата терминала (sync fallback, обычно cert-sign идёт через Kafka) |
| POST | `/api/v1/smart-cards/issue` | Выпуск сертификата смарт-карты |
| GET | `/api/v1/terminals/root-ca(/{format})` | Root CA в PEM/DER |
| POST | `/api/v1/certificates/server` | Выпуск серверного сертификата |
| GET | `/api/v1/certificates/ca-chain` | CA chain bundle (Root + Intermediate) |

**Kafka consumer**: `@KafkaListener("asop.terminal.cert.commands")` в `CertCommandConsumer.kt` выпускает сертификат через `TerminalCertService.issueTerminalCertificate()` и публикует `CertIssued` в `asop.terminal.cert.issued` с пробросом `X-Event-Id` header. CA chain включается в `CertIssued.caChain`.

### Роли смарт-карт
`PASSENGER_ANONYMOUS`, `PASSENGER_BENEFIT`, `DRIVER`, `CONTROLLER`, `DISPATCHER`, `CARRIER_ADMIN`, `REGION_ADMIN`, `SUPER_ADMIN`, `DISTRIBUTOR_ADMIN`, `DISTRIBUTOR_TERMINAL`, `SERVICE`

### DN-шаблоны
```yaml
DRIVER: "CN={cardId}, OU=DRIVER:{carrierId}, O=ASOP"
CONTROLLER: "CN={cardId}, OU=CONTROLLER:{carrierId}, O=ASOP"
```

---

## 9. База данных

### Ключевые таблицы
- `ASOP_USERS`, `ASOP_USER_ROLES`, `ASOP_USER_CARRIERS` — пользователи
- `ASOP_CARRIERS`, `ASOP_CONTRACTS`, `ASOP_VEHICLES` — перевозчики
- `ASOP_CARDS`, `ASOP_CARD_MIFARES`, `ASOP_CARD_TARIFFS`, `ASOP_CARD_BANKS` — карты
- `ASOP_TERMINALS` (с `UNIQUE` constraint на `TERMINAL_SERIAL`), `ASOP_DISTRIBUTOR_TERMINALS`, `ASOP_TIDS` — терминалы
- `ASOP_TERMINAL_CERTS` — история X.509 сертификатов терминалов (v002):
  - `IS_CURRENT` boolean, `CA_CHAIN` PEM
  - **UNIQUE partial index** `uq_tc_current_per_terminal ON (TERMINAL_ID) WHERE IS_CURRENT = true` — не более одного активного
- `ASOP_SESSIONS`, `ASOP_TRANSACTIONS`, `ASOP_CARD_DEBTS` — транзакции
- `ASOP_AUDIT_TASKS`, `ASOP_AUDIT_BRIGADES`, `ASOP_AUDIT_INSPECTIONS` — КРС

### Соглашения
- **Все ID**: UUID v7 через `UuidCreator.getTimeOrderedEpoch()` (`UuidUtils.newId()`)
- **Миграции**: Liquibase, в `infrastructure/db-migrations/{service}/`
- Активные миграции только в user-service; у остальных `liquibase.enabled=false`

---

## 10. Фронтенд

### Стек
- Vite + React 18 + TypeScript
- React Router (клиентская маршрутизация)
- TanStack Query (серверное состояние)
- oidc-client-ts (OIDC Auth Code + PKCE)
- Axios (HTTP-клиент)

### Архитектура
- Vite dev mode проксирует `/api` → `http://localhost:8080` (gateway)
- API-клиент: `BASE=/api/v1`, Bearer token из oidc-client-ts
- `useCommand` hook: паттерн 202 + polling для write-команд

### Страницы
`Login`, `Callback` (OIDC), `Dashboard`, `Users`, `Terminals`, `Cards`, `Regions`, `Territories`, `Organizers`, `Routes`, `FareZones`, `TransportStops`, `Vehicles`, `Paths`, `Schedule`

---

## 11. Сборка и деплой

### Docker
```bash
./gradlew bootJar
docker compose -f infrastructure/docker/docker-compose.yml down -v
docker compose -f infrastructure/docker/docker-compose.yml up -d --build
```

`--build` обязателен после пересборки JARs. `down -v` удаляет volumes (fresh-start конвенция).

Каждый сервис имеет `Dockerfile` (eclipse-temurin:21-jre). Liquibase миграции монтируются из `infrastructure/db-migrations/` в `/db-migrations/` внутри контейнера.

### Docker Compose
Все 12 сервисов + route-service + PostgreSQL + Kafka + Keycloak + Zookeeper + Liquibase + web-admin на общей сети `asop-net`.

**Запуск из Windows:** `.\infrastructure\docker\start.ps1` (PowerShell wave-based скрипт). Git Bash не видит Docker Desktop.

### Ключевые переменные окружения
| Переменная | Назначение |
|-----------|------------|
| `BOOTSTRAP_ENABLED` | Включить bootstrap при старте |
| `BOOTSTRAP_ADMIN_PASSWORD` | Начальный пароль администратора |
| `KEYCLOAK_URL` | Внутренний URL Keycloak |
| `KEYCLOAK_ADMIN_PASSWORD` | Пароль admin Keycloak |
| `ROOT_CA_KEYSTORE_PASSWORD` | Пароль хранилища Root CA |
| `ASOP_ENV` | `local` (по умолчанию) или `docker` |
| `KC_HOSTNAME` | Hostname Keycloak (localhost в Docker) |

---

## 12. Ключевые решения и обоснование

| Решение | Обоснование |
|---------|-------------|
| **Gateway → Kafka → Сервисы** (без записи в БД из gateway) | CQRS-подобное разделение; gateway — только роутер/валидатор |
| **Pass-through identity** | Backend-сервисам не нужно валидировать JWT; доверие во внутренней сети |
| **Кастомный JWT decoder (без проверки issuer)** | URL issuer Keycloak отличается внутри Docker и снаружи |
| **API-модули как отдельные Gradle-подпроекты** | Чистое разделение контрактов; сервис и клиент используют одни DTO/интерфейсы |
| **UUID v7** | Time-ordered → лучшая производительность B-tree индексов чем UUID v4 |
| **ECC P-256 для сертификатов** | Ключи меньше RSA; аппаратная поддержка в Android StrongBox, DESFire EV3 |
| **In-memory event store (TTL 30 мин)** | MVP-простота; события эфемерны (только отслеживание до подтверждения сервисом) |
| **mTLS для терминалов** | Аутентификация устройств офлайн; независимость от Keycloak |
| **Chain 1 + Chain 2 в SecurityConfig** | Чистое разделение mTLS (терминалы) и JWT (веб) потоков |
| **Cert signing choreographed saga** (4 hops через Kafka) | Хореография через топики `asop.terminal.cert.{commands,issued,events}` с пробросом `X-Event-Id` через headers — нет single point of failure, каждая стадия независимо ретраится |
| **UNIQUE partial index** на `IS_CURRENT=true` | DB-уровневая защита от race condition при параллельной ротации сертификатов (`uq_tc_current_per_terminal`) |
| **Open HTTPS endpoint для cert-sign** (без JWT/mTLS) | Chicken-and-egg: первая регистрация терминала невозможна при строгой аутентификации; mTLS появляется после выпуска первого сертификата |
| **Liquibase в отдельной директории** | Централизованное управление миграциями; не встроено в JAR сервисов |
