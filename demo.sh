#!/usr/bin/env bash
# Демо-стенд одной командой:
#   1) docker compose up --build и ожидание /actuator/health = UP;
#   2) публичная ссылка через Cloudflare Quick Tunnel (cloudflared tunnel --url http://localhost:8088)
#      и проверка через неё /api и векторных тайлов.
# Ctrl+C закрывает туннель; контейнеры остаются (остановить: docker compose down).
#
# Нужно заранее (в git не попадают):
#   .env                         JWT_SECRET, DB_PASSWORD
#   secrets/htpasswd             basic auth для nginx (логин demo)
#   secrets/demo-credentials.env DEMO_USER, DEMO_PASSWORD — только для самопроверки ссылки (необязательно)
# docker-compose.override.yml (порты БД и backend для разработки) здесь намеренно не используется:
# наружу открыт только frontend на :8088 под паролем.
set -euo pipefail
cd "$(dirname "$0")"

PORT=8088
DC=(docker compose -f docker-compose.yml)

die() { echo "ОШИБКА: $*" >&2; exit 1; }
[ -f .env ] || die ".env не найден (нужны JWT_SECRET и DB_PASSWORD)"
[ -f secrets/htpasswd ] || die "secrets/htpasswd не найден, например: printf 'demo:%s\n' \"\$(openssl passwd -6 ПАРОЛЬ)\" > secrets/htpasswd"

CF=$(command -v cloudflared || command -v cloudflared.exe || true)
if [ -z "$CF" ]; then
  for p in "${LOCALAPPDATA:-}/cloudflared/cloudflared.exe" "${LOCALAPPDATA:-}/Microsoft/WinGet/Links/cloudflared.exe" "/c/Program Files (x86)/cloudflared/cloudflared.exe" "/c/Program Files/cloudflared/cloudflared.exe"; do
    [ -x "$p" ] && CF=$p && break
  done
fi
[ -n "$CF" ] || die "cloudflared не найден (Windows: winget install Cloudflare.cloudflared; macOS: brew install cloudflared)"

echo "==> Сборка и запуск (docker compose up --build)"
"${DC[@]}" up --build -d

echo "==> Жду /actuator/health = UP"
up=""
for _ in $(seq 1 100); do
  if "${DC[@]}" exec -T frontend wget -qO- http://backend:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then up=1; break; fi
  sleep 3
done
[ -n "$up" ] || { "${DC[@]}" logs --tail 60 backend; die "backend не поднялся"; }
echo "    backend: UP"

echo "==> Туннель cloudflared → http://localhost:$PORT"
LOG=$(mktemp)
"$CF" tunnel --no-autoupdate --url "http://localhost:$PORT" >"$LOG" 2>&1 &
CF_PID=$!
trap 'kill "$CF_PID" 2>/dev/null || true; rm -f "$LOG"' EXIT INT TERM

URL=""
for _ in $(seq 1 60); do
  URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG" | head -1 || true)
  [ -n "$URL" ] && break
  sleep 1
done
[ -n "$URL" ] || { cat "$LOG"; die "cloudflared не выдал ссылку"; }

if [ -f secrets/demo-credentials.env ]; then
  # shellcheck disable=SC1091
  . secrets/demo-credentials.env
  AUTH="${DEMO_USER:-demo}:${DEMO_PASSWORD}"
  echo "==> Проверка ссылки (DNS нового адреса появляется за несколько секунд)"
  code=""
  for _ in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$URL/api/catalog/tree" || true)
    [ "$code" = 200 ] && break
    sleep 2
  done
  echo "    /api/catalog/tree с паролем: HTTP $code"
  echo "    /api/catalog/tree без пароля: HTTP $(curl -s -o /dev/null -w '%{http_code}' "$URL/api/catalog/tree")"
  OBL=$(curl -s -u "$AUTH" "$URL/api/catalog/tree" | grep -o '"id":"[0-9a-f-]*","code":"atd_oblasts"' | cut -d'"' -f4)
  TILE=$(curl -s -u "$AUTH" -o /dev/null -w '%{http_code} %{size_download} байт' "$URL/api/tiles/$OBL/5/22/11.pbf")
  echo "    тайл atd_oblasts 5/22/11: HTTP $TILE"
fi

echo
echo "  Публичная ссылка: $URL"
echo "  Логин: demo, пароль: в secrets/demo-credentials.env"
echo "  Ctrl+C — закрыть туннель"
wait "$CF_PID"
