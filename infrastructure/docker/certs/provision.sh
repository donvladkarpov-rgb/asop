#!/bin/bash
set -e
set -o pipefail

CRYPTO_URL="${CRYPTO_URL:-https://crypto-service:8081}"
CERT_DIR="${CERT_DIR:-/tmp/certs}"
SERVICE_NAME="${SERVICE_NAME:-unknown}"
KEY_ALIAS="${KEY_ALIAS:-$SERVICE_NAME}"
PASSWORD="${CERTS_PASSWORD:-changeit}"
DNS_NAMES="${DNS_NAMES:-$SERVICE_NAME}"

mkdir -p "$CERT_DIR"

info() { echo "[provision] $*"; }
warn() { echo "[provision][WARN] $*" >&2; }

# ---- JVM runner with retry and SIGTERM forwarding ----
# На финальной попытке exec — JVM становится PID 1, получает SIGTERM напрямую.
# На промежуточных — фоновый процесс + trap для корректной пересылки SIGTERM.
MAX_RESTARTS=5
run_jvm() {
  local attempt="$1"
  shift
  if [ "$attempt" -eq "$MAX_RESTARTS" ]; then
    info "Starting JVM ($SERVICE_NAME), final attempt $attempt/$MAX_RESTARTS: $*"
    exec "$@"
  fi
  info "Starting JVM ($SERVICE_NAME), attempt $attempt/$MAX_RESTARTS: $*"
  set +e
  "$@" &
  local child=$!
  trap 'kill -TERM "$child" 2>/dev/null' TERM
  wait "$child"
  local exit_code=$?
  set -e
  trap - TERM
  return $exit_code
}

# Special case: crypto-service is the CA. Spring Boot reads server.p12 BEFORE
# RootCaService.init runs (Netty binds in onRefresh, beans created later), so we
# cannot rely on Java to seed the leaf cert in time. Здесь мы собираем полную
# цепочку в bash: Root CA -> Intermediate CA -> server.p12 (signed by
# Intermediate CA). Java-RootCaService.loadRootCa()/loadIntermediateCa() при
# старте просто загрузит эти готовые keystore (существующие файлы -> skip).
if [ "$SERVICE_NAME" = "crypto-service" ]; then
  KEYSTORE_PATH="${KEYSTORE_PATH:-file:/data/server.p12}"
  KEYSTORE_FILE=$(echo "$KEYSTORE_PATH" | sed 's/^file://')
  DATA_DIR="$(dirname "$KEYSTORE_FILE")"
  ROOT_P12="$DATA_DIR/root-ca.p12"
  INT_P12="$DATA_DIR/intermediate-ca.p12"

  mkdir -p "$DATA_DIR" "$CERT_DIR"

  # Если есть pre-generated dev CA (bind mount, переживает down -v) — копируем и используем
  if [ -f "/data/dev-ca/root-ca.p12" ] && [ -f "/data/dev-ca/intermediate-ca.p12" ] && [ -f "/data/dev-ca/server.p12" ] && [ -f "/data/dev-ca/truststore.p12" ]; then
    cp /data/dev-ca/root-ca.p12 "$ROOT_P12"
    cp /data/dev-ca/intermediate-ca.p12 "$INT_P12"
    cp /data/dev-ca/server.p12 "$KEYSTORE_FILE"
    cp /data/dev-ca/truststore.p12 "$CERT_DIR/truststore.p12"
    info "Using pre-generated dev CA from /data/dev-ca (bind mount, survives down -v)"
    exec "$@"
  fi

  if [ -f "$KEYSTORE_FILE" ] && [ -f "$ROOT_P12" ] && [ -f "$INT_P12" ] && [ -f "$CERT_DIR/truststore.p12" ]; then
    info "Crypto keystores exist (volume), skipping CA bootstrap"
    exec "$@"
  fi

  rm -f /tmp/root-key.pem /tmp/root-cert.pem /tmp/root-csr.pem /tmp/int-key.pem /tmp/int-csr.pem \
        /tmp/int-cert.pem /tmp/srv-key.pem /tmp/srv-csr.pem /tmp/srv-cert.pem \
        /tmp/root-cert.srl /tmp/int-cert.srl /tmp/root-ext.cnf /tmp/san.cnf

# ---- 1. Root CA (self-signed) ----
  info "Bootstrap: generating Root CA..."
  openssl ecparam -genkey -name prime256v1 -noout -out /tmp/root-key.pem
  printf "[ca_ext]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n" > /tmp/root-ext.cnf
  openssl req -new -key /tmp/root-key.pem -out /tmp/root-csr.pem -subj "/CN=ASOP Root CA,O=ASOP,C=RU"
  openssl x509 -req -in /tmp/root-csr.pem -signkey /tmp/root-key.pem -out /tmp/root-cert.pem -days 3650 \
    -extfile /tmp/root-ext.cnf -extensions ca_ext
  openssl pkcs12 -export -inkey /tmp/root-key.pem -in /tmp/root-cert.pem \
    -name asop-root-ca -out "$ROOT_P12" -password "pass:$PASSWORD"

  # ---- 2. Intermediate CA (signed by Root CA) ----
  info "Bootstrap: generating Intermediate CA..."
  openssl ecparam -genkey -name prime256v1 -noout -out /tmp/int-key.pem
  openssl req -new -key /tmp/int-key.pem -out /tmp/int-csr.pem \
    -subj "/CN=ASOP Intermediate CA,O=ASOP,C=RU"
  openssl x509 -req -in /tmp/int-csr.pem -CA /tmp/root-cert.pem -CAkey /tmp/root-key.pem \
    -CAcreateserial -out /tmp/int-cert.pem -days 1825 -extfile /tmp/root-ext.cnf -extensions ca_ext
  openssl pkcs12 -export -inkey /tmp/int-key.pem -in /tmp/int-cert.pem \
    -name asop-intermediate-ca -out "$INT_P12" -password "pass:$PASSWORD"

  # ---- 3. Server leaf cert (signed by Intermediate CA, with SAN) ----
  info "Bootstrap: generating crypto-service server cert signed by Intermediate CA..."
  openssl ecparam -genkey -name prime256v1 -noout -out /tmp/srv-key.pem
  openssl req -new -key /tmp/srv-key.pem -out /tmp/srv-csr.pem -subj "/CN=crypto-service,O=ASOP"
  printf "[v3_req]\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nbasicConstraints=critical,CA:FALSE\nsubjectAltName=DNS:crypto-service,DNS:localhost\n" > /tmp/san.cnf
  openssl x509 -req -in /tmp/srv-csr.pem -CA /tmp/int-cert.pem -CAkey /tmp/int-key.pem \
    -CAcreateserial -out /tmp/srv-cert.pem -days 365 \
    -extfile /tmp/san.cnf -extensions v3_req
  openssl pkcs12 -export -inkey /tmp/srv-key.pem -in /tmp/srv-cert.pem \
    -name "$KEY_ALIAS" -out "$KEYSTORE_FILE" -password "pass:$PASSWORD"

  # ---- 4. Truststore (Root CA + Intermediate CA) ----
  info "Bootstrap: building truststore with Root CA + Intermediate CA..."
  keytool -importcert -keystore "$CERT_DIR/truststore.p12" -storepass "$PASSWORD" \
    -storetype PKCS12 -alias root-ca -file /tmp/root-cert.pem -noprompt
  keytool -importcert -keystore "$CERT_DIR/truststore.p12" -storepass "$PASSWORD" \
    -storetype PKCS12 -alias intermediate-ca -file /tmp/int-cert.pem -noprompt

  rm -f /tmp/root-key.pem /tmp/root-cert.pem /tmp/root-csr.pem /tmp/int-key.pem /tmp/int-csr.pem \
        /tmp/int-cert.pem /tmp/srv-key.pem /tmp/srv-csr.pem /tmp/srv-cert.pem \
        /tmp/root-cert.srl /tmp/int-cert.srl /tmp/root-ext.cnf /tmp/san.cnf

  info "Crypto CA bootstrap complete: root-ca.p12, intermediate-ca.p12, server.p12 (Intermediate-CA-signed)"
  exec "$@"
fi

info "Waiting for crypto-service at $CRYPTO_URL..."
i=0
while [ $i -lt 60 ]; do
  i=$((i + 1))
  if curl -skf "$CRYPTO_URL/actuator/health" >/dev/null 2>&1; then
    info "crypto-service is ready"
    break
  fi
  if [ $i -eq 60 ]; then
    echo "ERROR: crypto-service not available after 60s"
    exit 1
  fi
  sleep 2
done

# Certs exist from a previous run — skip provisioning.
# После restart certs уже есть, JVM стартует через exec (получает SIGTERM как PID 1).
# Если Kafka недоступна и JVM падает, Docker перезапускает контейнер (весь скрипт заново).
if [ -f "$CERT_DIR/truststore.p12" ] && [ -f "$CERT_DIR/$SERVICE_NAME.p12" ]; then
  info "Certs already exist, skipping provisioning"
  exec "$@"
fi

# ---- Fetch CA chain ----
info "Fetching CA chain from crypto-service..."
CA_CHAIN=$(curl -skf "$CRYPTO_URL/api/v1/certificates/ca-chain")
echo "$CA_CHAIN" > "$CERT_DIR/ca-chain.pem"

# Extract Intermediate CA (first cert) and Root CA (second cert) from chain
awk 'BEGIN {c=0} /-----BEGIN CERTIFICATE-----/ {c++; if (c==1) in_cert=1} in_cert {print} /-----END CERTIFICATE-----/ {if (in_cert && c==1) {in_cert=0; exit}}' \
  "$CERT_DIR/ca-chain.pem" > "$CERT_DIR/intermediate-ca.pem"
awk 'BEGIN {c=0} /-----BEGIN CERTIFICATE-----/ {c++; if (c==2) in_cert=1} in_cert {print} /-----END CERTIFICATE-----/ {if (in_cert) exit}' \
  "$CERT_DIR/ca-chain.pem" > "$CERT_DIR/root-ca.pem"

info "Building truststore (Root CA + Intermediate CA)..."
rm -f "$CERT_DIR/truststore.p12"
keytool -importcert -keystore "$CERT_DIR/truststore.p12" -storepass "$PASSWORD" \
  -storetype PKCS12 -alias root-ca -file "$CERT_DIR/root-ca.pem" -noprompt
keytool -importcert -keystore "$CERT_DIR/truststore.p12" -storepass "$PASSWORD" \
  -storetype PKCS12 -alias intermediate-ca -file "$CERT_DIR/intermediate-ca.pem" -noprompt

# ---- Generate EC keypair ----
info "Generating EC P-256 keypair..."
openssl ecparam -genkey -name prime256v1 -noout -out "$CERT_DIR/$SERVICE_NAME-key.pem"

# Extract public key in DER + Base64
openssl ec -in "$CERT_DIR/$SERVICE_NAME-key.pem" -pubout -outform DER 2>/dev/null | base64 > "$CERT_DIR/$SERVICE_NAME-pub.b64"
PUBKEY=$(tr -d '\n' < "$CERT_DIR/$SERVICE_NAME-pub.b64")

# ---- Build JSON payload ----
DNS_JSON=""
OLD_IFS="$IFS"; IFS=","
for dns in $DNS_NAMES; do
  DNS_JSON="${DNS_JSON}\"${dns}\","
done
IFS="$OLD_IFS"
DNS_JSON="[${DNS_JSON%,}]"
JSON="{\"commonName\":\"$SERVICE_NAME\",\"publicKeyBase64\":\"$PUBKEY\",\"dnsNames\":$DNS_JSON}"

# ---- Request server cert ----
info "Requesting server cert from crypto-service..."
CERT_B64=$(curl -skf -X POST "$CRYPTO_URL/api/v1/certificates/server" \
  -H "Content-Type: application/json" \
  -d "$JSON" | sed 's/.*"certificateBase64":"\([^"]*\)".*/\1/')

echo "$CERT_B64" | base64 -d > "$CERT_DIR/$SERVICE_NAME-cert.der"
openssl x509 -inform DER -in "$CERT_DIR/$SERVICE_NAME-cert.der" -out "$CERT_DIR/$SERVICE_NAME-cert.pem"

info "Building keystore..."
rm -f "$CERT_DIR/$SERVICE_NAME.p12"
openssl pkcs12 -export \
  -in "$CERT_DIR/$SERVICE_NAME-cert.pem" \
  -inkey "$CERT_DIR/$SERVICE_NAME-key.pem" \
  -name "$KEY_ALIAS" \
  -out "$CERT_DIR/$SERVICE_NAME.p12" \
  -password "pass:$PASSWORD"

rm -f "$CERT_DIR/$SERVICE_NAME-key.pem" "$CERT_DIR/$SERVICE_NAME-pub.b64" \
      "$CERT_DIR/$SERVICE_NAME-cert.der" "$CERT_DIR/$SERVICE_NAME-cert.pem" \
      "$CERT_DIR/ca-chain.pem" "$CERT_DIR/root-ca.pem" "$CERT_DIR/intermediate-ca.pem"

info "Provisioning complete: $CERT_DIR/$SERVICE_NAME.p12"

# ---- Run JVM with restart-on-failure (cold-start race-condition resilience) ----
# Сервисы с @KafkaListener могут упасть, если Kafka ещё не поднялась при cold start.
# В режиме docker-compose up зависимости обычно обеспечивают порядок старта, но
# в dev с wave-based ручным запуском первый процесс часто проигрывает гонку.
# Перезапускаем до MAX_RESTARTS раз с нарастающей задержкой.
# run_jvm() использует exec на финальной попытке (JVM → PID 1, получает SIGTERM напрямую)
# и background + trap на промежуточных (SIGTERM пересылается дочернему JVM).
ATTEMPT=0
while [ "$ATTEMPT" -lt "$MAX_RESTARTS" ]; do
  ATTEMPT=$((ATTEMPT + 1))
  set +e
  run_jvm "$ATTEMPT" "$@"
  EXIT_CODE=$?
  set -e
  if [ "$EXIT_CODE" -eq 0 ]; then
    info "JVM exited cleanly"
    exit 0
  fi
  DELAY=$((ATTEMPT * 5))
  warn "$SERVICE_NAME exited with code $EXIT_CODE, restarting in ${DELAY}s..."
  sleep "$DELAY"
done
warn "ERROR: $SERVICE_NAME failed $MAX_RESTARTS times"
exit 1
