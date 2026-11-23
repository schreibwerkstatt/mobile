#!/usr/bin/env bash
# Exportiert den bestehenden App-Signaturschlüssel (derselbe wie für die GitHub-APKs)
# verschlüsselt für Play App Signing. Siehe playstore/README.md „App-Signatur".
#
#   scripts/play-export-signing-key.sh <pepk.jar> <encryption_public_key.pem> [ausgabe.zip]
#
# pepk.jar und die .pem lädt man in der Play Console herunter (die .pem ist pro App
# eindeutig). pepk fragt Keystore- und Key-Passwort selbst ab; das Skript liest nur
# storeFile und keyAlias aus keystore.properties und gibt keine Passwörter weiter.
# Die ZIP enthält den privaten Schlüssel (für Google verschlüsselt): nie committen,
# nach dem Upload löschen.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
pepk="${1:?Pfad zu pepk.jar fehlt}"
pem="${2:?Pfad zu encryption_public_key.pem fehlt}"
out="${3:-${TMPDIR:-/tmp}/play-signing-key.zip}"

props="$root/keystore.properties"
[ -f "$props" ] || { echo "keystore.properties fehlt im Projekt-Root" >&2; exit 1; }
store="$(sed -n 's/^storeFile=//p' "$props" | tr -d '\r')"
alias="$(sed -n 's/^keyAlias=//p' "$props" | tr -d '\r')"
case "$store" in /*) ;; *) store="$root/$store" ;; esac
[ -f "$store" ] || { echo "Keystore nicht gefunden: $store" >&2; exit 1; }

case "$out" in "$root"/*) echo "Ausgabe nicht ins Repo legen: $out" >&2; exit 1 ;; esac

java -jar "$pepk" \
  --keystore="$store" \
  --alias="$alias" \
  --output="$out" \
  --include-cert \
  --rsa-aes-encryption \
  --encryption-key-path="$pem"

echo
echo "Fertig: $out"
echo "In der Play Console hochladen und danach löschen."
