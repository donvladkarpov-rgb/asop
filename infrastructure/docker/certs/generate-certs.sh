#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${CERTS_OUT:-$DIR}"
CRYPTO_URL="${CRYPTO_URL:-http://localhost:8081}"
PASSWORD="${CERTS_PASSWORD:-changeit}"

mkdir -p "$OUT"

info()  { echo -e "\033[1;34m[INFO]\033[0m $*"; }
err()   { echo -e "\033[1;31m[ERR]\033[0m $*" >&2; }

# Check dependencies
for cmd in openssl curl keytool python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    err "Required tool '$cmd' not found"
    exit 1
  fi
done

# ---- 1. Wait for crypto-service ----
info "Waiting for crypto-service at $CRYPTO_URL..."
for i in {1..30}; do
  if curl -sf "$CRYPTO_URL/actuator/health" >/dev/null 2>&1; then
    info "crypto-service is ready"
    break
  fi
  if [ "$i" -eq 30 ]; then
    err "crypto-service not reachable after 30s"
    exit 1
  fi
  sleep 2
done

# ---- 2. Fetch CA chain & build truststore ----
info "Fetching CA chain..."
CA_CHAIN=$(curl -sf "$CRYPTO_URL/api/v1/certificates/ca-chain")
echo "$CA_CHAIN" > "$OUT/ca-chain.pem"

# Extract Root CA (last certificate in chain — two certs: intermediate then root)
awk 'BEGIN {c=0} /-----BEGIN CERTIFICATE-----/ {c++; if (c==2) in_cert=1} in_cert {print} /-----END CERTIFICATE-----/ {if (in_cert) exit}' \
  "$OUT/ca-chain.pem" > "$OUT/root-ca.pem"

info "Building shared truststore..."
rm -f "$OUT/truststore.p12"
keytool -importcert -keystore "$OUT/truststore.p12" -storepass "$PASSWORD" \
  -storetype PKCS12 -alias root-ca -file "$OUT/root-ca.pem" -noprompt

# ---- 3. Generate EC keypair + request server cert ----
generate_cert() {
  local name="$1"
  shift
  local dns_names=("$@")

  info "Generating cert for '$name'..."

  local key_file="$OUT/$name-key.pem"
  local pub_b64="$OUT/$name-pub.b64"
  local cert_pem="$OUT/$name-cert.pem"

  # Generate EC keypair
  openssl ecparam -genkey -name prime256v1 -noout -out "$key_file"

  # Extract public key in DER + Base64
  openssl ec -in "$key_file" -pubout -outform DER 2>/dev/null | base64 > "$pub_b64"

  local pubkey
  pubkey=$(tr -d '\n' < "$pub_b64")

  # Build JSON payload
  if [ ${#dns_names[@]} -eq 0 ]; then
    json_payload="{\"commonName\":\"$name\",\"publicKeyBase64\":\"$pubkey\"}"
  else
    dns_json=$(printf ',"%s"' "${dns_names[@]}")
    dns_json="[${dns_json:1}]"
    json_payload="{\"commonName\":\"$name\",\"publicKeyBase64\":\"$pubkey\",\"dnsNames\":$dns_json}"
  fi

  # Request cert from crypto-service
  cert_b64=$(curl -sf -X POST "$CRYPTO_URL/api/v1/certificates/server" \
    -H "Content-Type: application/json" \
    -d "$json_payload" | python3 -c "import sys,json; print(json.load(sys.stdin)['certificateBase64'])")

  # Decode DER → PEM
  echo "$cert_b64" | base64 -d | openssl x509 -inform DER -out "$cert_pem"

  # Build PKCS12 keystore
  rm -f "$OUT/$name.p12"
  openssl pkcs12 -export \
    -in "$cert_pem" \
    -inkey "$key_file" \
    -name "$name" \
    -out "$OUT/$name.p12" \
    -password "pass:$PASSWORD"

  rm -f "$key_file" "$pub_b64" "$cert_pem"

  info "  -> $OUT/$name.p12"
}

# ---- 4. Generate certs for all services ----
# Format: "service-name:dns1,dns2,..."
SERVICES=(
  "gateway:localhost,gateway"
  "user-service:user-service"
  "terminal-service:terminal-service"
  "session-service:session-service"
  "card-service:card-service"
  "carrier-service:carrier-service"
  "debt-service:debt-service"
  "audit-service:audit-service"
  "fiscal-service:fiscal-service"
  "admin-service:admin-service"
  "crypto-service:crypto-service"
  "kafka:kafka"
  "keycloak:keycloak,localhost"
)

for entry in "${SERVICES[@]}"; do
  name="${entry%%:*}"
  dns="${entry#*:}"
  IFS=',' read -ra DNS_ARRAY <<< "$dns"
  generate_cert "$name" "${DNS_ARRAY[@]}"
done

info ""
info "=== Certificate generation complete ==="
ls -1 "$OUT"/*.p12
