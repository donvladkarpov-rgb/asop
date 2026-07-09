# Полная документация схемы базы данных АСОП

> **Версия:** Финальная (с модулями фискализации и долгов)  
> **СУБД:** PostgreSQL 14+ с расширением PostGIS  
> **Всего таблиц:** 67  
> **Представлений (VIEW):** 1  
> **Функций:** 6  
> **Первичные ключи:** UUID (генерируются на уровне приложения)

---

## 📑 Оглавление

### [0. Регионы и Территории (ФИАС/ГАР)](#0-регионы-и-территории-фиасгар)
* [`ASOP_REGIONS`](#asop_regions)
* [`ASOP_TERRITORIES`](#asop_territories)
* [`ASOP_ORGANIZERS`](#asop_organizers)
* [`ASOP_ORGANIZER_TERRITORIES`](#asop_organizer_territories)

### [1. Справочники](#1-справочники)
* [`ASOP_ROLES`](#asop_roles)
* [`ASOP_CARD_TYPES`](#asop_card_types)
* [`ASOP_TARIFF_TYPES`](#asop_tariff_types)
* [`ASOP_SESSION_TYPES`](#asop_session_types)
* [`ASOP_EVENT_TYPES`](#asop_event_types)
* [`ASOP_TRANSACTION_TYPES`](#asop_transaction_types)
* [`ASOP_TRANSACTION_RESULTS`](#asop_transaction_results)
* [`ASOP_SERVICES`](#asop_services)
* [`ASOP_BENEFITS`](#asop_benefits)
* [`ASOP_BENEFIT_STEPS`](#asop_benefit_steps)

### [1.1. Дистрибьюторы карт](#11-дистрибьюторы-карт)
* [`ASOP_CARDS_DISTRIBUTORS`](#asop_cards_distributors)

### [2. Перевозчики, Договоры и ТС](#2-перевозчики-договоры-и-тс)
* [`ASOP_CARRIERS`](#asop_carriers)
* [`ASOP_CONTRACTS`](#asop_contracts)
* [`ASOP_CONTRACT_ROUTES`](#asop_contract_routes)
* [`ASOP_VEHICLE_TYPES`](#asop_vehicle_types)
* [`ASOP_VEHICLE_MODELS`](#asop_vehicle_models)
* [`ASOP_VEHICLES`](#asop_vehicles)

### [3. Пользователи и Безопасность](#3-пользователи-и-безопасность)
* [`ASOP_USERS`](#asop_users)
* [`ASOP_USER_ROLES`](#asop_user_roles)
* [`ASOP_USER_CARRIERS`](#asop_user_carriers)
* [`ASOP_USER_REGIONS`](#asop_user_regions)

### [4. Маршруты и Пути](#4-маршруты-и-пути)
* [`ASOP_FARE_ZONES`](#asop_fare_zones)
* [`ASOP_TRANSPORT_STOPS`](#asop_transport_stops)
* [`ASOP_ROUTES`](#asop_routes)
* [`ASOP_PATHS`](#asop_paths)
* [`ASOP_PATH_TRANSPORT_STOPS`](#asop_path_transport_stops)
* [`ASOP_SCHEDULE`](#asop_schedule)
* [`ASOP_PATH_SERVICES`](#asop_path_services)
* [`ASOP_PATH_DISCOUNTS`](#asop_path_discounts)
* [`ASOP_PATH_BENEFITS`](#asop_path_benefits)

### [5. Карты, Льготы, Тарифы](#5-карты-льготы-тарифы)
* [`ASOP_CARDS`](#asop_cards)
* [`ASOP_CARD_MIFARES`](#asop_card_mifares)
* [`ASOP_CARD_BANKS`](#asop_card_banks)
* [`ASOP_CARD_TARIFFS`](#asop_card_tariffs)
* [`ASOP_BLACKLISTS`](#asop_blacklists)
* [`ASOP_USER_BENEFITS`](#asop_user_benefits)
* [`ASOP_TARIFF_RATES`](#asop_tariff_rates)

### [5.1. Долги по картам](#51-долги-по-картам)
* [`ASOP_CARD_DEBTS`](#asop_card_debts)
* [`ASOP_DEBT_RECOVERY_ATTEMPTS`](#asop_debt_recovery_attempts)
* [`V_ACTIVE_CARD_DEBTS`](#v_active_card_debts) *(VIEW)*

### [6. Оборудование: Терминалы, TID, Профили, ПО](#6-оборудование-терминалы-tid-профили-по)
* [`ASOP_TERMINAL_PROFILES`](#asop_terminal_profiles)
* [`ASOP_TERMINAL_SOFTWARE`](#asop_terminal_software)
* [`ASOP_DISTRIBUTOR_TERMINALS`](#asop_distributor_terminals)
* [`ASOP_TIDS`](#asop_tids)
* [`ASOP_TERMINALS`](#asop_terminals)

### [7. Сессии, Транзакции, Аудит, КРС](#7-сессии-транзакции-аудит-крс)
* [`ASOP_SESSIONS`](#asop_sessions)
* [`ASOP_AUDIT_SERVICES`](#asop_audit_services)
* [`ASOP_AUDIT_TASKS`](#asop_audit_tasks)
* [`ASOP_AUDIT_TASK_PATHS`](#asop_audit_task_paths)
* [`ASOP_AUDIT_BRIGADES`](#asop_audit_brigades)
* [`ASOP_AUDIT_BRIGADE_MEMBERS`](#asop_audit_brigade_members)
* [`ASOP_AUDIT_INSPECTIONS`](#asop_audit_inspections)
* [`ASOP_AUDIT_INSPECTION_TASKS`](#asop_audit_inspection_tasks)
* [`ASOP_TRANSACTIONS`](#asop_transactions)
* [`ASOP_TRANSACTION_CARDS`](#asop_transaction_cards)
* [`ASOP_PAYMENTS`](#asop_payments)
* [`ASOP_GPS_TRACKING`](#asop_gps_tracking)
* [`ASOP_EVENTS`](#asop_events)

### [8. Модуль Фискализации](#8-модуль-фискализации)
* [`ASOP_FISCALIZERS`](#asop_fiscalizers)
* [`ASOP_CARRIER_FISCALIZERS`](#asop_carrier_fiscalizers)
* [`ASOP_FISCALIZER_TOKENS`](#asop_fiscalizer_tokens)
* [`ASOP_FISCAL_RECEIPTS`](#asop_fiscal_receipts)
* [`ASOP_FISCAL_RECEIPT_ATTEMPTS`](#asop_fiscal_receipt_attempts)
* [`ASOP_FISCAL_MONTHLY_REPORTS`](#asop_fiscal_monthly_reports)

### [9. Функции БД](#9-функции-бд)
* [`fn_get_active_fiscal_token`](#fn_get_active_fiscal_token)
* [`fn_calculate_next_retry`](#fn_calculate_next_retry)
* [`fn_create_card_debt`](#fn_create_card_debt)
* [`fn_recover_card_debt`](#fn_recover_card_debt)
* [`fn_expire_overdue_debts`](#fn_expire_overdue_debts)
* [`fn_calculate_debt_recovery_retry`](#fn_calculate_debt_recovery_retry)

---

## 0. Регионы и Территории (ФИАС/ГАР)

<a id="asop_regions"></a>
### `ASOP_REGIONS`
Справочник регионов на основе данных ФИАС/ГАР.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `REGION_ID` | UUID | PK | Первичный ключ региона |
| `MUNICIPAL_DIVISION` | VARCHAR(255) | | Муниципальное деление |
| `ADMIN_DIVISION` | VARCHAR(255) | | Административно-территориальное деление |
| `FEDERAL_DISTRICT` | VARCHAR(255) | | Федеральный округ |
| `IFNS_FL_CODE` | VARCHAR(4) | | Код ИФНС ФЛ |
| `IFNS_UL_CODE` | VARCHAR(4) | | Код ИФНС ЮЛ |
| `OKATO_CODE` | VARCHAR(11) | | Код ОКАТО |
| `OKTMO_CODE` | VARCHAR(11) | | Код ОКТМО |
| `OKTMO_BUDGET_CODE` | VARCHAR(11) | | Код ОКТМО бюджетополучателя |
| `FIAS_ID` | VARCHAR(36) | UNIQUE | Уникальный номер в ГАР (ID FIAS) |
| `REGISTRY_RECORD_ID` | VARCHAR(30) | | Уникальный номер реестровой записи |

[↑ Наверх](#-оглавление)

<a id="asop_territories"></a>
### `ASOP_TERRITORIES`
Административно-территориальные единицы с гео-полигонами и реквизитами ФИАС.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TERRITORY_ID` | UUID | PK | Первичный ключ территории |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |
| `MUNICIPAL_DIVISION` | VARCHAR(255) | | Муниципальное деление |
| `ADMIN_DIVISION` | VARCHAR(255) | | Административно-территориальное деление |
| `FEDERAL_DISTRICT` | VARCHAR(255) | | Федеральный округ |
| `IFNS_FL_CODE` | VARCHAR(4) | | Код ИФНС ФЛ |
| `IFNS_UL_CODE` | VARCHAR(4) | | Код ИФНС ЮЛ |
| `OKATO_CODE` | VARCHAR(11) | | Код ОКАТО |
| `OKTMO_CODE` | VARCHAR(11) | | Код ОКТМО |
| `OKTMO_BUDGET_CODE` | VARCHAR(11) | | Код ОКТМО бюджетополучателя |
| `FIAS_ID` | VARCHAR(36) | UNIQUE | Уникальный номер в ГАР |
| `REGISTRY_RECORD_ID` | VARCHAR(30) | | Уникальный номер реестровой записи |
| `GEO_POLYGON` | GEOGRAPHY(POLYGON, 4326) | | Географический полигон территории |

[↑ Наверх](#-оглавление)

<a id="asop_organizers"></a>
### `ASOP_ORGANIZERS`
Организаторы перевозок.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `ORGANIZER_ID` | UUID | PK | Первичный ключ |
| `ORGANIZER_NAME` | VARCHAR(255) | NOT NULL | Наименование |

[↑ Наверх](#-оглавление)

<a id="asop_organizer_territories"></a>
### `ASOP_ORGANIZER_TERRITORIES`
Связь Многие-ко-многим: Организаторы ↔ Территории.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `ORGANIZER_ID` | UUID | PK, FK → ASOP_ORGANIZERS | Идентификатор организатора |
| `TERRITORY_ID` | UUID | PK, FK → ASOP_TERRITORIES | Идентификатор территории |

[↑ Наверх](#-оглавление)

---

## 1. Справочники

<a id="asop_roles"></a>
### `ASOP_ROLES`
Роли доступа в системе.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `ROLE_ID` | UUID | PK | Первичный ключ роли |
| `ROLE_NAME` | VARCHAR(255) | NOT NULL | Наименование роли |

[↑ Наверх](#-оглавление)

<a id="asop_card_types"></a>
### `ASOP_CARD_TYPES`
Типы провозных носителей.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARD_TYPE_ID` | UUID | PK | Первичный ключ |
| `CARD_TYPE_NAME` | VARCHAR(255) | NOT NULL | Название типа карты |

[↑ Наверх](#-оглавление)

<a id="asop_tariff_types"></a>
### `ASOP_TARIFF_TYPES`
Типы тарифов.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TARIFF_TYPE_ID` | UUID | PK | Первичный ключ |
| `CODE` | VARCHAR(50) | UNIQUE, NOT NULL | Системный код |
| `NAME` | VARCHAR(100) | NOT NULL | Наименование |
| `DESCRIPTION` | TEXT | | Описание |

[↑ Наверх](#-оглавление)

<a id="asop_session_types"></a>
### `ASOP_SESSION_TYPES`
Типы рабочих сессий.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `SESSION_TYPE_ID` | UUID | PK | Первичный ключ |
| `SESSION_TYPE_CODE` | VARCHAR(30) | UNIQUE, NOT NULL | Код (DRIVER_SHIFT, PASSENGER_TRIP, KRS_AUDIT) |
| `SESSION_TYPE_NAME` | VARCHAR(100) | NOT NULL | Название |

[↑ Наверх](#-оглавление)

<a id="asop_event_types"></a>
### `ASOP_EVENT_TYPES`
Типы системных событий для журнала аудита.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `EVENT_TYPE` | CHAR(4) | PK | Системный код (CUSR, TPAY, SOPN) |
| `EVENT_TYPE_NAME` | VARCHAR(128) | NOT NULL | Название |

[↑ Наверх](#-оглавление)

<a id="asop_transaction_types"></a>
### `ASOP_TRANSACTION_TYPES`
Типы финансовых операций.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TRANSACTION_TYPE_ID` | UUID | PK | Первичный ключ |
| `TRANSACTION_TYPE_NAME` | VARCHAR(255) | NOT NULL | Название |

[↑ Наверх](#-оглавление)

<a id="asop_transaction_results"></a>
### `ASOP_TRANSACTION_RESULTS`
Результаты выполнения транзакций.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TRANSACTION_RESULT_ID` | UUID | PK | Первичный ключ |
| `TRANSACTION_RESULT_NAME` | VARCHAR(255) | NOT NULL | Название результата |

[↑ Наверх](#-оглавление)

<a id="asop_services"></a>
### `ASOP_SERVICES`
Классификатор платных услуг ("Услуга" в чеке).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `SERVICE_ID` | UUID | PK | Первичный ключ |
| `SERVICE_NAME` | VARCHAR(100) | NOT NULL | Название услуги |
| `DESCRIPTION` | TEXT | | Описание |
| `PRIORITY` | INT | UNIQUE, NOT NULL | Уникальный приоритет |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |

[↑ Наверх](#-оглавление)

<a id="asop_benefits"></a>
### `ASOP_BENEFITS`
Справочник льготных категорий.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `BENEFIT_ID` | UUID | PK | Первичный ключ |
| `BENEFIT_CODE` | VARCHAR(50) | UNIQUE, NOT NULL | Код льготы |
| `BENEFIT_NAME` | VARCHAR(100) | NOT NULL | Название |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |
| `DESCRIPTION` | TEXT | | Описание |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_benefit_steps"></a>
### `ASOP_BENEFIT_STEPS`
Шаги накопительных льгот.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `STEP_ID` | UUID | PK | Первичный ключ |
| `BENEFIT_ID` | UUID | FK → ASOP_BENEFITS, NOT NULL, CASCADE | Льгота |
| `STEP_ORDER` | INT | NOT NULL | Порядок шага |
| `TRIP_THRESHOLD_FROM` | INT | DEFAULT 0, NOT NULL | Нижняя граница поездок |
| `TRIP_THRESHOLD_TO` | INT | | Верхняя граница поездок |
| `DISCOUNT_SHARE` | NUMERIC(4,2) | NOT NULL, CHECK 0-1 | Доля скидки |
| `PERIOD_TYPE` | VARCHAR(20) | DEFAULT 'MONTHLY', CHECK (DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY) | Тип периода |

[↑ Наверх](#-оглавление)

---

## 1.1. Дистрибьюторы карт

<a id="asop_cards_distributors"></a>
### `ASOP_CARDS_DISTRIBUTORS`
Юрлица-дистрибьюторы карт, которые могут пополнять MIFARE-карты через свои платёжные терминалы.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARDS_DISTRIBUTOR_ID` | UUID | PK | Первичный ключ |
| `DISTRIBUTOR_NAME` | VARCHAR(255) | NOT NULL | Наименование дистрибьютора |
| `INN` | VARCHAR(12) | UNIQUE, NOT NULL | ИНН организации |
| `KPP` | VARCHAR(9) | | КПП организации |
| `LEGAL_ADDRESS` | VARCHAR(500) | | Юридический адрес |
| `CONTACT_PHONE` | VARCHAR(20) | | Контактный телефон |
| `CONTACT_EMAIL` | VARCHAR(100) | | Контактный email |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

---

## 2. Перевозчики, Договоры и ТС

<a id="asop_carriers"></a>
### `ASOP_CARRIERS`
Перевозчики (транспортные компании, ГУП, ИП).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARRIER_ID` | UUID | PK | Первичный ключ |
| `CARRIER_NAME` | VARCHAR(255) | NOT NULL | Наименование |
| `INN` | VARCHAR(12) | UNIQUE, NOT NULL | ИНН организации |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |

[↑ Наверх](#-оглавление)

<a id="asop_contracts"></a>
### `ASOP_CONTRACTS`
Общий справочник договоров с контрагентами (перевозчиками или дистрибьюторами карт).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CONTRACT_ID` | UUID | PK | Первичный ключ |
| `CONTRACTOR_TYPE` | VARCHAR(20) | NOT NULL, CHECK (CARRIER, CARDS_DISTRIBUTOR) | Тип контрагента |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS | Перевозчик (если CONTRACTOR_TYPE='CARRIER') |
| `CARDS_DISTRIBUTOR_ID` | UUID | FK → ASOP_CARDS_DISTRIBUTORS | Дистрибьютор (если CONTRACTOR_TYPE='CARDS_DISTRIBUTOR') |
| `CONTRACT_NUMBER` | VARCHAR(100) | NOT NULL | Номер договора |
| `START_DATE` | DATE | NOT NULL | Дата начала действия |
| `END_DATE` | DATE | | Дата окончания действия |
| `STATUS` | VARCHAR(20) | DEFAULT 'ACTIVE', CHECK (DRAFT, ACTIVE, SUSPENDED, TERMINATED) | Статус |
| `COMMISSION_PERCENT` | NUMERIC(5,2) | CHECK (0-100) | Комиссия АСОП в процентах |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_contract_routes"></a>
### `ASOP_CONTRACT_ROUTES`
Связь договора с маршрутами из справочника.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CONTRACT_ID` | UUID | PK, FK → ASOP_CONTRACTS | Договор |
| `ROUTE_ID` | UUID | PK, FK → ASOP_ROUTES | Маршрут |

[↑ Наверх](#-оглавление)

<a id="asop_vehicle_types"></a>
### `ASOP_VEHICLE_TYPES`
Справочник типов транспортных средств.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `VEHICLE_TYPE_ID` | UUID | PK | Первичный ключ |
| `TYPE_NAME` | VARCHAR(100) | NOT NULL | Название типа |

[↑ Наверх](#-оглавление)

<a id="asop_vehicle_models"></a>
### `ASOP_VEHICLE_MODELS`
Справочник моделей транспортных средств.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `VEHICLE_MODEL_ID` | UUID | PK | Первичный ключ |
| `MODEL_NAME` | VARCHAR(255) | NOT NULL | Название модели |

[↑ Наверх](#-оглавление)

<a id="asop_vehicles"></a>
### `ASOP_VEHICLES`
Транспортные средства.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `VEHICLE_ID` | UUID | PK | Первичный ключ |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS, NOT NULL | Перевозчик |
| `VEHICLE_TYPE_ID` | UUID | FK → ASOP_VEHICLE_TYPES, NOT NULL | Тип ТС (справочник) |
| `VEHICLE_MODEL_ID` | UUID | FK → ASOP_VEHICLE_MODELS, NOT NULL | Модель ТС (справочник) |
| `VEHICLE_NUMBER` | VARCHAR(16) | NOT NULL | ГРЗ (гос. регистрационный знак) |
| `VEHICLE_NAME` | VARCHAR(255) | NOT NULL | Внутреннее наименование |

[↑ Наверх](#-оглавление)

---

## 3. Пользователи и Безопасность

<a id="asop_users"></a>
### `ASOP_USERS`
Пользователи системы. ПДн защищены: СНИЛС хэшируется и шифруется, ФИО сокращено, аутентификация через Keycloak.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `USER_ID` | UUID | PK | Первичный ключ |
| `FIRST_NAME` | VARCHAR(100) | NOT NULL | Имя |
| `LAST_NAME_INITIAL` | CHAR(1) | NOT NULL | Первая буква фамилии |
| `PATRONYMIC_INITIAL` | CHAR(1) | | Первая буква отчества |
| `PHONE` | VARCHAR(20) | | Телефон |
| `SNILS_HASH` | VARCHAR(64) | UNIQUE | Хэш СНИЛС (для проверки уникальности) |
| `SNILS_ENCRYPTED` | BYTEA | | Зашифрованное значение СНИЛС |
| `KEYCLOAK_ID` | VARCHAR(255) | UNIQUE | Идентификатор в Keycloak |

[↑ Наверх](#-оглавление)

<a id="asop_user_roles"></a>
### `ASOP_USER_ROLES`
Связь пользователей и ролей (Многие-ко-многим).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `USER_ID` | UUID | PK, FK → ASOP_USERS | Пользователь |
| `ROLE_ID` | UUID | PK, FK → ASOP_ROLES | Роль |

[↑ Наверх](#-оглавление)

<a id="asop_user_carriers"></a>
### `ASOP_USER_CARRIERS`
Связь пользователей и перевозчиков (Многие-ко-многим).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `USER_ID` | UUID | PK, FK → ASOP_USERS | Пользователь |
| `CARRIER_ID` | UUID | PK, FK → ASOP_CARRIERS | Перевозчик |

[↑ Наверх](#-оглавление)

<a id="asop_user_regions"></a>
### `ASOP_USER_REGIONS`
Связь пользователей и регионов (Многие-ко-многим).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `USER_ID` | UUID | PK, FK → ASOP_USERS | Пользователь |
| `REGION_ID` | UUID | PK, FK → ASOP_REGIONS | Регион |

[↑ Наверх](#-оглавление)

---

## 4. Маршруты и Пути

<a id="asop_fare_zones"></a>
### `ASOP_FARE_ZONES`
Тарифные зоны.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `ZONE_ID` | UUID | PK | Первичный ключ |
| `ZONE_CODE` | VARCHAR(20) | UNIQUE, NOT NULL | Код зоны |
| `ZONE_NAME` | VARCHAR(100) | NOT NULL | Название |
| `DESCRIPTION` | VARCHAR(256) | | Описание |
| `ZONE_POLYGON` | GEOGRAPHY(POLYGON, 4326) | | Гео-полигон зоны |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |

[↑ Наверх](#-оглавление)

<a id="asop_transport_stops"></a>
### `ASOP_TRANSPORT_STOPS`
Справочник остановок общественного транспорта.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `STOP_ID` | UUID | PK | Первичный ключ |
| `FARE_ZONE_ID` | UUID | FK → ASOP_FARE_ZONES | Тарифная зона |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |
| `STOP_CODE` | VARCHAR(20) | UNIQUE, NOT NULL | Код остановки |
| `STOP_NAME` | VARCHAR(200) | NOT NULL | Название |
| `STOP_ADDRESS` | VARCHAR(500) | | Адрес |
| `ZONE_POLYGON` | GEOGRAPHY(POLYGON, 4326) | | Гео-полигон зоны остановки |
| `DESCRIPTION` | TEXT | | Описание |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_routes"></a>
### `ASOP_ROUTES`
Справочник маршрутов (номер, название, категория).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `ROUTE_ID` | UUID | PK | Первичный ключ |
| `ROUTE_NUMBER` | VARCHAR(50) | NOT NULL | Номер маршрута |
| `ROUTE_NAME` | VARCHAR(255) | NOT NULL | Название маршрута |
| `ORGANIZER_ID` | UUID | FK → ASOP_ORGANIZERS | Организатор |
| `MINISTRY_REGISTRY_NO` | VARCHAR(50) | | Номер в реестре Минтранса |
| `ROUTE_CATEGORY` | VARCHAR(30) | CHECK (CITY, SUBURBAN, INTERCITY, EXPRESS) | Категория |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |

[↑ Наверх](#-оглавление)

<a id="asop_paths"></a>
### `ASOP_PATHS`
Физические пути (направления) маршрута с начальной и конечной остановками.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `PATH_ID` | UUID | PK | Первичный ключ |
| `ROUTE_ID` | UUID | FK → ASOP_ROUTES, NOT NULL | Ссылка на справочник маршрутов |
| `PATH_NAME` | VARCHAR(100) | NOT NULL | Название пути (напр., "Прямой", "Обратный") |
| `START_STOP_ID` | UUID | FK → ASOP_TRANSPORT_STOPS | Начальная остановка |
| `END_STOP_ID` | UUID | FK → ASOP_TRANSPORT_STOPS | Конечная остановка |
| `ROUTE_OBJECT` | JSONB | | JSON-геометрия/конфиг пути |
| `BENEFIT_POLICY` | VARCHAR(20) | DEFAULT 'ALL', CHECK (ALL, ALLOWLIST, NONE) | Политика льгот |
| `PATH_START_DATE` | TIMESTAMP | | Дата начала действия |
| `PATH_END_DATE` | TIMESTAMP | | Дата окончания действия |
| `DESCRIPTION` | VARCHAR(512) | | Описание |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |

[↑ Наверх](#-оглавление)

<a id="asop_path_transport_stops"></a>
### `ASOP_PATH_TRANSPORT_STOPS`
Связь Пути и Остановки (порядок следования).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `PATH_STOP_ID` | UUID | PK | Первичный ключ |
| `PATH_ID` | UUID | FK → ASOP_PATHS, NOT NULL | Путь |
| `STOP_ID` | UUID | FK → ASOP_TRANSPORT_STOPS, NOT NULL | Остановка |
| `SERIAL_NUMBER` | INT | NOT NULL | Порядковый номер |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_schedule"></a>
### `ASOP_SCHEDULE`
Расписание прибытия на остановки по дням недели.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `SCHEDULE_ID` | UUID | PK | Первичный ключ |
| `PATH_ID` | UUID | FK → ASOP_PATHS, NOT NULL, CASCADE | Путь |
| `STOP_ID` | UUID | FK → ASOP_TRANSPORT_STOPS, NOT NULL | Остановка |
| `DAY_MASK` | INT | DEFAULT 127, CHECK 1-127 | Маска дней недели |
| `ARRIVAL_TIME` | TIME | NOT NULL | Время прибытия |
| `DWELL_TIME_SEC` | INT | DEFAULT 30 | Время стоянки (сек) |
| `REGION_ID` | UUID | FK → ASOP_REGIONS, NOT NULL | Привязка к региону |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |

[↑ Наверх](#-оглавление)

<a id="asop_path_services"></a>
### `ASOP_PATH_SERVICES`
Дополнительные услуги на пути. Цена и доступность могут зависеть от перевозчика, ТС или тарифа.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `PATH_SERVICE_ID` | UUID | PK | Первичный ключ |
| `PATH_ID` | UUID | FK → ASOP_PATHS, NOT NULL, CASCADE | Путь |
| `SERVICE_ID` | UUID | FK → ASOP_SERVICES, NOT NULL | Услуга |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS | Опционально: только для конкретного перевозчика |
| `VEHICLE_ID` | UUID | FK → ASOP_VEHICLES | Опционально: только для конкретного ТС |
| `TARIFF_TYPE_ID` | UUID | FK → ASOP_TARIFF_TYPES | Опционально: только для конкретного типа тарифа |
| `PRICE` | NUMERIC(10,2) | NOT NULL | Стоимость услуги при данных условиях |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |

[↑ Наверх](#-оглавление)

<a id="asop_path_discounts"></a>
### `ASOP_PATH_DISCOUNTS`
Скидки на пути. Поддерживает фиксированные суммы и проценты.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `PATH_DISCOUNT_ID` | UUID | PK | Первичный ключ |
| `PATH_ID` | UUID | FK → ASOP_PATHS, NOT NULL, CASCADE | Путь |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS | Опционально |
| `VEHICLE_ID` | UUID | FK → ASOP_VEHICLES | Опционально |
| `TARIFF_TYPE_ID` | UUID | FK → ASOP_TARIFF_TYPES | Опционально |
| `DISCOUNT_NAME` | VARCHAR(100) | NOT NULL | Название |
| `DISCOUNT_TYPE` | VARCHAR(20) | DEFAULT 'PERCENT', CHECK (PERCENT, FIXED) | Тип скидки |
| `DISCOUNT_VALUE` | NUMERIC(10,2) | NOT NULL, CHECK >= 0 | Значение скидки |
| `VALID_FROM` | TIMESTAMP | NOT NULL | Дата начала |
| `VALID_UNTIL` | TIMESTAMP | | Дата окончания |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |

[↑ Наверх](#-оглавление)

<a id="asop_path_benefits"></a>
### `ASOP_PATH_BENEFITS`
Льготы, действующие на конкретных путях.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `PATH_BENEFIT_ID` | UUID | PK | Первичный ключ |
| `PATH_ID` | UUID | FK → ASOP_PATHS, NOT NULL, CASCADE | Путь |
| `BENEFIT_ID` | UUID | FK → ASOP_BENEFITS, NOT NULL, CASCADE | Льгота |

[↑ Наверх](#-оглавление)

---

## 5. Карты, Льготы, Тарифы

<a id="asop_cards"></a>
### `ASOP_CARDS`
Транспортные и банковские карты, зарегистрированные в системе.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARD_ID` | UUID | PK | Первичный ключ |
| `CARD_TYPE_ID` | UUID | FK → ASOP_CARD_TYPES, NOT NULL | Тип карты |
| `USER_ID` | UUID | FK → ASOP_USERS | Владелец |
| `IS_PRIMARY` | BOOLEAN | DEFAULT false | Флаг основной карты (для льгот) |
| `LAST_SYNC_RECEIPT_TIME` | INT | DEFAULT 0 | Unix-время последней синхронизации с чипом |
| `REGISTERED_AT` | TIMESTAMP | | Дата регистрации |
| `REGISTERED_BY_USER_ID` | UUID | | Кто зарегистрировал |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_card_mifares"></a>
### `ASOP_CARD_MIFARES`
Технические параметры MIFARE-карт.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARD_ID` | UUID | PK, FK → ASOP_CARDS, CASCADE | Ссылка на карту |
| `UID` | BYTEA | UNIQUE, NOT NULL | Уникальный идентификатор чипа |
| `ATQA` | SMALLINT | | Ответ на запрос типа A |
| `SAK` | SMALLINT | | Код выбора приложения |
| `PROTOCOL_VERSION` | INT | | Версия протокола |
| `MEMORY_MAP` | JSONB | | JSON-карта памяти |

[↑ Наверх](#-оглавление)

<a id="asop_card_banks"></a>
### `ASOP_CARD_BANKS`
Данные привязанных банковских карт.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARD_ID` | UUID | PK, FK → ASOP_CARDS, CASCADE | Ссылка на карту |
| `PAN_TOKEN` | VARCHAR(256) | NOT NULL | Токенизированный PAN |
| `PAN_LAST4` | CHAR(4) | | Последние 4 цифры |
| `BIN` | CHAR(6) | | BIN-код |
| `IS_TOKENIZED` | BOOLEAN | DEFAULT false | Флаг токенизации |

[↑ Наверх](#-оглавление)

<a id="asop_card_tariffs"></a>
### `ASOP_CARD_TARIFFS`
Активные тарифы на картах.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARD_TARIFF_ID` | UUID | PK | Первичный ключ |
| `CARD_ID` | UUID | FK → ASOP_CARDS, NOT NULL, CASCADE | Карта |
| `TARIFF_TYPE_ID` | UUID | FK → ASOP_TARIFF_TYPES, NOT NULL | Тип тарифа |
| `BALANCE` | NUMERIC(10,2) | | Денежный баланс |
| `TRAVEL_COUNT` | INT | | Текущее кол-во поездок |
| `MAX_TRAVEL_COUNT` | INT | | Лимит поездок |
| `EXPIRATION_DATE` | TIMESTAMP | | Дата окончания |
| `ACTIVATED_AT` | TIMESTAMP | | Дата активации |
| `PURCHASE_TRANSACTION_ID` | UUID | FK → ASOP_TRANSACTIONS | Транзакция покупки |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_blacklists"></a>
### `ASOP_BLACKLISTS`
Заблокированные карты.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARD_ID` | UUID | PK, FK → ASOP_CARDS | Карта |
| `BLOCK_TYPE` | VARCHAR(20) | NOT NULL, CHECK (PERMANENT, NEGATIVE_BALANCE) | Тип блокировки |
| `BLOCKED_AT` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP, NOT NULL | Дата блокировки |
| `RELATED_DEBT_ID` | UUID | FK → ASOP_CARD_DEBTS | Ссылка на активный долг (для NEGATIVE_BALANCE) |
| `AUTO_UNBLOCK_ON_RECOVERY` | BOOLEAN | DEFAULT false | Авто-разблокировка при погашении долга |

[↑ Наверх](#-оглавление)

<a id="asop_user_benefits"></a>
### `ASOP_USER_BENEFITS`
Привязка льгот к пользователям с датами действия.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `ASSIGNMENT_ID` | UUID | PK | Первичный ключ |
| `USER_ID` | UUID | FK → ASOP_USERS, NOT NULL, CASCADE | Пользователь |
| `BENEFIT_ID` | UUID | FK → ASOP_BENEFITS, NOT NULL | Льгота |
| `VALID_FROM` | TIMESTAMP | NOT NULL | Дата начала |
| `VALID_UNTIL` | TIMESTAMP | | Дата окончания |
| `SYNC_VERSION` | INT | DEFAULT 1, NOT NULL | Версия синхронизации |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_tariff_rates"></a>
### `ASOP_TARIFF_RATES`
Тарифные ставки (цены) по зонам, путям и перевозчикам.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TARIFF_RATE_ID` | UUID | PK | Первичный ключ |
| `TARIFF_TYPE_ID` | UUID | FK → ASOP_TARIFF_TYPES, NOT NULL | Тип тарифа |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS | Перевозчик |
| `ZONE_ID` | UUID | FK → ASOP_FARE_ZONES | Тарифная зона |
| `PATH_ID` | UUID | FK → ASOP_PATHS | Путь |
| `PRICE` | NUMERIC(10,2) | NOT NULL | Стоимость |
| `DESCRIPTION` | TEXT | | Описание |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

---

## 5.1. Долги по картам

<a id="asop_card_debts"></a>
### `ASOP_CARD_DEBTS`
Долги по картам. Жизненный цикл: OPEN → RECOVERY_IN_PROGRESS → RECOVERED/EXPIRED/WRITTEN_OFF.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `DEBT_ID` | UUID | PK | Первичный ключ |
| `CARD_ID` | UUID | FK → ASOP_CARDS, NOT NULL | Карта |
| `TRANSACTION_ID` | UUID | FK → ASOP_TRANSACTIONS | Исходная транзакция |
| `SESSION_ID` | UUID | FK → ASOP_SESSIONS | Сессия, в которой произошел проезд в долг |
| `TERMINAL_ID` | UUID | FK → ASOP_TERMINALS | Терминал, зафиксировавший долг |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS, NOT NULL | Перевозчик |
| `DEBT_AMOUNT` | NUMERIC(10,2) | NOT NULL, CHECK > 0 | Сумма долга |
| `CURRENCY` | CHAR(3) | DEFAULT 'RUB', NOT NULL | Валюта |
| `DEBT_STATUS` | VARCHAR(30) | DEFAULT 'OPEN', CHECK (OPEN, RECOVERY_IN_PROGRESS, RECOVERED, EXPIRED, WRITTEN_OFF) | Статус долга |
| `DEBT_OPENED_AT` | TIMESTAMP | NOT NULL | Когда создан долг |
| `DEBT_DUE_DATE` | TIMESTAMP | NOT NULL | Крайний срок списания (обычно +14 дней) |
| `RECOVERED_AT` | TIMESTAMP | | Когда успешно списан |
| `RECOVERED_TRANSACTION_ID` | UUID | FK → ASOP_TRANSACTIONS | Транзакция успешного списания |
| `WRITE_OFF_AT` | TIMESTAMP | | Когда списан как безнадёжный |
| `WRITE_OFF_REASON` | VARCHAR(255) | | Причина списания |
| `BLACKLIST_ENTRY_ID` | UUID | | Обратная ссылка на запись в стоп-листе |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_debt_recovery_attempts"></a>
### `ASOP_DEBT_RECOVERY_ATTEMPTS`
История попыток списания долга с карты.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `ATTEMPT_ID` | UUID | PK | Первичный ключ |
| `DEBT_ID` | UUID | FK → ASOP_CARD_DEBTS, NOT NULL, CASCADE | Долг |
| `ATTEMPT_NUMBER` | INT | NOT NULL | Номер попытки (1, 2, 3...) |
| `RECOVERY_TRANSACTION_ID` | UUID | FK → ASOP_TRANSACTIONS | Транзакция попытки списания |
| `AMOUNT_ATTEMPTED` | NUMERIC(10,2) | NOT NULL, CHECK > 0 | Сколько пытались списать |
| `STATUS` | VARCHAR(20) | DEFAULT 'PENDING', CHECK (PENDING, SUCCESS, FAILED, TIMEOUT, REJECTED) | Статус попытки |
| `ERROR_CODE` | VARCHAR(50) | | Код ошибки |
| `ERROR_MESSAGE` | VARCHAR(1000) | | Текст ошибки |
| `BANK_RESPONSE` | JSONB | | Полный ответ от банка |
| `ATTEMPTED_AT` | TIMESTAMP | NOT NULL | Когда предпринята попытка |
| `COMPLETED_AT` | TIMESTAMP | | Когда получен результат |
| `DURATION_MS` | INT | | Длительность (мс) |
| `NEXT_RETRY_AT` | TIMESTAMP | | Когда следующая попытка |

[↑ Наверх](#-оглавление)

<a id="v_active_card_debts"></a>
### `V_ACTIVE_CARD_DEBTS` *(VIEW)*
Представление для отчётности по активным долгам.

| Поле | Тип | Описание |
|------|-----|----------|
| `DEBT_ID` | UUID | ID долга |
| `CARD_ID` | UUID | ID карты |
| `CARD_TYPE_ID` | UUID | Тип карты |
| `CARD_TYPE_NAME` | VARCHAR | Название типа карты |
| `CARRIER_ID` | UUID | ID перевозчика |
| `CARRIER_NAME` | VARCHAR | Название перевозчика |
| `DEBT_AMOUNT` | NUMERIC | Сумма долга |
| `DEBT_STATUS` | VARCHAR | Статус долга |
| `DEBT_OPENED_AT` | TIMESTAMP | Дата открытия |
| `DEBT_DUE_DATE` | TIMESTAMP | Крайний срок |
| `DAYS_OPEN` | NUMERIC | Дней открыт |
| `DAYS_REMAINING` | NUMERIC | Дней осталось |
| `TOTAL_ATTEMPTS` | BIGINT | Всего попыток |
| `SUCCESSFUL_ATTEMPTS` | BIGINT | Успешных попыток |

[↑ Наверх](#-оглавление)

---

## 6. Оборудование: Терминалы, TID, Профили, ПО

<a id="asop_terminal_profiles"></a>
### `ASOP_TERMINAL_PROFILES`
Справочник профилей настроек терминалов.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `PROFILE_ID` | UUID | PK | Первичный ключ |
| `PROFILE_NAME` | VARCHAR(100) | UNIQUE, NOT NULL | Имя профиля |
| `PROFILE_PARAMS` | JSONB | | Параметры конфигурации |

[↑ Наверх](#-оглавление)

<a id="asop_terminal_software"></a>
### `ASOP_TERMINAL_SOFTWARE`
Справочник версий программного обеспечения терминалов.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `SOFTWARE_VERSION_ID` | UUID | PK | Первичный ключ |
| `TERMINAL_TYPE` | VARCHAR(100) | NOT NULL | Тип терминала (Azur, Feithen) |
| `VERSION` | VARCHAR(100) | NOT NULL | Версия ПО |
| `FILE_PATH` | VARCHAR(500) | | Путь к файлу прошивки/ПО |
| `UPDATE_DATE` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_distributor_terminals"></a>
### `ASOP_DISTRIBUTOR_TERMINALS`
Платёжные терминалы дистрибьюторов карт для пополнения MIFARE-карт.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `DISTRIBUTOR_TERMINAL_ID` | UUID | PK | Первичный ключ |
| `CARDS_DISTRIBUTOR_ID` | UUID | FK → ASOP_CARDS_DISTRIBUTORS, NOT NULL | Дистрибьютор карт |
| `CONTRACT_ID` | UUID | FK → ASOP_CONTRACTS | Договор с дистрибьютором |
| `TERMINAL_NUMBER` | VARCHAR(16) | NOT NULL | Инвентарный номер |
| `TERMINAL_SERIAL` | VARCHAR(64) | NOT NULL | Серийный номер (SN) |
| `TERMINAL_MODEL` | VARCHAR(100) | | Модель терминала |
| `PAYMENT_PROVIDER_ID` | VARCHAR(100) | UNIQUE, NOT NULL | Уникальный ID в платёжной системе дистрибьютора |
| `STATUS` | VARCHAR(50) | DEFAULT 'WAREHOUSE', CHECK (WAREHOUSE, ISSUED, ACTIVE, SUSPENDED, DECOMMISSIONED) | Статус |
| `MOL_USER_ID` | UUID | FK → ASOP_USERS | Материально-ответственное лицо |
| `PROFILE_ID` | UUID | FK → ASOP_TERMINAL_PROFILES | Ссылка на профиль настроек |
| `SOFTWARE_VERSION_ID` | UUID | FK → ASOP_TERMINAL_SOFTWARE | Ссылка на версию ПО |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_tids"></a>
### `ASOP_TIDS`
Пул эквайринговых идентификаторов терминалов (TID).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TID_ID` | UUID | PK | Первичный ключ |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS, NOT NULL | Перевозчик |
| `TERMINAL_ID` | UUID | FK → ASOP_TERMINALS | Терминал |
| `TID_VALUE` | VARCHAR(20) | UNIQUE, NOT NULL | Значение TID от банка |
| `STATUS` | VARCHAR(20) | DEFAULT 'UNUSED', CHECK (UNUSED, ASSIGNED, REVOKED) | Статус |
| `ASSIGNED_AT` | TIMESTAMP | | Дата назначения |
| `UNASSIGNED_AT` | TIMESTAMP | | Дата отзыва |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_terminals"></a>
### `ASOP_TERMINALS`
Терминалы оплаты и валидаторы.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TERMINAL_ID` | UUID | PK | Первичный ключ |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS | Перевозчик |
| `TERMINAL_NUMBER` | VARCHAR(16) | NOT NULL | Инвентарный номер |
| `TERMINAL_SERIAL` | VARCHAR(64) | NOT NULL | Серийный номер (SN) |
| `TERMINAL_MODEL` | VARCHAR(100) | | Модель терминала |
| `STATUS` | VARCHAR(50) | DEFAULT 'WAREHOUSE', CHECK (WAREHOUSE, ISSUED_TO_ENGINEER, IN_OPERATION, REPAIR, DECOMMISSIONED) | Статус |
| `MOL_USER_ID` | UUID | FK → ASOP_USERS | Материально-ответственное лицо |
| `PARENT_TERMINAL_ID` | UUID | FK → ASOP_TERMINALS | Ссылка на родителя (для валидаторов) |
| `TID_ID` | UUID | FK → ASOP_TIDS | Ссылка на TID |
| `VEHICLE_ID` | UUID | FK → ASOP_VEHICLES | Ссылка на ТС |
| `PROFILE_ID` | UUID | FK → ASOP_TERMINAL_PROFILES | Ссылка на профиль настроек |
| `SOFTWARE_VERSION_ID` | UUID | FK → ASOP_TERMINAL_SOFTWARE | Ссылка на версию ПО |
| `BENEFITS_SYNC_TOKEN` | VARCHAR(64) | | Токен синхронизации льгот |
| `LAST_BENEFITS_SYNC_AT` | TIMESTAMP | | Время последней синхронизации |

[↑ Наверх](#-оглавление)

---

## 7. Сессии, Транзакции, Аудит, КРС

<a id="asop_sessions"></a>
### `ASOP_SESSIONS`
Иерархические сессии. Привязаны к PATH_ID (конкретному пути).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `SESSION_ID` | UUID | PK | Первичный ключ |
| `SESSION_TYPE_ID` | UUID | FK → ASOP_SESSION_TYPES, NOT NULL | Тип сессии |
| `PARENT_SESSION_ID` | UUID | FK → ASOP_SESSIONS, SET NULL | Родительская сессия (иерархия) |
| `TERMINAL_ID` | UUID | FK → ASOP_TERMINALS | Терминал |
| `TID_ID` | UUID | FK → ASOP_TIDS | TID (эквайринг) |
| `OPENED_BY_USER_ID` | UUID | FK → ASOP_USERS, SET NULL | Кто открыл |
| `CLOSED_BY_USER_ID` | UUID | FK → ASOP_USERS, SET NULL | Кто закрыл |
| `CARD_ID` | UUID | FK → ASOP_CARDS | Карта (для поездок) |
| `PATH_ID` | UUID | FK → ASOP_PATHS | Конкретный путь |
| `VEHICLE_ID` | UUID | FK → ASOP_VEHICLES | ТС |
| `STARTED_AT` | TIMESTAMP | NOT NULL | Время начала (UTC) |
| `CLOSED_AT` | TIMESTAMP | | Время окончания |
| `STARTED_AT_LOCAL` | TIMESTAMP | NOT NULL | Локальное время начала |
| `CLOSED_AT_LOCAL` | TIMESTAMP | | Локальное время окончания |
| `EXPIRATION_TIME` | TIMESTAMP | NOT NULL | Время истечения |
| `STATUS` | VARCHAR(20) | DEFAULT 'IN_PROGRESS', CHECK (IN_PROGRESS, CLOSED, CANCELLED, CONFIRMED, NOT_CONFIRMED) | Статус |
| `ATTRIBUTES` | JSONB | | Специфичные данные |

[↑ Наверх](#-оглавление)

<a id="asop_audit_services"></a>
### `ASOP_AUDIT_SERVICES`
Справочник контрольно-ревизионных служб (КРС).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `AUDIT_SERVICE_ID` | UUID | PK | Первичный ключ |
| `SERVICE_CODE` | VARCHAR(50) | UNIQUE, NOT NULL | Код службы |
| `SERVICE_NAME` | VARCHAR(255) | NOT NULL | Наименование |
| `ISSUER_TYPE` | VARCHAR(20) | NOT NULL, CHECK (ORGANIZER, CARRIER) | Кто создал |
| `ORGANIZER_ID` | UUID | FK → ASOP_ORGANIZERS | Организатор |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS | Перевозчик |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_audit_tasks"></a>
### `ASOP_AUDIT_TASKS`
Задания на проведение проверок.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TASK_ID` | UUID | PK | Первичный ключ |
| `TASK_NUMBER` | VARCHAR(50) | NOT NULL | Номер задания |
| `ISSUER_TYPE` | VARCHAR(20) | NOT NULL, CHECK (ORGANIZER, CARRIER) | Тип инициатора |
| `ORGANIZER_ID` | UUID | FK → ASOP_ORGANIZERS | Организатор |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS | Перевозчик |
| `ASSIGNED_AUDIT_SERVICE_ID` | UUID | FK → ASOP_AUDIT_SERVICES, NOT NULL | Назначенная служба КРС |
| `TASK_START_DATE` | TIMESTAMP | NOT NULL | Дата начала |
| `TASK_END_DATE` | TIMESTAMP | | Дата окончания |
| `STATUS` | VARCHAR(20) | DEFAULT 'DRAFT', NOT NULL, CHECK (DRAFT, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED) | Статус |
| `DESCRIPTION` | TEXT | | Описание |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_audit_task_paths"></a>
### `ASOP_AUDIT_TASK_PATHS`
Список путей, охваченных заданием КРС (1:N).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TASK_PATH_ID` | UUID | PK | Первичный ключ |
| `TASK_ID` | UUID | FK → ASOP_AUDIT_TASKS, NOT NULL, CASCADE | Задание |
| `PATH_ID` | UUID | FK → ASOP_PATHS, NOT NULL | Путь |

[↑ Наверх](#-оглавление)

<a id="asop_audit_brigades"></a>
### `ASOP_AUDIT_BRIGADES`
Бригады контролеров, сформированные под задание.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `BRIGADE_ID` | UUID | PK | Первичный ключ |
| `TASK_ID` | UUID | FK → ASOP_AUDIT_TASKS, NOT NULL | Задание |
| `FOREMAN_USER_ID` | UUID | FK → ASOP_USERS, SET NULL | Бригадир |
| `BRIGADE_STATUS` | VARCHAR(20) | DEFAULT 'FORMING', NOT NULL, CHECK (FORMING, ACTIVE, COMPLETED, CANCELLED) | Статус |
| `STARTED_AT` | TIMESTAMP | | Время начала |
| `CLOSED_AT` | TIMESTAMP | | Время закрытия |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_audit_brigade_members"></a>
### `ASOP_AUDIT_BRIGADE_MEMBERS`
Состав бригады (бригадир и контролеры).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `MEMBER_ID` | UUID | PK | Первичный ключ |
| `BRIGADE_ID` | UUID | FK → ASOP_AUDIT_BRIGADES, NOT NULL, CASCADE | Бригада |
| `USER_ID` | UUID | FK → ASOP_USERS, NOT NULL | Пользователь |
| `ROLE` | VARCHAR(20) | NOT NULL, CHECK (FOREMAN, CONTROLLER) | Роль |

[↑ Наверх](#-оглавление)

<a id="asop_audit_inspections"></a>
### `ASOP_AUDIT_INSPECTIONS`
Акты проведенных проверок.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `INSPECTION_ID` | UUID | PK | Первичный ключ |
| `BRIGADE_ID` | UUID | FK → ASOP_AUDIT_BRIGADES, NOT NULL | Бригада |
| `CONTROLLER_SESSION_ID` | UUID | FK → ASOP_SESSIONS, NOT NULL | Сессия контролера |
| `PATH_ID` | UUID | FK → ASOP_PATHS | Путь проверки |
| `INSPECTION_START` | TIMESTAMP | NOT NULL | Время начала проверки |
| `INSPECTION_END` | TIMESTAMP | | Время окончания |
| `DURATION` | INTERVAL | | Длительность |
| `PATH_NAME` | VARCHAR(255) | | Направление/путь |
| `PASSENGERS_CHECKED` | INT | DEFAULT 0 | Всего проверено |
| `PASSENGERS_PAID` | INT | DEFAULT 0 | С оплатой |
| `PASSENGERS_COMPENSATED` | INT | DEFAULT 0 | С компенсацией |
| `PASSENGERS_UNPAID` | INT | DEFAULT 0 | Без оплаты |
| `FINES_COUNT` | INT | DEFAULT 0 | Количество штрафов |
| `STATUS` | VARCHAR(20) | DEFAULT 'DRAFT', NOT NULL, CHECK (DRAFT, SUBMITTED, APPROVED, REJECTED) | Статус акта |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_audit_inspection_tasks"></a>
### `ASOP_AUDIT_INSPECTION_TASKS`
Связь M2M: одна инспекция может быть проведена по нескольким заданиям КРС.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `INSPECTION_TASK_ID` | UUID | PK | Первичный ключ |
| `INSPECTION_ID` | UUID | FK → ASOP_AUDIT_INSPECTIONS, NOT NULL, CASCADE | Инспекция |
| `TASK_ID` | UUID | FK → ASOP_AUDIT_TASKS, NOT NULL | Задание |

[↑ Наверх](#-оглавление)

<a id="asop_transactions"></a>
### `ASOP_TRANSACTIONS`
Финансовые проводки (списания).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TRANSACTION_ID` | UUID | PK | Первичный ключ |
| `STARTED_AT` | TIMESTAMP | NOT NULL | Время начала |
| `COMPLETED_AT` | TIMESTAMP | | Время завершения |
| `SESSION_ID` | UUID | FK → ASOP_SESSIONS | Сессия |
| `TRANSACTION_TYPE_ID` | UUID | FK → ASOP_TRANSACTION_TYPES, NOT NULL | Тип транзакции |
| `TRANSACTION_RESULT_ID` | UUID | FK → ASOP_TRANSACTION_RESULTS, NOT NULL | Результат |
| `AMOUNT` | NUMERIC(10,2) | DEFAULT 0, NOT NULL | Сумма |
| `CURRENCY` | CHAR(3) | DEFAULT 'RUB' | Валюта |
| `ACQUIRER_REFERENCE` | VARCHAR(128) | | Ссылка от эквайера |
| `ERROR_CODE` | VARCHAR(50) | | Код ошибки |
| `ERROR_MESSAGE` | VARCHAR(512) | | Сообщение об ошибке |
| `METADATA` | JSONB | | Дополнительные данные |

[↑ Наверх](#-оглавление)

<a id="asop_transaction_cards"></a>
### `ASOP_TRANSACTION_CARDS`
Детализация транзакций по картам.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TRANSACTION_CARD_ID` | UUID | PK | Первичный ключ |
| `TRANSACTION_ID` | UUID | FK → ASOP_TRANSACTIONS, NOT NULL, CASCADE | Транзакция |
| `CARD_ID` | UUID | FK → ASOP_CARDS, NOT NULL | Карта |
| `CARD_ROLE` | VARCHAR(20) | NOT NULL, CHECK (PAYER, REFUND, BENEFIT, GUEST) | Роль карты |
| `TARIFF_APPLIED_ID` | UUID | FK → ASOP_CARD_TARIFFS | Примененный тариф |
| `BALANCE_BEFORE` | NUMERIC(10,2) | | Баланс до |
| `BALANCE_AFTER` | NUMERIC(10,2) | | Баланс после |

[↑ Наверх](#-оглавление)

<a id="asop_payments"></a>
### `ASOP_PAYMENTS`
Поступления (пополнения) на карту. Поддержка Offline Top-Up.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `PAYMENT_ID` | UUID | PK | Первичный ключ |
| `CARD_ID` | UUID | FK → ASOP_CARDS, NOT NULL | Карта пополнения |
| `RECEIPT_UNIX_TIME` | INT | NOT NULL | Порядковый номер (Unix time, 32-бит) |
| `SESSION_ID` | UUID | FK → ASOP_SESSIONS | Сессия (если в терминале) |
| `EVENT_ID` | UUID | FK → ASOP_EVENTS | Системное событие |
| `USER_ID` | UUID | FK → ASOP_USERS | Пользователь (если известен) |
| `DISTRIBUTOR_TERMINAL_ID` | UUID | FK → ASOP_DISTRIBUTOR_TERMINALS | Терминал дистрибьютора |
| `AMOUNT` | NUMERIC(10,2) | DEFAULT 0 | Сумма деньгами |
| `TRIPS_ADDED` | INT | DEFAULT 0 | Количество добавленных поездок |
| `PAYMENT_METHOD` | VARCHAR(50) | | Способ оплаты |
| `STATUS` | VARCHAR(20) | DEFAULT 'PENDING', CHECK (PENDING, APPLIED, FAILED, REFUNDED) | Статус |
| `EXTERNAL_REF` | VARCHAR(128) | | Ссылка на внешний чек |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |

[↑ Наверх](#-оглавление)

<a id="asop_gps_tracking"></a>
### `ASOP_GPS_TRACKING`
GPS-трекинг транспорта (поток координат).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `POSITION_ID` | UUID | PK | Первичный ключ |
| `VEHICLE_ID` | UUID | FK → ASOP_VEHICLES, NOT NULL, CASCADE | ТС |
| `PATH_ID` | UUID | FK → ASOP_PATHS, NOT NULL | Путь |
| `SESSION_ID` | UUID | FK → ASOP_SESSIONS | Текущая сессия-рейс |
| `GPS_COORD` | GEOGRAPHY(POINT, 4326) | | Координаты |
| `RECORDED_AT` | TIMESTAMP | NOT NULL | Время записи |
| `SPEED_KMH` | NUMERIC(5,2) | | Скорость (км/ч) |
| `STATUS` | VARCHAR(30) | DEFAULT 'MOVING' | Статус движения |

[↑ Наверх](#-оглавление)

<a id="asop_events"></a>
### `ASOP_EVENTS`
Журнал системных действий и аудит событий.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `EVENT_ID` | UUID | PK | Первичный ключ |
| `EVENT_TIME` | TIMESTAMP | NOT NULL | Время события (UTC) |
| `EVENT_LOCAL_TIME` | TIMESTAMP | NOT NULL | Локальное время |
| `EVENT_TYPE` | CHAR(4) | FK → ASOP_EVENT_TYPES, NOT NULL | Тип события |
| `USER_ID` | UUID | FK → ASOP_USERS | Инициатор |
| `SESSION_ID` | UUID | FK → ASOP_SESSIONS | Сессия |
| `REFERENCE_TYPE_ID` | INT | | Тип связанного объекта |
| `REFERENCE_ID` | UUID | | ID связанного объекта |
| `EVENT_DETAILS` | VARCHAR(256) | | Краткое описание |
| `EVENT_OBJECT` | JSONB | | Полный контекст |

[↑ Наверх](#-оглавление)

---

## 8. Модуль Фискализации

<a id="asop_fiscalizers"></a>
### `ASOP_FISCALIZERS`
Справочник фискализаторов (ОФД/сервисов фискализации).

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `FISCALIZER_ID` | UUID | PK | Первичный ключ |
| `FISCALIZER_CODE` | VARCHAR(50) | UNIQUE, NOT NULL | Код (BIFIT, ATOL_ONLINE, SHTRIH_M) |
| `FISCALIZER_NAME` | VARCHAR(255) | NOT NULL | Наименование |
| `BASE_API_URL` | VARCHAR(500) | | Базовый URL API |
| `DESCRIPTION` | TEXT | | Описание |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_carrier_fiscalizers"></a>
### `ASOP_CARRIER_FISCALIZERS`
Настройки фискализации для конкретного перевозчика.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `CARRIER_FISCALIZER_ID` | UUID | PK | Первичный ключ |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS, NOT NULL | Перевозчик |
| `FISCALIZER_ID` | UUID | FK → ASOP_FISCALIZERS, NOT NULL | Фискализатор |
| `CUSTOM_API_URL` | VARCHAR(500) | | Переопределение URL |
| `IS_PRIMARY` | BOOLEAN | DEFAULT false | Основной фискализатор |
| `IS_ACTIVE` | BOOLEAN | DEFAULT true | Флаг активности |
| `SETTINGS` | JSONB | | Дополнительные настройки |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_fiscalizer_tokens"></a>
### `ASOP_FISCALIZER_TOKENS`
История токенов доступа к API фискализатора. Токены хранятся в зашифрованном виде.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `TOKEN_ID` | UUID | PK | Первичный ключ |
| `CARRIER_FISCALIZER_ID` | UUID | FK → ASOP_CARRIER_FISCALIZERS, NOT NULL | Связь с перевозчиком-фискализатором |
| `TOKEN_ENCRYPTED` | BYTEA | NOT NULL | Зашифрованный токен |
| `TOKEN_HINT` | VARCHAR(50) | | Подсказка (последние 4 символа) |
| `VALID_FROM` | TIMESTAMP | NOT NULL | Дата начала действия |
| `VALID_UNTIL` | TIMESTAMP | NOT NULL | Дата окончания действия |
| `STATUS` | VARCHAR(20) | DEFAULT 'ACTIVE', CHECK (ACTIVE, EXPIRED, REVOKED) | Статус |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_fiscal_receipts"></a>
### `ASOP_FISCAL_RECEIPTS`
Фискальные чеки. Каждая транзакция = один чек. Retry-механизм при ошибках.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `RECEIPT_ID` | UUID | PK | Первичный ключ |
| `TRANSACTION_ID` | UUID | FK → ASOP_TRANSACTIONS, NOT NULL | Ссылка на транзакцию |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS, NOT NULL | Перевозчик |
| `CARRIER_FISCALIZER_ID` | UUID | FK → ASOP_CARRIER_FISCALIZERS, NOT NULL | Через какого фискализатора |
| `RECEIPT_NUMBER` | VARCHAR(100) | | Номер чека (от фискализатора) |
| `FISCAL_SIGN` | VARCHAR(100) | | Фискальный признак |
| `STATUS` | VARCHAR(20) | DEFAULT 'PENDING', CHECK (PENDING, SENT, CONFIRMED, RETRY, FAILED, CANCELLED) | Статус |
| `ATTEMPT_COUNT` | INT | DEFAULT 0, NOT NULL | Количество попыток |
| `MAX_ATTEMPTS` | INT | DEFAULT 10, NOT NULL | Максимум попыток |
| `LAST_ATTEMPT_AT` | TIMESTAMP | | Время последней попытки |
| `NEXT_RETRY_AT` | TIMESTAMP | | Когда следующая попытка |
| `LAST_ERROR_MESSAGE` | VARCHAR(1000) | | Последняя ошибка |
| `RECEIPT_DATA` | JSONB | | Данные чека |
| `RESPONSE_DATA` | JSONB | | Ответ от фискализатора |
| `CONFIRMED_AT` | TIMESTAMP | | Когда успешно фискализирован |
| `CREATED_AT` | TIMESTAMP | NOT NULL | Дата создания |
| `UPDATED_AT` | TIMESTAMP | NOT NULL | Дата обновления |

[↑ Наверх](#-оглавление)

<a id="asop_fiscal_receipt_attempts"></a>
### `ASOP_FISCAL_RECEIPT_ATTEMPTS`
История попыток отправки каждого чека.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `ATTEMPT_ID` | UUID | PK | Первичный ключ |
| `RECEIPT_ID` | UUID | FK → ASOP_FISCAL_RECEIPTS, NOT NULL, CASCADE | Чек |
| `ATTEMPT_NUMBER` | INT | NOT NULL | Номер попытки (1, 2, 3...) |
| `TOKEN_ID` | UUID | FK → ASOP_FISCALIZER_TOKENS | Каким токеном отправляли |
| `REQUEST_PAYLOAD` | JSONB | | Тело запроса |
| `RESPONSE_PAYLOAD` | JSONB | | Тело ответа |
| `HTTP_STATUS` | INT | | HTTP-код ответа |
| `SUCCESS` | BOOLEAN | DEFAULT false, NOT NULL | Успешно ли |
| `ERROR_MESSAGE` | VARCHAR(1000) | | Текст ошибки |
| `ERROR_CODE` | VARCHAR(50) | | Код ошибки |
| `ATTEMPTED_AT` | TIMESTAMP | NOT NULL | Когда предпринята попытка |
| `DURATION_MS` | INT | | Длительность (мс) |

[↑ Наверх](#-оглавление)

<a id="asop_fiscal_monthly_reports"></a>
### `ASOP_FISCAL_MONTHLY_REPORTS`
Ежемесячные агрегированные отчёты по фискализации для каждого перевозчика.

| Поле | Тип | Ограничения | Описание |
|------|-----|-------------|----------|
| `REPORT_ID` | UUID | PK | Первичный ключ |
| `CARRIER_ID` | UUID | FK → ASOP_CARRIERS, NOT NULL | Перевозчик |
| `FISCALIZER_ID` | UUID | FK → ASOP_FISCALIZERS, NOT NULL | Фискализатор |
| `REPORT_MONTH` | DATE | NOT NULL | Первый день месяца |
| `TOTAL_RECEIPTS` | INT | DEFAULT 0, NOT NULL | Всего чеков |
| `CONFIRMED_RECEIPTS` | INT | DEFAULT 0, NOT NULL | Успешных чеков |
| `FAILED_RECEIPTS` | INT | DEFAULT 0, NOT NULL | Проваленных чеков |
| `PENDING_RECEIPTS` | INT | DEFAULT 0, NOT NULL | Ожидающих чеков |
| `CANCELLED_RECEIPTS` | INT | DEFAULT 0, NOT NULL | Отменённых чеков |
| `TOTAL_AMOUNT` | NUMERIC(15,2) | DEFAULT 0, NOT NULL | Сумма по успешным чекам |
| `AVG_CONFIRMATION_MS` | INT | | Среднее время подтверждения (мс) |
| `SUCCESS_RATE` | NUMERIC(5,2) | | Процент успешных чеков (0-100) |
| `STATUS` | VARCHAR(20) | DEFAULT 'DRAFT', CHECK (DRAFT, FINALIZED) | Статус |
| `GENERATED_AT` | TIMESTAMP | NOT NULL | Дата генерации |
| `FINALIZED_AT` | TIMESTAMP | | Дата финализации |
| `FILE_PATH` | VARCHAR(500) | | Путь к файлу отчёта |

[↑ Наверх](#-оглавление)

---

## 9. Функции БД

<a id="fn_get_active_fiscal_token"></a>
### `fn_get_active_fiscal_token`
**Возвращает:** `UUID`  
**Параметры:** `p_carrier_fiscalizer_id UUID, p_transaction_time TIMESTAMP`

Возвращает ID токена, действующего на момент транзакции. Используется при отправке чека.

[↑ Наверх](#-оглавление)

<a id="fn_calculate_next_retry"></a>
### `fn_calculate_next_retry`
**Возвращает:** `TIMESTAMP`  
**Параметры:** `p_attempt_count INT`

Вычисляет время следующей попытки отправки чека по экспоненциальной схеме (1 мин → 5 мин → 15 мин → 1 час → 6 часов → 12 часов → 24 часа).

[↑ Наверх](#-оглавление)

<a id="fn_create_card_debt"></a>
### `fn_create_card_debt`
**Возвращает:** `UUID` (ID созданного долга)  
**Параметры:** `p_card_id UUID, p_transaction_id UUID, p_session_id UUID, p_terminal_id UUID, p_carrier_id UUID, p_debt_amount NUMERIC, p_recovery_days INT DEFAULT 14`

Создаёт долг по карте и добавляет карту в стоп-лист. Вызывается при проезде в долг.

[↑ Наверх](#-оглавление)

<a id="fn_recover_card_debt"></a>
### `fn_recover_card_debt`
**Возвращает:** `VOID`  
**Параметры:** `p_debt_id UUID, p_recovery_transaction_id UUID`

Помечает долг как погашенный и удаляет карту из стоп-листа.

[↑ Наверх](#-оглавление)

<a id="fn_expire_overdue_debts"></a>
### `fn_expire_overdue_debts`
**Возвращает:** `INT` (количество списанных долгов)  
**Параметры:** `p_write_off_reason VARCHAR DEFAULT 'Истёк срок списания (14 дней)'`

Cron-задача: списывает просроченные долги как безнадёжные и разблокирует карты.

[↑ Наверх](#-оглавление)

<a id="fn_calculate_debt_recovery_retry"></a>
### `fn_calculate_debt_recovery_retry`
**Возвращает:** `TIMESTAMP`  
**Параметры:** `p_attempt_count INT`

Вычисляет время следующей попытки списания долга (1 час → 6 часов → 1 день → 3 дня → далее ежедневно).

[↑ Наверх](#-оглавление)

---

## 📊 Итоговая сводка

| Раздел | Таблиц | VIEW | Функций |
|--------|--------|------|---------|
| 0. Регионы и Территории | 4 | — | — |
| 1. Справочники | 10 | — | — |
| 1.1. Дистрибьюторы | 1 | — | — |
| 2. Перевозчики, Договоры и ТС | 6 | — | — |
| 3. Пользователи и Безопасность | 4 | — | — |
| 4. Маршруты и Пути | 9 | — | — |
| 5. Карты, Льготы, Тарифы | 7 | — | — |
| 5.1. Долги по картам | 2 | 1 | 4 |
| 6. Оборудование | 5 | — | — |
| 7. Сессии, Транзакции, Аудит, КРС | 13 | — | — |
| 8. Модуль Фискализации | 6 | — | 2 |
| **ИТОГО** | **67** | **1** | **6** |

---

*Документация сгенерирована для финальной схемы БД АСОП (TAVRIDA) с модулями фискализации и долгов.*