#!/usr/bin/env bash
# cert-init's entrypoint (docker-compose.e2e.yml): generates every TLS/truststore artifact the
# browser-e2e deployment needs, once, into a shared volume other services mount read-only.
# PKI generation only -- no manifest, no readiness checks. See docker-compose.e2e.yml's comments
# for why those are separate services.
#
# Usage: generate-e2e-certs.sh <output-dir>
set -euo pipefail

out_dir="${1:?output directory argument required}"
mkdir -p "$out_dir"

# Compose re-runs completed one-shots on a later `up`/`run`; regenerating would leave the already
# running identity provider and gateway serving certs the new truststore no longer trusts.
if [ -f "$out_dir/.complete" ]; then
  echo "TLS material already present in $out_dir; keeping it."
  exit 0
fi

gateway_host="app.e2e.test"
issuer_host="auth.e2e.test"

echo "Generating the gateway's TLS certificate (SAN=${gateway_host})..."
openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -keyout "$out_dir/gateway-key.pem" -out "$out_dir/gateway-cert.pem" \
  -subj "/CN=${gateway_host}" -addext "subjectAltName=DNS:${gateway_host}"

echo "Generating the identity provider's TLS keystore (SAN=${issuer_host})..."
openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -keyout "$out_dir/issuer-key.pem" -out "$out_dir/issuer-cert.pem" \
  -subj "/CN=${issuer_host}" -addext "subjectAltName=DNS:${issuer_host}"
openssl pkcs12 -export -inkey "$out_dir/issuer-key.pem" -in "$out_dir/issuer-cert.pem" \
  -out "$out_dir/issuer-keystore.p12" -passout pass:browser-e2e

echo "Generating the backend services' Java truststore (trusts the identity provider's cert)..."
rm -f "$out_dir/issuer-truststore.p12"
keytool -importcert -noprompt -alias "$issuer_host" -file "$out_dir/issuer-cert.pem" \
  -keystore "$out_dir/issuer-truststore.p12" -storetype PKCS12 -storepass changeit

touch "$out_dir/.complete"
echo "TLS material written to $out_dir."
