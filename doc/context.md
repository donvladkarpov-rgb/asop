#  Контекст проекта ASOP — Полный гайд

**Дата создания:** 08 июля 2026
**Версия:** 0.1.0-SNAPSHOT
**Статус:** MVP в разработке

---

## 📖 Оглавление

1. [Архитектура проекта](#1-архитектура-проекта)
2. [Структура модулей](#2-структура-модулей)
3. [Безопасность и аутентификация](#3-безопасность-и-аутентификация)
4. [Криптография и PKI](#4-криптография-и-pki)
5. [База данных](#5-база-данных)
6. [Python-скрипты миграции](#6-python-скрипты-миграции)
7. [Gradle конфигурация](#7-gradle-конфигурация)
8. [Git и .gitignore](#8-git-и-gitignore)
9. [Аналитика ролей и bootstrap](#9-аналитика-ролей-и-bootstrap)
10. [Ключевые уроки](#10-ключевые-уроки)

---

## 1. Архитектура проекта

### Технологический стек
- **Язык:** Kotlin 2.0.21
- **Фреймворк:** Spring Boot 3.3.5 (WebFlux, реактивный)
- **БД:** PostgreSQL 14+ с PostGIS
- **Очереди:** Kafka 3.7.1
- **Аутентификация:** Keycloak 25.0.4 (JWT для веба)
- **Криптография:** Bouncy Castle 1.78.1, ECC P-256
- **Сборка:** Gradle 8.10.2 с configuration cache
- **UUID:** v7 (Time-Ordered, RFC 9562)

### Микросервисы
```
backend/
├── shared/                    # Общие библиотеки
│   ├── asop-common/           # BaseEntity, DomainEvent, ErrorCode, KafkaTopic
│   ├── asop-dto/              # DTO (переносится в API-модули)
│   ├── asop-kafka-contracts/  # Kafka события
│   └── api/                   # API-контракты
│       ├── gateway-api/
│       ├── crypto-api/
│       ├── carrier-api/
│       ── ... (10 модулей)
── gateway-service/           # API Gateway (WebFlux + двойная аутентификация)
├── crypto-service/            # Root CA, выпуск сертификатов
├── carrier-service/           # Управление перевозчиками
├── terminal-service/          # Управление терминалами
├── session-service/           # Сессии и смены
├── card-service/              # Управление картами
├── user-service/              # Пользователи + Keycloak
├── debt-service/              # Долги по картам
├── fiscal-service/            # Фискализация (ОФД)
└── audit-service/             # КРС (контролёры)
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
    ├── dto/
    │   ├── request/         # Request DTO
    │   └── response/        # Response DTO
    └── exception/           # Исключения API
```

**Пример API-интерфейса:**
```kotlin
@RequestMapping("/api/v1/carriers")
interface CarrierApi {
    @PostMapping
    fun createCarrier(
        @Valid @RequestBody request: CarrierCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>>
}
```

**Реализация в сервисе:**
```kotlin
@RestController
class CarrierController(
    private val carrierCommandService: CarrierCommandService
) : CarrierApi {
    override fun createCarrier(...) = ...
}
```

---

## 3. Безопасность и аутентификация

### Двойная аутентификация в Gateway

**Chain 1: Терминалы (mTLS + X.509)**
- Пути: `/api/v1/terminals/**`, `/api/v1/sync/**`
- Аутентификация: клиентский сертификат
- Principal: CN из сертификата (terminalSerial)

**Chain 2: Веб-клиенты (JWT через Keycloak)**
- Все остальные пути
- Аутентификация: Bearer token
- Principal: user_id из JWT

### SecurityConfig.kt
```kotlin
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean @Order(1)
    fun terminalSecurityFilterChain(http: ServerHttpSecurity) = http
        .securityMatcher(ServerWebExchangeMatchers.pathMatchers(
            "/api/v1/terminals/**", "/api/v1/sync/**"))
        .x509 { it.principalExtractor(TerminalPrincipalExtractor()) }
        .build()

    @Bean @Order(2)
    fun webSecurityFilterChain(http: ServerHttpSecurity) = http
        .oauth2ResourceServer { it.jwt { } }
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

**⚠️ Важно:** `X509PrincipalExtractor` находится в пакете `org.springframework.security.web.authentication.preauth.x509`, а не `web.server.authentication`.

---

## 4. Криптография и PKI

### Иерархия CA
```
Root CA (self-signed, 10 лет)
└── Intermediate CA (подписан Root CA, 5 лет)
    ├── Terminal Certificates (1 год)
    ├── Smart Card Certificates (1 год)
    └── Driver Certificates (1 год)
```

### RootCaService.kt — ключевые методы
- `initializeCaHierarchy()` — вызывается при старте
- `generateRootCa()` — генерация self-signed Root CA
- `generateIntermediateCa()` — генерация Intermediate CA
- `signCertificate()` — выпуск end-entity сертификатов

### Root CA хранится в PKCS#12
```yaml
asop:
  crypto:
    root-ca:
      keystore-path: ./data/root-ca.p12
      keystore-password: ${ROOT_CA_KEYSTORE_PASSWORD}
      key-alias: asop-root-ca
      validity-years: 10
      dn: "CN=ASOP Root CA, O=ASOP, C=RU"
```

### Endpoint'ы crypto-service
- `POST /api/v1/terminals/register` — выпуск сертификата терминала
- `POST /api/v1/smart-cards/issue` — выпуск сертификата карты
- `GET /api/v1/terminals/root-ca` — Root CA в PEM
- `GET /api/v1/terminals/root-ca/der` — Root CA в DER
- `GET /api/v1/terminals/root-ca/public-key` — только публичный ключ

**⚠️ Важно:** `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` не существует в Spring 6.1. Использовать строковый литерал `"application/x-pem-file"`.

### Роли смарт-карт
```kotlin
enum class SmartCardRole {
    PASSENGER_ANONYMOUS, PASSENGER_BENEFIT,
    DRIVER, CONTROLLER, DISPATCHER,
    CARRIER_ADMIN, REGION_ADMIN, SUPER_ADMIN,
    DISTRIBUTOR_ADMIN, DISTRIBUTOR_TERMINAL, SERVICE
}
```

### DN-шаблоны для разных ролей
```yaml
smart-card-cert:
  dn-templates:
    DRIVER: "CN={cardId}, OU=DRIVER:{carrierId}, O=ASOP"
    CONTROLLER: "CN={cardId}, OU=CONTROLLER:{carrierId}, O=ASOP"
    PASSENGER_BENEFIT: "CN={cardId}, OU=PASSENGER:{carrierId}, O=ASOP"
    DEFAULT: "CN={cardId}, OU={role}, O=ASOP"
```

---

## 5. База данных

### Ключевые таблицы

**Пользователи:**
- `ASOP_USERS` — пользователи (ПДн защищены)
- `ASOP_USER_ROLES` — роли пользователей
- `ASOP_USER_CARRIERS` — привязка к перевозчикам
- `ASOP_USER_REGIONS` — привязка к регионам

**Перевозчики:**
- `ASOP_CARRIERS` — перевозчики
- `ASOP_CONTRACTS` — договоры
- `ASOP_VEHICLES` — транспортные средства

**Карты:**
- `ASOP_CARDS` — все карты
- `ASOP_CARD_MIFARES` — MIFARE-карты (с PKI полями)
- `ASOP_CARD_TARIFFS` — тарифы на картах
- `ASOP_CARD_BANKS` — банковские карты

**Терминалы:**
- `ASOP_TERMINALS` — терминалы
- `ASOP_DISTRIBUTOR_TERMINALS` — терминалы дистрибьюторов
- `ASOP_TIDS` — пул TID

**Транзакции:**
- `ASOP_SESSIONS` — сессии (иерархические)
- `ASOP_TRANSACTIONS` — финансовые проводки
- `ASOP_CARD_DEBTS` — долги по картам

**КРС:**
- `ASOP_AUDIT_TASKS` — задания на проверки
- `ASOP_AUDIT_BRIGADES` — бригады контролёров
- `ASOP_AUDIT_INSPECTIONS` — акты проверок

### PKI поля в ASOP_CARD_MIFARES
```sql
CARD_ROLE            VARCHAR(30)  -- роль карты
CERTIFICATE_SERIAL   VARCHAR(50)  -- серийник X.509
PUBLIC_KEY_HASH      VARCHAR(64)  -- SHA-256 публичного ключа
KEY_VERSION          INT          -- версия ключа
VALID_FROM           TIMESTAMP    -- начало действия
VALID_UNTIL          TIMESTAMP    -- окончание действия
REVOKED_AT           TIMESTAMP    -- отзыв
LAST_AUTH_AT         TIMESTAMP    -- последняя аутентификация
LAST_AUTH_TERMINAL   UUID         -- терминал последней аутентификации
```

### UUID v7
Генерируется на уровне приложения через `UuidCreator.getTimeOrderedEpoch()`. В БД есть fallback-функция `gen_uuid_v7()`.

---

## 6. Python-скрипты миграции

### diagnose_project.py
Диагностика структуры проекта. Находит контроллеры, DTO, анализирует backend.

### create_api_modules.py
Создаёт структуру API-модулей в `backend/shared/api/`:
- 10 модулей (gateway-api, crypto-api, carrier-api, ...)
- Для каждого: build.gradle.kts, директории, marker-класс

### migrate_dto_to_api.py
Переносит DTO из сервисов в API-модули:
- Меняет package (точное сравнение строк, без regex)
- Обновляет импорты в контроллерах
- Удаляет старые файлы

### migrate_asop_dto.py
Переносит DTO из `asop-dto` в `gateway-api`.

### migrate_crypto_dto.py
Переносит DTO из `crypto-service` в `crypto-api`.

### fix_api_build_gradle_v3.py
Исправляет build.gradle.kts API-модулей:
- Добавляет `platform(libs.spring.boot.dependencies)`
- Добавляет `spring-boot-dependencies` в libs.versions.toml

### find_dto.py
Поиск DTO файлов в проекте.

---

## 7. Gradle конфигурация

### settings.gradle.kts
```kotlin
// ============ Shared modules ============
include(
    ":backend:shared:asop-common",
    ":backend:shared:asop-dto",
    ":backend:shared:asop-kafka-contracts"
)

// ============ Shared API modules ============
include(
    ":backend:shared:api:gateway-api",
    ":backend:shared:api:crypto-api",
    ":backend:shared:api:carrier-api",
    ":backend:shared:api:session-api",
    ":backend:shared:api:terminal-api",
    ":backend:shared:api:card-api",
    ":backend:shared:api:user-api",
    ":backend:shared:api:debt-api",
    ":backend:shared:api:fiscal-api",
    ":backend:shared:api:audit-api"
)

// ============ Backend services ============
include(
    ":backend:gateway-service",
    ":backend:carrier-service",
    // ... остальные сервисы
)
```

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

asop.version=0.1.0-SNAPSHOT
```

**⚠️ Важно:** Без увеличения памяти (`-Xmx4g` для Gradle, `-Xmx2g` для Kotlin daemon) сборка падает с OOM при 20+ модулях.

### libs.versions.toml — ключевые зависимости
```toml
[versions]
kotlin = "2.0.21"
spring-boot = "3.3.5"
bouncy-castle = "1.78.1"

[libraries]
spring-boot-dependencies = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "spring-boot" }
```

---

## 8. Git и .gitignore

### .gitignore
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

### Очистка индекса Git
```powershell
# Удалить build/ из индекса
Get-ChildItem -Path . -Recurse -Directory -Filter "build" | ForEach-Object {
    $relativePath = $_.FullName.Substring((Get-Location).Path.Length + 1) -replace '\\', '/'
    git rm -r --cached "$relativePath/" 2>$null
}

# Удалить сертификаты из индекса
git rm --cached *.p12
git rm --cached *.pem
git rm --cached *.key

# Закоммитить
git add .gitignore
git commit -m "chore: fix .gitignore and remove tracked files from index"
```

---

## 9. Аналитика ролей и bootstrap

### Иерархия ролей
```
SUPER_ADMIN (супер-админ системы)
├── Создаёт перевозчиков
├── Создаёт админов перевозчиков
├── Настраивает регионы, тарифы, льготы
└── Видит всю систему

CARRIER_ADMIN (админ перевозчика)
├── Управляет СВОИМ перевозчиком
├── Создаёт водителей, диспетчеров
├── Управляет ТС и терминалами
└── НЕ видит других перевозчиков

CONTROLLER_ADMIN (админ контролёров)
├── Управляет бригадами КРС
── Создаёт контролёров
└── Работает в пределах региона

DISTRIBUTOR_ADMIN (админ дистрибьютора карт)
├── Управляет точками продаж
├── Управляет платёжными терминалами
└── Видит только свои точки

DISPATCHER (диспетчер)
├── Управляет сменами водителей
├── Назначает ТС на маршруты
── Работает в пределах перевозчика

DRIVER (водитель) — MIFARE-карта + PKI
├── Открывает/закрывает смену
└── НЕ имеет доступа к веб-админке

CONTROLLER (контролёр) — MIFARE-карта + PKI
── Проводит проверки
└── НЕ имеет доступа к веб-админке
```

### Способы аутентификации
| Роль | Способ | Протокол |
|------|--------|----------|
| Веб-роли (админы, диспетчеры) | Keycloak + JWT | HTTPS + Bearer token |
| Физические роли (водители, контролёры) | MIFARE-карта + Challenge-Response | NFC + PKI |
| Пассажиры | MIFARE-карта (UID или PKI) | NFC |

### Bootstrap супер-админа
При первом запуске системы:
1. Docker Compose поднимает PostgreSQL, Keycloak, Kafka, сервисы
2. `user-service` видит пустую БД
3. Запускает `BootstrapService`:
    - Создаёт realm `asop` в Keycloak
    - Создаёт роли (SUPER_ADMIN, CARRIER_ADMIN, ...)
    - Создаёт первого пользователя: `admin@asop.local`
    - Пароль из переменной `BOOTSTRAP_ADMIN_PASSWORD`
4. Супер-админ логинится, меняет пароль

### Переменные окружения для bootstrap
```yaml
user-service:
  environment:
    BOOTSTRAP_ENABLED: "true"
    BOOTSTRAP_ADMIN_EMAIL: "admin@asop.local"
    BOOTSTRAP_ADMIN_PASSWORD: "${ADMIN_PASSWORD}"
    KEYCLOAK_URL: "http://keycloak:8080"
    KEYCLOAK_ADMIN_USER: "admin"
    KEYCLOAK_ADMIN_PASSWORD: "${KEYCLOAK_ADMIN_PASSWORD}"
```

---

## 10. Ключевые уроки

### ❌ Что НЕ работает
1. **PowerShell + regex для рефакторинга кода** — хрупко, ломает форматирование
2. **`MediaType.APPLICATION_PEM_CERTIFICATE_VALUE`** — нет в Spring 6.1, использовать `"application/x-pem-file"`
3. **`X509PrincipalExtractor` из `web.server.authentication`** — неправильный пакет, правильный: `preauth.x509`
4. **`extractPrincipal` возвращает `Mono<Any>`** — интерфейс синхронный, возвращает `Any`
5. **`BOOT_DEPENDENCIES`** — не существует как публичная константа, использовать `platform(libs.spring.boot.dependencies)`
6. **Мало памяти для Gradle** — OOM при 20+ модулях, нужно `-Xmx4g`
7. **Паттерн `.*/` в .gitignore** — игнорирует `.git`, использовать явные правила

### ✅ Что работает
1. **Python для миграций** — надёжнее PowerShell, точное сравнение строк
2. **API-модули в `backend/shared/api/`** — правильное место для контрактов
3. **`platform(libs.spring.boot.dependencies)`** — правильный способ импорта BOM
4. **Разделение на Chain 1 (mTLS) и Chain 2 (JWT)** — чистая архитектура
5. **Root CA генерится автоматически** при первом старте crypto-service
6. **UUID v7** — time-ordered, лучше для индексации чем v4

### 📋 Чеклист для новых модулей
- [ ] Создать API-модуль в `backend/shared/api/{name}-api/`
- [ ] Добавить в `settings.gradle.kts`
- [ ] Создать build.gradle.kts с `platform(libs.spring.boot.dependencies)`
- [ ] Создать API-интерфейс в `controller/`
- [ ] Создать DTO в `dto/request/` и `dto/response/`
- [ ] Добавить зависимость в сервис: `implementation(project(":backend:shared:api:{name}-api"))`
- [ ] Реализовать интерфейс в контроллере сервиса
- [ ] Проверить сборку: `.\gradlew clean build`

---

## 📎 Ссылки на файлы в базе знаний

- `RootCaService.kt` — иерархия CA, выпуск сертификатов
- `RootCaProperties.kt` — конфигурация криптографии
- `SecurityConfig.kt` — двойная аутентификация
- `TerminalCertController.kt` — endpoint'ы для терминалов
- `TerminalCertService.kt` — выпуск сертификатов терминалов
- `CarrierCreateRequest.kt` — DTO создания перевозчика
- `CarrierUpdateRequest.kt` — DTO обновления перевозчика
- `CarrierResponse.kt` — DTO ответа перевозчика
- `TerminalRegisterRequest.kt` — DTO регистрации терминала
- `TerminalResponse.kt` — DTO ответа терминала
- `asop_schema.sql` — полная схема БД
- `.gitignore` — правила игнорирования

---

**Конец документа.**
*Создано: 08 июля 2026*
*Версия: 1.0*