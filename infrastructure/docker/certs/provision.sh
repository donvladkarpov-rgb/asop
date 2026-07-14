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

# Special case: crypto-service generates self-signed cert (can't call its own API yet)
# Retry не нужен — crypto-service сам генерирует сертификаты и не зависит от Kafka.
# Если JVM падает, Docker перезапускает контейнер (весь скрипт заново).
if [ "$SERVICE_NAME" = "crypto-service" ]; then
  KEYSTORE_PATH="${KEYSTORE_PATH:-file:/data/server.p12}"
  KEYSTORE_FILE=$(echo "$KEYSTORE_PATH" | sed 's/^file://')

  if [ -f "$KEYSTORE_FILE" ] && [ -f "$CERT_DIR/truststore.p12" ]; then
    info "Keystore and truststore exist, skipping"
    exec "$@"
  fi

  mkdir -p "$(dirname "$KEYSTORE_FILE")"

  info "Generating self-signed cert for crypto-service..."
  openssl ecparam -genkey -name prime256v1 -noout -out /tmp/crypto-key.pem
  openssl req -new -key /tmp/crypto-key.pem -out /tmp/crypto-csr.pem -subj "/CN=crypto-service"
  openssl x509 -req -in /tmp/crypto-csr.pem -signkey /tmp/crypto-key.pem -out /tmp/crypto-cert.pem -days 3650

  openssl pkcs12 -export -in /tmp/crypto-cert.pem -inkey /tmp/crypto-key.pem \
    -name "$KEY_ALIAS" -out "$KEYSTORE_FILE" -password "pass:$PASSWORD"

  keytool -importcert -keystore "$CERT_DIR/truststore.p12" -storepass "$PASSWORD" \
    -storetype PKCS12 -alias crypto-ca -file /tmp/crypto-cert.pem -noprompt

  rm -f /tmp/crypto-key.pem /tmp/crypto-csr.pem /tmp/crypto-cert.pem

  info "Self-signed cert generated: $KEYSTORE_FILE"
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
