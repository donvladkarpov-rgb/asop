#!/bin/sh
set -e

CRYPTO_URL="${CRYPTO_URL:-https://crypto-service:8081}"
CERT_DIR="${CERT_DIR:-/certs}"
PASSWORD="${CERTS_PASSWORD:-changeit}"

info() { echo "[certs-init] $*"; }

# Run provision.sh for each infrastructure service
for service in kafka keycloak; do
  info "Provisioning certs for $service..."

  # Wait for crypto-service before each one
  i=0
  while [ $i -lt 60 ]; do
    i=$((i + 1))
    if curl -skf "$CRYPTO_URL/actuator/health" >/dev/null 2>&1; then
      break
    fi
    if [ $i -eq 60 ]; then
      echo "ERROR: crypto-service not available after 60s"
      exit 1
    fi
    sleep 2
  done

  info "crypto-service ready, generating $service cert..."

  # Fetch CA chain and build truststore (only once, shared)
  if [ ! -f "$CERT_DIR/truststore.p12" ]; then
    CA_CHAIN=$(curl -skf "$CRYPTO_URL/api/v1/certificates/ca-chain")
    echo "$CA_CHAIN" > /tmp/ca-chain.pem

    # Split chain: first cert = intermediate, second cert = root
    awk 'BEGIN {c=0} /-----BEGIN CERTIFICATE-----/ {c++; if (c==1) in_cert=1} in_cert {print} /-----END CERTIFICATE-----/ {if (in_cert && c==1) {in_cert=0; exit}}' \
      /tmp/ca-chain.pem > /tmp/intermediate-ca.pem
    awk 'BEGIN {c=0} /-----BEGIN CERTIFICATE-----/ {c++; if (c==2) in_cert=1} in_cert {print} /-----END CERTIFICATE-----/ {if (in_cert) exit}' \
      /tmp/ca-chain.pem > /tmp/root-ca.pem

    keytool -importcert -keystore "$CERT_DIR/truststore.p12" -storepass "$PASSWORD" \
      -storetype PKCS12 -alias root-ca -file /tmp/root-ca.pem -noprompt
    keytool -importcert -keystore "$CERT_DIR/truststore.p12" -storepass "$PASSWORD" \
      -storetype PKCS12 -alias intermediate-ca -file /tmp/intermediate-ca.pem -noprompt

    rm -f /tmp/root-ca.pem /tmp/intermediate-ca.pem /tmp/ca-chain.pem
    info "Truststore created at $CERT_DIR/truststore.p12 (Root CA + Intermediate CA)"
  fi

  # Generate EC keypair
  openssl ecparam -genkey -name prime256v1 -noout -out /tmp/$service-key.pem
  PUBKEY=$(openssl ec -in /tmp/$service-key.pem -pubout -outform DER 2>/dev/null | base64 | tr -d '\n')

  # Request server cert
  CERT_B64=$(curl -skf -X POST "$CRYPTO_URL/api/v1/certificates/server" \
    -H "Content-Type: application/json" \
    -d "{\"commonName\":\"$service\",\"publicKeyBase64\":\"$PUBKEY\",\"dnsNames\":[\"$service\"]}" | \
    sed 's/.*"certificateBase64":"\([^"]*\)".*/\1/')

  echo "$CERT_B64" | base64 -d > /tmp/$service-cert.der
  openssl x509 -inform DER -in /tmp/$service-cert.der -out /tmp/$service-cert.pem

  # Build PKCS12 keystore with CA chain
  rm -f "$CERT_DIR/$service.p12"
  curl -skf "$CRYPTO_URL/api/v1/certificates/ca-chain" > /tmp/ca-chain.pem
  openssl pkcs12 -export \
    -in /tmp/$service-cert.pem \
    -inkey /tmp/$service-key.pem \
    -certfile /tmp/ca-chain.pem \
    -name "$service" \
    -out "$CERT_DIR/$service.p12" \
    -password "pass:$PASSWORD"
  rm -f /tmp/ca-chain.pem

  chmod 644 "$CERT_DIR/$service.p12"
  chmod 644 "$CERT_DIR/truststore.p12"

  rm -f /tmp/$service-key.pem /tmp/$service-cert.der /tmp/$service-cert.pem /tmp/$service-pub.b64

  if [ "$service" = "kafka" ]; then
    echo "changeit" > "$CERT_DIR/kafka_keystore_cred"
    echo "changeit" > "$CERT_DIR/kafka_sslkey_cred"
    echo "changeit" > "$CERT_DIR/kafka_truststore_cred"
    chmod 644 "$CERT_DIR/kafka_keystore_cred" "$CERT_DIR/kafka_sslkey_cred" "$CERT_DIR/kafka_truststore_cred"
    info "Kafka credential files created"
  fi

  info "$service.p12 created at $CERT_DIR/$service.p12"
done

info "All infrastructure certs provisioned."
