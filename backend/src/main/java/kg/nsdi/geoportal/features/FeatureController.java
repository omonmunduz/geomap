package kg.nsdi.geoportal.features;

import kg.nsdi.geoportal.audit.AuditService;
import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.catalog.Layer;
import kg.nsdi.geoportal.catalog.LayerRepository;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.common.Json;
import kg.nsdi.geoportal.common.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Карточка объекта и редактор геометрии (ЧТЗ п. 4.4.3, 4.4.6; FR-GIS-03).
 * Правки применяются к рабочей (draft) версии слоя — при необходимости это копия опубликованной (copy-on-write);
 * для оперативных слоёв — публикуются немедленно (п. 4.4.10).
 */
@RestController
public class FeatureController {
    private final JdbcTemplate db;
    private final LayerRepository layers;
    private final AuditService audit;
    private final Json json;

    public FeatureController(JdbcTemplate db, LayerRepository layers, AuditService audit, Json json) {
        this.db = db; this.layers = layers; this.audit = audit; this.json = json;
    }

    @GetMapping("/api/features/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT f.id, f.layer_id, f.version, f.title, f.attrs::text AS attrs, f.deleted, f.created_at, f.updated_at,
                   ST_AsGeoJSON(f.geom, 6) AS geometry, GeometryType(f.geom) AS geometry_type,
                   ST_X(ST_PointOnSurface(f.geom)) AS lon, ST_Y(ST_PointOnSurface(f.geom)) AS lat,
                   ARRAY[ST_XMin(f.geom), ST_YMin(f.geom), ST_XMax(f.geom), ST_YMax(f.geom)] AS bbox,
                   CASE WHEN GeometryType(f.geom) LIKE '%POLYGON' THEN ST_Area(f.geom::geography) END AS area_m2,
                   CASE WHEN GeometryType(f.geom) LIKE '%LINESTRING' THEN ST_Length(f.geom::geography) END AS length_m,
                   (SELECT a.title FROM spatial.features a JOIN catalog.layers al ON al.id=a.layer_id AND al.code='atd_districts'
                     AND a.version=al.published_version AND NOT a.deleted WHERE ST_Intersects(a.geom, ST_PointOnSurface(f.geom)) LIMIT 1) AS district,
                   (SELECT a.title FROM spatial.features a JOIN catalog.layers al ON al.id=a.layer_id AND al.code='atd_oblasts'
                     AND a.version=al.published_version AND NOT a.deleted WHERE ST_Intersects(a.geom, ST_PointOnSurface(f.geom)) LIMIT 1) AS region
            FROM spatial.features f WHERE f.id=?""", id);
        if (rows.isEmpty()) throw ApiException.notFound("Объект");
        Map<String, Object> r = rows.get(0);
        Layer l = layers.get((UUID) r.get("layer_id"));
        if (Boolean.TRUE.equals(r.get("deleted")) || !l.canViewVersion(u, (Integer) r.get("version")))
            throw ApiException.forbidden("Объект недоступен");

        Map<String, Object> attrs = json.map((String) r.get("attrs"));
        List<Map<String, Object>> shown = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (var a : layers.attributes(l.id())) {
            used.add(a.name());
            shown.add(attr(a.name(), a.labelRu(), labelFor(a, attrs.get(a.name())), a.type(), a.required()));
        }
        attrs.forEach((k, v) -> { if (!used.contains(k)) shown.add(attr(k, k, v, "text", false)); });

        Map<String, Object> m = Rows.camel(r);
        m.remove("attrs");
        m.put("attributes", shown);
        m.put("rawAttrs", attrs);
        m.put("geometry", json.tree((String) r.get("geometry")));
        m.put("bbox", toList(r.get("bbox")));
        m.put("layerTitle", l.titleRu());
        m.put("layerCode", l.code());
        m.put("canEdit", l.canEdit(u));
        return m;
    }

    private static Map<String, Object> attr(String name, String label, Object value, String type, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("label", label); m.put("value", value); m.put("type", type); m.put("required", required);
        return m;
    }

    private Object labelFor(LayerRepository.Attr a, Object value) {
        if (value == null || !"enum".equals(a.type()) || a.optionsJson() == null) return value;
        for (Object o : json.list(a.optionsJson())) {
            if (o instanceof Map<?, ?> opt && String.valueOf(value).equals(String.valueOf(opt.get("value")))) return opt.get("label");
        }
        return value;
    }

    private static List<Double> toList(Object arr) {
        try {
            Object[] a = (Object[]) ((java.sql.Array) arr).getArray();
            return Arrays.stream(a).map(o -> ((Number) o).doubleValue()).toList();
        } catch (Exception e) { return null; }
    }

    @GetMapping("/api/features/{id}/history")
    public List<Map<String, Object>> history(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.require();
        UUID layerId = db.queryForObject("SELECT layer_id FROM spatial.features WHERE id=?", UUID.class, id);
        if (!layers.get(layerId).canEdit(u)) throw ApiException.forbidden("Нет доступа к истории объекта");
        // история объекта во всех версиях слоя (копии объекта в черновиках связаны через origin_id)
        return db.queryForList("""
            SELECT h.id, h.op, h.version, h.at, u.display_name AS author, h.attrs::text AS attrs
            FROM spatial.feature_history h LEFT JOIN core.users u ON u.id=h.by_user
            WHERE h.feature_id IN (SELECT c.id FROM spatial.features f JOIN spatial.features c ON c.layer_id=f.layer_id AND c.origin_id=f.origin_id
                                   WHERE f.id=?)
            ORDER BY h.at DESC""", id)
            .stream().map(r -> { var m = Rows.camel(r); m.put("attrs", json.tree((String) r.get("attrs"))); return m; }).toList();
    }

    public record FeatureInput(Map<String, Object> geometry, Map<String, Object> attrs) {}

    @PostMapping("/api/layers/{layerId}/features")
    @Transactional
    public Map<String, Object> create(@PathVariable UUID layerId, @RequestBody FeatureInput in) {
        CurrentUser u = CurrentUser.require();
        Layer l = lockLayer(layerId);
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        if (in.geometry() == null) throw ApiException.bad("Не задана геометрия");
        Map<String, Object> attrs = cleanAttrs(l, in.attrs(), true);
        String geo = json.write(in.geometry());
        validateGeometry(l, geo);

        int version = editableVersion(l, u);
        UUID id = db.queryForObject("""
            INSERT INTO spatial.features(layer_id,version,geom,attrs,title,created_by,updated_by)
            VALUES (?,?,ST_SetSRID(ST_GeomFromGeoJSON(?),4326),?::jsonb,?,?,?) RETURNING id""", UUID.class,
            layerId, version, geo, json.write(attrs), titleOf(l, attrs), u.id(), u.id());
        history(id, layerId, "create", u.id(), version);
        afterEdit(l, version);
        audit.log(u, "feature_create", "feature", id, Map.of("layer", l.code()));
        return get(id);
    }

    @PutMapping("/api/features/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody FeatureInput in) {
        CurrentUser u = CurrentUser.require();
        Layer l = lockLayer(db.queryForObject("SELECT layer_id FROM spatial.features WHERE id=?", UUID.class, id));
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        int version = editableVersion(l, u);
        id = inVersion(id, version);
        if (in.geometry() != null) {
            String geo = json.write(in.geometry());
            validateGeometry(l, geo);
            db.update("UPDATE spatial.features SET geom=ST_SetSRID(ST_GeomFromGeoJSON(?),4326) WHERE id=?", geo, id);
        }
        if (in.attrs() != null) {
            Map<String, Object> attrs = cleanAttrs(l, in.attrs(), true);
            db.update("UPDATE spatial.features SET attrs=?::jsonb, title=? WHERE id=?", json.write(attrs), titleOf(l, attrs), id);
        }
        db.update("UPDATE spatial.features SET updated_at=now(), updated_by=? WHERE id=?", u.id(), id);
        history(id, l.id(), "update", u.id(), version);
        afterEdit(l, version);
        audit.log(u, "feature_update", "feature", id, Map.of("layer", l.code()));
        return get(id);
    }

    @DeleteMapping("/api/features/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.require();
        Layer l = lockLayer(db.queryForObject("SELECT layer_id FROM spatial.features WHERE id=?", UUID.class, id));
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        int version = editableVersion(l, u);
        id = inVersion(id, version);
        db.update("UPDATE spatial.features SET deleted=true, updated_at=now(), updated_by=? WHERE id=?", u.id(), id);
        history(id, l.id(), "delete", u.id(), version);
        afterEdit(l, version);
        audit.log(u, "feature_delete", "feature", id, Map.of("layer", l.code()));
        return Map.of("deleted", true);
    }

    /** Блокировка строки слоя на время правки: два редактора не создадут два черновика. */
    private Layer lockLayer(UUID layerId) {
        db.query("SELECT id FROM catalog.layers WHERE id=? FOR UPDATE", rs -> null, layerId);
        return layers.get(layerId);
    }

    /**
     * Версия, в которую пишутся правки. Опубликованная версия неоперативного слоя не меняется:
     * при первой правке создаётся черновик — копия опубликованных объектов (copy-on-write), он виден только редакторам
     * до «Опубликовать». Оперативные слои (п. 4.4.10) по-прежнему правятся и публикуются сразу.
     */
    private int editableVersion(Layer l, CurrentUser u) {
        Integer draft = l.draftVersion();
        if (draft != null && (l.operational() || !draft.equals(l.publishedVersion()))) return draft;
        int version = layers.nextVersion(l.id());
        if (draft == null) {
            db.update("INSERT INTO catalog.layer_versions(layer_id,version,created_by,source,source_srid,feature_count,note) VALUES (?,?,?,?,4326,0,?)",
                    l.id(), version, u.id(), "редактор геометрии", "Создано вручную на карте");
        } else {
            db.update("INSERT INTO catalog.layer_versions(layer_id,version,created_by,source,source_srid,feature_count,note) VALUES (?,?,?,?,4326,0,?)",
                    l.id(), version, u.id(), "редактор геометрии", "Черновик на основе v" + draft);
            int n = db.update("""
                INSERT INTO spatial.features(layer_id,version,geom,attrs,title,created_at,created_by,updated_at,updated_by,origin_id)
                SELECT layer_id, ?, geom, attrs, title, created_at, created_by, updated_at, updated_by, origin_id
                FROM spatial.features WHERE layer_id=? AND version=? AND NOT deleted""", version, l.id(), draft);
            db.update("UPDATE catalog.layer_versions SET feature_count=? WHERE layer_id=? AND version=?", n, l.id(), version);
        }
        db.update("UPDATE catalog.layers SET draft_version=? WHERE id=?", version, l.id());
        return version;
    }

    /** Объект в заданной версии слоя: сам объект или его копия в черновике (по origin_id). */
    private UUID inVersion(UUID id, int version) {
        List<UUID> r = db.queryForList("""
            SELECT c.id FROM spatial.features f JOIN spatial.features c ON c.layer_id=f.layer_id AND c.origin_id=f.origin_id
            WHERE f.id=? AND c.version=? AND NOT c.deleted""", UUID.class, id, version);
        if (r.isEmpty()) throw ApiException.notFound("Объект в рабочей версии слоя");
        return r.get(0);
    }

    private void history(UUID featureId, UUID layerId, String op, UUID userId, int version) {
        db.update("INSERT INTO spatial.feature_history(feature_id,layer_id,op,geom,attrs,by_user,version) " +
                  "SELECT id, layer_id, ?, geom, attrs, ?, ? FROM spatial.features WHERE id=?", op, userId, version, featureId);
        db.update("UPDATE catalog.layer_versions v SET feature_count=(SELECT count(*) FROM spatial.features f " +
                  "WHERE f.layer_id=v.layer_id AND f.version=v.version AND NOT f.deleted) " +
                  "WHERE v.layer_id=? AND v.version=(SELECT version FROM spatial.features WHERE id=?)", layerId, featureId);
    }

    private void afterEdit(Layer l, int version) {
        if (l.operational()) db.update("UPDATE catalog.layers SET published_version=? WHERE id=?", version, l.id());
        layers.touch(l.id());
    }

    /** Проверка геометрии при сохранении: валидность, тип, попадание в территорию КР. */
    private void validateGeometry(Layer l, String geoJson) {
        Map<String, Object> r;
        try {
            r = db.queryForMap("""
                SELECT ST_IsValid(g) AS valid, ST_IsValidReason(g) AS reason, GeometryType(g) AS gtype,
                       ST_Intersects(g, ST_MakeEnvelope(69.0, 39.0, 80.5, 43.5, 4326)) AS in_kg
                FROM (SELECT ST_SetSRID(ST_GeomFromGeoJSON(?),4326) AS g) s""", geoJson);
        } catch (Exception e) { throw ApiException.bad("Некорректная геометрия GeoJSON"); }
        if (!Boolean.TRUE.equals(r.get("valid"))) throw ApiException.bad("Невалидная геометрия: " + r.get("reason"));
        if (!Boolean.TRUE.equals(r.get("in_kg"))) throw ApiException.bad("Объект вне территории Кыргызской Республики");
        String t = String.valueOf(r.get("gtype"));
        boolean ok = switch (l.geomType()) {
            case "point" -> t.endsWith("POINT");
            case "line" -> t.endsWith("LINESTRING");
            case "polygon" -> t.endsWith("POLYGON");
            default -> true;
        };
        if (!ok) throw ApiException.bad("Тип геометрии " + t + " не соответствует типу слоя (" + l.geomType() + ")");
    }

    /** Приведение типов и проверка обязательных полей по атрибутной схеме слоя. */
    private Map<String, Object> cleanAttrs(Layer l, Map<String, Object> in, boolean checkRequired) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null) in.forEach((k, v) -> { if (v != null && !(v instanceof String s && s.isBlank())) out.put(k, v); });
        for (var a : layers.attributes(l.id())) {
            Object v = out.get(a.name());
            if (v == null) {
                if (checkRequired && a.required()) throw ApiException.bad("Не заполнено обязательное поле «" + a.labelRu() + "»");
                continue;
            }
            if ("number".equals(a.type()) && !(v instanceof Number)) {
                try { out.put(a.name(), Double.parseDouble(v.toString().replace(',', '.').trim())); }
                catch (NumberFormatException e) { throw ApiException.bad("Поле «" + a.labelRu() + "» должно быть числом"); }
            }
        }
        return out;
    }

    private static String titleOf(Layer l, Map<String, Object> attrs) {
        if (l.titleAttr() != null && attrs.get(l.titleAttr()) != null) return attrs.get(l.titleAttr()).toString();
        for (String k : List.of("name", "name_ru", "title", "наименование")) if (attrs.get(k) != null) return attrs.get(k).toString();
        return l.titleRu();
    }
}
