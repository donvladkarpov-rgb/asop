#!/bin/sh
set -e

CRYPTO_HOST="${CRYPTO_SERVICE_HOST:-crypto-service}"
CRYPTO_PORT="${CRYPTO_SERVICE_PORT:-8081}"
KEYSTORE_PASS="${GATEWAY_KEYSTORE_PASSWORD:-changeit}"
TRUSTSTORE_PASS="${TRUSTSTORE_PASSWORD:-changeit}"

_raw_path="${GATEWAY_KEYSTORE_PATH:-/certs/gateway-keystore.p12}"
KEYSTORE_FILE="${_raw_path#file:}"
_raw_path="${TRUSTSTORE_PATH:-/certs/truststore.p12}"
TRUSTSTORE_FILE="${_raw_path#file:}"

if [ -f "$KEYSTORE_FILE" ] && [ -f "$TRUSTSTORE_FILE" ]; then
  echo "Certificates already exist, skipping provisioning."
  exec "$@"
fi

echo "Waiting for crypto-service..."
i=0
while [ $i -lt 30 ]; do
  i=$((i + 1))
  if curl -sf "http://${CRYPTO_HOST}:${CRYPTO_PORT}/actuator/health" > /dev/null 2>&1; then
    echo "crypto-service is ready."
    break
  fi
  if [ $i -eq 30 ]; then
    echo "ERROR: crypto-service not available after 30 attempts."
    exit 1
  fi
  sleep 2
done

echo "Generating ECC P-256 key pair..."
openssl ecparam -genkey -name prime256v1 -out /tmp/gateway.key

echo "Extracting public key..."
PUBKEY=$(openssl ec -in /tmp/gateway.key -pubout 2>/dev/null | tail -n +2 | head -n -1 | tr -d '\n')

echo "Requesting server certificate from crypto-service..."
RESPONSE=$(curl -sf -X POST "http://${CRYPTO_HOST}:${CRYPTO_PORT}/api/v1/certificates/server" \
  -H "Content-Type: application/json" \
  -d "{\"commonName\":\"gateway.asop.local\",\"publicKeyBase64\":\"$PUBKEY\"}")

CERT_B64=$(echo "$RESPONSE" | sed 's/.*"certificateBase64":"\([^"]*\)".*/\1/')

echo "Creating PKCS12 keystore..."
echo "$CERT_B64" | openssl base64 -d -A > /tmp/gateway.crt
mkdir -p "$(dirname "$KEYSTORE_FILE")"
openssl pkcs12 -export \
  -in /tmp/gateway.crt \
  -inkey /tmp/gateway.key \
  -out "$KEYSTORE_FILE" \
  -passout "pass:${KEYSTORE_PASS}" \
  -name gateway

echo "Fetching Root CA certificate..."
ROOT_CA=$(curl -sf "http://${CRYPTO_HOST}:${CRYPTO_PORT}/api/v1/terminals/root-ca")
echo "$ROOT_CA" > /tmp/root-ca.pem

echo "Creating truststore..."
mkdir -p "$(dirname "$TRUSTSTORE_FILE")"
keytool -import -trustcacerts -alias root-ca \
  -file /tmp/root-ca.pem \
  -keystore "$TRUSTSTORE_FILE" \
  -storepass "$TRUSTSTORE_PASS" \
  -noprompt

rm -f /tmp/gateway.key /tmp/gateway.crt /tmp/root-ca.pem

echo "Certificate provisioning complete."
exec "$@"
