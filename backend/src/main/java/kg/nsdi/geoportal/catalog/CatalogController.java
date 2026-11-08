package kg.nsdi.geoportal.catalog;

import kg.nsdi.geoportal.audit.AuditService;
import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.common.Json;
import kg.nsdi.geoportal.common.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Каталог слоёв: дерево тем (публичное), «Мои слои», создание слоя, публикация, версии и откат
 * (ЧТЗ п. 4.4.2, 5.1.1.2, 5.1.1.4).
 */
@RestController
public class CatalogController {
    private final JdbcTemplate db;
    private final LayerRepository layers;
    private final AuditService audit;
    private final Json json;

    public CatalogController(JdbcTemplate db, LayerRepository layers, AuditService audit, Json json) {
        this.db = db; this.layers = layers; this.audit = audit; this.json = json;
    }

    private static final String LAYER_SELECT = """
        SELECT l.id, l.code, l.appendix_no, l.title_ru, l.title_ky, l.description, l.kind, l.geom_type, l.access_level,
               l.is_operational, l.archived, l.style::text AS style, l.draft_version, l.published_version, l.updated_at,
               l.owner_org_id, o.short_name AS owner, o.code AS owner_code, t.code AS theme_code, t.title_ru AS theme_title,
               (SELECT count(*) FROM spatial.features f WHERE f.layer_id=l.id AND f.version=l.published_version AND NOT f.deleted) AS published_count,
               (SELECT count(*) FROM spatial.features f WHERE f.layer_id=l.id AND f.version=l.draft_version AND NOT f.deleted) AS draft_count
        FROM catalog.layers l JOIN core.organizations o ON o.id=l.owner_org_id JOIN catalog.themes t ON t.id=l.theme_id
        """;

    private Map<String, Object> layerDto(Map<String, Object> r, CurrentUser u) {
        Layer l = layers.get((UUID) r.get("id"));
        Map<String, Object> m = Rows.camel(r);
        m.put("style", json.tree((String) r.get("style")));
        m.put("canEdit", l.canEdit(u));
        m.put("canView", l.canViewPublished(u));
        String status = l.archived() ? "archived" : l.publishedVersion() == null ? (l.draftVersion() == null ? "empty" : "draft")
                : (Objects.equals(l.draftVersion(), l.publishedVersion()) ? "published" : "published_with_draft");
        m.put("status", status);
        return m;
    }

    /** Публичное дерево слоёв по темам. Служебные слои скрыты от анонимов и граждан. */
    @GetMapping("/api/catalog/tree")
    public List<Map<String, Object>> tree() {
        CurrentUser u = CurrentUser.get();
        List<Map<String, Object>> themes = db.queryForList("SELECT id, code, title_ru, title_ky FROM catalog.themes ORDER BY sort");
        List<Map<String, Object>> all = db.queryForList(LAYER_SELECT + " WHERE NOT l.archived ORDER BY t.sort, l.kind, l.title_ru");
        List<Map<String, Object>> out = new ArrayList<>();
        for (var t : themes) {
            List<Map<String, Object>> ls = new ArrayList<>();
            for (var r : all) {
                if (!t.get("code").equals(r.get("theme_code"))) continue;
                if ("restricted".equals(r.get("access_level")) && !CurrentUser.staff(u)) continue;
                ls.add(layerDto(r, u));
            }
            Map<String, Object> tm = Rows.camel(t);
            tm.put("layers", ls);
            out.add(tm);
        }
        return out;
    }

    @GetMapping("/api/catalog/layers/{id}")
    public Map<String, Object> layer(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        Layer l = layers.get(id);
        if (!l.canViewPublished(u) && !l.canEdit(u)) {
            if ("restricted".equals(l.accessLevel())) throw ApiException.forbidden("Слой для служебного пользования");
        }
        Map<String, Object> m = layerDto(db.queryForMap(LAYER_SELECT + " WHERE l.id=?", id), u);
        m.put("metadata", Rows.camel(db.queryForList("SELECT abstract, keywords, lineage, update_freq, contact FROM catalog.layer_metadata WHERE layer_id=?", id)
                .stream().findFirst().orElse(Map.of())));
        m.put("attributes", layers.attributes(id));
        m.put("bbox", bbox(id, l.canEdit(u) && l.draftVersion() != null ? l.draftVersion() : l.publishedVersion()));
        if (l.canEdit(u)) {
            m.put("versions", db.queryForList("""
                SELECT v.version, v.created_at, v.source, v.source_srid, v.feature_count, v.note, u.display_name AS author
                FROM catalog.layer_versions v LEFT JOIN core.users u ON u.id=v.created_by
                WHERE v.layer_id=? ORDER BY v.version DESC""", id).stream().map(Rows::camel).toList());
        }
        return m;
    }

    private List<Double> bbox(UUID layerId, Integer version) {
        if (version == null) return null;
        return db.query("""
            SELECT ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) FROM
              (SELECT ST_Extent(geom)::geometry e FROM spatial.features WHERE layer_id=? AND version=? AND NOT deleted) s WHERE e IS NOT NULL""",
            rs -> rs.next() ? List.of(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)) : null, layerId, version);
    }

    /** «Мои слои»: слои организации сотрудника (администратор видит все) + статус последнего импорта. */
    @GetMapping("/api/my/layers")
    public List<Map<String, Object>> myLayers() {
        CurrentUser u = CurrentUser.require();
        List<Map<String, Object>> rows = u.isAdmin()
                ? db.queryForList(LAYER_SELECT + " ORDER BY l.updated_at DESC")
                : db.queryForList(LAYER_SELECT + " WHERE l.owner_org_id = ? ORDER BY l.updated_at DESC", u.orgId());
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = layerDto(r, u);
            m.put("lastImport", db.queryForList("""
                SELECT id, status, progress, file_name, message, created_at FROM jobs.import_jobs
                WHERE layer_id=? ORDER BY created_at DESC LIMIT 1""", r.get("id")).stream().map(Rows::camel).findFirst().orElse(null));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/api/catalog/themes")
    public List<Map<String, Object>> themes() {
        return db.queryForList("SELECT code, title_ru, title_ky FROM catalog.themes ORDER BY sort").stream().map(Rows::camel).toList();
    }

    public record NewLayer(String titleRu, String titleKy, String geomType, String themeCode, String accessLevel, String description) {}

    /** Шаг 1 мастера импорта: «Создать новый слой». */
    @PostMapping("/api/layers")
    @Transactional
    public Map<String, Object> create(@RequestBody NewLayer req) {
        CurrentUser u = CurrentUser.require();
        if (!u.isStaff() || u.orgId() == null) throw ApiException.forbidden("Создавать слои могут сотрудники государственных органов");
        if (req.titleRu() == null || req.titleRu().isBlank()) throw ApiException.bad("Укажите наименование слоя (рус.)");
        if (!List.of("point", "line", "polygon", "mixed").contains(req.geomType())) throw ApiException.bad("Некорректный тип геометрии");
        String access = "restricted".equals(req.accessLevel()) ? "restricted" : "open";
        String code = "u_" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = db.queryForObject("""
            INSERT INTO catalog.layers(code,title_ru,title_ky,description,theme_id,kind,geom_type,access_level,owner_org_id,style)
            SELECT ?,?,?,?,t.id,'thematic',?,?,?, jsonb_build_object('color', ?) FROM catalog.themes t WHERE t.code=? RETURNING id""",
            UUID.class, code, req.titleRu().trim(), req.titleKy(), req.description(), req.geomType(), access, u.orgId(),
            randomColor(), req.themeCode());
        if (id == null) throw ApiException.bad("Тема не найдена");
        db.update("INSERT INTO catalog.layer_metadata(layer_id, abstract, update_freq, contact) VALUES (?,?,?,?)",
                id, req.description(), "не реже 1 раза в квартал", u.orgName());
        audit.log(u, "layer_create", "layer", id, Map.of("title", req.titleRu()));
        return layer(id);
    }

    private static String randomColor() {
        String[] p = {"#1971c2", "#2f9e44", "#e8590c", "#9c36b5", "#c2255c", "#0c8599", "#f08c00", "#5c940d"};
        return p[new Random().nextInt(p.length)];
    }

    @PostMapping("/api/layers/{id}/publish")
    @Transactional
    public Map<String, Object> publish(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.require();
        Layer l = layers.get(id);
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        if (l.draftVersion() == null) throw ApiException.conflict("Нет загруженных данных для публикации");
        db.update("UPDATE catalog.layers SET published_version=draft_version, archived=false, updated_at=clock_timestamp() WHERE id=?", id);
        audit.log(u, "layer_publish", "layer", id, Map.of("version", l.draftVersion()));
        return layer(id);
    }

    @PostMapping("/api/layers/{id}/unpublish")
    @Transactional
    public Map<String, Object> unpublish(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.require();
        Layer l = layers.get(id);
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        db.update("UPDATE catalog.layers SET published_version=NULL, updated_at=clock_timestamp() WHERE id=?", id);
        audit.log(u, "layer_unpublish", "layer", id, Map.of());
        return layer(id);
    }

    /** «Откатить к этой версии» (БП-2, последний шаг). */
    @PostMapping("/api/layers/{id}/rollback/{version}")
    @Transactional
    public Map<String, Object> rollback(@PathVariable UUID id, @PathVariable int version) {
        CurrentUser u = CurrentUser.require();
        Layer l = layers.get(id);
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        Integer exists = db.queryForObject("SELECT count(*) FROM catalog.layer_versions WHERE layer_id=? AND version=?", Integer.class, id, version);
        if (exists == null || exists == 0) throw ApiException.notFound("Версия");
        db.update("UPDATE catalog.layers SET draft_version=?, published_version=CASE WHEN published_version IS NULL THEN NULL ELSE ? END, " +
                  "updated_at=clock_timestamp() WHERE id=?", version, version, id);
        audit.log(u, "layer_rollback", "layer", id, Map.of("from", String.valueOf(l.publishedVersion()), "to", version));
        return layer(id);
    }

    public record LayerPatch(String titleRu, String titleKy, String accessLevel, String description, String color) {}

    @PatchMapping("/api/layers/{id}")
    @Transactional
    public Map<String, Object> patch(@PathVariable UUID id, @RequestBody LayerPatch p) {
        CurrentUser u = CurrentUser.require();
        Layer l = layers.get(id);
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        if (p.titleRu() != null && !p.titleRu().isBlank()) db.update("UPDATE catalog.layers SET title_ru=? WHERE id=?", p.titleRu().trim(), id);
        if (p.titleKy() != null) db.update("UPDATE catalog.layers SET title_ky=? WHERE id=?", p.titleKy(), id);
        if (p.description() != null) db.update("UPDATE catalog.layers SET description=? WHERE id=?", p.description(), id);
        if (p.accessLevel() != null && List.of("open", "restricted").contains(p.accessLevel()))
            db.update("UPDATE catalog.layers SET access_level=? WHERE id=?", p.accessLevel(), id);
        if (p.color() != null && p.color().matches("#[0-9a-fA-F]{6}"))
            db.update("UPDATE catalog.layers SET style = style || jsonb_build_object('color', ?) WHERE id=?", p.color(), id);
        layers.touch(id);
        audit.log(u, "layer_update", "layer", id, Map.of());
        return layer(id);
    }
}
