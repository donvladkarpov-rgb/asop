-- ============================================================
-- Seed data: DELTA-3 (medium increment, ~10-20k rows)
-- Все данные привязаны к региону ...0103 и перевозчику ...1403.
-- VERSION/created_at/updated_at НЕ указываем — триггеры проставят.
-- Использовать ПОСЛЕ seed-data.sql + delta-1 + delta-2,
-- перед полной выгрузкой (full dump):
--   docker compose -f infrastructure/docker/docker-compose.yml \
--     exec -T postgres psql -U asop -d asop < infrastructure/docker/seed-data-delta-3.sql
-- ============================================================

-- ============ GLOBAL-справочники (без фильтра) ============

-- Roles (2 000)
INSERT INTO ASOP_ROLES (ROLE_ID, ROLE_NAME)
SELECT
    md5('d3:role:' || n)::uuid,
    'Роль delta-3 #' || n
FROM generate_series(1, 2000) AS n
ON CONFLICT (ROLE_ID) DO NOTHING;

-- ============ FILTERED: по региону ...0103 ============

-- Transport Stops (3 000)
INSERT INTO ASOP_TRANSPORT_STOPS (STOP_ID, FARE_ZONE_ID, REGION_ID, STOP_CODE, STOP_NAME, STOP_ADDRESS, IS_ACTIVE)
SELECT
    md5('d3:stop:' || n)::uuid,
    md5('d2:zone:' || (n % 5000 + 1))::uuid,
    '00000000-0000-0000-0000-000000000103',
    'D3S' || lpad(n::text, 8, '0'),
    'Остановка delta-3 #' || n,
    'г. Симферополь, ул. Тестовая-3, ' || n,
    true
FROM generate_series(1, 3000) AS n
ON CONFLICT DO NOTHING;

-- Schedule (3 000)
INSERT INTO ASOP_SCHEDULE (SCHEDULE_ID, PATH_ID, STOP_ID, DAY_MASK, ARRIVAL_TIME, DWELL_TIME_SEC, REGION_ID, IS_ACTIVE)
SELECT
    md5('d3:sched:' || n)::uuid,
    md5('d2:path:' || (n % 5000 + 1))::uuid,
    md5('d3:stop:' || (n % 3000 + 1))::uuid,
    127,
    ('09:00:00'::time + (n % 720) * interval '1 minute'),
    30,
    '00000000-0000-0000-0000-000000000103',
    true
FROM generate_series(1, 3000) AS n
ON CONFLICT DO NOTHING;

-- ============ FILTERED: по перевозчику ...1403 ============

-- TIDs (3 000)
INSERT INTO ASOP_TIDS (TID_ID, CARRIER_ID, TID_VALUE, STATUS)
SELECT
    md5('d3:tid:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403',
    'D3-TID-' || lpad(n::text, 6, '0'),
    'UNUSED'
FROM generate_series(1, 3000) AS n
ON CONFLICT (TID_ID) DO NOTHING;

-- Vehicles (2 000)
INSERT INTO ASOP_VEHICLES (VEHICLE_ID, CARRIER_ID, VEHICLE_TYPE_ID, VEHICLE_MODEL_ID, VEHICLE_NUMBER, VEHICLE_NAME)
SELECT
    md5('d3:vehicle:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403',
    '00000000-0000-0000-0000-000000001601',
    '00000000-0000-0000-0000-000000001701',
    'D3' || lpad(n::text, 7, '0'),
    'ТС delta-3 #' || n
FROM generate_series(1, 2000) AS n
ON CONFLICT (VEHICLE_ID) DO NOTHING;

-- ============ USER-таблицы (users ...1403) ============

-- Users (2 000)
INSERT INTO ASOP_USERS (USER_ID, FIRST_NAME, LAST_NAME_INITIAL, PATRONYMIC_INITIAL, PHONE)
SELECT
    md5('d3:user:' || n)::uuid,
    'Пользователь' || (n + 40000),
    'П',
    'П',
    '+7-978-400-' || lpad((n % 10000)::text, 4, '0')
FROM generate_series(1, 2000) AS n
ON CONFLICT (USER_ID) DO NOTHING;

-- User Carriers (привязка к ...1403)
INSERT INTO ASOP_USER_CARRIERS (USER_ID, CARRIER_ID)
SELECT
    md5('d3:user:' || n)::uuid,
    '00000000-0000-0000-0000-000000001403'
FROM generate_series(1, 2000) AS n
ON CONFLICT (USER_ID, CARRIER_ID) DO NOTHING;

-- User Regions (привязка к ...0103)
INSERT INTO ASOP_USER_REGIONS (USER_ID, REGION_ID)
SELECT
    md5('d3:user:' || n)::uuid,
    '00000000-0000-0000-0000-000000000103'
FROM generate_series(1, 2000) AS n
ON CONFLICT (USER_ID, REGION_ID) DO NOTHING;

-- ============ CARD-таблицы (карты пользователей ...1403) ============

-- Cards (2 000)
INSERT INTO ASOP_CARDS (CARD_ID, CARD_TYPE_ID, USER_ID, IS_PRIMARY, REGISTERED_AT, REGISTERED_BY_USER_ID)
SELECT
    md5('d3:card:' || n)::uuid,
    '00000000-0000-0000-0000-000000000401',
    md5('d3:user:' || n)::uuid,
    true,
    NOW(),
    md5('d3:user:' || n)::uuid
FROM generate_series(1, 2000) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- Card Mifares
INSERT INTO ASOP_CARD_MIFARES (CARD_ID, UID, ATQA, SAK, PROTOCOL_VERSION, CARD_ROLE, KEY_VERSION)
SELECT
    md5('d3:card:' || n)::uuid,
    decode(lpad(to_hex(2000000000 + n), 16, '0'), 'hex'),
    68,
    8,
    2,
    'PASSENGER_BENEFIT',
    1
FROM generate_series(1, 2000) AS n
ON CONFLICT (CARD_ID) DO NOTHING;

-- Card Tariffs
INSERT INTO ASOP_CARD_TARIFFS (CARD_TARIFF_ID, CARD_ID, TARIFF_TYPE_ID, BALANCE, TRAVEL_COUNT, MAX_TRAVEL_COUNT, EXPIRATION_DATE, IS_ACTIVE)
SELECT
    md5('d3:ctariff:' || n)::uuid,
    md5('d3:card:' || n)::uuid,
    '00000000-0000-0000-0000-000000000501',
    (n * 10)::numeric,
    n % 50,
    100,
    '2026-12-31',
    true
FROM generate_series(1, 2000) AS n
ON CONFLICT (CARD_TARIFF_ID) DO NOTHING;

-- Tariff Rates (2 000)
INSERT INTO ASOP_TARIFF_RATES (TARIFF_RATE_ID, TARIFF_TYPE_ID, CARRIER_ID, ZONE_ID, PATH_ID, PRICE, DESCRIPTION, IS_ACTIVE)
SELECT
    md5('d3:trate:' || n)::uuid,
    '00000000-0000-0000-0000-000000000501',
    '00000000-0000-0000-0000-000000001403',
    md5('d2:zone:' || (n % 5000 + 1))::uuid,
    md5('d2:path:' || (n % 5000 + 1))::uuid,
    (n % 200 + 1)::numeric,
    'Тариф delta-3 #' || n,
    true
FROM generate_series(1, 2000) AS n;
