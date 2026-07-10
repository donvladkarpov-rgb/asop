#!/bin/sh
set -e

CRYPTO_URL="${CRYPTO_URL:-https://crypto-service:8081}"
CERT_DIR="${CERT_DIR:-/tmp/certs}"
SERVICE_NAME="${SERVICE_NAME:-unknown}"
KEY_ALIAS="${KEY_ALIAS:-$SERVICE_NAME}"
PASSWORD="${CERTS_PASSWORD:-changeit}"
DNS_NAMES="${DNS_NAMES:-$SERVICE_NAME}"

mkdir -p "$CERT_DIR"

info() { echo "[provision] $*"; }

# Special case: crypto-service generates self-signed cert (can't call its own API yet)
if [ "$SERVICE_NAME" = "crypto-service" ]; then
  KEYSTORE_PATH="${KEYSTORE_PATH:-file:/data/server.p12}"
  KEYSTORE_FILE=$(echo "$KEYSTORE_PATH" | sed 's/^file://')

  if [ -f "$KEYSTORE_FILE" ]; then
    info "Keystore exists, skipping"
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

if [ -f "$CERT_DIR/truststore.p12" ] && [ -f "$CERT_DIR/$SERVICE_NAME.p12" ]; then
  info "Certs already exist, skipping provisioning"
  exec "$@"
fi

# ---- Fetch CA chain ----
info "Fetching CA chain from crypto-service..."
CA_CHAIN=$(curl -skf "$CRYPTO_URL/api/v1/certificates/ca-chain")
echo "$CA_CHAIN" > "$CERT_DIR/ca-chain.pem"

# Extract Root CA (second cert in chain)
awk 'BEGIN {c=0} /-----BEGIN CERTIFICATE-----/ {c++; if (c==2) in_cert=1} in_cert {print} /-----END CERTIFICATE-----/ {if (in_cert) exit}' \
  "$CERT_DIR/ca-chain.pem" > "$CERT_DIR/root-ca.pem"

info "Building truststore..."
rm -f "$CERT_DIR/truststore.p12"
keytool -importcert -keystore "$CERT_DIR/truststore.p12" -storepass "$PASSWORD" \
  -storetype PKCS12 -alias root-ca -file "$CERT_DIR/root-ca.pem" -noprompt

# ---- Generate EC keypair ----
info "Generating EC P-256 keypair..."
openssl ecparam -genkey -name prime256v1 -noout -out "$CERT_DIR/$SERVICE_NAME-key.pem"

# Extract public key in DER + Base64
openssl ec -in "$CERT_DIR/$SERVICE_NAME-key.pem" -pubout -outform DER 2>/dev/null | base64 > "$CERT_DIR/$SERVICE_NAME-pub.b64"
PUBKEY=$(tr -d '\n' < "$CERT_DIR/$SERVICE_NAME-pub.b64")

# ---- Build JSON payload ----
IFS=',' read -ra DNS_ARRAY <<< "$DNS_NAMES"
if [ ${#DNS_ARRAY[@]} -eq 0 ]; then
  JSON="{\"commonName\":\"$SERVICE_NAME\",\"publicKeyBase64\":\"$PUBKEY\"}"
else
  DNS_JSON=$(printf ',"%s"' "${DNS_ARRAY[@]}")
  DNS_JSON="[${DNS_JSON:1}]"
  JSON="{\"commonName\":\"$SERVICE_NAME\",\"publicKeyBase64\":\"$PUBKEY\",\"dnsNames\":$DNS_JSON}"
fi

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
      "$CERT_DIR/ca-chain.pem" "$CERT_DIR/root-ca.pem"

info "Provisioning complete: $CERT_DIR/$SERVICE_NAME.p12"
exec "$@"
