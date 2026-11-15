# Task: build the Geoportal NIPD MVP and verify it against the acceptance criteria it claims to cover

## Context
This repo (geomap) is an MVP "сквозной срез" of the Geoportal NIPD KR spec (ЧТЗ 04.11.2026): import → publish → map → search → zoning check → report. Backend: Spring Boot 3 / Java 21 / PostgreSQL 16 + PostGIS 3 / Flyway / GDAL. Frontend: React 18 + TypeScript + MapLibre GL. See README.md.

**The Java code has never been compiled.** SQL was checked against PostGIS and the frontend in a browser, but nothing has run end to end.

The spec has 187 functional requirements, each with a control scenario (КС) in Appendix З. A code review found that this MVP touches 40 of them (8 "Есть", 32 "Частично"). Your job is to prove or disprove that by running it.

## Rules
- Do NOT add new features. Only fix bugs that stop the EXISTING functionality from compiling, starting or working.
- Keep each fix small. Make a separate commit for each logical fix, with a clear message (e.g. `fix(zoning): ...`).
- If something can't be verified (missing dependency, needs a real external system), mark it BLOCKED with the reason. Don't fake it.
- Don't change the DB schema unless a migration actually fails. If it does, add a new migration (V3__...) instead of editing V1/V2.
- When an FR is "Частично", test the part that exists AND confirm the stated gap is real. If the gap turns out to be already implemented, say so.

## Phase 1 — Build and start
1. Check the tooling: `java -version` (need 21), `mvn -v`, `node -v`, `docker compose version`, `ogr2ogr --version`. Report anything missing.
2. Backend: `cd backend && mvn -q package -DskipTests`. Fix compile errors one by one. Then run `mvn test` if there are any tests.
3. Frontend: `cd frontend && npm ci && npm run build` (includes the TypeScript type check). Fix any errors.
4. Full stack: `docker compose up --build -d`. Wait for health, then check:
   - http://localhost:8088 loads
   - Flyway migrations V1 and V2 applied (check the logs)
   - the demo seeder loaded the 7 layers in demo-data/
   - `GET /api/catalog/tree` returns the themes and layers
5. Log in through the dev-login (POST /api/auth/dev-login, or the UI "Войти → Войти через Infocom ID") as each demo account: authorized, staff and admin. Save a token for each role.

## Phase 2 — Run the control scenarios
Run each scenario below, through the API (curl) and/or the UI. For UI checks, use Playwright (install it in a scratch folder outside the repo, or as a dev dependency if that's cleaner) and save screenshots to `docs/acceptance/screenshots/<FR>.png`.

Use the files in `demo-data/samples/` for import tests:
- `hospitals_kyrg06_zone3.zip`: should import with reprojection from Kyrg-06
- `green_zones_with_error.geojson`: should fail validation and produce an errors CSV

For role-dependent checks, repeat as anonymous, authorized and staff/admin. Confirm that restricted layers and endpoints are refused (403) where they should be.

| FR | Review verdict | Steps (from КС) | Expected result (from КС) | Reviewer note |
|---|---|---|---|---|
| FR-DAT-01 | Есть (MVP) | Загрузить точечный, линейный и площадной слои. | Все типы геометрии сохранены и читаются из базы. | spatial.features + атрибуты; точки/линии/полигоны |
| FR-DAT-03 | Частично | Приложить изображение и скан документа к объекту. | Файлы сохранены и открываются из карточки. | Вложения только к расхождениям зонирования, не к любым записям |
| FR-DAT-04 | Частично | Запустить пакетную и потоковую загрузку; подключиться по HTTP, SSH, SFTP и через брокер Kafka. | Данные приняты по каждому каналу; ошибки записаны в журнал. | Только пакетная загрузка файла через UI; нет SFTP/SSH/Kafka, потока |
| FR-DAT-05 | Частично | Открыть реестр; выполнить поиск и фильтрацию. | Записи со связями отображаются; фильтры работают. | Список слоёв и реестр расхождений с фильтрами; общего модуля реестров нет |
| FR-DAT-06 | Частично | Открыть карточку; использовать Избранное, буфер, печать, маршрут, экспорт. | Все действия выполняются. | Карточка: избранное (в localStorage), печать, экспорт GeoJSON/CSV; нет буфера и маршрута |
| FR-DAT-08 | Частично | Создать, изменить, удалить запись и связь. | Изменения сохранены и записаны в журнал; права соблюдены. | CRUD объектов с правами и историей; связей нет |
| FR-DAT-09 | Частично | Перейти от карточки к объекту на карте. | Карта позиционируется на объекте. | Карточка ↔ карта для объектов слоя; реестровых записей отдельно нет |
| FR-DAT-10 | Частично | Заполнить метаданные набора по ISO 19115-1. | Метаданные валидны и находятся в каталоге. | layer_metadata: аннотация, периодичность, контакт; полного профиля ISO 19115 нет |
| FR-DAT-12 | Частично | Загрузить набор с заведомыми дефектами (пропуски, самопересечения, неверные значения атрибутов). | Набор переведён в статус «карантин»; отчёт содержит оценку по элементам ISO 19157; публикация возможна только после подтверждения. | Валидация при импорте + CSV ошибок; профиля ISO 19157 и статуса «карантин» нет |
| FR-GIS-00 | Есть (MVP) | Открыть карту; переключить подложки. | По умолчанию OSM; переключатель работает. | OSM по умолчанию, переключатель подложек |
| FR-GIS-01 | Частично | Открыть дерево слоёв. | Слои сгруппированы по темам; поиск по названию работает. | Дерево слоёв по темам, видимость; нет ползунка прозрачности и смены порядка |
| FR-GIS-03 | Частично | Создать точку, линию, полигон; изменить вершины. | Объекты сохраняются; редактирование работает. | Точка/линия/полигон, узлы, ввод координат (WGS-84); перемещение/удаление проверить |
| FR-GIS-05 | Частично | Кликнуть по объекту; открыть легенду. | Легенда соответствует слоям; карточка отображается. | Легенда в дереве слоёв, клик → карточка |
| FR-GIS-06 | Частично | Подключить внутренний слой и внешний слой по WFS и WFS-T; изменить объект внешнего слоя. | Слои отображаются; транзакция WFS-T выполнена при наличии прав. | Только внутренние слои (MVT из PostGIS); WFS/WFS-T нет |
| FR-GIS-07 | Частично | Импортировать файлы всех поддерживаемых форматов; указать систему координат. | Объекты загружены; ошибки перечислены в отчёте. | GDAL: SHP, GPKG, GeoJSON, KML/KMZ, TAB/MIF, FileGDB, CSV; через API — тот же эндпоинт; сверить с полным перечнем |
| FR-SRCH-01 | Частично | Искать по тестовому корпусу русских и кыргызских запросов. | Точность и полнота не ниже установленных значений. | tsvector simple + pg_trgm; без морфологии ru/ky (OpenSearch — целевая) |
| FR-SRCH-02 | Частично | Искать в области, по контуру, по центру и в буфере. | Результаты внутри заданной области. | Поиск в прямоугольной области; нет по контуру, центру карты, буферу |
| FR-SRCH-03 | Частично | Открыть результаты поиска. | Объекты подсвечены; предпросмотр и карточка доступны. | Подсветка, список, карточка; печать = window.print() |
| FR-SRCH-04 | Частично | Экспортировать результаты в CSV, XML и PDF. | Файлы открываются и содержат результаты. | CSV и GeoJSON; нет XML и PDF |
| FR-SRCH-05 | Есть (MVP) | Искать служебные данные без права доступа. | Служебные результаты не возвращаются. | Фильтр access_level на сервере по роли |
| FR-CFG-01 | Частично | Изменить масштабы видимости и стиль слоя. | Параметры применены на карте. | Создание слоя, тема, уровень доступа, стиль; масштабов видимости нет |
| FR-CFG-04 | Частично | Создать справочник и элемент. | Справочник доступен в карточках. | Только правила зонирования (PUT /admin/zoning-rules) |
| FR-PUB-01 | Частично | Открыть портал на настольном и мобильном устройстве. | Интерфейс адаптируется. | Веб-портал есть; адаптивность под мобильные не проверена |
| FR-PUB-02 | Частично | Зайти ролями гражданина, сотрудника, администратора. | Доступ соответствует матрице прав. | 3 фиксированные роли: authorized / staff / admin |
| FR-AUTH-01 | Частично | Войти через «Түндүк» и Infocom ID. | Создан единый внутренний профиль. | Вход через Infocom ID эмулирован (dev-login); «Түндүк» нет |
| FR-AUTH-02 | Частично | Войти сотрудником; проверить занятость. | Организация и должность подтверждены через Е-Кызмат. | MockEmploymentRegistry вместо API Е-Кызмат |
| FR-AUTH-03 | Частично | Создать, заблокировать, удалить учётную запись. | Операции выполнены; записаны в журнал. | Смена роли и статуса (блокировка); создание/удаление нет |
| FR-AUTH-06 | Частично | Выполнить вход, выход, блокировку. | События записаны с методом входа. | audit.events в PostgreSQL, не ClickHouse |
| FR-ADM-01 | Частично | Назначить роль и область видимости; открыть журнал. | Изменения применены и видны в журнале. | Список пользователей, роли, журнал аудита; областей видимости нет |
| FR-ADM-02 | Частично | Сформировать отчёт в Excel с выбором полей: востребованные сервисы и слои, распределение пользователей по странам. | Отчёт содержит выбранные поля и корректные значения. | Сводная статистика /admin/stats; метрик использования и Excel-отчёта нет |
| FR-RPT-01 | Частично | Сформировать отчёт по выборке. | Отчёт отражает текущую выборку. | Только отчёт по зонированию в XLSX/PDF; нет отчёта по выборке, DOCX/PPTX |
| FR-RPT-04 | Частично | Выгрузить таблицу. | Все атрибуты в выбранном формате. | XLSX только для отчёта по зонированию |
| FR-TER-01 | Есть (MVP) | Загрузить слой текущего освоения и слой утверждённого зонирования; открыть их совместно. | Оба слоя загружены и совмещены без смещения; система координат Kyrg-06. | Слои urban_zones и current_development |
| FR-TER-02 | Есть (MVP) | Запустить пересечение на контрольном наборе с известными расхождениями. | Выявлено не менее 95% контрольных расхождений; ложных срабатываний нет. | Пересечение, выявление расхождений (ZoningService.runCheck) |
| FR-TER-03 | Есть (MVP) | Открыть реестр расхождений; изменить статус записи. | Запись содержит объект, тип, ответственного; история статусов с автором и временем. | zoning.discrepancies: тип, ответственный, статус, история |
| FR-TER-04 | Есть (MVP) | Включить подсветку; отфильтровать по типу, статусу и территории. | Отображаются только соответствующие расхождения. | ZoningPage: фильтры по типу/статусу/территории, подсветка |
| FR-TER-05 | Есть (MVP) | Сформировать отчёт по району в PDF и XLSX. | Отчёт содержит число и типы расхождений; суммы совпадают с реестром. | ReportService: XLSX и PDF |
| FR-TER-06 | Частично | Открыть карту территориального управления; включить слои границ, кадастра и инфраструктуры; найти участок по кадастровому номеру. | Слои отображаются совместно; участок найден и подсвечен; порядок и прозрачность слоёв изменяются. | АТД, населённые пункты, дороги, гидрография; кадастра нет |
| FR-TER-07 | Частично | Создать зону с видом разрешённого использования, привязать документ; изменить границу; открыть историю версий. | Зона сохранена с атрибутами и документом; предыдущая версия доступна в истории с автором и датой. | Просмотр зон и правила; ведение/анализ зон — базово |
| FR-TUR-04 | Частично | Переключить язык интерфейса на кыргызский, русский, английский. | Интерфейс и справочники отображаются на выбранном языке. | Интерфейс ru/ky для названий слоёв; английского нет |

## Phase 3 — Report
Create `docs/acceptance/mvp-verification.md` with:
1. **Build summary:** what failed, what you fixed (with commit hashes), what's still broken.
2. **Results table:** FR | КС | Result (PASS / PARTIAL / FAIL / BLOCKED) | Evidence (curl command plus a short response excerpt, or a screenshot path) | Notes. Add a column "Verdict changed?" where your result differs from the review verdict above.
3. **Totals:** how many PASS / PARTIAL / FAIL / BLOCKED.
4. **Gaps found:** anything the КС expects that the MVP doesn't do, even if the review called it "Есть".

Also write `docs/acceptance/mvp-verification.csv` (columns: fr_code, result, notes; UTF-8; semicolon-separated) so the results can be merged back into the requirements spreadsheet.

At the end, give me a short summary: does it build, does it run, the PASS/PARTIAL/FAIL/BLOCKED totals, and the top 5 problems.
