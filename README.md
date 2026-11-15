[![CI](https://github.com/omonmunduz/geomap/actions/workflows/ci.yml/badge.svg)](https://github.com/omonmunduz/geomap/actions/workflows/ci.yml)

## Быстрый старт
```bash
docker compose up --build      # → http://localhost:8088, без пароля
```
Локальный запуск открыт без пароля. Basic auth включается только для публичного стенда — через `./demo.sh`
(см. «Демо-стенд» ниже).

Вход: «Войти» → «Войти через Infocom ID» → выбрать демо-учётную запись (вход эмулируется).

## Демо-стенд
Публичная ссылка на стенд одной командой (Git Bash / Linux / macOS), **только с паролем**:
```bash
./demo.sh
```
Скрипт собирает и запускает `docker compose -f docker-compose.yml -f docker-compose.demo.yml` (без
`docker-compose.override.yml`: наружу открыт только :8088). `docker-compose.demo.yml` подменяет конфиг nginx на
`frontend/nginx.auth.conf` и монтирует `secrets/htpasswd`; без этого файла `demo.sh` останавливается с ошибкой. Скрипт
ждёт `/actuator/health` = UP, поднимает туннель `cloudflared tunnel --url http://localhost:8088`, проверяет через
полученную ссылку `/actuator/health` и векторный тайл и печатает ссылку. Если `cloudflared` падает, он перезапускается
в цикле, и каждая новая ссылка с временем дописывается в `demo-url.txt` (в git не попадает). Ctrl+C закрывает туннель,
контейнеры продолжают работать (`restart: unless-stopped`; остановить —
`docker compose -f docker-compose.yml -f docker-compose.demo.yml down`). Нужен `cloudflared` в PATH или в `%LOCALAPPDATA%\cloudflared\cloudflared.exe`
(`winget install Cloudflare.cloudflared`, `brew install cloudflared` или бинарник со страницы релизов
github.com/cloudflare/cloudflared).

**Секреты** — в git не попадают (`.gitignore`):

| Файл | Что внутри |
|---|---|
| `.env` | `JWT_SECRET` (подпись токенов), `DB_PASSWORD` — docker compose подставляет их автоматически |
| `secrets/htpasswd` | логин и хэш пароля basic auth, монтируется в nginx (`/etc/nginx/htpasswd`) |
| `secrets/demo-credentials.env` | `DEMO_USER` / `DEMO_PASSWORD` открытым текстом — чтобы передать доступ и для самопроверки в `demo.sh` |

Пересоздать:
```bash
# .env — новый JWT_SECRET завершит все сессии; новый DB_PASSWORD применяется только к новой БД:
# docker compose down -v (данные удаляются, демо-слои загрузятся заново) или ALTER USER geo PASSWORD '…' в существующей
printf 'JWT_SECRET=%s\nDB_PASSWORD=%s\n' "$(openssl rand -hex 32)" "$(openssl rand -hex 16)" > .env

# basic auth (логин demo)
mkdir -p secrets
PW=$(openssl rand -base64 18 | tr '+/' '-_')
printf 'demo:%s\n' "$(openssl passwd -6 "$PW")" > secrets/htpasswd
printf 'DEMO_USER=demo\nDEMO_PASSWORD=%s\n' "$PW" > secrets/demo-credentials.env
docker compose -f docker-compose.yml -f docker-compose.demo.yml restart frontend
```

Как устроена защита (`frontend/nginx.auth.conf`): в демо-режиме весь сайт закрыт basic auth. Запросы к `/api` с заголовком `Authorization: Bearer <JWT>`
(их шлёт приложение после демо-входа) пропускаются к backend без пароля — браузер не может отправить Basic и Bearer
одновременно, — а backend проверяет подпись токена; получить токен без пароля нельзя.

**Ссылка `*.trycloudflare.com` временная**: живёт, пока работает `demo.sh`, после перезапуска адрес другой; это
тестовый сервис Cloudflare без гарантий доступности. Для постоянного стенда — VPS с Docker: тот же
`docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build`, перед ним reverse proxy с TLS на своём домене (например, Caddy),
наружу только 80/443. Либо именованный Cloudflare Tunnel (`cloudflared tunnel create`, нужен аккаунт Cloudflare и домен)
с постоянным адресом. В обоих случаях для настоящего показа стоит выключить демо-вход (`DEV_LOGIN=false`), когда
появится реальный вход.

## Без Docker
```bash
createdb geoportal                         # PostgreSQL 16 + PostGIS 3, GDAL (ogr2ogr) в PATH
cd backend && mvn spring-boot:run          # :8080 — миграции Flyway + демо-данные
cd frontend && npm install && npm run dev  # :5173, прокси /api → :8080
```

## Тесты
```bash
cd backend && mvn verify   # нужны Docker (PostGIS в Testcontainers) и GDAL в PATH (apt install gdal-bin)
cd frontend && npm run typecheck && npm run build
```
Без GDAL тесты бэкенда падают сразу с сообщением «Для тестов нужен GDAL». В CI (`.github/workflows/ci.yml`) то же
выполняется на каждый push и pull request.

## Структура
- `backend/` — Spring Boot 3 (Java 21): auth, catalog, tiles (MVT), imports (GDAL), search, zoning (+ отчёты XLSX/PDF), admin, audit; миграции в `src/main/resources/db/migration`
- `frontend/` — React 18 + TypeScript + MapLibre GL
- `demo-data/` — демо-слои (Natural Earth, geoBoundaries; зонирование Бишкека — синтетическое) и `samples/` для показа импорта

## Переменные окружения для показа
| Переменная | По умолчанию | Что делает |
|---|---|---|
| `SEED_DEMO` | `true` | загрузить демо-слои из `demo-data/` при первом старте (пустая БД) |
| `SEED_ZONING_CHECK` | `true` | после загрузки выполнить начальную проверку зонирования по Бишкеку, чтобы реестр расхождений не был пустым. `false` — проверка не выполняется, и первый запуск на показе даёт 29 **новых** расхождений |
| `DEV_LOGIN` | `true` | демо-вход без Infocom ID. При `false` бэкенд не стартует, если `JWT_SECRET` не задан, равен значению по умолчанию или короче 32 байт |

Флаги сидера срабатывают только на пустой БД:
```bash
docker compose down -v && SEED_ZONING_CHECK=false docker compose up --build
```

Сценарий показа (~15 минут) — [docs/DEMO.md](docs/DEMO.md).

## Статус
Бэкенд собирается (`mvn -B package`), стек поднимается `docker compose up --build`: Flyway применяет миграции,
сидер загружает 7 демо-слоёв (328 объектов). Приёмочный прогон пройден 10.11.2026 — результаты в
[docs/acceptance/](docs/acceptance/), исправления — в истории git и [FIXES.md](FIXES.md).
