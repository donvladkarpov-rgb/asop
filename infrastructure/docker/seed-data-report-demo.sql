-- =============================================================================
-- seed-data-report-demo.sql — демо-данные для отчёта-реестра транзакций
-- по операциям регистрации проезда (web-admin → «Отчёты»).
--
-- ВАЖНО: запускать ПОСЛЕ seed-data.sql (нужны организация/перевозчик/ТС/путь/
-- остановки/карты/льготы Москвы). Скрипт полностью идемпотентен (ON CONFLICT DO NOTHING).
--
--   for f in seed-data.sql seed-data-report-demo.sql; do
--     docker compose -f infrastructure/docker/docker-compose.yml \
--       exec -T postgres psql -U asop -d asop < infrastructure/docker/$f
--   done
--
-- В seed-data.sql нет ASOP_TERMINALS / ASOP_SESSIONS / ASOP_TRANSACTIONS —
-- создаём их здесь. Все поездки ставятся внутрь текущих суток
-- (date_trunc('day', NOW()) + …), чтобы отчёт за сегодня был непустым сразу.
--
-- Охват демо:
--   * 2 смены × 2 рейса = 4 рейса (2 водителя, 2 терминала);
--   * МИФЕР-оплата поездками (amount 0, appliedTariff «Списание поездок»);
--   * банковская карта (ASOP_BANK_PAYMENTS FARE → «Банковская карта», сумма, RRN);
--   * валидация без списания (…0803 / …0903);
--   * пассажир с льготой (metadata.benefitId → шаг льготы по остатку поездок).
-- Фискализация не демонстрируется: ASOP_CARRIER_FISCALIZERS в сидах пуста,
-- поэтому fiscal-колонки отчёта остаются пустыми (см. doc: фискализация — вне MVP).
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1. Терминалы (в seed-data.sql их нет вообще)
-- -----------------------------------------------------------------------------
INSERT INTO ASOP_TERMINALS (TERMINAL_ID, CARRIER_ID, TERMINAL_NUMBER, TERMINAL_SERIAL, TERMINAL_MODEL, STATUS, TIMEZONE)
VALUES ('00000000-0000-0000-0000-600000000100', '00000000-0000-0000-0000-000000001401', 'ТРМ-МСК-01', 'DEMO-REPORT-TERM-001', 'Feitian F20', 'IN_OPERATION', 'Europe/Moscow'),
       ('00000000-0000-0000-0000-600000000200', '00000000-0000-0000-0000-000000001401', 'ТРМ-МСК-02', 'DEMO-REPORT-TERM-002', 'Feitian F20', 'IN_OPERATION', 'Europe/Moscow')
ON CONFLICT (TERMINAL_ID) DO NOTHING;

-- ФИО + дата рождения водителей: LAST_NAME в сидах не заполнен, а отчёт
-- показывает «ФИО водителя» (и дату рождения в скобках, если заведена).
UPDATE ASOP_USERS
SET LAST_NAME = 'Сергеев',
    BIRTH_DATE = DATE '1985-04-12'
WHERE USER_ID = '00000000-0000-0000-0000-300000000600';

UPDATE ASOP_USERS
SET LAST_NAME = 'Дмитриев',
    BIRTH_DATE = DATE '1990-11-03'
WHERE USER_ID = '00000000-0000-0000-0000-300000000800';

-- -----------------------------------------------------------------------------
-- 2. Смены (SHIFT …000000000601) — 2 штуки, разные водители и терминалы
-- -----------------------------------------------------------------------------
INSERT INTO ASOP_SESSIONS (SESSION_ID, SESSION_TYPE_ID, PARENT_SESSION_ID, TERMINAL_ID, OPENED_BY_USER_ID, STARTED_AT, STARTED_AT_LOCAL, CLOSED_AT, CLOSED_AT_LOCAL, EXPIRATION_TIME, STATUS)
VALUES ('00000000-0000-0000-0000-610000000100', '00000000-0000-0000-0000-000000000601', NULL,
        '00000000-0000-0000-0000-600000000100', '00000000-0000-0000-0000-300000000600',
        date_trunc('day', NOW()) + INTERVAL '8 hours 30 minutes',
        (date_trunc('day', NOW()) + INTERVAL '8 hours 30 minutes')::timestamp,
        date_trunc('day', NOW()) + INTERVAL '11 hours 0 minutes',
        (date_trunc('day', NOW()) + INTERVAL '11 hours 0 minutes')::timestamp,
        date_trunc('day', NOW()) + INTERVAL '20 hours 30 minutes', 'CLOSED'),
       ('00000000-0000-0000-0000-610000000200', '00000000-0000-0000-0000-000000000601', NULL,
        '00000000-0000-0000-0000-600000000200', '00000000-0000-0000-0000-300000000800',
        date_trunc('day', NOW()) + INTERVAL '12 hours 30 minutes',
        (date_trunc('day', NOW()) + INTERVAL '12 hours 30 minutes')::timestamp,
        date_trunc('day', NOW()) + INTERVAL '15 hours 0 minutes',
        (date_trunc('day', NOW()) + INTERVAL '15 hours 0 minutes')::timestamp,
        date_trunc('day', NOW()) + INTERVAL '24 hours 30 minutes', 'CLOSED')
ON CONFLICT (SESSION_ID) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. Рейсы (TRIP …000000000603) — по 2 на смену, путь и ТС Москвы
--    Путь …220000000100: маршрут …200000000100, пункт отправления …180000000100,
--    пункт назначения …180000001000.
-- -----------------------------------------------------------------------------
INSERT INTO ASOP_SESSIONS (SESSION_ID, SESSION_TYPE_ID, PARENT_SESSION_ID, TERMINAL_ID, PATH_ID, VEHICLE_ID, OPENED_BY_USER_ID, STARTED_AT, STARTED_AT_LOCAL, CLOSED_AT, CLOSED_AT_LOCAL, EXPIRATION_TIME, STATUS)
VALUES
  -- Смена 1 / водитель Сергеев С.
  ('00000000-0000-0000-0000-620000000100', '00000000-0000-0000-0000-000000000603', '00000000-0000-0000-0000-610000000100',
   '00000000-0000-0000-0000-600000000100', '00000000-0000-0000-0000-220000000100', '00000000-0000-0000-0000-130000000100', '00000000-0000-0000-0000-300000000600',
   date_trunc('day', NOW()) + INTERVAL '8 hours 35 minutes', (date_trunc('day', NOW()) + INTERVAL '8 hours 35 minutes')::timestamp,
   date_trunc('day', NOW()) + INTERVAL '9 hours 40 minutes', (date_trunc('day', NOW()) + INTERVAL '9 hours 40 minutes')::timestamp,
   date_trunc('day', NOW()) + INTERVAL '11 hours 0 minutes', 'CLOSED'),
  ('00000000-0000-0000-0000-620000000200', '00000000-0000-0000-0000-000000000603', '00000000-0000-0000-0000-610000000100',
   '00000000-0000-0000-0000-600000000100', '00000000-0000-0000-0000-220000000100', '00000000-0000-0000-0000-130000000100', '00000000-0000-0000-0000-300000000600',
   date_trunc('day', NOW()) + INTERVAL '9 hours 45 minutes', (date_trunc('day', NOW()) + INTERVAL '9 hours 45 minutes')::timestamp,
   date_trunc('day', NOW()) + INTERVAL '10 hours 50 minutes', (date_trunc('day', NOW()) + INTERVAL '10 hours 50 minutes')::timestamp,
   date_trunc('day', NOW()) + INTERVAL '11 hours 0 minutes', 'CLOSED'),
  -- Смена 2 / водитель Дмитриев Д.
  ('00000000-0000-0000-0000-620000000300', '00000000-0000-0000-0000-000000000603', '00000000-0000-0000-0000-610000000200',
   '00000000-0000-0000-0000-600000000200', '00000000-0000-0000-0000-220000000100', '00000000-0000-0000-0000-130000000200', '00000000-0000-0000-0000-300000000800',
   date_trunc('day', NOW()) + INTERVAL '12 hours 35 minutes', (date_trunc('day', NOW()) + INTERVAL '12 hours 35 minutes')::timestamp,
   date_trunc('day', NOW()) + INTERVAL '13 hours 40 minutes', (date_trunc('day', NOW()) + INTERVAL '13 hours 40 minutes')::timestamp,
   date_trunc('day', NOW()) + INTERVAL '15 hours 0 minutes', 'CLOSED'),
  ('00000000-0000-0000-0000-620000000400', '00000000-0000-0000-0000-000000000603', '00000000-0000-0000-0000-610000000200',
   '00000000-0000-0000-0000-600000000200', '00000000-0000-0000-0000-220000000100', '00000000-0000-0000-0000-130000000200', '00000000-0000-0000-0000-300000000800',
   date_trunc('day', NOW()) + INTERVAL '13 hours 45 minutes', (date_trunc('day', NOW()) + INTERVAL '13 hours 45 minutes')::timestamp,
   date_trunc('day', NOW()) + INTERVAL '14 hours 50 minutes', (date_trunc('day', NOW()) + INTERVAL '14 hours 50 minutes')::timestamp,
   date_trunc('day', NOW()) + INTERVAL '15 hours 0 minutes', 'CLOSED')
ON CONFLICT (SESSION_ID) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. Транзакции регистрации проезда
--    tripsAt (epoch ms) кладём в METADATA — по нему отчёт датирует поездку.
-- -----------------------------------------------------------------------------
WITH demo (tx_id, trip_id, trip_at, type_id, result_id, amount, trips_after, card_id, tariff_id, bank_amount, benefit_id) AS (
    VALUES
      -- Рейс 1: МИФЕР-оплата поездками (водительская карта, без списания денег)
      ('00000000-0000-0000-0000-630000000101'::uuid, '00000000-0000-0000-0000-620000000100'::uuid, date_trunc('day', NOW()) + INTERVAL '8 hours 42 minutes', '00000000-0000-0000-0000-000000000801'::uuid, '00000000-0000-0000-0000-000000000901'::uuid, 0.00, 4, '00000000-0000-0000-0000-400000000100'::uuid, '00000000-0000-0000-0000-430000000100'::uuid, NULL::numeric, NULL::uuid),
      -- Рейс 1: пассажир с льготой (метаданные указывают benefitId → шаг льготы 50%)
      ('00000000-0000-0000-0000-630000000102'::uuid, '00000000-0000-0000-0000-620000000100'::uuid, date_trunc('day', NOW()) + INTERVAL '8 hours 51 minutes', '00000000-0000-0000-0000-000000000801'::uuid, '00000000-0000-0000-0000-000000000901'::uuid, 0.00, 12, '00000000-0000-0000-0000-400000000300'::uuid, '00000000-0000-0000-0000-430000000300'::uuid, NULL::numeric, '00000000-0000-0000-0000-150000000200'::uuid),
      -- Рейс 1: банковская карта (оплата эквайером → «Банковская карта», сумма 62.00, RRN)
      ('00000000-0000-0000-0000-630000000103'::uuid, '00000000-0000-0000-0000-620000000100'::uuid, date_trunc('day', NOW()) + INTERVAL '9 hours 4 minutes', '00000000-0000-0000-0000-000000000801'::uuid, '00000000-0000-0000-0000-000000000901'::uuid, 62.00, NULL, '00000000-0000-0000-0000-400000000200'::uuid, NULL::uuid, 62.00, NULL::uuid),
      -- Рейс 1: валидация без списания (анонимная карта, …0803 / …0903)
      ('00000000-0000-0000-0000-630000000104'::uuid, '00000000-0000-0000-0000-620000000100'::uuid, date_trunc('day', NOW()) + INTERVAL '9 hours 12 minutes', '00000000-0000-0000-0000-000000000803'::uuid, '00000000-0000-0000-0000-000000000903'::uuid, 0.00, NULL, '00000000-0000-0000-0000-401000000800'::uuid, '00000000-0000-0000-0000-431000000800'::uuid, NULL::numeric, NULL::uuid),
      -- Рейс 2 (смена 1): МИФЕР + банковская карта
      ('00000000-0000-0000-0000-630000000105'::uuid, '00000000-0000-0000-0000-620000000200'::uuid, date_trunc('day', NOW()) + INTERVAL '9 hours 52 minutes', '00000000-0000-0000-0000-000000000801'::uuid, '00000000-0000-0000-0000-000000000901'::uuid, 0.00, 5, '00000000-0000-0000-0000-400000000100'::uuid, '00000000-0000-0000-0000-430000000100'::uuid, NULL::numeric, NULL::uuid),
      ('00000000-0000-0000-0000-630000000106'::uuid, '00000000-0000-0000-0000-620000000200'::uuid, date_trunc('day', NOW()) + INTERVAL '10 hours 3 minutes', '00000000-0000-0000-0000-000000000801'::uuid, '00000000-0000-0000-0000-000000000901'::uuid, 62.00, NULL, '00000000-0000-0000-0000-400000000400'::uuid, NULL::uuid, 62.00, NULL::uuid),
      -- Рейс 3 (смена 2): валидация без списания + льготный пассажир
      ('00000000-0000-0000-0000-630000000107'::uuid, '00000000-0000-0000-0000-620000000300'::uuid, date_trunc('day', NOW()) + INTERVAL '12 hours 44 minutes', '00000000-0000-0000-0000-000000000803'::uuid, '00000000-0000-0000-0000-000000000903'::uuid, 0.00, NULL, '00000000-0000-0000-0000-401000000800'::uuid, '00000000-0000-0000-0000-431000000800'::uuid, NULL::numeric, NULL::uuid),
      ('00000000-0000-0000-0000-630000000108'::uuid, '00000000-0000-0000-0000-620000000300'::uuid, date_trunc('day', NOW()) + INTERVAL '12 hours 58 minutes', '00000000-0000-0000-0000-000000000801'::uuid, '00000000-0000-0000-0000-000000000901'::uuid, 0.00, 31, '00000000-0000-0000-0000-400000000300'::uuid, '00000000-0000-0000-0000-430000000300'::uuid, NULL::numeric, '00000000-0000-0000-0000-150000000200'::uuid),
      -- Рейс 4 (смена 2): МИФЕР + банковская карта
      ('00000000-0000-0000-0000-630000000109'::uuid, '00000000-0000-0000-0000-620000000400'::uuid, date_trunc('day', NOW()) + INTERVAL '13 hours 52 minutes', '00000000-0000-0000-0000-000000000801'::uuid, '00000000-0000-0000-0000-000000000901'::uuid, 0.00, 6, '00000000-0000-0000-0000-400000000100'::uuid, '00000000-0000-0000-0000-430000000100'::uuid, NULL::numeric, NULL::uuid),
      ('00000000-0000-0000-0000-630000000110'::uuid, '00000000-0000-0000-0000-620000000400'::uuid, date_trunc('day', NOW()) + INTERVAL '14 hours 7 minutes', '00000000-0000-0000-0000-000000000801'::uuid, '00000000-0000-0000-0000-000000000901'::uuid, 62.00, NULL, '00000000-0000-0000-0000-400000000200'::uuid, NULL::uuid, 62.00, NULL::uuid)
)
INSERT INTO ASOP_TRANSACTIONS (TRANSACTION_ID, STARTED_AT, COMPLETED_AT, SESSION_ID, TRANSACTION_TYPE_ID, TRANSACTION_RESULT_ID, AMOUNT, CURRENCY, METADATA, CREATED_AT)
SELECT tx_id,
       trip_at,
       trip_at + INTERVAL '3 seconds',
       trip_id,
       type_id,
       result_id,
       amount,
       'RUB',
       jsonb_strip_nulls(jsonb_build_object(
           'tripsAt', (EXTRACT(EPOCH FROM trip_at) * 1000)::bigint,
           'tripsAfter', trips_after,
           'anonymous', card_id = '00000000-0000-0000-0000-401000000800'::uuid,
           'benefitId', benefit_id)),
       trip_at + INTERVAL '5 seconds'
FROM demo
ON CONFLICT (TRANSACTION_ID) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. Детализация по картам (CARD_ROLE = 'PAYER')
-- -----------------------------------------------------------------------------
WITH demo (tc_id, tx_id, card_id, tariff_id, balance_before, balance_after) AS (
    VALUES
      ('00000000-0000-0000-0000-640000000101'::uuid, '00000000-0000-0000-0000-630000000101'::uuid, '00000000-0000-0000-0000-400000000100'::uuid, '00000000-0000-0000-0000-430000000100'::uuid, 4, 3),
      ('00000000-0000-0000-0000-640000000102'::uuid, '00000000-0000-0000-0000-630000000102'::uuid, '00000000-0000-0000-0000-400000000300'::uuid, '00000000-0000-0000-0000-430000000300'::uuid, 13, 12),
      ('00000000-0000-0000-0000-640000000103'::uuid, '00000000-0000-0000-0000-630000000103'::uuid, '00000000-0000-0000-0000-400000000200'::uuid, NULL::uuid, NULL::numeric, NULL::numeric),
      ('00000000-0000-0000-0000-640000000104'::uuid, '00000000-0000-0000-0000-630000000104'::uuid, '00000000-0000-0000-0000-401000000800'::uuid, '00000000-0000-0000-0000-431000000800'::uuid, NULL::numeric, NULL::numeric),
      ('00000000-0000-0000-0000-640000000105'::uuid, '00000000-0000-0000-0000-630000000105'::uuid, '00000000-0000-0000-0000-400000000100'::uuid, '00000000-0000-0000-0000-430000000100'::uuid, 3, 2),
      ('00000000-0000-0000-0000-640000000106'::uuid, '00000000-0000-0000-0000-630000000106'::uuid, '00000000-0000-0000-0000-400000000400'::uuid, NULL::uuid, NULL::numeric, NULL::numeric),
      ('00000000-0000-0000-0000-640000000107'::uuid, '00000000-0000-0000-0000-630000000107'::uuid, '00000000-0000-0000-0000-401000000800'::uuid, '00000000-0000-0000-0000-431000000800'::uuid, NULL::numeric, NULL::numeric),
      ('00000000-0000-0000-0000-640000000108'::uuid, '00000000-0000-0000-0000-630000000108'::uuid, '00000000-0000-0000-0000-400000000300'::uuid, '00000000-0000-0000-0000-430000000300'::uuid, 32, 31),
      ('00000000-0000-0000-0000-640000000109'::uuid, '00000000-0000-0000-0000-630000000109'::uuid, '00000000-0000-0000-0000-400000000100'::uuid, '00000000-0000-0000-0000-430000000100'::uuid, 2, 1),
      ('00000000-0000-0000-0000-640000000110'::uuid, '00000000-0000-0000-0000-630000000110'::uuid, '00000000-0000-0000-0000-400000000200'::uuid, NULL::uuid, NULL::numeric, NULL::numeric)
)
INSERT INTO ASOP_TRANSACTION_CARDS (TRANSACTION_CARD_ID, TRANSACTION_ID, CARD_ID, CARD_ROLE, TARIFF_APPLIED_ID, BALANCE_BEFORE, BALANCE_AFTER)
SELECT tc_id, tx_id, card_id, 'PAYER', tariff_id, balance_before, balance_after
FROM demo
ON CONFLICT (TRANSACTION_CARD_ID) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 6. Платежи банковской картой за проезд (PAYMENT_TYPE = 'FARE')
--    Именно они превращают строку отчёта в «Банковская карта» + сумму + RRN.
-- -----------------------------------------------------------------------------
INSERT INTO ASOP_BANK_PAYMENTS (PAYMENT_ID, CARD_ID, CARD_TOKEN, PAN_LAST4, BIN, AMOUNT, CURRENCY, PAYMENT_TYPE, STATUS, PROVIDER, RRN, TERMINAL_ID, SESSION_ID, TRANSACTION_ID, OCCURRED_AT)
SELECT ('00000000-0000-0000-0000-' || '650000000' || right(replace(t.TRANSACTION_ID::text, '-', ''), 3))::uuid,
       tc.CARD_ID,
       cb.PAN_TOKEN,
       cb.PAN_LAST4,
       cb.BIN,
       t.AMOUNT,
       'RUB',
       'FARE',
       'AUTHORIZED',
       'MOCK',
       'RRN' || right(replace(t.TRANSACTION_ID::text, '-', ''), 3),
       tm.TERMINAL_ID,
       s.SESSION_ID,
       t.TRANSACTION_ID,
       t.STARTED_AT
FROM ASOP_TRANSACTIONS t
         JOIN ASOP_TRANSACTION_CARDS tc ON tc.TRANSACTION_ID = t.TRANSACTION_ID AND tc.CARD_ROLE = 'PAYER'
         JOIN ASOP_CARD_BANKS cb ON cb.CARD_ID = tc.CARD_ID
         LEFT JOIN ASOP_SESSIONS s ON s.SESSION_ID = t.SESSION_ID
         LEFT JOIN ASOP_TERMINALS tm ON tm.TERMINAL_ID = s.TERMINAL_ID
WHERE t.TRANSACTION_ID IN ('00000000-0000-0000-0000-630000000103',
                           '00000000-0000-0000-0000-630000000106',
                           '00000000-0000-0000-0000-630000000110')
ON CONFLICT (PAYMENT_ID) DO NOTHING;

COMMIT;

-- Сводка демо-данных
SELECT 'Терминалы' AS entity, COUNT(*) FROM ASOP_TERMINALS WHERE TERMINAL_SERIAL LIKE 'DEMO-REPORT-TERM-%'
UNION ALL SELECT 'Смены', COUNT(*) FROM ASOP_SESSIONS WHERE SESSION_ID IN ('00000000-0000-0000-0000-610000000100', '00000000-0000-0000-0000-610000000200')
UNION ALL SELECT 'Рейсы', COUNT(*) FROM ASOP_SESSIONS WHERE SESSION_ID BETWEEN '00000000-0000-0000-0000-620000000100' AND '00000000-0000-0000-0000-620000000400'
UNION ALL SELECT 'Транзакции', COUNT(*) FROM ASOP_TRANSACTIONS WHERE TRANSACTION_ID BETWEEN '00000000-0000-0000-0000-630000000101' AND '00000000-0000-0000-0000-630000000110'
UNION ALL SELECT 'Платежи картой', COUNT(*) FROM ASOP_BANK_PAYMENTS WHERE TRANSACTION_ID BETWEEN '00000000-0000-0000-0000-630000000101' AND '00000000-0000-0000-0000-630000000110';