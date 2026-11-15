# FIXES — прогон сборки и запуска 2026-11-10

Исправлений в этом прогоне не потребовалось.

| Шаг | Результат |
|---|---|
| `mvn -B clean package -DskipTests` (maven:3.9-eclipse-temurin-21) | BUILD SUCCESS, 28 файлов, 0 ошибок, 0 предупреждений |
| `docker compose up --build` на чистых томах | Flyway: V1 schema, V2 reference data, V3 feature lineage — success; DemoSeeder: 7 слоёв, 328 объектов; 0 ошибок в логе |

Ошибки компиляции и запуска, найденные ранее, уже исправлены в истории git (коммиты `fix(...)`,
см. `git log` и `docs/acceptance/`). Миграция V3 появилась в одном из тех раундов.
