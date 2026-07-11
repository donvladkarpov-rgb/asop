# TODO — ASOP Platform

_См. также `infrastructure/docker/todo.md` — задачи по Docker инфраструктуре._

## 1. Certificate Architecture ✅

Всё реализовано: crypto-service — единый CA, сертификаты выпускаются автоматически
через `certs-init` и `provision.sh`. Root CA + Intermediate CA в PKCS#12.

- [x] **Cert signing saga (4 hops)** — реализована через Kafka:
      `Gateway → asop.terminal.cert.commands → crypto-service → asop.terminal.cert.issued → terminal-service → asop.terminal.cert.events → Gateway EventService`
- [x] **`ASOP_TERMINAL_CERTS` таблица** — добавлена в v002 миграцию с UNIQUE partial index `uq_tc_current_per_terminal`
- [x] **`UNIQUE` constraint на `ASOP_TERMINALS.TERMINAL_SERIAL`** — защита от дублей терминалов
- [x] **Android rewrite** — `CertificateService.kt` теперь идёт через Gateway + polling `GET /api/v1/events/{eventId}`

Детали по компрометации ключей — см. `infrastructure/docker/todo.md`.

## 2. Frontend: Admin UI

- [ ] **Страницы**:
  - [ ] `CarriersPage` — список перевозчиков
  - [ ] `SessionsPage` — список смен
  - [ ] `CarrierDetailPage` — просмотр/редактирование перевозчика
  - [ ] `UserDetailPage` — просмотр пользователя

- [ ] **CRUD формы**:
  - [ ] Создание пользователя
  - [ ] Редактирование терминала
  - [ ] Выпуск карты

- [x] **Смена пароля**:
  - [x] Форма смены пароля в профиле
  - [x] `POST /api/v1/users/password/change`

## 3. Keycloak 25 Совместимость

- [ ] **Prod: отключить Direct Access Grant**, оставить только OIDC Auth Code + PKCE
  - Сейчас в MVP используется Direct Access Grant для разработки
  - В production все клиенты должны идти через Authorization Code Flow

- [x] **Bootstrap фикс**:
  - Inline credentials в `UserRepresentation`
  - firstName + lastName обязательны
  - realm с полными настройками
  - ✅ Исправлено

## 4. Shared UserResolver

- [ ] **Создать shared UserResolver в `asop-common`**
  - Интерфейс: `fun resolveUserId(keycloakId: String): Mono<UUID>`
  - Возвращает `userId` (UUID) по keycloakId
  - Кеширование результата (Caffeine)
  - Используется всеми сервисами для resolve keycloakId → userId

## 5. JWT decoder для всех сервисов

- [ ] **Добавить JwtDecoderConfig или permitAll в сервисы:**
  - [ ] carrier-service
  - [x] terminal-service (SecurityConfig использует JWT ресурс-сервер, клиенты mTLS идут через Gateway)
  - [ ] session-service
  - [ ] card-service
  - [ ] debt-service
  - [ ] audit-service
  - [ ] fiscal-service

  Каждый сервис должен иметь либо `JwtDecoderConfig.kt` (как в gateway), либо `SecurityConfig` с `permitAll` (как в user-service).

## 6. Liquibase / Миграции

- [x] **user-service**:
  - Решено: Liquibase запускается отдельным контейнером, сервисы не содержат DataSource.
  - ✅ Не актуально

- [ ] **Другие сервисы** — включить Liquibase после добавления changelogs:
  - [ ] carrier-service
  - [ ] terminal-service
  - [ ] session-service
  - [ ] card-service
  - [ ] debt-service
  - [ ] audit-service
  - [ ] fiscal-service

## 7. PKI / Сертификаты

- [x] **DN encoding bug** — `X500Name(name)` в crypto-service переупорядочивает компоненты DN, PKIX chain validation падает. Фикс: `X500Name.getInstance(ASN1Sequence.getInstance(encoded))`.
- [x] **SAN in certs** — provision.sh всегда передаёт `dnsNames` в JSON при запросе сертификата.
- [ ] **Production SSL**: отключить `defaultConfiguration(NONE)` в gateway WebClient и включить hostname verification.
- [ ] **Production: выпускать сертификаты с SAN из provision.sh** — сейчас gateway работает с отключенной проверкой.

## 8. i18n / Многоязычность

- [ ] **Добавить i18n (react-intl или i18next)**
  - [ ] Создать файлы переводов: ru.json, en.json
  - [ ] Заменить все строки на вызовы `intl.formatMessage()`
  - [ ] Добавить переключатель языка в сайдбар/хедер
  - [ ] Перевести на английский (en.json)
  - [ ] Язык по умолчанию: русский
  - [ ] Сохранять выбор языка в localStorage

## 8. Прочее

- [x] **Dockerfile bug** ✅
- [x] **Gateway SSL всегда включён** ✅ (SSL_ENABLED больше не используется)
- [x] **cert-managed crypto-service** ✅ (через provision.sh entrypoint)
- [ ] **Ключи/сертификаты: план на production**
  - Сейчас в dev: `docker compose down -v` → fresh CA + certs каждый раз
  - В production: ключи будут в HSM / Kubernetes Secrets, не в volumes
  - Нужно разработать `recover-certs.sh` для перевыпуска всех сертификатов
  - Детали: `infrastructure/docker/todo.md`
- [ ] **Скрипты тестовых данных** — `infrastructure/docker/todo.md`
