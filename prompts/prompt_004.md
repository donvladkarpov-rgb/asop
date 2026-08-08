# Промпт 004: 3DES-ключи карт и параметры АСОП

## Принятые решения (по умолчанию, подтверждены пользователем)

- **Вариант Б** доставки ключей: 3DES-ключи едут по существующему mTLS-каналу (в составе дельта/полной выкачки), **без ECIES**. ECIES-вариант А (выделенный ECDH-ключ терминала) отклонён из-за **непортативности** `PURPOSE_AGREE` между OEM-реализациями Android Keystore (TEE/StrongBox) — нет гарантии, что ECDH на неэкспортируемых ключах будет работать на всех терминалах. Для платформы, идущей в автобусы Крыма (возможна закупка разных терминалов), архитектурная зависимость от OEM-фичи недопустима. Вариант Б одинаково работает на всех Android-устройствах.
- **«5 лет»** — серверный фильтр выдачи (оркестратор не отдаёт ключи старше N лет), не терминальный.
- **`ASOP_CONFIG_PARAMS`** — серверная таблица, на терминалы НЕ синкается. Иерархия: base (все scope NULL) → region → organizer → carrier → distributor → krs.
- **Генерация ключа** — через crypto-service `POST /keys/generate` (возвращает зашифрованный blob), не раскрывая plaintext наружу.

---

## 1. Схема БД — `infrastructure/db-migrations/asop_schema.sql`

### 1.1. `ASOP_3DES_KEYS` (глобальный пул ротируемых 3DES-ключей карт)

- `KEY_ID UUID NOT NULL` PRIMARY KEY (UUIDv7)
- `KEY_MATERIAL TEXT NOT NULL` — 3DES-ключ (3K3DES, **24 байта**), зашифрованный **публичным ключом сервера** (base64). В открытом виде в БД не хранится никогда. 24-байтный формат выбран как универсальный — 2K3DES (16 байт) и 2DES (8 байт) корректно обрабатываются 3DES-CBC с 24-байтным ключом (первые 16/8 байт используются, остаток игнорируется Java `DESede`). Для maps с 2K3DES-ключами (как тестовый клон key0) ключ дополняется до 24 байт при записи.
- `CREATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `UPDATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `DELETED_AT TIMESTAMPTZ`
- `VERSION BIGINT`
- Триггеры: soft-delete (одноколоночный), touch-updated, delta-version (по образцу существующих дельта-таблиц).
- Индексы по дельта-столбцам (VERSION и т.д.).

**Правило:** физическое удаление серверных записей **запрещено** — только метка `DELETED_AT`. `PurgeJob` оркестратора должен **исключить** эту таблицу из физического purge-цикла (`SET session_replication_role='replica'` purge не должен её трогать).

### 1.2. `ASOP_CONFIG_PARAMS` (иерархия перекрытия, серверная)

- `PARAM_ID UUID NOT NULL` PRIMARY KEY (UUIDv7)
- `REGION_ID UUID NULL` (FK, nullable)
- `ORGANIZER_ID UUID NULL` (FK, nullable)
- `CARRIER_ID UUID NULL` (FK, nullable)
- `DISTRIBUTOR_ID UUID NULL` (FK, nullable)
- `KRS_ID UUID NULL` (FK, nullable)
- `PARAMS JSONB NOT NULL`
- `CREATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `UPDATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `DELETED_AT TIMESTAMPTZ`
- `VERSION BIGINT`

**Правило разрешения:** «базовая» запись = все 5 scope-колонок NULL. Параметры строки с более глубоким scope перекрывают нижние по цепочке: base → region → organizer → carrier → distributor → krs. Перекрываются только те ключи JSONB, которые указаны; отсутствующие берутся из нижнего уровня.

---

## 2. crypto-service — мастер серверного ключа

- Новый **выделенный ключ шифрования данных** (persist в `./data/server-enc-key.p12`, по образцу Root/Intermediate CA), не связан с CA и TLS-ключами.
- Эндпоинты (в crypto-service всё `permitAll`):
  - `GET /api/v1/keys/public` → серверный публичный ключ (для шифрования при вставке записей).
  - `POST /api/v1/keys/decrypt` `{cipherBase64}` → открытые 24 байта. Используется оркестратором перед отправкой на терминал.
  - `POST /api/v1/keys/generate` → генерирует случайный 24-байтный 3DES-ключ, возвращает зашифрованный blob (для кнопки «Добавить» в админке и для шедулера).
- **Dev-режим (п.8 промпта):** конфиг `asop.dev-3des-key.enabled`. Когда включён — `generate` всегда возвращает **один заранее зафиксированный ключ** (сгенерировать один раз при реализации, **задокументировать** в AGENTS.md/doc). В проде — только случайная генерация. Это экономит дорогие карты при отладке.

---

## 3. admin-service — хостинг обеих таблиц + REST

- Дельта: `GET /api/v1/3des-keys/delta` через `DeltaSupport` (versionSince/includeDeleted/limit).
- CRUD `3des-keys`:
  - `GET /api/v1/3des-keys` — список, `KEY_MATERIAL` возвращается **зашифрованным**.
  - `POST /api/v1/3des-keys` — вызывает crypto `generate`, вставляет запись.
  - `DELETE /api/v1/3des-keys/{id}` — метит `DELETED_AT` (soft delete).
- CRUD `config-params`: GET/POST/PUT/DELETE (soft).
- **`GET /api/v1/config-params/base`** — возвращает base-строку (scope=NULL) `ASOP_CONFIG_PARAMS` в JSON. Используется оркестратором для чтения параметров шедулера и фильтра «5 лет». Без аргументов, без auth (внутреннее доверие, как `/delta`).
- Gateway `ServiceRegistry`: добавить `three-des-keys` → admin-service:8091, `config-params` → admin-service:8091.

---

## 4. orchestrator — доставка + шедулер

- `MasterRegistry`: `asop_3des_keys` → admin `3des-keys`, поместить в `GLOBAL_TABLES` (без фильтра carrier/region — глобальный пул на все терминалы, подтверждено пользователем).
- **Proto** (обе копии `schema.proto` — backend и android — **идентичны**): новый message и файл:
  ```protobuf
  message Asop3desKeysRow {
    string key_id = 1;          // UUIDv7
    bytes  key_material = 2;    // plaintext 24 байта (после decrypt сервером)
    int64  created_at = 3;      // epoch millis
    int64  deleted_at = 4;      // epoch millis, 0 если не удалён
    int64  version = 5;         // delta watermark
  }
  message Asop3desKeysFile {
    repeated Asop3desKeysRow rows = 1;
  }
  ```
  Добавить `Asop3desKeysFile` в `DeltaChunk` (по образцу других `*File` message'ей).
- `DeltaSyncService.fetchTable` и `FullSyncService`: для `asop_3des_keys` — спец-трансформ: blob (зашифрованный серверным ключом) → crypto `decrypt` → plaintext 24 байта в proto-поле `key_material`. Отправка plaintext'ом **по mTLS** (вариант Б), per-terminal шифрование не используется.
- **Серверный фильтр «5 лет»:** в выгрузку включаются только ключи с `CREATED_AT >= now() - N лет`, где N настраивается в base-конфиге. Применяется и к дельте, и к полной выкачке. Оркестратор читает base-конфиг через `GET /api/v1/config-params/base` (admin-service) при каждом запуске дельта/полной выкачки.
- **Шедулер** (`SchedulingConfigurer`, динамический cron): периодически вызывает crypto `generate` → вставляет новую запись в `ASOP_3DES_KEYS` (через admin-service `POST /api/v1/3des-keys`). Параметры (cron-шаблон, `enabled` start/stop) читаются из **base-строки** `config-params` (scope=NULL) через `GET /api/v1/config-params/base` с дефолтами в `application.yml` оркестратора. Cron перечитывается из БД при каждом срабатывании; `enabled=false` останавливает шедулер.
- **`PurgeJob`:** исключить `asop_3des_keys` из физического purge (см. п.1.1). В `PurgeJob.runPurge` — фильтр `MasterRegistry.ALL.keys.filter { it != "asop_3des_keys" }`.

---

## 5. Терминал (Android, вариант Б)

- Новая Room-сущность `TerminalKeyEntity(KEY_ID PK, KEY_MATERIAL_ENC TEXT, CREATED_AT, DELETED_AT, VERSION)`, `AppDatabase` → **v5** (текущая v4 → v5; `fallbackToDestructiveMigration()` уже включён). **Не** использовать `reference_rows` (иначе ломается глобальный водяной знак `maxVersion()`).
- В `ReferenceSyncStore.applyChunk/applyFile` — ветка по `tableName == "asop_3des_keys"`: переписать в новую сущность, при приёме **перешифровать локальным Keystore-AES-ключом** (один ключ на все записи, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, неэкспортируемый, аппаратно защищённый в TEE). Хранить только зашифрованное значение.
- При карточных операциях — терминал просит Keystore расшифровать в памяти; ключ наружу не выходит.
- **Физическое удаление** локальных записей с `DELETED_AT` (в отличие от soft-фильтра в `reference_rows`).
- Порядок по `KEY_ID` (UUIDv7, time-ordered): **DESC** — первый в списке = самый свежий. Применение к MIFARE-картам последовательно — **последующим промптом**, здесь только хранение.

---

## 6. web-admin

- Меню: **«Ключи 3des»** (`/three-des-keys`) и **«Параметры АСОП»** (`/config-params`) — `Sidebar.tsx` + маршруты в `App.tsx`.
- `ThreeDesKeysPage`: таблица (показывать зашифрованные ключи), кнопка «Добавить» (ручная генерация через сервер), кнопка «Удалить» (метит `DELETED_AT`). Sync-axios, паттерн `CardsDistributors.tsx`.
- `ConfigParamsPage`: редактирование строк иерархии (base + scopes), JSONB через textarea (паттерн `Contracts.tsx`).
- Типы/API вручную: `types/reference.ts`, `api/threeDesKeys.ts`, `api/configParams.ts`.

---

## 7. Миграция

- DDL редактируется в `infrastructure/db-migrations/asop_schema.sql` (первичный источник), затем копируется в `infrastructure/db-migrations/migrations/v001-init.sql` (исполняемый Liquibase changeset). Оба SQL-файла идентичны. Перегенерировать `v001-init.yaml` не нужно (Liquibase changeset ссылается на sqlFile, содержимое которого меняется — checksum mismatch лечится `down -v`).
- Deploy: `docker compose down -v` (полное пересоздание БД, т.к. изменён v001 → checksum mismatch).

---

## 8. Порядок реализации

1. DDL в `asop_schema.sql` + перегенерация миграции → `down -v`.
2. crypto-service: выделенный ключ + эндпоинты (`/keys/public`, `/keys/decrypt`, `/keys/generate`) + dev-ключ (сгенерировать и задокументировать).
3. admin-service: дельта + CRUD обеих таблиц; gateway `ServiceRegistry`.
4. Proto ×2 → `MasterRegistry` → decrypt-трансформ → шедулер + исключение из `PurgeJob`.
5. Android: сущность v5 + ветка в `ReferenceSyncStore` + Keystore-AES + физический purge.
6. web-admin: две страницы + меню + маршруты.

## 9. Факты из кодовой базы (учесть при реализации)

- `ASOP_TERMINAL_CERTS.CERT_DATA` — формат хранения сертификатов терминала (PEM vs Base64(DER)). Для варианта Б парсинг сертификатов на оркестраторе не требуется — используется только `terminalId` для адресации. Если позже понадобится извлечение публичного ключа терминала из сертификата — верифицировать формат отдельно.
- Terminal async command flow: все `/api/v1/sync/**` за mTLS (Chain Order 1) — дельта и полная выкачка уже защищены.
- Gateway → MinIO (S3) идёт по внутреннему plain HTTP (`http://minio:9000`), терминал в MinIO напрямую не ходит.
- `DeltaChunk` сериализация — рефлексия по дескриптору (`chunkDescriptor.findFieldByName(table)`), без per-table Kotlin кода. Новые message'ы (`Asop3desKeysFile`) автоматически подхватываются.
- Таблицы в дельте должны быть синхронно добавлены: `schema.proto` ×2, `MasterRegistry`, `/delta` на мастер-сервисе, триггеры/индексы в DDL.
- Текущая версия `AppDatabase` = **v4** (не v3, как в AGENTS.md — устарело). Новая миграция → v5.
- `PurgeJob.runPurge` (`PurgeJob.kt:46`) итерирует `MasterRegistry.ALL.keys` — для исключения `asop_3des_keys` нужен `.filter { it != "asop_3des_keys" }` в этой строке.
