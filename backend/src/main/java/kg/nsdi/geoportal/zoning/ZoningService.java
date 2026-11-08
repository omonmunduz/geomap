package kg.nsdi.geoportal.zoning;

import kg.nsdi.geoportal.audit.AuditService;
import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.catalog.Layer;
import kg.nsdi.geoportal.catalog.LayerRepository;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.common.Json;
import kg.nsdi.geoportal.common.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Якорный сервис «Цифровое территориальное управление» (ЧТЗ п. 3.7.1, БП-4):
 * FR-TER-01 совмещение слоёв, FR-TER-02 пространственное пересечение, FR-TER-03 реестр расхождений,
 * FR-TER-04 подсветка и фильтрация, FR-TER-05 отчёт по территории.
 */
@Service
public class ZoningService {
    public static final String ZONING_LAYER = "urban_zones";
    public static final String DEV_LAYER = "current_development";
    /** Доля площади объекта, начиная с которой второй вид зоны считается существенным пересечением. */
    static final double CROSS_SHARE = 0.10;
    static final double NO_ZONE_SHARE = 0.05;

    public static final Map<String, String> TYPE_LABELS = Map.of(
            "USE_NOT_ALLOWED", "Использование не соответствует зоне",
            "NO_ZONE", "Объект вне утверждённого зонирования",
            "CROSSES_ZONES", "Объект пересекает границу зон");
    public static final Map<String, String> STATUS_LABELS = new LinkedHashMap<>();
    static {
        STATUS_LABELS.put("new", "Новое");
        STATUS_LABELS.put("in_review", "На рассмотрении");
        STATUS_LABELS.put("confirmed", "Нарушение подтверждено");
        STATUS_LABELS.put("resolved", "Устранено");
        STATUS_LABELS.put("dismissed", "Отклонено (не нарушение)");
    }

    private final JdbcTemplate db;
    private final LayerRepository layers;
    private final AuditService audit;
    private final Json json;

    public ZoningService(JdbcTemplate db, LayerRepository layers, AuditService audit, Json json) {
        this.db = db; this.layers = layers; this.audit = audit; this.json = json;
    }

    public Map<String, Object> config() {
        Layer z = layers.byCode(ZONING_LAYER), d = layers.byCode(DEV_LAYER);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zoningLayer", Map.of("id", z.id(), "title", z.titleRu(), "version", String.valueOf(z.publishedVersion())));
        m.put("devLayer", Map.of("id", d.id(), "title", d.titleRu(), "version", String.valueOf(d.publishedVersion())));
        m.put("zoneTypes", enumOptions(z, "zone_type"));
        m.put("useTypes", enumOptions(d, "use_type"));
        m.put("rules", db.queryForList("SELECT zone_type, use_type, allowed FROM zoning.rules").stream().map(Rows::camel).toList());
        m.put("typeLabels", TYPE_LABELS);
        m.put("statusLabels", STATUS_LABELS);
        return m;
    }

    private List<Object> enumOptions(Layer l, String attr) {
        return layers.attributes(l.id()).stream().filter(a -> a.name().equals(attr)).findFirst()
                .map(a -> json.list(a.optionsJson())).orElse(List.of());
    }

    Map<String, String> labels(Layer l, String attr) {
        Map<String, String> m = new HashMap<>();
        for (Object o : enumOptions(l, attr)) if (o instanceof Map<?, ?> opt) m.put(String.valueOf(opt.get("value")), String.valueOf(opt.get("label")));
        return m;
    }

    /** Территории для выбора: районы и области (базовый слой АТД). */
    public List<Map<String, Object>> territories() {
        return db.queryForList("""
            SELECT f.id, f.title, l.code AS level,
                   ARRAY[ST_XMin(f.geom), ST_YMin(f.geom), ST_XMax(f.geom), ST_YMax(f.geom)] AS bbox
            FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id AND f.version=l.published_version
            WHERE l.code IN ('atd_oblasts','atd_districts') AND NOT f.deleted
            ORDER BY l.code DESC, f.title""").stream().map(r -> {
                var m = Rows.camel(r);
                try { m.put("bbox", Arrays.stream((Object[]) ((java.sql.Array) r.get("bbox")).getArray()).map(o -> ((Number) o).doubleValue()).toList()); }
                catch (Exception e) { m.put("bbox", null); }
                return m;
            }).toList();
    }

    /** Запуск проверки соответствия текущего освоения утверждённому зонированию. */
    @Transactional
    public Map<String, Object> runCheck(UUID territoryId, CurrentUser u) {
        Layer z = layers.byCode(ZONING_LAYER), d = layers.byCode(DEV_LAYER);
        if (z.publishedVersion() == null) throw ApiException.conflict("Слой зонирования не опубликован");
        if (d.publishedVersion() == null) throw ApiException.conflict("Слой текущего освоения не опубликован");

        String territoryName;
        String terrGeomSql;
        Object[] terrArgs;
        if (territoryId != null) {
            territoryName = db.query("SELECT title FROM spatial.features WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, territoryId);
            if (territoryName == null) throw ApiException.notFound("Территория");
            terrGeomSql = "(SELECT geom FROM spatial.features WHERE id=?)";
            terrArgs = new Object[]{territoryId};
        } else {
            territoryName = "Вся территория слоя зонирования";
            terrGeomSql = "(SELECT ST_Envelope(ST_Collect(geom)) FROM spatial.features WHERE layer_id=? AND version=? AND NOT deleted)";
            terrArgs = new Object[]{z.id(), z.publishedVersion()};
        }

        UUID checkId = db.queryForObject("INSERT INTO zoning.checks(territory_id,territory_name,zoning_layer,zoning_version,dev_layer,dev_version,run_by) " +
                "VALUES (?,?,?,?,?,?,?) RETURNING id", UUID.class, territoryId, territoryName, z.id(), z.publishedVersion(), d.id(), d.publishedVersion(), u.id());

        // объекты освоения в пределах территории
        List<Object> args = new ArrayList<>(Arrays.asList(terrArgs));
        args.addAll(List.of(d.id(), d.publishedVersion()));
        List<Map<String, Object>> devs = db.queryForList("""
            WITH terr AS (SELECT %s AS g)
            SELECT f.id, f.title, f.attrs->>'use_type' AS use_type, ST_Area(f.geom::geography) AS area
            FROM spatial.features f, terr
            WHERE f.layer_id=? AND f.version=? AND NOT f.deleted AND ST_Intersects(f.geom, terr.g)""".formatted(terrGeomSql), args.toArray());

        // пересечения с зонами
        Map<UUID, List<Map<String, Object>>> overlaps = new HashMap<>();
        if (!devs.isEmpty()) {
            List<Object> a2 = new ArrayList<>(Arrays.asList(terrArgs));
            a2.addAll(List.of(d.id(), d.publishedVersion(), z.id(), z.publishedVersion()));
            db.queryForList("""
                WITH terr AS (SELECT %s AS g),
                     dev AS (SELECT f.id, f.geom FROM spatial.features f, terr
                             WHERE f.layer_id=? AND f.version=? AND NOT f.deleted AND ST_Intersects(f.geom, terr.g))
                SELECT dev.id AS dev_id, zf.id AS zone_id, zf.attrs->>'zone_type' AS zone_type, zf.title AS zone_title,
                       ST_Area(ST_Intersection(dev.geom, zf.geom)::geography) AS a
                FROM dev JOIN spatial.features zf ON zf.layer_id=? AND zf.version=? AND NOT zf.deleted AND ST_Intersects(dev.geom, zf.geom)
                """.formatted(terrGeomSql), a2.toArray())
              .forEach(r -> overlaps.computeIfAbsent((UUID) r.get("dev_id"), k -> new ArrayList<>()).add(r));
        }
        Map<String, String> zl = labels(z, "zone_type"), ul = labels(d, "use_type");
        Map<String, Boolean> rules = new HashMap<>();
        db.queryForList("SELECT zone_type, use_type, allowed FROM zoning.rules")
          .forEach(r -> rules.put(r.get("zone_type") + "|" + r.get("use_type"), (Boolean) r.get("allowed")));

        // предыдущие расхождения по этой территории помечаются как «не обнаружено», затем upsert восстанавливает найденные
        if (territoryId != null) db.update("UPDATE zoning.discrepancies SET still_present=false WHERE territory_id=?", territoryId);
        else db.update("UPDATE zoning.discrepancies SET still_present=false WHERE territory_id IS NULL");

        Map<String, Integer> byType = new TreeMap<>();
        int created = 0;
        for (var dv : devs) {
            UUID devId = (UUID) dv.get("id");
            double area = ((Number) dv.get("area")).doubleValue();
            String use = (String) dv.get("use_type");
            List<Map<String, Object>> ov = new ArrayList<>(overlaps.getOrDefault(devId, List.of()));
            ov.sort((x, y) -> Double.compare(((Number) y.get("a")).doubleValue(), ((Number) x.get("a")).doubleValue()));
            double covered = ov.stream().mapToDouble(o -> ((Number) o.get("a")).doubleValue()).sum();

            if (ov.isEmpty() || area <= 0 || covered / area < NO_ZONE_SHARE) {
                created += upsert(checkId, territoryId, territoryName, dv, null, "NO_ZONE", use, null,
                        "Объект не попадает ни в одну утверждённую функциональную зону", z.ownerOrgId());
                byType.merge("NO_ZONE", 1, Integer::sum);
                continue;
            }
            Map<String, Object> main = ov.get(0);
            String zt = (String) main.get("zone_type");
            Set<String> otherTypes = new LinkedHashSet<>();
            for (var o : ov.subList(1, ov.size()))
                if (((Number) o.get("a")).doubleValue() / area >= CROSS_SHARE && !Objects.equals(o.get("zone_type"), zt))
                    otherTypes.add((String) o.get("zone_type"));
            if (!otherTypes.isEmpty()) {
                created += upsert(checkId, territoryId, territoryName, dv, (UUID) main.get("zone_id"), "CROSSES_ZONES", use, zt,
                        "Объект расположен в нескольких зонах: " + zl.getOrDefault(zt, zt) + ", " +
                                String.join(", ", otherTypes.stream().map(t -> zl.getOrDefault(t, t)).toList()), z.ownerOrgId());
                byType.merge("CROSSES_ZONES", 1, Integer::sum);
            }
            Boolean allowed = rules.get(zt + "|" + use);
            if (use == null || allowed == null || !allowed) {
                String det = use == null ? "Не указан вид фактического использования объекта"
                        : "Вид использования «" + ul.getOrDefault(use, use) + "» не разрешён в зоне «" + zl.getOrDefault(zt, zt) + "» (" + main.get("zone_title") + ")";
                created += upsert(checkId, territoryId, territoryName, dv, (UUID) main.get("zone_id"), "USE_NOT_ALLOWED", use, zt, det, z.ownerOrgId());
                byType.merge("USE_NOT_ALLOWED", 1, Integer::sum);
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("objectsChecked", devs.size());
        stats.put("discrepancies", byType.values().stream().mapToInt(i -> i).sum());
        stats.put("byType", byType);
        stats.put("newDiscrepancies", created);
        db.update("UPDATE zoning.checks SET stats=?::jsonb WHERE id=?", json.write(stats), checkId);
        audit.log(u, "zoning_check", "territory", territoryId == null ? "all" : territoryId.toString(), stats);
        Map<String, Object> out = new LinkedHashMap<>(stats);
        out.put("checkId", checkId);
        out.put("territoryName", territoryName);
        return out;
    }

    /** @return 1 если расхождение новое */
    private int upsert(UUID checkId, UUID territoryId, String territoryName, Map<String, Object> dev, UUID zoneId, String type,
                       String use, String zoneType, String details, UUID orgId) {
        UUID devId = (UUID) dev.get("id");
        String fp = devId + ":" + type;
        UUID existing = db.query("SELECT id FROM zoning.discrepancies WHERE fingerprint=?", rs -> rs.next() ? (UUID) rs.getObject(1) : null, fp);
        if (existing != null) {
            db.update("""
                UPDATE zoning.discrepancies SET last_check_id=?, still_present=true, zone_feature_id=?, zone_type=?, use_type=?, details=?,
                       territory_id=coalesce(?, territory_id), territory_name=coalesce(?, territory_name),
                       geom=(SELECT geom FROM spatial.features WHERE id=?), updated_at=now() WHERE id=?""",
                checkId, zoneId, zoneType, use, details, territoryId, territoryId == null ? null : territoryName, devId, existing);
            return 0;
        }
        UUID id = db.queryForObject("""
            INSERT INTO zoning.discrepancies(fingerprint,first_check_id,last_check_id,territory_id,territory_name,dev_feature_id,zone_feature_id,
                   type,use_type,zone_type,object_title,details,geom,responsible_org_id)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,geom,? FROM spatial.features WHERE id=? RETURNING id""", UUID.class,
            fp, checkId, checkId, territoryId, territoryName, devId, zoneId, type, use, zoneType, dev.get("title"), details, orgId, devId);
        db.update("INSERT INTO zoning.discrepancy_history(discrepancy_id,from_status,to_status,comment,by_name) VALUES (?,NULL,'new',?,?)",
                id, "Выявлено автоматической проверкой", "Система");
        return 1;
    }

    public record Filter(UUID territoryId, String type, String status, Boolean present, LocalDate from, LocalDate to) {}

    public List<Map<String, Object>> list(Filter f) {
        StringBuilder sql = new StringBuilder("""
            SELECT d.id, d.type, d.status, d.object_title, d.use_type, d.zone_type, d.details, d.territory_id, d.territory_name,
                   d.still_present, d.created_at, d.updated_at, o.short_name AS responsible_org, d.dev_feature_id,
                   ST_AsGeoJSON(d.geom, 6) AS geometry, ST_X(ST_PointOnSurface(d.geom)) AS lon, ST_Y(ST_PointOnSurface(d.geom)) AS lat
            FROM zoning.discrepancies d LEFT JOIN core.organizations o ON o.id=d.responsible_org_id WHERE true""");
        List<Object> args = new ArrayList<>();
        if (f.territoryId() != null) { sql.append(" AND d.territory_id=?"); args.add(f.territoryId()); }
        if (f.type() != null && !f.type().isBlank()) { sql.append(" AND d.type=?"); args.add(f.type()); }
        if (f.status() != null && !f.status().isBlank()) { sql.append(" AND d.status=?"); args.add(f.status()); }
        if (f.present() != null) { sql.append(" AND d.still_present=?"); args.add(f.present()); }
        if (f.from() != null) { sql.append(" AND d.created_at >= ?::date"); args.add(f.from().toString()); }
        if (f.to() != null) { sql.append(" AND d.created_at < (?::date + 1)"); args.add(f.to().toString()); }
        sql.append(" ORDER BY d.created_at DESC, d.object_title");
        return db.queryForList(sql.toString(), args.toArray()).stream().map(r -> {
            var m = Rows.camel(r);
            m.put("geometry", json.tree((String) r.get("geometry")));
            m.put("typeLabel", TYPE_LABELS.get((String) r.get("type")));
            m.put("statusLabel", STATUS_LABELS.get((String) r.get("status")));
            return m;
        }).toList();
    }

    public Map<String, Object> get(UUID id) {
        List<Map<String, Object>> l = list(new Filter(null, null, null, null, null, null)).stream()
                .filter(m -> id.equals(m.get("id"))).toList();
        if (l.isEmpty()) throw ApiException.notFound("Расхождение");
        Map<String, Object> m = new LinkedHashMap<>(l.get(0));
        m.put("history", db.queryForList("""
            SELECT id, from_status, to_status, comment, attachment_name, at, by_name FROM zoning.discrepancy_history
            WHERE discrepancy_id=? ORDER BY at DESC, id DESC""", id).stream().map(Rows::camel).toList());
        Map<String, Object> ids = db.queryForMap("SELECT dev_feature_id, zone_feature_id FROM zoning.discrepancies WHERE id=?", id);
        m.put("devAttrs", labeled(layers.byCode(DEV_LAYER), attrs((UUID) ids.get("dev_feature_id"))));
        m.put("zoneAttrs", ids.get("zone_feature_id") == null ? null : labeled(layers.byCode(ZONING_LAYER), attrs((UUID) ids.get("zone_feature_id"))));
        m.put("zoneGeometry", ids.get("zone_feature_id") == null ? null : json.tree(db.queryForObject(
                "SELECT ST_AsGeoJSON(geom,6) FROM spatial.features WHERE id=?", String.class, ids.get("zone_feature_id"))));
        return m;
    }

    /** Атрибуты объекта с подписями из атрибутной схемы слоя (значения enum — по справочнику). */
    private List<Map<String, Object>> labeled(Layer l, Map<String, Object> attrs) {
        if (attrs == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (var a : layers.attributes(l.id())) {
            used.add(a.name());
            Object v = attrs.get(a.name());
            if (v == null) continue;
            if ("enum".equals(a.type())) v = labels(l, a.name()).getOrDefault(String.valueOf(v), String.valueOf(v));
            out.add(Map.of("label", a.labelRu(), "value", v));
        }
        attrs.forEach((k, v) -> { if (!used.contains(k) && v != null) out.add(Map.of("label", k, "value", v)); });
        return out;
    }

    private Map<String, Object> attrs(UUID featureId) {
        String s = db.query("SELECT attrs::text FROM spatial.features WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, featureId);
        return s == null ? null : json.map(s);
    }

    @Transactional
    public Map<String, Object> changeStatus(UUID id, String status, String comment, String attachmentName, String attachmentPath, CurrentUser u) {
        if (!STATUS_LABELS.containsKey(status)) throw ApiException.bad("Неизвестный статус");
        String from = db.query("SELECT status FROM zoning.discrepancies WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, id);
        if (from == null) throw ApiException.notFound("Расхождение");
        if (from.equals(status) && (comment == null || comment.isBlank()) && attachmentName == null)
            throw ApiException.bad("Статус не изменён");
        if (List.of("resolved", "dismissed").contains(status) && (comment == null || comment.isBlank()))
            throw ApiException.bad("Для статуса «" + STATUS_LABELS.get(status) + "» укажите обоснование в комментарии");
        db.update("UPDATE zoning.discrepancies SET status=?, updated_at=now() WHERE id=?", status, id);
        db.update("INSERT INTO zoning.discrepancy_history(discrepancy_id,from_status,to_status,comment,attachment_name,attachment_path,by_user,by_name) " +
                  "VALUES (?,?,?,?,?,?,?,?)", id, from, status, comment, attachmentName, attachmentPath, u.id(), u.name());
        audit.log(u, "discrepancy_status", "discrepancy", id, Map.of("from", from, "to", status));
        return get(id);
    }

    public Map<String, Object> attachment(UUID discrepancyId, long historyId) {
        return db.queryForMap("SELECT attachment_name, attachment_path FROM zoning.discrepancy_history WHERE id=? AND discrepancy_id=?", historyId, discrepancyId);
    }

    public List<Map<String, Object>> checks() {
        return db.queryForList("""
            SELECT c.id, c.territory_name, c.run_at, c.stats::text AS stats, u.display_name AS run_by
            FROM zoning.checks c LEFT JOIN core.users u ON u.id=c.run_by ORDER BY c.run_at DESC LIMIT 20""")
            .stream().map(r -> { var m = Rows.camel(r); m.put("stats", json.tree((String) r.get("stats"))); return m; }).toList();
    }
}
