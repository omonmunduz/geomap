-- Геопортал НИПД КР — MVP. Базовая схема данных (ЧТЗ п. 5.3.1, таблицы 14–19)
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS spatial;
CREATE SCHEMA IF NOT EXISTS jobs;
CREATE SCHEMA IF NOT EXISTS staging;
CREATE SCHEMA IF NOT EXISTS zoning;
CREATE SCHEMA IF NOT EXISTS audit;

-- ---------- core: организации, пользователи, роли ----------
CREATE TABLE core.organizations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        text NOT NULL UNIQUE,
    name_ru     text NOT NULL,
    name_ky     text,
    short_name  text NOT NULL
);

CREATE TABLE core.users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ext_subject   text NOT NULL UNIQUE,          -- subject из Infocom ID / Түндүк
    email         text,
    display_name  text NOT NULL,
    position      text,
    org_id        uuid REFERENCES core.organizations(id),
    role          text NOT NULL CHECK (role IN ('authorized','staff','admin')),
    status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active','blocked')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz
);

-- ---------- catalog: темы, слои, метаданные, атрибутная схема, версии ----------
CREATE TABLE catalog.themes (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code      text NOT NULL UNIQUE,
    title_ru  text NOT NULL,
    title_ky  text,
    sort      int NOT NULL DEFAULT 0
);

CREATE TABLE catalog.layers (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code               text NOT NULL UNIQUE,
    appendix_no        text,                         -- № слоя в Приложении 1 (перечень министерств)
    title_ru           text NOT NULL,
    title_ky           text,
    description        text,
    theme_id           uuid NOT NULL REFERENCES catalog.themes(id),
    kind               text NOT NULL DEFAULT 'thematic' CHECK (kind IN ('base','thematic')),
    geom_type          text NOT NULL CHECK (geom_type IN ('point','line','polygon','mixed','raster')),
    access_level       text NOT NULL DEFAULT 'open' CHECK (access_level IN ('open','restricted')),
    owner_org_id       uuid NOT NULL REFERENCES core.organizations(id),
    is_operational     boolean NOT NULL DEFAULT false,  -- оперативный слой (МЧС, п. 4.4.10)
    archived           boolean NOT NULL DEFAULT false,
    title_attr         text,                            -- атрибут, используемый как наименование объекта
    style              jsonb NOT NULL DEFAULT '{}'::jsonb,
    scale_min          int,
    scale_max          int,
    draft_version      int,                             -- последняя загруженная версия (рабочая)
    published_version  int,                             -- версия, видимая пользователям
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

-- Метаданные по ISO 19115 (минимальный профиль MVP)
CREATE TABLE catalog.layer_metadata (
    layer_id     uuid PRIMARY KEY REFERENCES catalog.layers(id) ON DELETE CASCADE,
    abstract     text,
    keywords     text[],
    lineage      text,       -- происхождение
    update_freq  text,       -- периодичность актуализации
    contact      text
);

CREATE TABLE catalog.layer_attributes (
    layer_id   uuid NOT NULL REFERENCES catalog.layers(id) ON DELETE CASCADE,
    name       text NOT NULL,
    label_ru   text NOT NULL,
    type       text NOT NULL DEFAULT 'text' CHECK (type IN ('text','number','date','enum')),
    required   boolean NOT NULL DEFAULT false,
    options    jsonb,           -- для enum: [{"value":"..","label":".."}]
    sort       int NOT NULL DEFAULT 0,
    PRIMARY KEY (layer_id, name)
);

CREATE TABLE catalog.layer_versions (
    layer_id       uuid NOT NULL REFERENCES catalog.layers(id) ON DELETE CASCADE,
    version        int NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid REFERENCES core.users(id),
    source         text,          -- имя файла / «редактор»
    source_srid    int,
    feature_count  int NOT NULL DEFAULT 0,
    note           text,
    PRIMARY KEY (layer_id, version)
);

-- ---------- spatial: пространственные объекты ----------
CREATE TABLE spatial.features (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    layer_id    uuid NOT NULL REFERENCES catalog.layers(id) ON DELETE CASCADE,
    version     int NOT NULL,
    geom        geometry(Geometry, 4326) NOT NULL,
    attrs       jsonb NOT NULL DEFAULT '{}'::jsonb,
    title       text,
    deleted     boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid REFERENCES core.users(id),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  uuid REFERENCES core.users(id),
    search      tsvector GENERATED ALWAYS AS (
                  setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
                  setweight(jsonb_to_tsvector('simple', attrs, '["string"]'), 'B')
                ) STORED
);
CREATE INDEX features_geom_gix   ON spatial.features USING gist (geom);
CREATE INDEX features_layer_ver  ON spatial.features (layer_id, version) WHERE NOT deleted;
CREATE INDEX features_search_gin ON spatial.features USING gin (search);
CREATE INDEX features_title_trgm ON spatial.features USING gin (title gin_trgm_ops);

-- История изменений объектов (редактор геометрии, FR-GIS-03)
CREATE TABLE spatial.feature_history (
    id          bigserial PRIMARY KEY,
    feature_id  uuid NOT NULL,
    layer_id    uuid NOT NULL,
    op          text NOT NULL CHECK (op IN ('create','update','delete')),
    geom        geometry(Geometry, 4326),
    attrs       jsonb,
    at          timestamptz NOT NULL DEFAULT now(),
    by_user     uuid REFERENCES core.users(id)
);
CREATE INDEX feature_history_fid ON spatial.feature_history (feature_id);

-- ---------- jobs: задания импорта ----------
CREATE TABLE jobs.import_jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    layer_id        uuid NOT NULL REFERENCES catalog.layers(id) ON DELETE CASCADE,
    status          text NOT NULL CHECK (status IN ('uploaded','validated','invalid','queued','running','done','failed','cancelled')),
    progress        int NOT NULL DEFAULT 0,
    file_name       text NOT NULL,
    file_path       text NOT NULL,
    ogr_layer       text,
    source_info     jsonb,          -- результат анализа файла (поля, СК, кол-во объектов)
    source_srid     int,
    mapping         jsonb,          -- {srcField: targetAttr|null}
    title_field     text,
    make_valid      boolean NOT NULL DEFAULT false,
    validation      jsonb,          -- {errors:[..], warnings:[..], valid:n}
    staging_table   text,
    result_version  int,
    message         text,
    created_by      uuid REFERENCES core.users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- ---------- zoning: якорный сервис «Цифровое территориальное управление» ----------
CREATE TABLE zoning.rules (
    zone_type  text NOT NULL,
    use_type   text NOT NULL,
    allowed    boolean NOT NULL,
    PRIMARY KEY (zone_type, use_type)
);

CREATE TABLE zoning.checks (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    territory_id    uuid,              -- объект слоя АТД (может быть NULL — вся страна/экстент)
    territory_name  text NOT NULL,
    zoning_layer    uuid NOT NULL REFERENCES catalog.layers(id),
    zoning_version  int NOT NULL,
    dev_layer       uuid NOT NULL REFERENCES catalog.layers(id),
    dev_version     int NOT NULL,
    stats           jsonb,
    run_at          timestamptz NOT NULL DEFAULT now(),
    run_by          uuid REFERENCES core.users(id)
);

CREATE TABLE zoning.discrepancies (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fingerprint         text NOT NULL UNIQUE,      -- dev feature + тип: повторная проверка не плодит дубли
    first_check_id      uuid REFERENCES zoning.checks(id),
    last_check_id       uuid REFERENCES zoning.checks(id),
    territory_id        uuid,
    territory_name      text,
    dev_feature_id      uuid NOT NULL,
    zone_feature_id     uuid,
    type                text NOT NULL CHECK (type IN ('USE_NOT_ALLOWED','NO_ZONE','CROSSES_ZONES')),
    use_type            text,
    zone_type           text,
    object_title        text,
    details             text,
    geom                geometry(Geometry, 4326) NOT NULL,
    responsible_org_id  uuid REFERENCES core.organizations(id),
    status              text NOT NULL DEFAULT 'new' CHECK (status IN ('new','in_review','confirmed','resolved','dismissed')),
    still_present       boolean NOT NULL DEFAULT true,  -- найдено при последней проверке
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX discrepancies_geom_gix ON zoning.discrepancies USING gist (geom);

CREATE TABLE zoning.discrepancy_history (
    id               bigserial PRIMARY KEY,
    discrepancy_id   uuid NOT NULL REFERENCES zoning.discrepancies(id) ON DELETE CASCADE,
    from_status      text,
    to_status        text NOT NULL,
    comment          text,
    attachment_name  text,
    attachment_path  text,
    at               timestamptz NOT NULL DEFAULT now(),
    by_user          uuid REFERENCES core.users(id),
    by_name          text
);

-- ---------- audit: журнал событий и ИБ (в целевой архитектуре — ClickHouse) ----------
CREATE TABLE audit.events (
    id           bigserial PRIMARY KEY,
    at           timestamptz NOT NULL DEFAULT now(),
    user_id      uuid,
    user_name    text,
    org_code     text,
    action       text NOT NULL,
    object_type  text,
    object_id    text,
    outcome      text NOT NULL DEFAULT 'success',
    details      jsonb,
    ip           text
);
CREATE INDEX audit_events_at ON audit.events (at DESC);
