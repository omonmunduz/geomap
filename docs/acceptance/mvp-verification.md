# Геопортал НИПД КР — MVP: verification against the control scenarios (КС)

Run date: 2026-11-08 · branch `mvp-stabilize` · stack: `docker compose up --build` (PostGIS 16-3.4, backend on Temurin 21 + GDAL 3.8.4, nginx frontend).
Every run used a **fresh database** (`docker compose down -v`).

> **Updated after the bug-fix round (§6).** Results below reflect the build at `c7f1484`. Rows changed by the fixes are marked *(updated)*.

| Evidence | What it is |
|---|---|
| `evidence/api-run.log` | Output of `scripts/acceptance.sh` after the fixes: every request with HTTP status + response excerpt, **125 `CHECK` lines, 125 PASS** (78 original + 47 regression checks for §6) |
| `evidence/pre-fix-run.log` | The same script (with the §6 regression checks) against the build **before** the fixes: 92 PASS, **33 FAIL** — every fix has failing checks there |
| `evidence/ui-run.log` | Output of `scripts/acceptance-ui.mjs` (Playwright 1.49.1, headless Chromium) after the fixes: 40 `CHECK` lines, **39 PASS, 1 FAIL** (FR-PUB-01, backlog) |
| `screenshots/<FR>.png` | One or more screenshots per UI-checked FR |
| `evidence/fr-ter-02-independent-check.{sql,txt}` | Independent set-based SQL recomputation of the zoning discrepancies |
| `evidence/zoning-report-bishkek*.{xlsx,pdf}` | Reports produced during the run |

Roles used: **anonymous**; **authorized** = `tunduk:citizen` (Азамат Гражданин); **staff** = `infocom:minzdrav` (Динара Мамытова), `infocom:minarch` (Айгерим Садыкова), `infocom:mchs` (Бакыт Осмонов); **admin** = `infocom:admin`.
Tokens come from `POST /api/auth/dev-login {"subject": …}`.

---

## 1. Build summary

| Step | Result | Notes |
|---|---|---|
| Tooling on host | Java, Maven, GDAL, psql, jq **not installed**; Node 24, Docker 29 / Compose 5 present | Everything Java/GDAL ran in containers (`maven:3.9-eclipse-temurin-21`, backend image); nothing installed on the host |
| `mvn package -DskipTests` | ✅ compiled first time, **no compile errors** | |
| `mvn test` | ✅ `No tests to run` | There is no `src/test` |
| `npm ci && npm run build` (incl. `tsc -b`) | ✅ | Only a Vite "chunk > 500 kB" warning |
| `docker compose up --build` | ❌ → ✅ | Backend crashed on start: `ogrinfo -json` unknown option (GDAL 3.4 in Ubuntu jammy). Fixed in **5d12f86** |
| Flyway V1, V2 (+ V3) | ✅ `Successfully applied 3 migrations` | V1/V2 untouched. V3 (`V3__feature_lineage.sql`) was added in the fix round for copy-on-write versioning (§6) |
| Demo seeder | ✅ 7 layers: 8 / 41 / 14 / 66 / 5 / 24 / 170 objects | Initial zoning check Бишкек: 117 objects, 29 discrepancies (17 / 3 / 9) |
| http://localhost:8088, `/api/catalog/tree` | ✅ 7 themes, 34 layers for anonymous (37 incl. 3 restricted) | |

**Fixes (one commit each):**

| Commit | Fix |
|---|---|
| `5d12f86` | `backend/Dockerfile`: runtime image `eclipse-temurin:21-jre-jammy` → `-noble` (GDAL 3.8). The import pipeline needs `ogrinfo -json` (GDAL ≥ 3.7); without it the seeder failed and the app did not start. |
| `400c56a` | `common/ErrorHandler.java`: the catch-all `Exception` handler turned Spring's own 400/404/405 errors and missing rows (`EmptyResultDataAccessException`) into **500 «Внутренняя ошибка»** (e.g. `PUT /api/features/<unknown id>`, `GET /api/catalog/layers/not-a-uuid`, any unknown route). They now return 400/404/405. |
| `263e722` | Local-dev only: `docker-compose.override.yml` publishes ports 5432/8080; `CLAUDE.md` added. |

Fixes from the second round (copy-on-write drafts, hyphenated search, logout audit, format validation) are listed in **§6**.

**Still open (backlog by decision, see §4):** layout not adaptive on phones, no ru/ky word forms, storage in WGS-84 rather than Kyrg-06, search exports without attributes, no layer-name search in the tree.

---

## 2. Results

Legend: **PASS** = КС expected result achieved · **PARTIAL** = the existing part works, the stated gap is real · **FAIL** = КС result not achieved · **BLOCKED** = needs a real external system.
Log references are `api-run.log` section headers (`### …`) or `ui-run.log` `[FR]` tags.

| FR | Review | Result | Verdict changed? | Evidence | Notes |
|---|---|---|---|---|---|
| FR-DAT-01 | Есть | **PASS** | — | api §FR-DAT-01: `POST /api/layers/{id}/features` point/line/polygon → `geometryType` POINT / LINESTRING / POLYGON; `GET /api/features/{id}` returns the stored geometry (4 vertices) | Seeded layers also cover all three types (settlements, roads, oblasts) |
| FR-DAT-03 | Частично | **PARTIAL** | — | api §FR-DAT-03: PNG + PDF attached via `POST /api/zoning/discrepancies/{id}/status -F file=@…` → 200; downloads byte-identical (`cmp`); `POST /api/features/{id}/attachments` → 404. UI: `screenshots/FR-DAT-03.png` (act in status history) | Gap confirmed: attachments only on zoning discrepancies, not on arbitrary objects |
| FR-DAT-04 | Частично | **PARTIAL** | — | api §FR-GIS-07/FR-DAT-04: 10 files uploaded via `POST /api/imports` (multipart) → 200 each; UI wizard works (`FR-DAT-12.png`) | Gap confirmed: batch file upload only. No streaming, HTTP pull, SSH/SFTP or Kafka (no code). Import errors go to the job + errors CSV; only successful commits go to the audit log |
| FR-DAT-05 | Частично | **PARTIAL** | — | api §FR-DAT-05: `/api/my/layers` (own org only), `/api/zoning/discrepancies?type=NO_ZONE` → 3, `?status=in_review` → 1; UI `FR-DAT-05.png` (name filter 1 → 0 rows) | Gap confirmed: no generic registry module, no relations between records |
| FR-DAT-06 | Частично | **PARTIAL** | — | ui [FR-DAT-06]: ☆ stores `gp_fav` in localStorage (anonymous gets «Войдите, чтобы сохранить в избранное»); print → `window.print()`; card CSV + GeoJSON download. `FR-DAT-06.png` | Gap confirmed: no buffer, no route. Favourites are per-browser only (not on the server) |
| FR-DAT-08 | Частично | **PARTIAL** *(updated)* | — | api §FR-DAT-08: create/update/delete → audit `feature_create/update/delete`; history 3 entries with author; other org staff → 403, citizen → 403, anonymous → 401; `GET /api/features/{id}/links` → 404. api §FIX-1: add/edit/delete in a published layer go to a draft; anonymous card/search/tile and the zoning check unchanged until publish; rollback restores | Gap confirmed: no relations ("связи"). The draft-bypass bug (§4 #1) is **fixed** in `f63c8ef` |
| FR-DAT-09 | Частично | **PASS** | **yes** (Частично → PASS) | ui [FR-DAT-09]: picking «Каракол» opens the card and fits the map to the object; card ⤢ re-zooms. `FR-DAT-09.png`, `FR-SRCH-03-card.png` | The КС step (card → object on map) works for every feature card. There is no separate "registry record" entity, but the discrepancy registry also positions the map (`FR-TER-03.png`) |
| FR-DAT-10 | Частично | **PARTIAL** | — | api §FR-DAT-10: `GET /api/catalog/layers/{id}` → `metadata {abstract, keywords, lineage, updateFreq, contact}`; UI `FR-DAT-10.png` | Gap confirmed: no ISO 19115-1 profile and no way to edit metadata (`PATCH /api/layers/{id}` only covers titles, description, access, colour) |
| FR-DAT-12 | Частично | **PARTIAL** | — | api §FR-DAT-12: missing «Наименование» → `blocking:true`; self-intersection → blocking; `errors.csv` contains «самопересечение»; commit of invalid job → **409**; imported draft hidden from anonymous until `POST /publish`. UI `FR-DAT-12.png` | Gap confirmed: no «карантин» status (job states are uploaded/validated/invalid/…), no ISO 19157 evaluation. Publication does require an explicit step |
| FR-GIS-00 | Есть | **PASS** | — | ui [FR-GIS-00]: default `osm`, 20 OSM tile requests, switch to «Без подложки» and back. `FR-GIS-00.png`, `FR-GIS-00-none.png` | Only two options (OSM / none): no satellite or topo basemap |
| FR-GIS-01 | Частично | **PARTIAL** | — | ui [FR-GIS-01]: 7 themes, 34 layer rows, visibility checkboxes; 0 text inputs and 0 range sliders in the tree. `FR-GIS-01.png` | **The review missed a gap:** the КС expects «поиск по названию работает» in the layer tree, and there is no such search (only the object search box). Opacity and ordering are also absent, as the review said |
| FR-GIS-03 | Частично | **PASS** | **yes** (Частично → PASS) | api §FR-DAT-01/FR-GIS-03: vertex added (4 → 5), point moved (lon 74.61), line deleted then 403 on read, wrong geometry type → 400, outside KR → 400. ui [FR-GIS-03]: point by coordinates saved in «Оползни, лавины» (`FR-GIS-03.png`) | The review's open items (move/delete) work. Line/polygon drawing and vertex dragging in the UI were not automated; the editor exposes them (Точка/Линия/Полигон, `direct_select`) and the same API is verified |
| FR-GIS-05 | Частично | **PASS** | **yes** (Частично → PASS) | ui [FR-GIS-05]: legend for «Текущее освоение территории» (6 classes); clicking the map opens the card («Чуйская область»). `FR-GIS-05.png` | Legend lives in the layer tree (category palette or colour swatch) |
| FR-GIS-06 | Частично | **PARTIAL** | — | api §FR-GIS-06: `/api/tiles/{id}/5/22/11.pbf` → 200, 1510 bytes; `/api/wfs?…GetCapabilities` → 401/404 | Gap confirmed: internal MVT only. No WFS/WFS-T client or server, no external-layer registration |
| FR-GIS-07 | Частично | **PARTIAL** | — | api §FR-GIS-07: SHP(zip), GPKG, GeoJSON, KML, KMZ, MapInfo TAB(zip), MIF(zip), FileGDB(zip), CSV(lon/lat), GML all recognised (feature count, fields, SRS). Kyrg-06 zone 3 auto-detected (EPSG:7694) and reprojected: bbox [74.53, 42.84, 74.64, 42.89] (Бишкек) | All 10 claimed formats read. TAB/MIF/CSV come back with `detectedSrid:null`, so the user must pick the CRS (the wizard asks). I couldn't compare against the full ЧТЗ format list (not in the repo). No raster import |
| FR-SRCH-01 | Частично | **PARTIAL** *(updated)* | — | api §FR-SRCH-01: «бишк» 26 / «Бишкек» 26 / **«Бишкеке» 0** / «чуй» 1 / **«Чүй» 0** / «ОБ-0001» 1 / «Каракол» 1 / «Tokmak» 1 / **«Бишкек шаары» 0** — identical before and after the fix. api §FIX-2: `1-02-03-0605-0433` → 1 (was 0); part `0605-0433` → **0** | Gap confirmed: no ru/ky morphology (backlog). Precision/recall can't be measured (no test corpus). Prefix noise («Ош» matches «ошибка») remains. Hyphenated codes are **fixed** in `f880760` for the full value; a fragment starting mid-code is still not found |
| FR-SRCH-02 | Частично | **PARTIAL** | — | api §FR-SRCH-02: rectangle → 171 results, all inside; **arbitrary triangle polygon → 88** (API accepts any GeoJSON polygon). ui `FR-SRCH-02.png` (rectangle drawn with the mouse) | Part of the gap is already implemented: contour search exists in the API, but the UI only draws rectangles. No centre or buffer search |
| FR-SRCH-03 | Частично | **PASS** | **yes** (Частично → PASS) | ui [FR-SRCH-03]: Enter on «Бишкек» → 20-item list with highlight; item → card. `FR-SRCH-03.png`, `FR-SRCH-03-card.png` | The review's note (print = `window.print()`) isn't part of this КС |
| FR-SRCH-04 | Частично | **PARTIAL** | — | ui [FR-SRCH-04]: results CSV (`Наименование;Слой;Долгота;Широта`) and GeoJSON (20 features); no XML/PDF buttons | Gap confirmed: no XML or PDF. **Also:** exports contain only title/layer/point coordinates, not the attributes or the real geometry |
| FR-SRCH-05 | Есть | **PASS** | — | api §FR-SRCH-05: restricted object found by staff/admin (1), not by anonymous/citizen (0); card, tile and layer as anonymous/citizen → 403, as staff → 200 | Server-side filter confirmed on 4 endpoints |
| FR-CFG-01 | Частично | **PARTIAL** | — | api §FR-CFG-01: `PATCH /api/layers/{id} {"color":"#ff0000"}` → `style.color` changed | Gap confirmed: no min/max zoom (visibility scales) in schema or API |
| FR-CFG-04 | Частично | **PARTIAL** | — | api §FR-CFG-04: `PUT /api/admin/zoning-rules` allowing agriculture in residential → re-check gives fewer than 29; restore → 29. UI `FR-CFG-04.png` | Gap confirmed: only the zoning-rule matrix; no generic dictionary module (`/api/admin/dictionaries` → 404) |
| FR-PUB-01 | Частично | **FAIL** | **yes** (Частично → FAIL) | ui [FR-PUB-01] (iPhone 13 profile): layout width 512 px on a 390 px screen, sidebar 300 px, **map 90 px**. `FR-PUB-01.png` | Desktop works. Phones get a fixed desktop layout: `styles.css` has no `@media` rules except print |
| FR-PUB-02 | Частично | **PARTIAL** | — | api §FR-SRCH-05/FR-PUB-02 role matrix (anonymous/citizen/staff/admin): `/api/my/layers` 401/403/200/200, `/api/zoning/*` 401/403/200/200, `/api/admin/*` 401/403/403/200; restricted layers hidden from anonymous/citizen. ui `FR-PUB-02-citizen.png` | The 4 roles are enforced consistently. Can't verify against the full ЧТЗ rights matrix (fixed roles, no scopes) |
| FR-AUTH-01 | Частично | **BLOCKED** | **yes** | api §FR-AUTH: emulated Infocom ID and **Түндүк** both work (citizen gets role `authorized`; first login creates a `core.users` row). ui `FR-AUTH-01.png` | Real Infocom ID / Түндүк OIDC isn't available. The review's «Түндүк нет» is inaccurate: an emulated Түндүк login exists |
| FR-AUTH-02 | Частично | **BLOCKED** | **yes** | api §FR-AUTH: organisation/position taken from `MockEmploymentRegistry`; `infocom:former` (is_active=false) → 403 + `login_denied` | Real Е-Кызмат isn't available; the mock path works |
| FR-AUTH-03 | Частично | **PARTIAL** | — | api §FR-AUTH: `PATCH /api/admin/users/{id} {"status":"blocked"}` → login 403 «заблокирована»; audit `user_update`; `POST /api/admin/users` → 404/405; `DELETE` → 405 | Gap confirmed: users are created implicitly on first login; no explicit create or delete |
| FR-AUTH-06 | Частично | **PASS** *(updated)* | **yes** (Частично → PASS) | api §FR-AUTH: `login` events carry `details.provider`; `login_denied` with reason; blocking logged as `user_update`. api §FIX-3a: `POST /api/auth/logout` → event `logout` {user, provider, time, IP}; UI run logged 3 logouts (`tunduk`, `infocom`). UI `FR-AUTH-06.png` | The КС (login, logout and blocking recorded with the login method) is now met; logout was **fixed** in `7bb6cb0`. Storage is PostgreSQL `audit.events`, not the target ClickHouse — an architecture item, not part of this КС |
| FR-ADM-01 | Частично | **PARTIAL** | — | api §FR-AUTH: role change staff → admin → staff via `PATCH`, visible in the journal; UI `FR-ADM-01.png` | Gap confirmed: no visibility scope per theme/layer |
| FR-ADM-02 | Частично | **FAIL** *(updated)* | **yes** (Частично → FAIL) | api §FR-ADM-02: `GET /api/admin/stats` → 8 counters (users, layers, features, imports, events…); api §FIX-3b: `?format=xlsx` → **400** «сводная статистика выдаётся только в JSON» (was: JSON with 200). UI `FR-ADM-02.png` | None of the КС outcome is achievable: no Excel, no field selection, no service/layer usage metrics, no distribution of users by country. The misleading format handling is **fixed** in `c7f1484` |
| FR-RPT-01 | Частично | **PARTIAL** *(updated)* | — | api §FR-TER-05/FR-RPT-01: zoning report respects the current filter (`&type=NO_ZONE` → report with 3 rows); api §FIX-3b: `format=docx` → **400** «Доступны: xlsx, pdf» (was: XLSX with 200); `format=pdf` still 200 | Gap confirmed: only the zoning report. No report for an arbitrary selection, no DOCX/PPTX. Silent format fallback **fixed** in `c7f1484` |
| FR-RPT-04 | Частично | **PARTIAL** | — | XLSX only for zoning (`evidence/zoning-report-bishkek.xlsx`); card CSV has all attributes; search-results CSV/GeoJSON have no attributes | Gap confirmed |
| FR-TER-01 | Есть | **PARTIAL** | **yes** (Есть → PARTIAL) | api §FR-TER-01: both layers published; ui `FR-TER-01.png` (zones and development overlay with no visible shift) | The КС expects «система координат Kyrg-06». Storage and display are WGS-84 / Web Mercator, and the demo layers are synthetic WGS-84 GeoJSON. Kyrg-06 is supported only as an import source (verified with the hospitals sample) |
| FR-TER-02 | Есть | **PASS** | — | api §FR-CFG-04/FR-TER-02: re-run → `[117, 29, 0 new]`; `evidence/fr-ter-02-independent-check.txt`: independent SQL finds 29 (17/3/9), **matched 29, missed 0, extra 0**. UI `FR-TER-02.png` | 100% of the planted discrepancies, no false positives. Caveat: the "control set" is the synthetic demo data with designed counts; no externally labelled set exists |
| FR-TER-03 | Есть | **PASS** | — | api §FR-TER-03: record has object, type, `responsibleOrg` (Минархстрой); every history entry has `byName` + `at`. ui [FR-TER-03]: status «Нарушение подтверждено» + act `akt.pdf`, shown in history. `FR-TER-03.png` | |
| FR-TER-04 | Есть | **PASS** | — | api §FR-TER-04: filters by type (9/17/3), status, territory and combined (3) return only matching rows; ui `FR-TER-04.png`: NO_ZONE → «Реестр расхождений (3)» with map highlight | |
| FR-TER-05 | Есть | **PASS** | — | `evidence/zoning-report-bishkek.{xlsx,pdf}`: «Всего расхождений: 29», by type 17/3/9 = registry rows 29 (17/9/3); PDF Cyrillic text extractable | The territory used is «Бишкек» (city of republican significance); there are no districts inside Бишкек in the demo data |
| FR-TER-06 | Частично | **PARTIAL** *(updated)* | — | ui `FR-TER-06.png`: oblasts, districts, settlements, roads and hydrography displayed together; ui [FR-TER-06]: `GET /api/search?q=1-02-03-0605-0433` → **1** (was 0) | Gap confirmed: no cadastre layer with data, no opacity/order. Objects can now be found by the `cad_no` attribute (fix `f880760`) |
| FR-TER-07 | Частично | **PARTIAL** *(updated)* | — | api §FR-TER-07: Минархстрой creates a zone with «Вид разрешённого использования», changes its boundary; `/history` shows create/update with author and time. api §FIX-1: the edit creates a new draft version (published v1 untouched); `/history` now records the version of each change; publish → new version, rollback → previous version | Gap confirmed: the approving document is only a text attribute (no file). Versioning of zone edits is **fixed** in `f63c8ef` |
| FR-TUR-04 | Частично | **PARTIAL** | — | ui [FR-TUR-04]: КЫР switches layer titles («АТБ: облустар»); «Слои», «Подложка» stay Russian; no EN. `FR-TUR-04.png` | Gap confirmed |

---

## 3. Totals

| Result | Count | FRs |
|---|---|---|
| **PASS** | **12** | DAT-01, DAT-09, GIS-00, GIS-03, GIS-05, SRCH-03, SRCH-05, AUTH-06, TER-02, TER-03, TER-04, TER-05 |
| **PARTIAL** | **24** | DAT-03, DAT-04, DAT-05, DAT-06, DAT-08, DAT-10, DAT-12, GIS-01, GIS-06, GIS-07, SRCH-01, SRCH-02, SRCH-04, CFG-01, CFG-04, PUB-02, AUTH-03, ADM-01, RPT-01, RPT-04, TER-01, TER-06, TER-07, TUR-04 |
| **FAIL** | **2** | PUB-01, ADM-02 |
| **BLOCKED** | **2** | AUTH-01, AUTH-02 |

Before the fix round: 11 / 25 / 2 / 2. After it, FR-AUTH-06 moved PARTIAL → PASS; the other affected FRs (DAT-08, TER-07, SRCH-01, RPT-01, ADM-02, TER-06) keep their result because their remaining gaps are not bugs.

Against the review (8 Есть / 32 Частично): 7 of the 8 «Есть» hold (TER-01 drops to PARTIAL because of Kyrg-06). 4 «Частично» are actually full passes for their КС (DAT-09, GIS-03, GIS-05, SRCH-03). 2 are FAIL (PUB-01, ADM-02) and 2 are BLOCKED on external systems (AUTH-01, AUTH-02).

---

## 4. Gaps and defects found (beyond the review)

1. ✅ *Fixed in `f63c8ef`.* **Edits to a published layer bypass the draft (data-governance bug).** `FeatureController.create/update/delete` write to `layer.draftVersion`. After publishing, that equals `publishedVersion`, so a staff edit to a normal (non-operational) layer, e.g. a new zone in «Зоны застройки», is public immediately and no new layer version is created. The class comment says «правки применяются к рабочей (draft) версии». Affects FR-DAT-08, FR-TER-07 (version history) and the zoning check, which sees unpublished-in-intent edits. Not fixed: it needs copy-on-write versioning.
2. ✅ *Fixed in `f880760`.* **Hyphenated codes in attributes can't be found.** The `simple` parser indexes `1-02-03-0605-0433` as `'1' '-02' '-03' '-0605' '-0433'`, while `SearchController` splits the query on non-alphanumerics into `1:* & 02:* …`. They never match. Titles like «ОБ-0001» only work through the `ILIKE` fallback. Affects cadastral numbers (FR-TER-06) and any code-like attribute. Suggested fix: build the tsquery with the same parser, e.g. from `to_tsvector('simple', :q)`. Not applied because it changes search behaviour.
3. ✅ **Unknown routes and bad input returned 500.** Fixed in `400c56a`.
4. **Not adaptive on phones** (FR-PUB-01): fixed 300 px sidebar, the map gets 90 px, the header overflows.
5. ✅ *Fixed in `7bb6cb0`.* **Logout isn't audited** (FR-AUTH-06): logout only clears the token in the browser.
6. **Layer tree has no name search** (FR-GIS-01): the КС requires it; the review only listed opacity/order.
7. **Search-result exports drop attributes and geometry** (FR-SRCH-04 / FR-RPT-04): CSV is title/layer/lon/lat; GeoJSON is points with `{id, title, layer}`.
8. **Search quality** (FR-SRCH-01):
   - Kyrgyz spellings («Чүй») and inflected Russian («Бишкеке») return nothing.
   - Prefix matching gives noise: «Ош» returns a green zone through the word «ошибка».
   - Settlements have no `name_ky` in the demo data at all.
9. ✅ *Fixed in `c7f1484`.* **`format=docx` and `stats?format=xlsx` silently ignore the format.** The report endpoint falls back to XLSX; stats always returns JSON.
10. **CRS not auto-detected for MapInfo and CSV** (`detectedSrid: null` for a WGS-84 TAB/MIF): the user has to pick it manually.
11. **FR-TER-01 Kyrg-06** (review: Есть): data are stored and shown in WGS-84 / Web Mercator. Kyrg-06 is only an import source.
12. **Favourites are per-browser** (`localStorage`): they are lost on another device or browser.

## 5. How to reproduce

```bash
docker compose down -v && docker compose up --build -d          # wait for "Initial zoning check" in backend log
# format samples for FR-GIS-07 (from demo-data/settlements.geojson, inside the backend image which has GDAL):
docker compose exec backend sh -c 'mkdir -p /tmp/fmt/tab /tmp/fmt/mif && cd /tmp/fmt && S=/app/demo-data/settlements.geojson &&
  ogr2ogr -f GPKG settlements.gpkg $S && ogr2ogr -f KML settlements.kml $S && ogr2ogr -f LIBKML settlements.kmz $S &&
  ogr2ogr -f "MapInfo File" tab/settlements.tab $S && ogr2ogr -f "MapInfo File" mif/settlements.mif $S -dsco FORMAT=MIF &&
  ogr2ogr -f OpenFileGDB settlements.gdb $S && ogr2ogr -f CSV settlements.csv $S -lco GEOMETRY=AS_XY && ogr2ogr -f GML settlements.gml $S'
# then copy /tmp/fmt out, zip tab/ → settlements_tab.zip, mif/ → settlements_mif.zip, settlements.gdb/ → settlements.gdb.zip
docker run --rm --network geoportal-mvp_default -v "$PWD:/w" -v "<fmt dir>:/fmt" -w /w -e BASE=http://backend:8080 \
  alpine:3.20 sh -c "apk add -q bash curl jq coreutils diffutils && bash scripts/acceptance.sh" > docs/acceptance/evidence/api-run.log
docker compose down -v && docker compose up -d   # fresh DB for the UI run
docker run --rm --ipc=host --network geoportal-mvp_default -v "$PWD:/w" -w /tmp -e BASE=http://frontend \
  mcr.microsoft.com/playwright:v1.49.1-noble sh -c "npm i -s playwright@1.49.1 && cp /w/scripts/acceptance-ui.mjs . && node acceptance-ui.mjs"
docker compose exec -T db psql -U geo -d geoportal -x < docs/acceptance/evidence/fr-ter-02-independent-check.sql
```

---

## 6. Fixes (second round)

Each fix has regression checks in `scripts/acceptance.sh` (sections `FIX-1` … `FIX-3b`). They were committed first (`b16985e`) and run against the unfixed build — `evidence/pre-fix-run.log`: **33 FAIL** — then pass after the fixes — `evidence/api-run.log`: **125/125 PASS**.

| Commit | Fix | Checks (api-run.log) |
|---|---|---|
| `b16985e` | Regression checks added (failing before the fixes) | — |
| `f63c8ef` | **Copy-on-write drafts.** The first create/edit/delete in a published non-operational layer copies the published objects into a new draft version; the published version, anonymous/`authorized` cards, tiles, search, catalog counts and `runCheck` are unchanged until «Опубликовать». Staff see the draft (status `published_with_draft`, draft tiles). Publish promotes the draft; rollback works. Operational layers still publish immediately. **V3**: `spatial.features.origin_id` (lineage across versions, defaulted by trigger) and `feature_history.version`. Edits sent with a published object id are applied to its copy in the draft; history spans versions and records the version. | §FIX-1: 40 checks — add / edit / delete × (anonymous card, search, tile, zoning check) × (draft, publish, rollback); version recorded; operational layer still immediate; zoning registry stable after re-publishing the development layer |
| `f880760` | **Hyphenated codes in search.** The prefix tsquery is built from the same `simple` parser lexemes as the index. Word-search results identical; SRCH-05 checks unchanged. | §FIX-2: full cadastral number → 1; «бишк» 26; «ОБ-0001» 1. **Partial `0605-0433` → 0** (a fragment that starts in the middle of the code is indexed as `-0605`, so it is still not found) |
| `7bb6cb0` | **Logout audited.** `POST /api/auth/logout` writes `logout` {user, organisation, provider, time, IP}; the client calls it on «Выйти»; the journal shows «Выход». | §FIX-3a: event `logout/infocom` for Асель Токтогулова; UI run: 3 logouts (tunduk, infocom) |
| `c7f1484` | **Unsupported formats → 400.** `GET /api/zoning/report?format=docx` and `GET /api/admin/stats?format=xlsx` return 400 with the supported formats instead of a silent fallback. | §FIX-3b: docx → 400, stats xlsx → 400, report pdf still 200 |

**What turned out bigger than described in fix 1**

- **Object identity across versions.** Every version is a full copy of the layer's objects with new primary keys, so after copy-on-write the id the client holds (from a card, a tile or a favourite) belongs to the published row, not to the draft. A lineage column was needed to map the published id to its draft copy. Hence the V3 migration rather than a code-only change.
- **Zoning registry duplication.** Because the zoning discrepancy fingerprint used the development object's id, re-publishing an edited development layer made all 29 discrepancies "new" again (seen in the intermediate run: `[29, 29]`). The fingerprint now uses `origin_id`, and an existing discrepancy is re-pointed to the current object. Old fingerprints remain valid because V3 sets `origin_id = id` for existing rows.
- **Not changed (still pre-existing):** zoning territories are referenced by ATD feature id, so re-publishing the ATD layer (by import or by editing) changes territory ids. Imports still create a fresh version with new lineage (a re-import is treated as new data). Concurrent first edits are serialized with `SELECT … FOR UPDATE` on the layer row.

Total effort for fix 1 stayed well under a day.
