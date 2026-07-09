# TODO — ASOP Platform

## 1. Certificate Architecture: crypto-service как единый CA

### Проблема
Сейчас `gateway-service` использует **self-signed** `gateway-keystore.p12` и `truststore.p12`, 
сгенерированные `keytool` вручную. Это небезопасно и не масштабируется.

### Решение
`crypto-service` должен быть единым источником всех сертификатов:
- Root CA (ECC P-256, Bouncy Castle) — уже реализован, авто-генерация при первом запуске
- Intermediate CA — пересоздаётся при каждом рестарте (MVP)
- Все сертификаты (gateway, terminal, smart-card) выпускаются только через crypto-service

### План

- [ ] **Эндпоинт в crypto-service** `POST /api/v1/servers/register` или `POST /api/v1/gateway/cert`
  - Принимает CSR или параметры (CN, SAN)
  - Возвращает подписанный сертификат + цепочку Intermediate CA + Root CA
  - Сохраняет сертификат в БД или файловое хранилище

- [ ] **Bootstrap gateway** — при старте gateway-service:
  1. Проверить, есть ли валидный сертификат (`/data/gateway.p12`)
  2. Если нет → запросить у crypto-service новый сертификат
  3. Сохранить p12 в volume (`/data/`)
  4. Перезагрузить SSL-контекст (или перезапустить)

- [ ] **Docker volumes**
  - Общий volume `certs` для gateway-service и crypto-service
  - Gateway НЕ хранит сертификаты в JAR (убрать из `src/main/resources/`)
  - `application.yml` переключается на `file:/certs/gateway-keystore.p12`

- [ ] **Truststore для mTLS** (client-auth: want)
  - Собирается из цепочки crypto-service (Root CA + Intermediate CA)
  - Импортируется при bootstrap gateway
  - Позволяет терминалам с сертификатами от crypto-service подключаться к gateway

- [ ] **Добавить self-signed certs в `.gitignore`**
  ```gitignore
  # Certificate files (generated)
  *.p12
  *.jks
  *.cer
  *.crt
  ```
  И удалить из репозитория `gateway-keystore.p12`, `truststore.p12`.

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

- [ ] **Смена пароля**:
  - [ ] Форма смены пароля в профиле
  - [ ] `POST /api/v1/users/password/change`

## 3. Keycloak 25 Совместимость

- [ ] **Password Grant** выключен в Keycloak 25 для новых realm:
  - Ошибка: `"Account is not fully set up"` / `resolve_required_actions`
  - Текущий workaround: Authorization Code flow (PKCE) через `oidc-client-ts`
  - Нужно разобраться, можно ли включить Direct Access Grant через политики

- [ ] **Bootstrap**:
  - `KeycloakAdminService.createUser()` — Keycloak 25 игнорирует `UserRepresentation.credentials`
  - Фикс: создаём пользователя без credentials, затем вызываем `resetPassword()` отдельно
  - ✅ Исправлено

## 4. Liquibase / Миграции

- [ ] **user-service**:
  - `spring-boot-starter-jdbc` добавлен как `runtimeOnly`, но `LiquibaseAutoConfiguration` 
    не срабатывает. Нужно разобраться почему.
  - Текущий workaround: создание таблиц вручную через `psql` в контейнере
  - Возможно, конфликт R2DBC + JDBC

- [ ] **Другие сервисы** нигде не включили Liquibase (`liquibase.enabled=false`)

## 5. Прочее

- [ ] **Dockerfile bug** — во всех 10 Dockerfile `COPY build/libs/*.jar app.jar` 
  копирует `-plain.jar` первой (алфавитно). 
  Исправлено на `COPY build/libs/*-SNAPSHOT.jar app.jar`.
  ✅ Исправлено

- [ ] **Gateway SSL** — `server.ssl.enabled: true` включен всегда. 
  Для dev можно добавить профиль `dev` без SSL.
