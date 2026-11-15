#!/usr/bin/env bash
# Демо-стенд одной командой:
#   1) docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build (basic auth в nginx)
#      и ожидание /actuator/health = UP;
#   2) публичная ссылка через Cloudflare Quick Tunnel (cloudflared tunnel --url http://localhost:8088)
#      и проверка через неё /actuator/health и векторного тайла.
# Если cloudflared падает, он перезапускается в цикле; каждая новая ссылка с временем дописывается в demo-url.txt.
# Ctrl+C закрывает туннель; контейнеры остаются (restart: unless-stopped;
# остановить: docker compose -f docker-compose.yml -f docker-compose.demo.yml down).
#
# Нужно заранее (в git не попадают):
#   .env                         JWT_SECRET, DB_PASSWORD
#   secrets/htpasswd             basic auth для nginx (логин demo) — без него стенд не запускается
#   secrets/demo-credentials.env DEMO_USER, DEMO_PASSWORD — только для самопроверки ссылки (необязательно)
# Пароль включает только docker-compose.demo.yml (frontend/nginx.auth.conf + secrets/htpasswd);
# обычный docker compose up — локальный запуск без пароля.
# docker-compose.override.yml (порты БД и backend для разработки) здесь намеренно не используется:
# наружу открыт только frontend на :8088 под паролем.
set -euo pipefail
cd "$(dirname "$0")"

PORT=8088
DC=(docker compose -f docker-compose.yml -f docker-compose.demo.yml)

die() { echo "ОШИБКА: $*" >&2; exit 1; }
[ -f .env ] || die ".env не найден (нужны JWT_SECRET и DB_PASSWORD)"
[ -d secrets/htpasswd ] && die "secrets/htpasswd — папка (её создаёт Docker, если файла не было). Удалите её и создайте файл, см. README, «Демо-стенд»"
[ -s secrets/htpasswd ] || die "secrets/htpasswd не найден или пуст — без него публичный стенд остался бы без пароля.
  Создайте: mkdir -p secrets && printf 'demo:%s\n' \"\$(openssl passwd -6 ПАРОЛЬ)\" > secrets/htpasswd
  (подробнее — README, «Демо-стенд»)"

CF=$(command -v cloudflared || command -v cloudflared.exe || true)
if [ -z "$CF" ]; then
  for p in "${LOCALAPPDATA:-}/cloudflared/cloudflared.exe" "${LOCALAPPDATA:-}/Microsoft/WinGet/Links/cloudflared.exe" "/c/Program Files (x86)/cloudflared/cloudflared.exe" "/c/Program Files/cloudflared/cloudflared.exe"; do
    [ -x "$p" ] && CF=$p && break
  done
fi
[ -n "$CF" ] || die "cloudflared не найден (Windows: winget install Cloudflare.cloudflared; macOS: brew install cloudflared)"

echo "==> Жду Docker (после входа в Windows Docker Desktop стартует не сразу)"
for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 5; done
docker info >/dev/null 2>&1 || die "Docker не запущен"

echo "==> Сборка и запуск с basic auth (docker-compose.yml + docker-compose.demo.yml)"
"${DC[@]}" up --build -d

echo "==> Жду /actuator/health = UP"
up=""
for _ in $(seq 1 100); do
  if "${DC[@]}" exec -T frontend wget -qO- http://backend:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then up=1; break; fi
  sleep 3
done
[ -n "$up" ] || { "${DC[@]}" logs --tail 60 backend; die "backend не поднялся"; }
echo "    backend: UP"

URL_FILE=demo-url.txt
ts() { date '+%Y-%m-%d %H:%M:%S'; }
LOG=$(mktemp)
CF_PID=""
cleanup() { if [ -n "$CF_PID" ]; then kill "$CF_PID" 2>/dev/null || true; fi; rm -f "$LOG"; }
trap 'echo; echo "Остановлено, туннель закрыт."; cleanup; exit 0' INT TERM
trap cleanup EXIT

# Самопроверка ссылки (если есть secrets/demo-credentials.env): health и векторный тайл через туннель с basic auth
check_url() {
  local url=$1
  [ -f secrets/demo-credentials.env ] || return 0
  # shellcheck disable=SC1091
  . secrets/demo-credentials.env
  local auth="${DEMO_USER:-demo}:${DEMO_PASSWORD}" code="" obl tile
  echo "    проверка ссылки (DNS нового адреса появляется за несколько секунд)…"
  for _ in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -u "$auth" "$url/actuator/health" || true)
    [ "$code" = 200 ] && break
    sleep 2
  done
  echo "    /actuator/health с паролем: HTTP $code $(curl -s -u "$auth" "$url/actuator/health" || true)"
  echo "    /actuator/health без пароля: HTTP $(curl -s -o /dev/null -w '%{http_code}' "$url/actuator/health" || true)"
  obl=$(curl -s -u "$auth" "$url/api/catalog/tree" | grep -o '"id":"[0-9a-f-]*","code":"atd_oblasts"' | cut -d'"' -f4 || true)
  tile=$(curl -s -u "$auth" -o /dev/null -w '%{http_code}, %{size_download}' "$url/api/tiles/$obl/5/22/11.pbf" || true)
  echo "    тайл atd_oblasts 5/22/11: HTTP $tile байт"
}

# cloudflared перезапускается, если упал; каждая новая ссылка дописывается с временем в demo-url.txt.
# Пауза между перезапусками растёт 10 с → 5 мин, если туннель падает сразу, и сбрасывается после 5 минут работы.
delay=10
attempt=0
while true; do
  attempt=$((attempt + 1))
  echo "==> [$(ts)] Туннель cloudflared → http://localhost:$PORT (запуск №$attempt)"
  : >"$LOG"
  started=$(date +%s)
  "$CF" tunnel --no-autoupdate --url "http://localhost:$PORT" >"$LOG" 2>&1 &
  CF_PID=$!

  URL=""
  for _ in $(seq 1 60); do
    URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG" | head -1 || true)
    [ -n "$URL" ] && break
    kill -0 "$CF_PID" 2>/dev/null || break
    sleep 1
  done

  if [ -n "$URL" ]; then
    echo "$(ts)  $URL" >>"$URL_FILE"
    echo
    echo "  Публичная ссылка: $URL   (записана в $URL_FILE)"
    echo "  Логин: demo, пароль: в secrets/demo-credentials.env. Ctrl+C — закрыть туннель."
    check_url "$URL"
  else
    echo "    cloudflared не выдал ссылку за 60 с:"; tail -5 "$LOG"
    kill "$CF_PID" 2>/dev/null || true
  fi

  rc=0
  wait "$CF_PID" || rc=$?
  CF_PID=""
  if [ $(( $(date +%s) - started )) -ge 300 ]; then delay=10; fi
  echo "!!! [$(ts)] cloudflared завершился (код $rc); перезапуск через $delay с. Последние строки лога:"
  tail -3 "$LOG" | sed 's/^/    /'
  sleep "$delay"
  delay=$(( delay * 2 > 300 ? 300 : delay * 2 ))
done
