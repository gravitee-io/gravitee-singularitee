#!/usr/bin/env bash
#
# Copyright © 2015 The Gravitee team (http://gravitee.io)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# Generates a throwaway certificate authority and two leaf certificates for the
# mTLS example (examples/modular/server-*-mtls.yaml).
#
# Usage:
#   ./scripts/gen-dev-certs.sh [--out DIR] [--days N]
#
#   --out   where to write (default: ./certs, gitignored)
#   --days  validity (default: 30 — short on purpose, these are disposable)
#
# Produces:
#   ca.crt / ca.key          the dev CA, trusted by BOTH sides
#   server.crt / server.key  what the callee presents        → grpc.ssl.keystore
#   client.crt / client.key  what the caller presents        → grpc.client.ssl.keystore
#
# Why three certificates and not one: mTLS only means something when caller and
# callee have DISTINCT identities. Reusing the server's key for the client would
# authenticate — it chains to the same CA — while making the two indistinguishable,
# so neither can be authorised or revoked separately.
#
# The server cert carries SAN localhost + 127.0.0.1 because the client verifies the
# hostname it dialled; a CN-only certificate is rejected by modern TLS stacks.
#
# ⚠️  DEVELOPMENT ONLY. The private keys are written unencrypted and the CA is not
#     protected in any way. Never use these outside a local machine.
#

set -euo pipefail

OUT="$(cd "$(dirname "$0")/.." && pwd)/certs"
DAYS=30
P12_PASSWORD="${P12_PASSWORD:-changeit}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)  OUT="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    -h|--help) sed -n '19,44p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

command -v openssl >/dev/null || { echo "openssl not found on PATH" >&2; exit 1; }

mkdir -p "$OUT"
cd "$OUT"

echo ">> Generating dev CA"
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -days "$DAYS" \
  -subj "/CN=Singularitee Dev CA" 2>/dev/null

# Signed with SANs: Vert.x verifies the dialled host against the SAN list, not the CN.
issue() { # name, subject, san
  echo ">> Generating $1 certificate"
  openssl req -newkey rsa:2048 -nodes -keyout "$1.key" -out "$1.csr" \
    -subj "$2" 2>/dev/null
  openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out "$1.crt" -days "$DAYS" -extfile <(printf '%s\n' "$3") 2>/dev/null
  rm -f "$1.csr"
}

issue server "/CN=localhost" "subjectAltName=DNS:localhost,IP:127.0.0.1"
# The client is verified by identity, not hostname — no SAN needed, but it must be
# marked for client authentication or strict servers reject it.
issue client "/CN=singularitee-client" "extendedKeyUsage=clientAuth"

# PKCS12 bundles: gravitee-node's file keystore loader takes a single path+password
# for pkcs12/jks, whereas its PEM mode expects an indexed certificates[N] list. The
# bundle form is what the Gravitee stack documents, so the example uses it.
echo ">> Bundling PKCS12 keystores (password: $P12_PASSWORD)"
openssl pkcs12 -export -out server.p12 -inkey server.key -in server.crt -certfile ca.crt \
  -passout "pass:$P12_PASSWORD" 2>/dev/null
openssl pkcs12 -export -out client.p12 -inkey client.key -in client.crt -certfile ca.crt \
  -passout "pass:$P12_PASSWORD" 2>/dev/null
# Truststore: the CA alone, no private key. Built with keytool, NOT
# `openssl pkcs12 -nokeys` — Java's PKCS12 provider reads zero entries from
# openssl's cert-only bundles, which silently yields an EMPTY truststore
# (anonymous callers then fail one way, legitimate ones another).
rm -f ca.p12
keytool -importcert -noprompt -alias dev-ca -file ca.crt \
  -keystore ca.p12 -storetype pkcs12 -storepass "$P12_PASSWORD" >/dev/null 2>&1

chmod 600 ./*.key ./*.p12
rm -f ca.srl

echo
echo ">> Wrote to $OUT:"
ls -1 "$OUT"
echo
echo "Server (callee):  GRAVITEE_GRPC_SECURED=true"
echo "                  GRAVITEE_GRPC_SSL_CLIENTAUTH=REQUIRED"
echo "                  GRAVITEE_GRPC_SSL_KEYSTORE_TYPE=pkcs12"
echo "                  GRAVITEE_GRPC_SSL_KEYSTORE_PATH=$OUT/server.p12"
echo "                  GRAVITEE_GRPC_SSL_KEYSTORE_PASSWORD=$P12_PASSWORD"
echo "                  GRAVITEE_GRPC_SSL_TRUSTSTORE_TYPE=pkcs12"
echo "                  GRAVITEE_GRPC_SSL_TRUSTSTORE_PATH=$OUT/ca.p12"
echo "                  GRAVITEE_GRPC_SSL_TRUSTSTORE_PASSWORD=$P12_PASSWORD"
echo
echo "Caller:           GRAVITEE_GRPC_CLIENT_SSL_TRUSTSTORE_TYPE=PKCS12"
echo "                  GRAVITEE_GRPC_CLIENT_SSL_TRUSTSTORE_PATH=$OUT/ca.p12"
echo "                  GRAVITEE_GRPC_CLIENT_SSL_TRUSTSTORE_PASSWORD=$P12_PASSWORD"
echo "                  GRAVITEE_GRPC_CLIENT_SSL_KEYSTORE_TYPE=PKCS12"
echo "                  GRAVITEE_GRPC_CLIENT_SSL_KEYSTORE_PATH=$OUT/client.p12"
echo "                  GRAVITEE_GRPC_CLIENT_SSL_KEYSTORE_PASSWORD=$P12_PASSWORD"
