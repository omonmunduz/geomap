-- Copy-on-write версии слоя: правка опубликованного слоя создаёт черновик-копию.
-- origin_id — «сквозной» идентификатор объекта между версиями (id объекта в версии, где он появился);
-- позволяет найти копию объекта в черновике по id из опубликованной версии и не плодить расхождения
-- зонирования после повторной публикации.
ALTER TABLE spatial.features ADD COLUMN origin_id uuid;
UPDATE spatial.features SET origin_id = id;

-- новые объекты (импорт, редактор) начинают собственную «линию»; копии в черновик передают origin_id явно
CREATE FUNCTION spatial.features_origin_default() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.origin_id := coalesce(NEW.origin_id, NEW.id);
    RETURN NEW;
END $$;
CREATE TRIGGER features_origin_default BEFORE INSERT ON spatial.features
    FOR EACH ROW EXECUTE FUNCTION spatial.features_origin_default();

ALTER TABLE spatial.features ALTER COLUMN origin_id SET NOT NULL;
CREATE INDEX features_origin ON spatial.features (layer_id, version, origin_id);

-- История изменений объекта хранит версию слоя, в которую внесена правка.
ALTER TABLE spatial.feature_history ADD COLUMN version int;
