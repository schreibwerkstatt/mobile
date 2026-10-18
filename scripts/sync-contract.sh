#!/usr/bin/env bash
# Zieht den Client-Vertrag des Servers (`contract/` im Repo schreibwerkstatt) in die
# Test-Ressourcen dieser App: app/src/test/resources/contract/. Die Contract-Tests
# (`*Contract*`) dekodieren die Fixtures dort mit den echten DTOs.
#
#   scripts/sync-contract.sh                 # aus ../schreibwerkstatt (bzw. $SW_SERVER_REPO)
#   SW_SERVER_REPO=/pfad/zum/server scripts/sync-contract.sh
#   scripts/sync-contract.sh --from-github   # flacher Klon von main (CI-Job contract-latest)
#
# Vorhandene Fixtures werden vorher gelöscht: eine Fixture, die der Server nicht mehr
# liefert, soll hier verschwinden statt still weiter grün zu testen.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GITHUB_URL="https://github.com/schreibwerkstatt/schreibwerkstatt"
DEST="${REPO_ROOT}/app/src/test/resources/contract"

TMP=""
cleanup() { if [ -n "$TMP" ]; then rm -rf "$TMP"; fi; }
trap cleanup EXIT

case "${1:-}" in
  --from-github)
    TMP="$(mktemp -d)"
    git clone --depth 1 --quiet "$GITHUB_URL" "$TMP/server"
    SERVER="$TMP/server"
    ;;
  "")
    SERVER="${SW_SERVER_REPO:-${REPO_ROOT}/../schreibwerkstatt}"
    ;;
  *)
    echo "Aufruf: $0 [--from-github]" >&2
    exit 2
    ;;
esac

SRC="${SERVER}/contract"
for f in version.json openapi.json CHANGELOG.md; do
  if [ ! -f "${SRC}/${f}" ]; then
    echo "Vertrag nicht gefunden: ${SRC}/${f} (SW_SERVER_REPO setzen oder --from-github)" >&2
    exit 1
  fi
done
if ! ls "${SRC}"/fixtures/*.json >/dev/null 2>&1; then
  echo "Keine Fixtures unter ${SRC}/fixtures/" >&2
  exit 1
fi

rm -rf "${DEST}/fixtures"
mkdir -p "${DEST}/fixtures"
cp "${SRC}/version.json" "${SRC}/openapi.json" "${SRC}/CHANGELOG.md" "${DEST}/"
cp "${SRC}"/fixtures/*.json "${DEST}/fixtures/"

COMMIT="$(git -C "$SERVER" rev-parse --short HEAD 2>/dev/null || echo unbekannt)"
COUNT="$(ls "${DEST}"/fixtures/*.json | wc -l | tr -d ' ')"
echo "Vertrag aus ${SERVER} (${COMMIT}) übernommen: $(tr -d '\n ' < "${DEST}/version.json"), ${COUNT} Fixtures."
