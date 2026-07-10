#!/bin/sh
set -e

CRYPTO_URL="${CRYPTO_URL:-https://crypto-service:8081}"
CERT_DIR="${WEB_ADMIN_CERT_DIR:-/certs}"
CERT_FILE="${CERT_DIR}/web-admin.crt"
KEY_FILE="${CERT_DIR}/web-admin.key"

# ---- Generate nginx config from template ----
export PROXY_PROTOCOL="${PROXY_PROTOCOL:-http}"

if [ -f /etc/nginx/templates/default.conf.template ]; then
  echo "Generating nginx config (proxy protocol: $PROXY_PROTOCOL)..."
  PROXY_PROTOCOL="$PROXY_PROTOCOL" envsubst '${PROXY_PROTOCOL}' \
    < /etc/nginx/templates/default.conf.template \
    > /etc/nginx/conf.d/default.conf
fi

if [ -f "$CERT_FILE" ] && [ -f "$KEY_FILE" ]; then
  echo "Certificates already exist, skipping provisioning."
  exec /docker-entrypoint.sh nginx -g "daemon off;"
fi

echo "Waiting for crypto-service..."
i=0
while [ $i -lt 60 ]; do
  i=$((i + 1))
  if curl -skf "${CRYPTO_URL}/actuator/health" > /dev/null 2>&1; then
    echo "crypto-service is ready."
    break
  fi
  if [ $i -eq 60 ]; then
    echo "ERROR: crypto-service not available after 60 attempts."
    exit 1
  fi
  sleep 2
done

echo "Generating ECC P-256 key pair..."
openssl ecparam -genkey -name prime256v1 -out /tmp/web-admin.key

echo "Extracting public key..."
PUBKEY=$(openssl ec -in /tmp/web-admin.key -pubout 2>/dev/null | tail -n +2 | head -n -1 | tr -d '\n')

echo "Requesting server certificate from crypto-service..."
RESPONSE=$(curl -skf -X POST "${CRYPTO_URL}/api/v1/certificates/server" \
  -H "Content-Type: application/json" \
  -d "{\"commonName\":\"localhost\",\"publicKeyBase64\":\"$PUBKEY\"}")

CERT_B64=$(echo "$RESPONSE" | sed 's/.*"certificateBase64":"\([^"]*\)".*/\1/')

echo "Writing certificate and key..."
mkdir -p "$CERT_DIR"
echo "$CERT_B64" | openssl base64 -d -A > /tmp/web-admin.der
openssl x509 -inform DER -in /tmp/web-admin.der -out "$CERT_FILE"
cp /tmp/web-admin.key "$KEY_FILE"
rm -f /tmp/web-admin.key /tmp/web-admin.der

echo "Certificate provisioning complete."
exec /docker-entrypoint.sh nginx -g "daemon off;"
