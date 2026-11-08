## Быстрый старт
```bash
docker compose up --build      # → http://localhost:8088
```
Вход: «Войти» → «Войти через Infocom ID» → выбрать демо-учётную запись (вход эмулируется).

## Без Docker
```bash
createdb geoportal                         # PostgreSQL 16 + PostGIS 3, GDAL (ogr2ogr) в PATH
cd backend && mvn spring-boot:run          # :8080 — миграции Flyway + демо-данные
cd frontend && npm install && npm run dev  # :5173, прокси /api → :8080
```

## Структура
- `backend/` — Spring Boot 3 (Java 21): auth, catalog, tiles (MVT), imports (GDAL), search, zoning (+ отчёты XLSX/PDF), admin, audit; миграции в `src/main/resources/db/migration`
- `frontend/` — React 18 + TypeScript + MapLibre GL
- `demo-data/` — демо-слои (Natural Earth, geoBoundaries; зонирование Бишкека — синтетическое) и `samples/` для показа импорта

## Статус
Java-код ещё ни разу не компилировался (в среде разработки не было доступа к Maven Central).
SQL проверен на PostGIS, фронтенд — в браузере. Первый шаг: `mvn package` в `backend/`.
