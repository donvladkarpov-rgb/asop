-- ============================================================
-- Seed data: DELTA-2 (large increment, ~100-200k rows)
-- Все данные привязаны к региону ...0103 и перевозчику ...1403.
-- VERSION/created_at/updated_at НЕ указываем — триггеры проставят.
-- Использовать ПОСЛЕ seed-data.sql + seed-data-delta-1.sql:
--   docker compose -f infrastructure/docker/docker-compose.yml \
--     exec -T postgres psql -U asop -d asop < infrastructure/docker/seed-data-delta-2.sql
-- ============================================================

-- ============ GLOBAL-справочники (без фильтра) ============

-- Roles (2 500)
INSERT INTO ASOP_ROLES (ROLE_ID, ROLE_NAME)
SELECT
    md5('d2:role:' || n)::uuid,
    'Роль delta-2 #' || n
FROM generate_series(1, 2500) AS n
ON CONFLICT (ROLE_ID) DO NOTHING;

-- Card Types (1 000)
INSERT INTO ASOP_CARD_TYPES (CARD_TYPE_ID, CARD_TYPE_NAME)
SELECT
    md5('d2:cardtype:' || n)::uuid,
    'Тип карты delta-2 #' || n
FROM generate_series(1, 1000) AS n
ON CONFLICT (CARD_TYPE_ID) DO NOTHING;

-- Tariff Types (1 000)
INSERT INTO ASOP_TARIFF_TYPES (TARIFF_TYPE_ID, CODE, NAME, DESCRIPTION)
SELECT
    md5('d2:tarifftype:' || n)::uuid,
    'D2T' || lpad(n::text, 6, '0'),
    'Тариф delta-2 #' || n,
    'Тариф для инкремента 2'
FROM generate_series(1, 1000) AS n
ON CONFLICT (TARIFF_TYPE_ID) DO NOTHING;

-- ============ FILTERED: по региону ...0103 ============

-- Fare Zones (2 500, ZONE_CODE уникален)
INSERT INTO ASOP_FARE_ZONES (ZONE_ID, ZONE_CODE, ZONE_NAME, DESCRIPTION, REGION_ID)
SELECT
    md5('d2:zone:' || n)::uuid,
    'D2Z' || lpad(n::text, 7, '0'),
    'Тарифная зона delta-2 #' || n,
    'Зона для инкремента 2',
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 2500) AS n
ON CONFLICT (ZONE_ID) DO NOTHING;

-- Transport Stops (7 500, STOP_CODE уникален)
INSERT INTO ASOP_TRANSPORT_STOPS (STOP_ID, FARE_ZONE_ID, REGION_ID, STOP_CODE, STOP_NAME, STOP_ADDRESS, IS_ACTIVE)
SELECT
    md5('d2:stop:' || n)::uuid,
    md5('d2:zone:' || (n % 2500 + 1))::uuid,
    '00000000-0000-0000-0000-000000000103',
    'D2S' || lpad(n::text, 8, '0'),
    'Остановка delta-2 #' || n,
    'г. Симферополь, ул. Тестовая-2, ' || n,
    true
FROM generate_series(1, 7500) AS n
ON CONFLICT DO NOTHING;

-- Routes (2 500)
INSERT INTO ASOP_ROUTES (ROUTE_ID, ROUTE_NUMBER, ROUTE_NAME, ORGANIZER_ID, REGION_ID)
SELECT
    md5('d2:route:' || n)::uuid,
    'D2-' || n,
    'Маршрут delta-2 #' || n,
    '00000000-0000-0000-0000-000000000303',
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 2500) AS n
ON CONFLICT (ROUTE_ID) DO NOTHING;

-- Paths (2 500)
INSERT INTO ASOP_PATHS (PATH_ID, ROUTE_ID, PATH_NAME, START_STOP_ID, END_STOP_ID, BENEFIT_POLICY, REGION_ID)
SELECT
    md5('d2:path:' || n)::uuid,
    md5('d2:route:' || (n % 2500 + 1))::uuid,
    'Путь delta-2 #' || n,
    md5('d2:stop:' || (n % 7500 + 1))::uuid,
    md5('d2:stop:' || ((n + 100) % 7500 + 1))::uuid,
    'ALL',
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 2500) AS n
ON CONFLICT (PATH_ID) DO NOTHING;

-- Schedule (7 500, пути 1-50 × по 150 записей)
INSERT INTO ASOP_SCHEDULE (SCHEDULE_ID, PATH_ID, STOP_ID, DAY_MASK, ARRIVAL_TIME, DWELL_TIME_SEC, REGION_ID, IS_ACTIVE)
SELECT
    md5('d2:sched:' || n)::uuid,
    md5('d2:path:' || (n % 50 + 1))::uuid,
    md5('d2:stop:' || (n % 7500 + 1))::uuid,
    127,
    ('08:00:00'::time + (n % 720) * interval '1 minute'),
    30,
    '00000000-0000-0000-0000-000000000103',
    true
FROM generate_series(1, 7500) AS n
ON CONFLICT DO NOTHING;

-- ============ FILTERED: по перевозчику ...1403 ============

-- TIDs (2 500)
INSERT INTO ASOP_TIDS (TID_ID, CARRIER_ID, TID_VALUE, STATUS)
SELECT
    md5('d2:tid:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403',
    'D2-TID-' || lpad(n::text, 6, '0'),
    'UNUSED'
FROM generate_series(1, 2500) AS n
ON CONFLICT (TID_ID) DO NOTHING;

-- Vehicles (2 500)
INSERT INTO ASOP_VEHICLES (VEHICLE_ID, CARRIER_ID, VEHICLE_TYPE_ID, VEHICLE_MODEL_ID, VEHICLE_NUMBER, VEHICLE_NAME)
SELECT
    md5('d2:vehicle:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403',
    '00000000-0000-0000-0000-000000001601',
    '00000000-0000-0000-0000-000000001701',
    'D2' || lpad(n::text, 7, '0'),
    'ТС delta-2 #' || n
FROM generate_series(1, 2500) AS n
ON CONFLICT (VEHICLE_ID) DO NOTHING;

-- Contracts (1 000)
INSERT INTO ASOP_CONTRACTS (CONTRACT_ID, CONTRACTOR_TYPE, CARRIER_ID, CARDS_DISTRIBUTOR_ID, CONTRACT_NUMBER, START_DATE, END_DATE, STATUS, COMMISSION_PERCENT, ATTRIBUTES)
SELECT
    md5('d2:contract:' || n)::uuid,
    'CARRIER',
    '00000000-0000-0000-0000-000000001403',
    NULL,
    'Д-КР-2-' || lpad(n::text, 6, '0'),
    '2024-01-01',
    '2026-12-31',
    'ACTIVE',
    2.50,
    jsonb_build_object('routeCount', n)
FROM generate_series(1, 1000) AS n
ON CONFLICT (CONTRACT_ID) DO NOTHING;

-- Cards Distributors (500)
INSERT INTO ASOP_CARDS_DISTRIBUTORS (CARDS_DISTRIBUTOR_ID, DISTRIBUTOR_NAME, INN, KPP, LEGAL_ADDRESS, CONTACT_PHONE, CONTACT_EMAIL, IS_ACTIVE)
SELECT
    md5('d2:dist:' || n)::uuid,
    'Дистрибьютор delta-2 #' || n,
    '9103' || lpad(n::text, 8, '0'),
    '910301001',
    'г. Симферополь, ул. Карт-2, ' || n,
    '+7-978-200-00-' || lpad((n % 100)::text, 2, '0'),
    'dist2_' || n || '@example.ru',
    true
FROM generate_series(1, 500) AS n
ON CONFLICT (CARDS_DISTRIBUTOR_ID) DO NOTHING;

-- ============ USER-таблицы (users ...1403) ============

-- Users (5 000)
INSERT INTO ASOP_USERS (USER_ID, FIRST_NAME, LAST_NAME_INITIAL, PATRONYMIC_INITIAL, PHONE)
SELECT
    md5('d2:user:' || n)::uuid,
    'Пользователь' || (n + 10000),
    'П',
    'П',
    '+7-978-300-' || lpad((n % 10000)::text, 4, '0')
FROM generate_series(1, 5000) AS n
ON CONFLICT (USER_ID) DO NOTHING;

-- User Roles
INSERT INTO ASOP_USER_ROLES (USER_ID, ROLE_ID)
SELECT
    md5('d2:user:' || n)::uuid,
    md5('d2:role:' || (n % 2500 + 1))::uuid
FROM generate_series(1, 5000) AS n
ON CONFLICT (USER_ID, ROLE_ID) DO NOTHING;

-- User Carriers (привязка к ...1403)
INSERT INTO ASOP_USER_CARRIERS (USER_ID, CARRIER_ID)
SELECT
    md5('d2:user:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403'
FROM generate_series(1, 5000) AS n
ON CONFLICT (USER_ID, CARRIER_ID) DO NOTHING;

-- User Regions (привязка к ...0103)
INSERT INTO ASOP_USER_REGIONS (USER_ID, REGION_ID)
SELECT
    md5('d2:user:' || n)::uuid,
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 5000) AS n
ON CONFLICT (USER_ID, REGION_ID) DO NOTHING;

-- ============ CARD-таблицы (карты пользователей ...1403) ============

-- Cards (5 000)
INSERT INTO ASOP_CARDS (CARD_ID, CARD_TYPE_ID, USER_ID, IS_PRIMARY, REGISTERED_AT, REGISTERED_BY_USER_ID)
SELECT
    md5('d2:card:' || n)::uuid,
    md5('d2:cardtype:' || (n % 1000 + 1))::uuid,
    md5('d2:user:' || n)::uuid,
    true,
    NOW(),
    md5('d2:user:' || n)::uuid
FROM generate_series(1, 5000) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- Card Mifares
INSERT INTO ASOP_CARD_MIFARES (CARD_ID, UID, ATQA, SAK, PROTOCOL_VERSION, CARD_ROLE, KEY_VERSION)
SELECT
    md5('d2:card:' || n)::uuid,
    decode(lpad(to_hex(1000000000 + n), 16, '0'), 'hex'),
    68,
    8,
    2,
    'PASSENGER_BENEFIT',
    1
FROM generate_series(1, 5000) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- Card Banks (только чётные)
INSERT INTO ASOP_CARD_BANKS (CARD_ID, PAN_TOKEN, BIN, IS_TOKENIZED)
SELECT
    md5('d2:card:' || n)::uuid,
    'tok_d2_' || lpad(to_hex(n), 8, '0'),
    '427638',
    true
FROM generate_series(2, 5000, 2) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- Card Tariffs
INSERT INTO ASOP_CARD_TARIFFS (CARD_TARIFF_ID, CARD_ID, TARIFF_TYPE_ID, BALANCE, TRAVEL_COUNT, MAX_TRAVEL_COUNT, EXPIRATION_DATE, IS_ACTIVE)
SELECT
    md5('d2:ctariff:' || n)::uuid,
    md5('d2:card:' || n)::uuid,
    md5('d2:tarifftype:' || (n % 1000 + 1))::uuid,
    (n * 10)::numeric,
    n % 100,
    100,
    '2026-12-31',
    true
FROM generate_series(1, 5000) AS n
ON CONFLICT (CARD_TARIFF_ID) DO NOTHING;

-- Tariff Rates (5 000)
INSERT INTO ASOP_TARIFF_RATES (TARIFF_RATE_ID, TARIFF_TYPE_ID, CARRIER_ID, ZONE_ID, PATH_ID, PRICE, DESCRIPTION, IS_ACTIVE)
SELECT
    md5('d2:trate:' || n)::uuid,
    md5('d2:tarifftype:' || (n % 1000 + 1))::uuid,
    '00000000-0000-0000-0000-000000001403',
    md5('d2:zone:' || (n % 2500 + 1))::uuid,
    md5('d2:path:' || (n % 2500 + 1))::uuid,
    (n % 200 + 1)::numeric,
    'Тариф delta-2 #' || n,
    true
FROM generate_series(1, 5000) AS n;
