# CLAUDE.md — Геопортал НИПД КР (MVP)

## What this is
MVP of the national spatial data portal of Kyrgyzstan (ТЗ «Геопортал НИПД КР»).
End-to-end slice: import → publish → map → search → zoning control → report.
UI text and error messages are in Russian — keep them in Russian.

## Stack
- `backend/` — Spring Boot 3.4, Java 21, JdbcTemplate (no JPA), Flyway, Spring Security resource server (HS256 JWT),
  GDAL CLI (`ogr2ogr`, `ogrinfo`) called via ProcessBuilder, Apache POI (XLSX), OpenPDF (PDF), Caffeine.
- `frontend/` — React 18 + TypeScript + Vite, MapLibre GL 4, @mapbox/mapbox-gl-draw (custom MapLibre-safe styles).
- DB — PostgreSQL 16 + PostGIS 3 (+ pg_trgm, pgcrypto). Geometry stored in EPSG:4326, tiles served in 3857.
- `demo-data/` — seeded on first start by `seed/DemoSeeder.java` through the real import pipeline.

## Status / known risk
The Java code was written without being able to compile it (no Maven access in the authoring environment).
It passed a syntax parser and manual review; all SQL was executed against PostGIS; the frontend was tested in Chrome
against a stand-in API with identical JSON shapes. Expect a few compile/runtime errors on first build.
Likely spots: `zoning/ReportService.java` (OpenPDF/POI API details), `config/SecurityConfig.java` + `auth/TokenService.java`
(NimbusJwtEncoder with ImmutableSecret, HS256), JdbcTemplate lambda overload resolution, java.sql.Timestamp vs OffsetDateTime.

## Rules
- Do NOT change REST paths or JSON field names — the frontend depends on them (camelCase via `common/Rows.camel`).
- Do NOT edit V1/V2 migrations once a DB exists; add `V3__...sql` instead.
- Keep fixes minimal; no new infrastructure (GeoServer/OpenSearch/ClickHouse are roadmap items, not MVP).
- Demo login (`/api/auth/dev-login`) is intentional; real Infocom ID OIDC is out of scope.

## Commands
- DB only: `docker compose up -d db` (port not published by default — add `ports: ["5432:5432"]` for local dev)
- Backend: `cd backend && mvn -B spring-boot:run` (env: DB_HOST, DB_PASSWORD, SEED_DIR=../demo-data)
- Frontend: `cd frontend && npm ci && npm run dev` → http://localhost:5173 (proxies /api to :8080)
- Everything: `docker compose up --build` → http://localhost:8088

## Expected results after first start (use as test oracle)
- Log: 7 layers seeded (atd_oblasts 8, atd_districts 41, settlements 14, roads 66, hydrography 5, urban_zones 24, current_development 170)
- Log: initial zoning check for Бишкек → objectsChecked 117, discrepancies 29 (USE_NOT_ALLOWED 17, NO_ZONE 3, CROSSES_ZONES 9)
- `GET /api/catalog/tree` (anonymous) → 7 themes, 34 layers (3 restricted ones hidden)
- `GET /api/search?q=бишк` → first result «Бишкек», layer «Населённые пункты»
- `POST /api/auth/dev-login {"subject":"infocom:former"}` → 403 + `audit.events` row with outcome `denied`
- Import `demo-data/samples/hospitals_kyrg06_zone3.zip` into layer `health` → detectedSrid 7694; validate with
  sourceSrid 7694 → blocking=true, 1 error «Объект №3: не заполнено обязательное поле «Наименование»»
- Import `demo-data/samples/green_zones_with_error.geojson` → without makeValid: self-intersection error; with makeValid: 2 objects OK
