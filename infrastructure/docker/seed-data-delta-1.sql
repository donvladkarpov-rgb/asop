-- ============================================================
-- Seed data: DELTA-1 (small increment, ~1-2k rows)
-- Все данные привязаны к региону ...0103 и перевозчику ...1403,
-- чтобы дельта-мастера возвращали их терминалу.
-- VERSION/created_at/updated_at НЕ указываем — триггеры проставят.
-- Использовать ПОСЛЕ seed-data.sql:
--   docker compose -f infrastructure/docker/docker-compose.yml \
--     exec -T postgres psql -U asop -d asop < infrastructure/docker/seed-data-delta-1.sql
-- ============================================================

-- ============ GLOBAL-справочники (без фильтра) ============

-- Roles (дополнительно к seed из v001-init)
INSERT INTO ASOP_ROLES (ROLE_ID, ROLE_NAME)
VALUES
    ('00000000-0000-0000-0000-000000000051', 'Диспетчер'),
    ('00000000-0000-0000-0000-000000000052', 'Кассир')
ON CONFLICT (ROLE_ID) DO NOTHING;

-- Card Types
INSERT INTO ASOP_CARD_TYPES (CARD_TYPE_ID, CARD_TYPE_NAME)
VALUES
    ('00000000-0000-0000-0000-000000000403', 'Социальная карта'),
    ('00000000-0000-0000-0000-000000000404', 'Транспортная карта школьника')
ON CONFLICT (CARD_TYPE_ID) DO NOTHING;

-- Tariff Types
INSERT INTO ASOP_TARIFF_TYPES (TARIFF_TYPE_ID, CODE, NAME, DESCRIPTION)
VALUES
    ('00000000-0000-0000-0000-000000000503', 'PAY_AS_YOU_GO', 'Пополняемый', 'Поездки списываются с баланса'),
    ('00000000-0000-0000-0000-000000000504', '90_MINUTES', '90 минут', 'Пересадки в течение 90 минут')
ON CONFLICT (TARIFF_TYPE_ID) DO NOTHING;

-- Vehicle Types
INSERT INTO ASOP_VEHICLE_TYPES (VEHICLE_TYPE_ID, TYPE_NAME)
VALUES
    ('00000000-0000-0000-0000-000000001603', 'Трамвай'),
    ('00000000-0000-0000-0000-000000001604', 'Электробус')
ON CONFLICT (VEHICLE_TYPE_ID) DO NOTHING;

-- Vehicle Models
INSERT INTO ASOP_VEHICLE_MODELS (VEHICLE_MODEL_ID, MODEL_NAME)
VALUES
    ('00000000-0000-0000-0000-000000001703', 'МАЗ-303'),
    ('00000000-0000-0000-0000-000000001704', 'КамАЗ-6282')
ON CONFLICT (VEHICLE_MODEL_ID) DO NOTHING;

-- ============ FILTERED: по региону ...0103 ============

-- Services (PRIORITY уникален глобально)
INSERT INTO ASOP_SERVICES (SERVICE_ID, SERVICE_NAME, DESCRIPTION, PRIORITY, REGION_ID)
SELECT
    md5('d1:service:' || n)::uuid,
    'Услуга delta-1 #' || n,
    'Сервисная услуга для Крыма',
    1000 + n,
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 30) AS n
ON CONFLICT DO NOTHING;

-- Benefits (BENEFIT_CODE уникален)
INSERT INTO ASOP_BENEFITS (BENEFIT_ID, BENEFIT_CODE, BENEFIT_NAME, REGION_ID, DESCRIPTION, IS_ACTIVE)
SELECT
    md5('d1:benefit:' || n)::uuid,
    'D1BEN' || lpad(n::text, 5, '0'),
    'Льгота delta-1 #' || n,
    '00000000-0000-0000-0000-000000000103',
    'Льгота для теста инкремента 1',
    true
FROM generate_series(1, 30) AS n
ON CONFLICT DO NOTHING;

-- Benefit Steps (на каждый benefit из delta-1)
INSERT INTO ASOP_BENEFIT_STEPS (STEP_ID, BENEFIT_ID, STEP_ORDER, TRIP_THRESHOLD_FROM, TRIP_THRESHOLD_TO, DISCOUNT_SHARE, PERIOD_TYPE)
SELECT
    md5('d1:step:' || n)::uuid,
    md5('d1:benefit:' || n)::uuid,
    1,
    0,
    50,
    0.25,
    'MONTHLY'
FROM generate_series(1, 30) AS n
ON CONFLICT DO NOTHING;

-- Территории для региона ...0103 (должны быть до ORGANIZER_TERRITORIES)
INSERT INTO ASOP_TERRITORIES (TERRITORY_ID, REGION_ID, MUNICIPAL_DIVISION, ADMIN_DIVISION, FEDERAL_DISTRICT, FIAS_ID)
SELECT
    md5('d1:territory:' || n)::uuid,
    '00000000-0000-0000-0000-000000000103',
    'Район ' || n,
    'Республика Крым',
    'Южный',
    md5('d1:fias:' || n)::text
FROM generate_series(1, 5) AS n
ON CONFLICT (TERRITORY_ID) DO NOTHING;

-- Organizer Territories (organizer ...0303, territory ...0203 Крым)
INSERT INTO ASOP_ORGANIZER_TERRITORIES (ORGANIZER_ID, TERRITORY_ID)
SELECT
    '00000000-0000-0000-0000-000000000303',
    md5('d1:territory:' || n)::uuid
FROM generate_series(1, 5) AS n
ON CONFLICT (ORGANIZER_ID, TERRITORY_ID) DO NOTHING;

-- Fare Zones (ZONE_CODE уникален)
INSERT INTO ASOP_FARE_ZONES (ZONE_ID, ZONE_CODE, ZONE_NAME, DESCRIPTION, REGION_ID)
SELECT
    md5('d1:zone:' || n)::uuid,
    'D1Z' || lpad(n::text, 4, '0'),
    'Тарифная зона delta-1 #' || n,
    'Зона для инкремента 1',
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 20) AS n
ON CONFLICT DO NOTHING;

-- Transport Stops (STOP_CODE уникален)
INSERT INTO ASOP_TRANSPORT_STOPS (STOP_ID, FARE_ZONE_ID, REGION_ID, STOP_CODE, STOP_NAME, STOP_ADDRESS, IS_ACTIVE)
SELECT
    md5('d1:stop:' || n)::uuid,
    md5('d1:zone:' || (n % 20 + 1))::uuid,
    '00000000-0000-0000-0000-000000000103',
    'D1S' || lpad(n::text, 6, '0'),
    'Остановка delta-1 #' || n,
    'г. Симферополь, ул. Тестовая, ' || n,
    true
FROM generate_series(1, 200) AS n
ON CONFLICT DO NOTHING;

-- Routes
INSERT INTO ASOP_ROUTES (ROUTE_ID, ROUTE_NUMBER, ROUTE_NAME, ORGANIZER_ID, REGION_ID)
SELECT
    md5('d1:route:' || n)::uuid,
    'D1-' || n,
    'Маршрут delta-1 #' || n,
    '00000000-0000-0000-0000-000000000303',
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 50) AS n
ON CONFLICT DO NOTHING;

-- Paths (по маршруту #1 — 2 пути, чтобы переиспользовать для расписания)
INSERT INTO ASOP_PATHS (PATH_ID, ROUTE_ID, PATH_NAME, START_STOP_ID, END_STOP_ID, BENEFIT_POLICY, REGION_ID)
SELECT
    md5('d1:path:' || n)::uuid,
    md5('d1:route:' || (n % 50 + 1))::uuid,
    'Путь delta-1 #' || n,
    md5('d1:stop:' || (n % 200 + 1))::uuid,
    md5('d1:stop:' || ((n + 50) % 200 + 1))::uuid,
    'ALL',
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 100) AS n
ON CONFLICT DO NOTHING;

-- Path Transport Stops (для первых 50 путей)
INSERT INTO ASOP_PATH_TRANSPORT_STOPS (PATH_STOP_ID, PATH_ID, STOP_ID, SERIAL_NUMBER, REGION_ID)
SELECT
    md5('d1:pstop:' || n)::uuid,
    md5('d1:path:' || (n % 100 + 1))::uuid,
    md5('d1:stop:' || (n % 200 + 1))::uuid,
    n % 5 + 1,
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 100) AS n
ON CONFLICT DO NOTHING;

-- Schedule (по путям 1-50, по 2 записи на путь)
INSERT INTO ASOP_SCHEDULE (SCHEDULE_ID, PATH_ID, STOP_ID, DAY_MASK, ARRIVAL_TIME, DWELL_TIME_SEC, REGION_ID, IS_ACTIVE)
SELECT
    md5('d1:sched:' || n)::uuid,
    md5('d1:path:' || (n % 50 + 1))::uuid,
    md5('d1:stop:' || (n % 200 + 1))::uuid,
    127,
    ('08:00:00'::time + (n % 600) * interval '1 minute'),
    30,
    '00000000-0000-0000-0000-000000000103',
    true
FROM generate_series(1, 500) AS n
ON CONFLICT DO NOTHING;

-- Path Services (пути 1-50 × услуги 1-10)
INSERT INTO ASOP_PATH_SERVICES (PATH_SERVICE_ID, PATH_ID, SERVICE_ID, CARRIER_ID, TARIFF_TYPE_ID, PRICE, IS_ACTIVE)
SELECT
    md5('d1:psvc:' || n)::uuid,
    md5('d1:path:' || (n % 50 + 1))::uuid,
    md5('d1:service:' || (n % 30 + 1))::uuid,
    '00000000-0000-0000-0000-000000001403',
    '00000000-0000-0000-0000-000000000501',
    (n % 100 + 1)::numeric,
    true
FROM generate_series(1, 100) AS n
ON CONFLICT DO NOTHING;

-- Path Discounts
INSERT INTO ASOP_PATH_DISCOUNTS (PATH_DISCOUNT_ID, PATH_ID, CARRIER_ID, DISCOUNT_NAME, DISCOUNT_TYPE, DISCOUNT_VALUE, VALID_FROM, IS_ACTIVE)
SELECT
    md5('d1:pdisc:' || n)::uuid,
    md5('d1:path:' || (n % 50 + 1))::uuid,
    '00000000-0000-0000-0000-000000001403',
    'Скидка delta-1 #' || n,
    'PERCENT',
    5 + (n % 20),
    '2024-01-01 00:00:00+03',
    true
FROM generate_series(1, 100) AS n
ON CONFLICT DO NOTHING;

-- Path Benefits (пути 1-50 × льготы 1-10)
INSERT INTO ASOP_PATH_BENEFITS (PATH_BENEFIT_ID, PATH_ID, BENEFIT_ID)
SELECT
    md5('d1:pben:' || n)::uuid,
    md5('d1:path:' || (n % 50 + 1))::uuid,
    md5('d1:benefit:' || (n % 30 + 1))::uuid
FROM generate_series(1, 100) AS n
ON CONFLICT DO NOTHING;

-- ============ FILTERED: по перевозчику ...1403 ============

-- Contracts (договоры перевозчика ...1403)
INSERT INTO ASOP_CONTRACTS (CONTRACT_ID, CONTRACTOR_TYPE, CARRIER_ID, CARDS_DISTRIBUTOR_ID, CONTRACT_NUMBER, START_DATE, END_DATE, STATUS, COMMISSION_PERCENT, ATTRIBUTES)
SELECT
    md5('d1:contract:' || n)::uuid,
    'CARRIER',
    '00000000-0000-0000-0000-000000001403',
    NULL,
    'Д-КР-2024-' || lpad(n::text, 4, '0'),
    '2024-01-01',
    '2026-12-31',
    'ACTIVE',
    2.50,
    jsonb_build_object('routeCount', n)
FROM generate_series(1, 20) AS n
ON CONFLICT DO NOTHING;

-- Contract Routes (договор 1 × маршруты 1-30)
INSERT INTO ASOP_CONTRACT_ROUTES (CONTRACT_ID, ROUTE_ID)
SELECT
    md5('d1:contract:' || 1)::uuid,
    md5('d1:route:' || (n % 50 + 1))::uuid
FROM generate_series(1, 30) AS n
ON CONFLICT (CONTRACT_ID, ROUTE_ID) DO NOTHING;

-- Cards Distributors
INSERT INTO ASOP_CARDS_DISTRIBUTORS (CARDS_DISTRIBUTOR_ID, DISTRIBUTOR_NAME, INN, KPP, LEGAL_ADDRESS, CONTACT_PHONE, CONTACT_EMAIL, IS_ACTIVE)
SELECT
    md5('d1:dist:' || n)::uuid,
    'Дистрибьютор delta-1 #' || n,
    '9102' || lpad(n::text, 8, '0'),
    '910201001',
    'г. Симферополь, ул. Карт, ' || n,
    '+7-978-000-00-' || lpad((n % 100)::text, 2, '0'),
    'dist' || n || '@example.ru',
    true
FROM generate_series(1, 10) AS n
ON CONFLICT DO NOTHING;

-- TIDs (TID_VALUE уникален)
INSERT INTO ASOP_TIDS (TID_ID, CARRIER_ID, TID_VALUE, STATUS)
SELECT
    md5('d1:tid:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403',
    'D1-TID-' || lpad(n::text, 5, '0'),
    'UNUSED'
FROM generate_series(1, 100) AS n
ON CONFLICT (TID_ID) DO NOTHING;

-- Vehicle Types/Models для перевозчика (детерминированные, ДО вставки Vehicles из-за FK)
INSERT INTO ASOP_VEHICLE_TYPES (VEHICLE_TYPE_ID, TYPE_NAME)
SELECT md5('d1:vtype:' || n)::uuid, 'Тип ТС delta-1 #' || n
FROM generate_series(1, 2) AS n
ON CONFLICT (VEHICLE_TYPE_ID) DO NOTHING;

INSERT INTO ASOP_VEHICLE_MODELS (VEHICLE_MODEL_ID, MODEL_NAME)
SELECT md5('d1:vmodel:' || n)::uuid, 'Модель ТС delta-1 #' || n
FROM generate_series(1, 2) AS n
ON CONFLICT (VEHICLE_MODEL_ID) DO NOTHING;

-- Vehicles (перевозчик ...1403)
INSERT INTO ASOP_VEHICLES (VEHICLE_ID, CARRIER_ID, VEHICLE_TYPE_ID, VEHICLE_MODEL_ID, VEHICLE_NUMBER, VEHICLE_NAME)
SELECT
    md5('d1:vehicle:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403',
    md5('d1:vtype:' || (n % 2 + 1))::uuid,
    md5('d1:vmodel:' || (n % 2 + 1))::uuid,
    'D1' || lpad(n::text, 6, '0'),
    'ТС delta-1 #' || n
FROM generate_series(1, 30) AS n
ON CONFLICT DO NOTHING;

-- ============ USER-таблицы (users ...1403) ============

-- Users (100 шт, привязаны к перевозчику ...1403 через USER_CARRIERS и региону ...0103 через USER_REGIONS)
INSERT INTO ASOP_USERS (USER_ID, FIRST_NAME, LAST_NAME_INITIAL, PATRONYMIC_INITIAL, PHONE)
SELECT
    md5('d1:user:' || n)::uuid,
    'Пользователь' || n,
    'П',
    'П',
    '+7-978-100-' || lpad((n % 10000)::text, 4, '0')
FROM generate_series(1, 100) AS n
ON CONFLICT (USER_ID) DO NOTHING;

-- User Roles
INSERT INTO ASOP_USER_ROLES (USER_ID, ROLE_ID)
SELECT
    md5('d1:user:' || n)::uuid,
    '00000000-0000-0000-0000-000000000002'
FROM generate_series(1, 100) AS n
ON CONFLICT (USER_ID, ROLE_ID) DO NOTHING;

-- User Carriers (привязка к ...1403)
INSERT INTO ASOP_USER_CARRIERS (USER_ID, CARRIER_ID)
SELECT
    md5('d1:user:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403'
FROM generate_series(1, 100) AS n
ON CONFLICT (USER_ID, CARRIER_ID) DO NOTHING;

-- User Regions (привязка к ...0103)
INSERT INTO ASOP_USER_REGIONS (USER_ID, REGION_ID)
SELECT
    md5('d1:user:' || n)::uuid,
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 100) AS n
ON CONFLICT (USER_ID, REGION_ID) DO NOTHING;

-- ============ CARD-таблицы (карты пользователей ...1403) ============

-- Cards
INSERT INTO ASOP_CARDS (CARD_ID, CARD_TYPE_ID, USER_ID, IS_PRIMARY, REGISTERED_AT, REGISTERED_BY_USER_ID)
SELECT
    md5('d1:card:' || n)::uuid,
    '00000000-0000-0000-0000-000000000401',
    md5('d1:user:' || n)::uuid,
    true,
    NOW(),
    md5('d1:user:' || n)::uuid
FROM generate_series(1, 100) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- Card Mifares (UID байты уникальны)
INSERT INTO ASOP_CARD_MIFARES (CARD_ID, UID, ATQA, SAK, PROTOCOL_VERSION, CARD_ROLE, KEY_VERSION)
SELECT
    md5('d1:card:' || n)::uuid,
    decode(lpad(to_hex(n), 16, '0'), 'hex'),
    68,
    8,
    2,
    'PASSENGER',
    1
FROM generate_series(1, 100) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- Card Banks (только чётные карты)
INSERT INTO ASOP_CARD_BANKS (CARD_ID, PAN_TOKEN, BIN, IS_TOKENIZED)
SELECT
    md5('d1:card:' || n)::uuid,
    'tok_d1_' || lpad(to_hex(n), 8, '0'),
    '427638',
    true
FROM generate_series(2, 100, 2) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- Card Tariffs
INSERT INTO ASOP_CARD_TARIFFS (CARD_TARIFF_ID, CARD_ID, TARIFF_TYPE_ID, BALANCE, TRAVEL_COUNT, MAX_TRAVEL_COUNT, EXPIRATION_DATE, IS_ACTIVE)
SELECT
    md5('d1:ctariff:' || n)::uuid,
    md5('d1:card:' || n)::uuid,
    '00000000-0000-0000-0000-000000000501',
    (n * 10)::numeric,
    n % 50,
    100,
    '2026-12-31',
    true
FROM generate_series(1, 100) AS n
ON CONFLICT (CARD_TARIFF_ID) DO NOTHING;

-- Blacklists (каждая 10-я карта)
INSERT INTO ASOP_BLACKLISTS (CARD_ID, BLOCK_TYPE, BLOCKED_AT)
SELECT
    md5('d1:card:' || (n * 10))::uuid,
    'PERMANENT',
    NOW()
FROM generate_series(1, 10) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- User Benefits (льготы пользователям ...1403)
INSERT INTO ASOP_USER_BENEFITS (ASSIGNMENT_ID, USER_ID, BENEFIT_ID, VALID_FROM, VALID_UNTIL, SYNC_VERSION)
SELECT
    md5('d1:uben:' || n)::uuid,
    md5('d1:user:' || n)::uuid,
    md5('d1:benefit:' || (n % 30 + 1))::uuid,
    NOW(),
    NULL,
    1
FROM generate_series(1, 50) AS n
ON CONFLICT (ASSIGNMENT_ID) DO NOTHING;

-- Tariff Rates (перевозчик ...1403, зона ...0103)
INSERT INTO ASOP_TARIFF_RATES (TARIFF_RATE_ID, TARIFF_TYPE_ID, CARRIER_ID, ZONE_ID, PATH_ID, PRICE, DESCRIPTION, IS_ACTIVE)
SELECT
    md5('d1:trate:' || n)::uuid,
    '00000000-0000-0000-0000-000000000501',
    '00000000-0000-0000-0000-000000001403',
    md5('d1:zone:' || (n % 20 + 1))::uuid,
    md5('d1:path:' || (n % 50 + 1))::uuid,
    (n % 100 + 1)::numeric,
    'Тариф delta-1 #' || n,
    true
FROM generate_series(1, 200) AS n
ON CONFLICT DO NOTHING;
