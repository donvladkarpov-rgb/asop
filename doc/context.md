# Контекст проекта ASOP — Полный гайд

**Дата создания:** 08 июля 2026
**Версия:** 0.2.0-SNAPSHOT
**Статус:** MVP в разработке

---

## 📖 Оглавление

1. [Архитектура проекта](#1-архитектура-проекта)
2. [Структура модулей](#2-структура-модулей)
3. [Gateway](#3-gateway)
4. [Безопасность и аутентификация](#4-безопасность-и-аутентификация)
5. [Pass-Through Identity](#5-pass-through-identity)
6. [Keycloak 25.0.4 Баги](#6-keycloak-2504-баги)
7. [Криптография и PKI](#7-криптография-и-pki)
8. [База данных](#8-база-данных)
9. [Frontend](#9-frontend)
10. [Gradle конфигурация](#10-gradle-конфигурация)
11. [Git и .gitignore](#11-git-и-gitignore)
12. [Bootstrap](#12-bootstrap)
13. [Docker deploy](#13-docker-deploy)
14. [Ключевые уроки](#14-ключевые-уроки)

---

## 1. Архитектура проекта

### Технологический стек
- **Язык:** Kotlin 2.0.21
- **Фреймворк:** Spring Boot 3.3.5 (WebFlux, реактивный)
- **БД:** PostgreSQL 14+ с PostGIS
- **Очереди:** Kafka 3.7.1
- **Аутентификация:** Keycloak 25.0.4 (JWT для веба)
- **Криптография:** Bouncy Castle 1.78.1, ECC P-256
- **Сборка:** Gradle 8.10.2
- **UUID:** v7 (Time-Ordered, RFC 9562)
- **Фронтенд:** Vite + React 18 + TypeScript + oidc-client-ts

### Микросервисы
```
backend/
├── shared/                    # Общие библиотеки
│   ├── asop-common/           # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, UuidUtils
│   ├── asop-dto/              # пусто (DTO перенесены в API-модули)
│   ├── asop-kafka-contracts/  # Kafka события
│   └── api/                   # API-контракты (11 модулей)
├── gateway-service/           # API Gateway (WebFlux + двойная аутентификация)
├── crypto-service/            # Root CA, выпуск сертификатов
├── carrier-service/           # Управление перевозчиками (R2DBC)
├── terminal-service/          # Управление терминалами
├── session-service/           # Сессии и смены
├── card-service/              # Управление картами
├── user-service/              # Пользователи + Keycloak bootstrap
├── debt-service/              # Долги по картам
├── fiscal-service/            # Фискализация (ОФД)
├── audit-service/             # КРС (контролёры)
└── admin-service/             # Справочники (Regions, Territories, Organizers)
```

---

## 2. Структура модулей

### Разделение API и реализации

**Проблема:** Контроллеры и DTO были в сервисах, что мешало переиспользованию.

**Решение:** Вынести API-контракты в `backend/shared/api/`

**Структура API-модуля:**
```
backend/shared/api/{name}-api/
├── build.gradle.kts
└── src/main/kotlin/ru/asop/api/{package}/
    ├── controller/          # Интерфейсы контроллеров
    ├── dto/request/         # Request DTO
    ├── dto/response/        # Response DTO
    └── exception/           # Исключения API
```

**Service → API dependency:** `implementation(project(":backend:shared:api:{domain}-api"))`

### Все модули (25)
```
:backend:shared:asop-common
:backend:shared:asop-dto
:backend:shared:asop-kafka-contracts
:backend:shared:api:gateway-api
:backend:shared:api:crypto-api
:backend:shared:api:carrier-api
:backend:shared:api:session-api
:backend:shared:api:terminal-api
:backend:shared:api:card-api
:backend:shared:api:user-api
:backend:shared:api:debt-api
:backend:shared:api:fiscal-api
:backend:shared:api:audit-api
:backend:shared:api:reference-api
:backend:gateway-service
:backend:carrier-service
:backend:crypto-service
:backend:terminal-service
:backend:session-service
:backend:card-service
:backend:user-service
:backend:debt-service
:backend:fiscal-service
:backend:audit-service
:backend:admin-service
```

### Сервисы и порты

| Путь | Порт | Роль |
|------|------|------|
| `gateway-service` | 8080 | API Gateway: JWT + mTLS, Kafka producer |
| `crypto-service` | 8081 | Root CA, X.509 cert issuance |
| `user-service` | 8082 | Users + Keycloak bootstrap |
| `terminal-service` | 8084 | Terminal management |
| `session-service` | 8085 | Sessions/shifts (tree hierarchy) |
| `card-service` | 8086 | Cards (MIFARE, bank) |
| `carrier-service` | 8087 | Carriers, contracts, vehicles (R2DBC) |
| `debt-service` | 8088 | Card debts |
| `audit-service` | 8089 | Inspections (КРС) |
| `fiscal-service` | 8090 | Fiscalization (OFD) |
| `admin-service` | 8091 | Справочники (Regions, Territories, Organizers) |

---

## 3. Gateway

**Принцип:** Gateway не пишет в БД. Только валидирует аутентификацию и пушит команды в Kafka.

### Маршрутизация

#### 1. Async writes (POST/PUT/DELETE с явным контроллером)
- Команда уходит в Kafka, gateway возвращает `202 Accepted` + `X-Event-Id`
- `keycloakId` передаётся в Kafka headers (`X-Keycloak-Id`)
- Пример: `CarrierController` / `CarrierCommandService`

#### 2. Sync proxy (GET + остальные запросы)
- `ProxyController` пересылает запросы в backend-сервисы через `WebClient`
- Маппинг ресурсов (`users`, `carriers`, `cards`, etc.) → base URL сервиса в `ServiceRegistry`
- `ASOP_ENV=local` (default → `localhost`), `ASOP_ENV=docker` → Docker hostnames
- Gateway добавляет `X-Keycloak-Id`, **убирает** `Authorization`

#### 3. Event tracking
- После отправки команды в Kafka `EventService` сохраняет статус `PENDING` (in-memory, TTL 30 мин)
- Фронт поллит `GET /api/v1/events/{eventId}` до `COMPLETED`/`FAILED`
- Пока сервисы не публикуют события в `.events` topics — статус навсегда PENDING

### Gateway files

| Файл | Назначение |
|------|------------|
| `config/ServiceRegistry.kt` | Маппинг resource → service URL |
| `config/WebClientConfig.kt` | WebClient bean для proxy (SSL truststore, hostname verification отключён) |
| `config/JwtDecoderConfig.kt` | Кастомный ReactiveJwtDecoder (без проверки issuer) |
| `config/KeycloakProxyController.kt` | Проксирование `/realms/**` в Keycloak с корректными `X-Forwarded-*` |
| `controller/ProxyController.kt` | Catch-all sync proxy |
| `controller/EventController.kt` | GET /api/v1/events/{eventId} |
| `controller/CarrierController.kt` | POST /api/v1/carriers (async) |
| `service/EventService.kt` | In-memory event store |
| `service/CarrierCommandService.kt` | Kafka producer с X-Keycloak-Id header |
| `model/EventStatus.kt` | EventState (PENDING, COMPLETED, FAILED) |

---

## 4. Безопасность и аутентификация

### Двойная аутентификация в Gateway

**Chain 1** (`@Order(1)`): mTLS для терминалов
- Пути: `/api/v1/terminals/**`, `/api/v1/sync/**`
- Principal = `CN` из X.509 сертификата
- Использует `X509PrincipalExtractor` из `org.springframework.security.web.authentication.preauth.x509` (синхронный, возвращает `Any`)

**Chain 2** (`@Order(2)`): JWT (Keycloak) для всего остального
- JWKS кэшируется локально, обновляется каждые 60 сек
- Нет сетевых вызовов к Keycloak на каждый запрос

### JWT issuer (важно!)

В Docker Keycloak (`KC_HOSTNAME=localhost`) выдаёт токены с `iss: http://localhost:8180/realms/asop`, но сервисы внутри Docker обращаются к Keycloak по `http://keycloak:8080`. Стандартный валидатор Spring Security отвергает токены.

**Решение:** Кастомный `ReactiveJwtDecoder` (`JwtDecoderConfig.kt`):
- Использует JWKS с `jwk-set-uri` для проверки подписи
- Не проверяет `iss` (accept any issuer)
- Проверяет `exp` и `nbf` вручную

Применён в gateway-service и user-service. Для других сервисов нужно добавить.

---

## 5. Pass-Through Identity

Gateway проверяет JWT, извлекает `sub` (keycloakId), передаёт в backend-сервисы **без** оригинального JWT.

### Схема
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

### Реализация
- **Gateway**: `ProxyController.extractIdentity()` использует `ReactiveSecurityContextHolder` для получения JWT, извлекает `sub`
- **Kafka async**: `X-Keycloak-Id` в Kafka headers
- **Backend сервисы**: не валидируют JWT (user-service имеет `permitAll`)
- Backend доверяет заголовку `X-Keycloak-Id` от gateway (внутренняя сеть)

### User-service
- `SecurityConfig.kt`: `permitAll`, JWT не проверяется
- `UserController`: читает `X-Keycloak-Id` из request headers (через `ServerWebExchange`)

---

## 6. Keycloak 25.0.4 Баги

Три бага при создании realm/users через Admin API:

### 1. Создание realm
```kotlin
// НЕ РАБОТАЕТ (ломает Direct Access Grant):
val realmRep = RealmRepresentation().apply {
    realm = "asop"
    enabled = true
}

// РАБОТАЕТ:
val realmRep = RealmRepresentation().apply {
    realm = "asop"
    enabled = true
    resetPasswordAllowed = true
    directGrantFlow = "direct grant"
    registrationAllowed = false
    verifyEmail = false
    loginWithEmailAllowed = true
    bruteForceProtected = false
}
```

### 2. Создание пользователя
```kotlin
// НЕ РАБОТАЕТ:
val userRep = UserRepresentation().apply {
    username = "admin@asop.local"
    enabled = true
}
// + отдельный POST /users/{id}/reset-password
// → "Account is not fully set up"

// РАБОТАЕТ: credentials inline
val userRep = UserRepresentation().apply {
    username = "admin@asop.local"
    enabled = true
    credentials = listOf(CredentialRepresentation().apply {
        type = CredentialRepresentation.PASSWORD
        value = password
        temporary = true
    })
}
```

### 3. Обязательные поля
```kotlin
// НЕ РАБОТАЕТ (даже с одним полем):
firstName = "Admin"
// или
lastName = "Admin"

// РАБОТАЕТ: оба поля обязательны
firstName = "Admin"
lastName = "ASOP"
```

---

## 7. Криптография и PKI

### Иерархия CA
```
Root CA (self-signed, ECC P-256, 10 лет)
└── Intermediate CA (подписан Root CA, 5 лет)
    ├── Terminal Certificates (1 год)
    ├── Smart Card Certificates (1 год)
    └── Driver Certificates (1 год)
```

### Хранение
- Root CA в PKCS#12 (`./data/root-ca.p12`), авто-генерация при первом старте
- ECC P-256 через Bouncy Castle
- Все подписи через Intermediate CA (пересоздаётся при каждом рестарте в MVP)

### Важные замечания
- `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` нет в Spring 6.1 — использовать `"application/x-pem-file"`
- `X509PrincipalExtractor` из `preauth.x509`, не из `web.server.authentication`
- Интерфейс синхронный — возвращает `Any`, не `Mono<Any>`

### Endpoint'ы crypto-service
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/terminals/register` | Выпуск сертификата терминала |
| POST | `/api/v1/smart-cards/issue` | Выпуск сертификата карты |
| GET | `/api/v1/terminals/root-ca(/{format})` | Root CA в PEM/DER |

### Роли смарт-карт
`PASSENGER_ANONYMOUS`, `PASSENGER_BENEFIT`, `DRIVER`, `CONTROLLER`, `DISPATCHER`, `CARRIER_ADMIN`, `REGION_ADMIN`, `SUPER_ADMIN`, `DISTRIBUTOR_ADMIN`, `DISTRIBUTOR_TERMINAL`, `SERVICE`

---

## 8. База данных

### Ключевые таблицы

**Пользователи:**
- `ASOP_USERS` — пользователи
- `ASOP_USER_ROLES` — роли
- `ASOP_USER_CARRIERS` — привязка к перевозчикам
- `ASOP_USER_REGIONS` — привязка к регионам

**Перевозчики:**
- `ASOP_CARRIERS` — перевозчики
- `ASOP_CONTRACTS` — договоры
- `ASOP_VEHICLES` — транспортные средства

**Карты:**
- `ASOP_CARDS` — все карты
- `ASOP_CARD_MIFARES` — MIFARE-карты (с PKI полями)
- `ASOP_CARD_TARIFFS` — тарифы
- `ASOP_CARD_BANKS` — банковские карты

**Терминалы:**
- `ASOP_TERMINALS` — терминалы
- `ASOP_DISTRIBUTOR_TERMINALS` — терминалы дистрибьюторов
- `ASOP_TIDS` — пул TID

**Транзакции:**
- `ASOP_SESSIONS` — сессии (иерархические)
- `ASOP_TRANSACTIONS` — финансовые проводки
- `ASOP_CARD_DEBTS` — долги

**КРС:**
- `ASOP_AUDIT_TASKS` — задания
- `ASOP_AUDIT_BRIGADES` — бригады
- `ASOP_AUDIT_INSPECTIONS` — акты

**Справочники:**
- `ASOP_REGIONS` — регионы
- `ASOP_TERRITORIES` — территории
- `ASOP_ORGANIZERS` — организаторы
- `ASOP_ORGANIZER_TERRITORIES` — привязка организаторов к территориям

### UUID v7
Генерируется на уровне приложения через `UuidCreator.getTimeOrderedEpoch()` (`UuidUtils.newId()`).
В БД есть fallback-функция `gen_uuid_v7()`.

### Миграции

Liquibase запускается **отдельным Docker-контейнером** (`liquibase:4.27`) после `postgres:healthy` и завершается после наката миграций.

**Структура:**
- `db.changelog-master.yaml` → `migrations/v001-init.yaml` → `v001-init.sql` (единый SQL)
- Все 67 таблиц, функции (gen_uuid_v7, set_timestamps, update_timestamps), seed roles
- Все FK idempotent: `ADD CONSTRAINT IF NOT EXISTS ... DEFERRABLE INITIALLY DEFERRED`

**Liquibase quirks:**
- `$$` dollar quotes не работают — использовать `$body$`
- `splitStatements: false` для sqlFile (JDBC сам разбивает многосоставные скрипты)
- `relativeToChangelogFile: true` во всех include

**Сервисы НЕ содержат Liquibase/DataSource/JDBC** — только R2DBC.

**`asop_schema.sql`** — справочная копия `v001-init.sql`, не исполняется.

---

## 9. Frontend

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
`Login`, `Callback` (OIDC), `Dashboard`, `Users`, `Terminals`, `Cards`, `Regions`, `Territories`, `Organizers`

Раздел **"Справочники"** в Sidebar: Regions, Territories, Organizers.

---

## 10. Gradle конфигурация

### build.gradle.kts для API-модулей
```kotlin
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter.webflux)
    api(libs.spring.boot.starter.validation)
    api(libs.jakarta.validation.api)
    api(project(":backend:shared:asop-common"))
}
```

### gradle.properties
```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=false
org.gradle.daemon=true
org.gradle.jvmargs=-Xmx4g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
kotlin.code.style=official
kotlin.incremental=true
kotlin.daemon.jvmargs=-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8
```

### ⚠️ Важно
- Без `-Xmx4g` Gradle падает с OOM при 20+ модулях
- `configuration-cache=false` — ломает компиляцию Kotlin (ClasspathSnapshotProperties)

---

## 11. Git и .gitignore

```gitignore
# Gradle
**/build/
.gradle/
gradle/wrapper/gradle-wrapper.jar

# IntelliJ IDEA
.idea/
*.iml
*.ipr
*.iws
out/

# Kotlin
.kotlin/

# OS
.DS_Store
Thumbs.db
desktop.ini

# Environment
.env
.env.local

# Secrets
*.p12
*.jks
*.keystore
*.pem
*.key
*.crt
*.der
```

**⚠️ Важно:** Паттерн `.*/` игнорирует ВСЕ точечные директории, включая `.git`. Использовать явные правила.

---

## 12. Bootstrap

На `ApplicationReadyEvent` (в user-service), если таблица `ASOP_USERS` пуста:
1. Создаёт realm `asop` в Keycloak (с полными настройками — см. баг №1)
2. Создаёт роли: `SUPER_ADMIN`, `CARRIER_ADMIN`, `DISPATCHER` и т.д.
3. Создаёт администратора `admin@asop.local` (credentials inline — см. баг №2)
4. Записывает в `ASOP_USERS` + `ASOP_USER_ROLES`
5. Создаёт публичный OIDC-клиент `asop-admin` (redirectUris: `http://localhost:3000/*`) через `ensureOidcClient()`

### Переменные окружения
- `BOOTSTRAP_ENABLED` — включить bootstrap
- `BOOTSTRAP_ADMIN_PASSWORD` — пароль администратора
- `KEYCLOAK_URL` — URL Keycloak
- `KEYCLOAK_ADMIN_PASSWORD` — пароль admin Keycloak

---

## 13. Docker deploy

```bash
# 1. Build JARs
./gradlew bootJar --no-daemon

# 2. Build & start all containers
docker compose -f infrastructure/docker/docker-compose.yml up -d --build

# 3. Specific service
docker compose -f infrastructure/docker/docker-compose.yml up -d --build user-service
```

Каждый сервис имеет свой `Dockerfile` (`eclipse-temurin:21-jre`).
Liquibase запускается отдельным контейнером (image: `liquibase:4.27`), который монтирует `infrastructure/db-migrations/` в `/db-migrations/`, выполняет миграции и завершается.
Docker-compose включает 16 контейнеров + liquibase (exited 0).

---

## 14. Ключевые уроки

### ❌ Что НЕ работает
1. **PowerShell + regex для рефакторинга кода** — хрупко, ломает форматирование
2. **`MediaType.APPLICATION_PEM_CERTIFICATE_VALUE`** — нет в Spring 6.1, использовать `"application/x-pem-file"`
3. **`X509PrincipalExtractor` из `web.server.authentication`** — неправильный пакет, правильный: `preauth.x509`
4. **`extractPrincipal` возвращает `Mono<Any>`** — интерфейс синхронный, возвращает `Any`
5. **`BOOT_DEPENDENCIES`** — не существует как публичная константа, использовать `platform(libs.spring.boot.dependencies)`
6. **Мало памяти для Gradle** — OOM при 20+ модулях, нужно `-Xmx4g`
7. **Паттерн `.*/` в .gitignore** — игнорирует `.git`, использовать явные правила
8. **Keycloak bare-minimum realm** — без `resetPasswordAllowed` и `directGrantFlow` ломает Direct Access Grant
9. **Keycloak `POST /users` без credentials** — раздельный resetPassword выдаёт "Account is not fully set up"
10. **Keycloak без firstName/lastName** — "Account is not fully set up"
11. **Keycloak issuer mismatch в Docker** — `localhost:8180` vs `keycloak:8080`
12. **Liquibase внутри Spring Boot + R2DBC** — конфликт DataSource (JDBC) и R2DBC. Liquibase должен быть отдельным контейнером.
13. **`$$` dollar quotes в Liquibase sqlFile** — ломают парсинг. Использовать `$body$`.
14. **`splitStatements: true` (default) для sqlFile** — разбивает CREATE FUNCTION на части. Использовать `splitStatements: false`.
15. **`ReactiveCrudRepository.save()` с не-null UUID** — делает UPDATE вместо INSERT. Использовать `R2dbcEntityTemplate.insert()`.
16. **`X500Name(cert.subjectX500Principal.name)` в crypto-service** — Java переупорядочивает DN в RFC2253, ломает PKIX на byte-level сравнении
17. **`provision.sh` без dnsNames при DNS_NAMES == SERVICE_NAME** — сертификаты без SAN, Java 17+ отклоняет hostname verification
18. **Gateway service URL scheme `http://`** — все сервисы слушают только HTTPS, `http://` вызывал PrematureCloseException

### ✅ Что работает
1. **Python для миграций** — надёжнее PowerShell, точное сравнение строк
2. **API-модули в `backend/shared/api/`** — правильное место для контрактов
3. **`platform(libs.spring.boot.dependencies)`** — правильный способ импорта BOM
4. **Разделение на Chain 1 (mTLS) и Chain 2 (JWT)** — чистая архитектура
5. **Root CA генерится автоматически** при первом старте crypto-service
6. **UUID v7** — time-ordered, лучше для индексации чем v4
7. **Pass-through identity** — backend сервисы не валидируют JWT, доверяют gateway
8. **Кастомный JWT decoder** — решает проблему issuer URL в Docker
9. **Inline credentials в Keycloak** — единственный рабочий способ для 25.x
10. **Liquibase отдельным контейнером** — решает проблему R2DBC ↔ JDBC в сервисах
11. **Единый v001-init.sql** — проще поддерживать, чем множество changelog'ов
12. **Gateway sync proxy для CRUD-справочников** — не требует Kafka для простых операций
13. **Keycloak proxy через gateway** — единый origin, без CORS, issuer адаптируется под `X-Forwarded-*` заголовки
14. **`X500Name.getInstance(ASN1Sequence.getInstance(encoded))`** — фикс DN байтового сравнения при PKIX chain validation
15. **`start.sh` с wave-based запуском** — последовательный запуск зависимостей через healthcheck

### 📋 Чеклист для новых модулей
- [ ] Создать API-модуль в `backend/shared/api/{name}-api/`
- [ ] Добавить в `settings.gradle.kts`
- [ ] Создать build.gradle.kts с `platform(libs.spring.boot.dependencies)`
- [ ] Создать API-интерфейс в `controller/`
- [ ] Создать DTO в `dto/request/` и `dto/response/`
- [ ] Добавить SQL-таблицы в `infrastructure/db-migrations/migrations/v001-init.sql`
- [ ] Добавить маппинг в `ServiceRegistry.kt` gateway (для sync CRUD)
- [ ] Добавить зависимость в сервис: `implementation(project(":backend:shared:api:{name}-api"))`
- [ ] Реализовать интерфейс в контроллере сервиса
- [ ] Добавить JwtDecoderConfig (или permitAll) для JWT issuer workaround
- [ ] Создать Dockerfile для сервиса
- [ ] Добавить сервис в docker-compose.yml
- [ ] Проверить сборку: `./gradlew clean build`

---

## 📎 Ссылки

- `doc/architecture.md` — архитектурные решения (English)
- `doc/auth.md` — детальная архитектура аутентификации
- `infrastructure/db-migrations/asop_schema.sql` — схема БД
- `backend/shared/asop-common/` — общие утилиты

---

**Конец документа.**
*Версия: 0.2.1 — обновлено 10 июля 2026*
