-- =====================================================
-- v002: Сертификаты терминалов + UNIQUE на TERMINAL_SERIAL
-- =====================================================
-- 1) UNIQUE constraint + index на ASOP_TERMINALS.TERMINAL_SERIAL
--    для предотвращения дублей терминалов по серийнику.
-- 2) Таблица ASOP_TERMINAL_CERTS — история X.509 сертификатов терминалов.
--    IS_CURRENT=true — активный (текущий) сертификат.
--    Частичный UNIQUE index гарантирует, что у терминала
--    не более одного IS_CURRENT=true сертификата.
-- =====================================================

-- ========================
-- 1. UNIQUE на TERMINAL_SERIAL
-- ========================
ALTER TABLE ASOP_TERMINALS
    ADD CONSTRAINT uq_terminals_serial UNIQUE (TERMINAL_SERIAL);
CREATE INDEX IF NOT EXISTS idx_terminals_serial ON ASOP_TERMINALS (TERMINAL_SERIAL);

-- ========================
-- 2. Таблица сертификатов терминалов
-- ========================
CREATE TABLE IF NOT EXISTS ASOP_TERMINAL_CERTS
(
    CERT_ID            UUID         NOT NULL,
    TERMINAL_ID        UUID         NOT NULL,
    CERT_SERIAL        VARCHAR(50)  NOT NULL,
    ISSUED_AT          TIMESTAMP    NOT NULL,
    EXPIRES_AT         TIMESTAMP    NOT NULL,
    REVOKED_AT         TIMESTAMP,
    REVOCATION_REASON  VARCHAR(255),
    IS_CURRENT         BOOLEAN      NOT NULL DEFAULT true,
    CERT_DATA          TEXT         NOT NULL,
    CA_CHAIN           TEXT,
    CREATED_AT         TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT pk_terminal_certs PRIMARY KEY (CERT_ID),
    CONSTRAINT fk_tc_terminal FOREIGN KEY (TERMINAL_ID)
        REFERENCES ASOP_TERMINALS (TERMINAL_ID)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_tc_cert_serial UNIQUE (CERT_SERIAL),
    CONSTRAINT chk_tc_dates CHECK (EXPIRES_AT > ISSUED_AT),
    CONSTRAINT chk_tc_not_both CHECK (
        NOT (IS_CURRENT = true AND REVOKED_AT IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_tc_terminal ON ASOP_TERMINAL_CERTS (TERMINAL_ID);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tc_current_per_terminal
    ON ASOP_TERMINAL_CERTS (TERMINAL_ID) WHERE IS_CURRENT = true;

COMMENT ON TABLE ASOP_TERMINAL_CERTS IS
    'Сертификаты терминалов (X.509, подписаны Intermediate CA). Хранит историю сертификатов для каждого терминала.';
COMMENT ON COLUMN ASOP_TERMINAL_CERTS.CERT_SERIAL IS
    'Серийный номер X.509 сертификата (hex). UNIQUE.';
COMMENT ON COLUMN ASOP_TERMINAL_CERTS.IS_CURRENT IS
    'Признак текущего (активного) сертификата. У терминала не более одного IS_CURRENT=true (partial UNIQUE index).';
COMMENT ON COLUMN ASOP_TERMINAL_CERTS.CERT_DATA IS
    'PEM-кодированный сертификат (для возврата терминалу без повторного обращения к crypto-service).';
COMMENT ON COLUMN ASOP_TERMINAL_CERTS.CA_CHAIN IS
    'PEM-цепочка CA (Root + Intermediate) на момент выпуска.';