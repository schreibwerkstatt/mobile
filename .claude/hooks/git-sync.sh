#!/usr/bin/env bash
# Holt den neuesten Stand von origin, damit beide Entwicklungsgeräte (MacBook,
# Remote-Desktop) nicht auf veraltetem Code arbeiten. Läuft bei Session-Start
# und vor jedem Prompt (gedrosselt). Pullt nur per Fast-Forward — divergierte
# Historie oder kollidierende lokale Änderungen werden gemeldet, nie gemergt.
# Die Ausgabe landet als Kontext bei Claude.

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

# Drosselung für UserPromptSubmit: höchstens alle 2 Minuten fetchen.
if [ "$1" = "throttle" ]; then
  fh="$(git rev-parse --git-dir)/FETCH_HEAD"
  if [ -f "$fh" ]; then
    age=$(( $(date +%s) - $(stat -f %m "$fh" 2>/dev/null || stat -c %Y "$fh") ))
    [ "$age" -lt 120 ] && exit 0
  fi
fi

timeout_cmd=""
command -v timeout >/dev/null && timeout_cmd="timeout 20"
command -v gtimeout >/dev/null && timeout_cmd="gtimeout 20"

if ! $timeout_cmd git fetch --quiet --prune origin 2>/dev/null; then
  echo "git-sync: fetch von origin fehlgeschlagen (offline?) — Stand evtl. veraltet."
  exit 0
fi

branch=$(git symbolic-ref --quiet --short HEAD) || exit 0
upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null) || exit 0

read -r ahead behind < <(git rev-list --left-right --count HEAD..."$upstream" 2>/dev/null)
[ "${behind:-0}" -eq 0 ] && {
  [ "${ahead:-0}" -gt 0 ] && echo "git-sync: $branch ist $ahead Commit(s) vor $upstream (noch nicht gepusht)."
  exit 0
}

if [ "${ahead:-0}" -gt 0 ]; then
  echo "git-sync: ACHTUNG — $branch ist divergiert ($ahead lokal / $behind remote vor). Nicht automatisch gepullt; User fragen (rebase/merge)."
  exit 0
fi

if out=$(git pull --ff-only --quiet 2>&1); then
  echo "git-sync: $behind neue(r) Commit(s) von $upstream per Fast-Forward gepullt:"
  git log --oneline -n "$behind" | sed 's/^/  /'
else
  echo "git-sync: ACHTUNG — $branch ist $behind Commit(s) hinter $upstream, Fast-Forward-Pull fehlgeschlagen (lokale Änderungen kollidieren?):"
  echo "$out" | sed 's/^/  /'
fi
exit 0
