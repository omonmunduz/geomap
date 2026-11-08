package kg.nsdi.geoportal.imports;

import com.fasterxml.jackson.databind.JsonNode;
import kg.nsdi.geoportal.audit.AuditService;
import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.catalog.Layer;
import kg.nsdi.geoportal.catalog.LayerRepository;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.common.Json;
import kg.nsdi.geoportal.common.Rows;
import kg.nsdi.geoportal.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/**
 * БП-2 «Загрузка и публикация пространственных данных» (ЧТЗ п. 4.4.2) — мастер импорта из 6 шагов:
 * анализ файла → СК → сопоставление полей → валидация → подтверждение → фоновая запись новой версии слоя.
 */
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    /** Ограничивающий прямоугольник КР с запасом — для выявления неверно указанной СК. */
    static final String KG_ENVELOPE = "ST_MakeEnvelope(69.0, 39.0, 80.5, 43.5, 4326)";

    private final JdbcTemplate db;
    private final GdalService gdal;
    private final LayerRepository layers;
    private final AuditService audit;
    private final Json json;
    private final AppProperties props;
    private final TransactionTemplate tx;

    public ImportService(JdbcTemplate db, GdalService gdal, LayerRepository layers, AuditService audit, Json json,
                         AppProperties props, TransactionTemplate tx) {
        this.db = db; this.gdal = gdal; this.layers = layers; this.audit = audit; this.json = json; this.props = props; this.tx = tx;
    }

    // ---------------------------------------------------------------- шаг 2: загрузка и анализ файла

    public Map<String, Object> upload(UUID layerId, String fileName, InputStream content, CurrentUser u) throws IOException {
        Layer l = layers.get(layerId);
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        if ("raster".equals(l.geomType())) throw ApiException.bad("Импорт растров выполняется подсистемой ортофотопланов (вне объёма MVP)");
        String safe = Paths.get(fileName == null ? "data" : fileName).getFileName().toString().replaceAll("[^\\p{L}\\p{N}._-]", "_");
        UUID jobId = UUID.randomUUID();
        Path dir = Paths.get(props.storageDir(), "imports", jobId.toString());
        Files.createDirectories(dir);
        Path file = dir.resolve(safe);
        Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);

        JsonNode info = json.mapper().readTree(gdal.info(file));
        JsonNode lyr = info.path("layers").path(0);
        if (lyr.isMissingNode()) throw ApiException.bad("В файле не найдено ни одного слоя");
        List<Map<String, Object>> fields = new ArrayList<>();
        for (JsonNode f : lyr.path("fields")) fields.add(Map.of("name", f.path("name").asText(), "type", f.path("type").asText()));
        JsonNode gf = lyr.path("geometryFields").path(0);
        Integer srid = null; String srsName = null;
        JsonNode cs = gf.path("coordinateSystem");
        if (!cs.isMissingNode()) {
            JsonNode id = cs.path("projjson").path("id");
            if ("EPSG".equals(id.path("authority").asText())) srid = id.path("code").asInt();
            srsName = cs.path("projjson").path("name").asText(null);
            if (srid == null && srsName != null && srsName.contains("WGS 84") && !srsName.contains("UTM")) srid = 4326;
        }
        Map<String, Object> sourceInfo = new LinkedHashMap<>();
        sourceInfo.put("ogrLayer", lyr.path("name").asText());
        sourceInfo.put("layerCount", info.path("layers").size());
        sourceInfo.put("featureCount", lyr.path("featureCount").asLong());
        sourceInfo.put("geometryType", gf.path("type").asText(null));
        sourceInfo.put("fields", fields);
        sourceInfo.put("detectedSrid", srid);
        sourceInfo.put("detectedSrsName", srsName);
        sourceInfo.put("fileSize", Files.size(file));

        db.update("""
            INSERT INTO jobs.import_jobs(id,layer_id,status,file_name,file_path,ogr_layer,source_info,source_srid,created_by)
            VALUES (?,?,'uploaded',?,?,?,?::jsonb,?,?)""", jobId, layerId, safe, file.toString(), lyr.path("name").asText(),
            json.write(sourceInfo), srid, u.id());

        Map<String, Object> resp = new LinkedHashMap<>(sourceInfo);
        resp.put("jobId", jobId);
        resp.put("fileName", safe);
        resp.put("targetAttributes", layers.attributes(layerId));
        resp.put("suggestedMapping", suggestMapping(l, fields));
        resp.put("suggestedTitleField", suggestTitle(l, fields));
        return resp;
    }

    private Map<String, String> suggestMapping(Layer l, List<Map<String, Object>> fields) {
        var attrs = layers.attributes(l.id());
        Map<String, String> m = new LinkedHashMap<>();
        for (var f : fields) {
            String name = (String) f.get("name");
            String target = attrs.isEmpty() ? name : attrs.stream().map(LayerRepository.Attr::name)
                    .filter(a -> a.equalsIgnoreCase(name)).findFirst().orElse(null);
            m.put(name, target);
        }
        return m;
    }

    private String suggestTitle(Layer l, List<Map<String, Object>> fields) {
        for (var f : fields) {
            String n = ((String) f.get("name")).toLowerCase(Locale.ROOT);
            if (n.equals(String.valueOf(l.titleAttr()).toLowerCase(Locale.ROOT))) return (String) f.get("name");
        }
        for (var f : fields) {
            String n = ((String) f.get("name")).toLowerCase(Locale.ROOT);
            if (n.startsWith("name") || n.contains("наимен") || n.equals("title")) return (String) f.get("name");
        }
        return null;
    }

    // ---------------------------------------------------------------- шаги 3–5: СК, сопоставление, валидация

    public record ValidateRequest(Integer sourceSrid, Map<String, String> mapping, String titleField, boolean makeValid) {}

    public Map<String, Object> validate(UUID jobId, ValidateRequest req, CurrentUser u) {
        Map<String, Object> job = job(jobId);
        Layer l = layers.get((UUID) job.get("layer_id"));
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        if (!List.of("uploaded", "validated", "invalid").contains((String) job.get("status")))
            throw ApiException.conflict("Задание уже выполняется или завершено");
        if (req.sourceSrid() == null) throw ApiException.bad("Укажите исходную систему координат");
        Integer known = db.queryForObject("SELECT count(*) FROM spatial_ref_sys WHERE srid=?", Integer.class, req.sourceSrid());
        if (known == null || known == 0) throw ApiException.bad("Неизвестная система координат EPSG:" + req.sourceSrid());

        String table = "j_" + jobId.toString().replace("-", "");
        gdal.loadToStaging(Paths.get((String) job.get("file_path")), (String) job.get("ogr_layer"), table, req.sourceSrid());
        String t = "staging." + table;

        Set<String> columns = new HashSet<>(db.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='staging' AND table_name=?", String.class, table));
        Map<String, String> mapping = new LinkedHashMap<>();
        if (req.mapping() != null) req.mapping().forEach((src, tgt) -> {
            if (!columns.contains(src)) throw ApiException.bad("Поле источника не найдено: " + src);
            if (tgt != null && !tgt.isBlank()) {
                if (!tgt.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,62}")) throw ApiException.bad("Недопустимое имя атрибута: " + tgt);
                mapping.put(src, tgt);
            }
        });
        if (req.titleField() != null && !req.titleField().isBlank() && !columns.contains(req.titleField()))
            throw ApiException.bad("Поле наименования не найдено: " + req.titleField());

        var attrs = layers.attributes(l.id());
        List<String> global = new ArrayList<>();
        List<String> perFeature = new ArrayList<>();
        Map<String, String> reverse = new HashMap<>();
        mapping.forEach((s, tg) -> reverse.put(tg, s));
        for (var a : attrs) {
            String src = reverse.get(a.name());
            if (src == null) {
                if (a.required()) global.add("Обязательный атрибут «" + a.labelRu() + "» не сопоставлен ни с одним полем источника");
                continue;
            }
            if (a.required())
                perFeature.add("SELECT ogc_fid, 'не заполнено обязательное поле «" + sqlStr(a.labelRu()) + "»' AS msg FROM " + t +
                               " WHERE NULLIF(trim(" + q(src) + "::text),'') IS NULL");
            if ("number".equals(a.type()))
                perFeature.add("SELECT ogc_fid, 'поле «" + sqlStr(a.labelRu()) + "» должно быть числом' AS msg FROM " + t +
                               " WHERE NULLIF(trim(" + q(src) + "::text),'') IS NOT NULL AND trim(replace(" + q(src) +
                               "::text, ',', '.')) !~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'");
        }
        String typeCheck = switch (l.geomType()) {
            case "point" -> "GeometryType(geom) NOT IN ('POINT','MULTIPOINT')";
            case "line" -> "GeometryType(geom) NOT IN ('LINESTRING','MULTILINESTRING')";
            case "polygon" -> "GeometryType(geom) NOT IN ('POLYGON','MULTIPOLYGON')";
            default -> "false";
        };
        perFeature.add("SELECT ogc_fid, 'пустая геометрия' AS msg FROM " + t + " WHERE geom IS NULL OR ST_IsEmpty(geom)");
        perFeature.add("SELECT ogc_fid, 'тип геометрии ' || GeometryType(geom) || ' не соответствует типу слоя' AS msg FROM " + t +
                       " WHERE geom IS NOT NULL AND " + typeCheck);
        String invalidSql = "SELECT ogc_fid, 'невалидная геометрия: ' || ST_IsValidReason(geom) AS msg FROM " + t +
                            " WHERE geom IS NOT NULL AND NOT ST_IsValid(geom)";
        if (!req.makeValid()) perFeature.add(invalidSql);

        List<Map<String, Object>> errors = db.queryForList("SELECT ogc_fid AS fid, msg AS message FROM (" +
                String.join(" UNION ALL ", perFeature) + ") e ORDER BY fid LIMIT 5000")
                .stream().map(r -> Map.<String, Object>of("fid", r.get("fid"), "message", translate((String) r.get("message")))).toList();

        List<Map<String, Object>> warnings = new ArrayList<>();
        if (req.makeValid()) db.queryForList("SELECT ogc_fid AS fid, msg FROM (" + invalidSql + ") e ORDER BY fid LIMIT 1000")
                .forEach(r -> warnings.add(Map.of("fid", r.get("fid"), "message", translate((String) r.get("msg")) + " — будет исправлена автоматически (ST_MakeValid)")));
        Map<String, Object> stats = db.queryForMap("SELECT count(*) AS total, count(*) FILTER (WHERE geom IS NOT NULL AND NOT (geom && " +
                KG_ENVELOPE + ")) AS outside FROM " + t);
        long total = ((Number) stats.get("total")).longValue();
        long outside = ((Number) stats.get("outside")).longValue();
        if (total == 0) global.add("Файл не содержит объектов");
        if (total > 0 && outside * 2 > total)
            global.add("Большинство объектов (" + outside + " из " + total + ") вне территории КР — вероятно, указана неверная исходная система координат");
        else if (outside > 0)
            db.queryForList("SELECT ogc_fid FROM " + t + " WHERE geom IS NOT NULL AND NOT (geom && " + KG_ENVELOPE + ") LIMIT 200", Long.class)
              .forEach(fid -> warnings.add(Map.of("fid", fid, "message", "объект вне территории Кыргызской Республики")));

        Set<Object> badFids = new HashSet<>();
        errors.forEach(e -> badFids.add(e.get("fid")));
        boolean blocking = !global.isEmpty() || !errors.isEmpty();
        List<Double> bbox = db.query("SELECT ST_XMin(e),ST_YMin(e),ST_XMax(e),ST_YMax(e) FROM (SELECT ST_Extent(geom)::geometry e FROM " + t + ") s WHERE e IS NOT NULL",
                rs -> rs.next() ? List.of(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)) : null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("valid", total - badFids.size());
        result.put("blocking", blocking);
        result.put("globalErrors", global);
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("bbox", bbox);

        db.update("""
            UPDATE jobs.import_jobs SET status=?, source_srid=?, mapping=?::jsonb, title_field=?, make_valid=?, validation=?::jsonb,
                   staging_table=?, updated_at=now() WHERE id=?""",
            blocking ? "invalid" : "validated", req.sourceSrid(), json.write(mapping), blank(req.titleField()), req.makeValid(),
            json.write(result), table, jobId);

        String preview = db.queryForObject("""
            SELECT json_build_object('type','FeatureCollection','features', coalesce(json_agg(json_build_object(
                     'type','Feature','properties', json_build_object('fid', ogc_fid), 'geometry', ST_AsGeoJSON(geom, 6)::json)), '[]'::json))::text
            FROM (SELECT ogc_fid, geom FROM %s WHERE geom IS NOT NULL ORDER BY ogc_fid LIMIT 3000) s""".formatted(t), String.class);
        result.put("preview", json.tree(preview));
        result.put("errorFids", badFids);
        return result;
    }

    /** CSV-отчёт об ошибках («Скачать отчёт об ошибках», шаг 5). */
    public String errorsCsv(UUID jobId) {
        Map<String, Object> job = job(jobId);
        Map<String, Object> v = json.map(String.valueOf(job.get("validation")));
        StringBuilder b = new StringBuilder("﻿уровень;объект;сообщение\n");
        if (v != null) {
            for (Object g : (List<?>) v.getOrDefault("globalErrors", List.of())) b.append("ошибка;;").append(csv(g)).append('\n');
            for (Object e : (List<?>) v.getOrDefault("errors", List.of())) {
                Map<?, ?> m = (Map<?, ?>) e;
                b.append("ошибка;").append(m.get("fid")).append(';').append(csv(m.get("message"))).append('\n');
            }
            for (Object e : (List<?>) v.getOrDefault("warnings", List.of())) {
                Map<?, ?> m = (Map<?, ?>) e;
                b.append("предупреждение;").append(m.get("fid")).append(';').append(csv(m.get("message"))).append('\n');
            }
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- шаг 6: подтверждение и фоновая запись

    public void markQueued(UUID jobId, CurrentUser u) {
        Map<String, Object> job = job(jobId);
        Layer l = layers.get((UUID) job.get("layer_id"));
        if (!l.canEdit(u)) throw ApiException.forbidden("Слой принадлежит другой организации");
        if (!"validated".equals(job.get("status"))) throw ApiException.conflict("Импорт возможен только после успешной проверки данных");
        db.update("UPDATE jobs.import_jobs SET status='queued', progress=0, updated_at=now() WHERE id=?", jobId);
    }

    /** Запись данных из staging в spatial.features как новой версии слоя. */
    public int commit(UUID jobId, CurrentUser u) {
        Map<String, Object> job = job(jobId);
        Layer l = layers.get((UUID) job.get("layer_id"));
        String t = "staging." + job.get("staging_table");
        try {
            progress(jobId, "running", 10, null);
            Map<String, Object> mappingRaw = json.map(String.valueOf(job.get("mapping")));
            Map<String, String> mapping = new LinkedHashMap<>();
            if (mappingRaw != null) mappingRaw.forEach((k, v) -> mapping.put(k, String.valueOf(v)));
            Map<String, String> types = new HashMap<>();
            layers.attributes(l.id()).forEach(a -> types.put(a.name(), a.type()));

            String attrsExpr = buildAttrsExpr(mapping, types);
            String titleSrc = blank((String) job.get("title_field"));
            if (titleSrc == null && l.titleAttr() != null)
                titleSrc = mapping.entrySet().stream().filter(e -> e.getValue().equals(l.titleAttr())).map(Map.Entry::getKey).findFirst().orElse(null);
            String titleExpr = titleSrc == null ? "NULL" : "NULLIF(trim(" + q(titleSrc) + "::text),'')";
            boolean makeValid = Boolean.TRUE.equals(job.get("make_valid"));
            String geomExpr = !makeValid ? "geom" : switch (l.geomType()) {
                case "point" -> "ST_CollectionExtract(ST_MakeValid(geom), 1)";
                case "line" -> "ST_CollectionExtract(ST_MakeValid(geom), 2)";
                case "polygon" -> "ST_CollectionExtract(ST_MakeValid(geom), 3)";
                default -> "ST_MakeValid(geom)";
            };

            Integer[] out = new Integer[2];
            tx.executeWithoutResult(s -> {
                int version = layers.nextVersion(l.id());
                db.update("INSERT INTO catalog.layer_versions(layer_id,version,created_by,source,source_srid,feature_count,note) VALUES (?,?,?,?,?,0,?)",
                        l.id(), version, u.id(), job.get("file_name"), job.get("source_srid"), "Импорт файла");
                int n = db.update("INSERT INTO spatial.features(layer_id,version,geom,attrs,title,created_by,updated_by) " +
                        "SELECT ?, ?, g, a, tt, ?, ? FROM (SELECT " + geomExpr + " AS g, jsonb_strip_nulls(" + attrsExpr + ") AS a, " +
                        titleExpr + " AS tt FROM " + t + " WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom)) s WHERE NOT ST_IsEmpty(g)",
                        l.id(), version, u.id(), u.id());
                db.update("UPDATE catalog.layer_versions SET feature_count=? WHERE layer_id=? AND version=?", n, l.id(), version);
                db.update("UPDATE catalog.layers SET draft_version=?, published_version=CASE WHEN is_operational THEN ? ELSE published_version END, " +
                          "updated_at=clock_timestamp() WHERE id=?", version, version, l.id());
                out[0] = version; out[1] = n;
            });
            progress(jobId, "running", 90, null);
            db.execute("DROP TABLE IF EXISTS " + t);
            db.update("UPDATE jobs.import_jobs SET status='done', progress=100, result_version=?, message=?, updated_at=now() WHERE id=?",
                    out[0], "Импортировано объектов: " + out[1] + ", версия " + out[0], jobId);
            audit.log(u, "import_commit", "layer", l.id(), Map.of("job", jobId.toString(), "version", out[0], "features", out[1],
                    "file", String.valueOf(job.get("file_name"))));
            return out[0];
        } catch (RuntimeException e) {
            log.error("Import {} failed", jobId, e);
            progress(jobId, "failed", 0, e.getMessage());
            throw e;
        }
    }

    private String buildAttrsExpr(Map<String, String> mapping, Map<String, String> types) {
        if (mapping.isEmpty()) return "'{}'::jsonb";
        List<String> chunks = new ArrayList<>();
        List<String> pairs = new ArrayList<>();
        for (var e : mapping.entrySet()) {
            String v = "number".equals(types.get(e.getValue()))
                    ? "NULLIF(trim(replace(" + q(e.getKey()) + "::text, ',', '.')),'')::numeric"
                    : "NULLIF(trim(" + q(e.getKey()) + "::text),'')";
            pairs.add("'" + sqlStr(e.getValue()) + "', " + v);
            if (pairs.size() == 40) { chunks.add("jsonb_build_object(" + String.join(", ", pairs) + ")"); pairs.clear(); }
        }
        if (!pairs.isEmpty()) chunks.add("jsonb_build_object(" + String.join(", ", pairs) + ")");
        return String.join(" || ", chunks);
    }

    // ---------------------------------------------------------------- служебное

    public Map<String, Object> status(UUID jobId, CurrentUser u) {
        Map<String, Object> job = job(jobId);
        if (!layers.get((UUID) job.get("layer_id")).canEdit(u)) throw ApiException.forbidden("Нет доступа");
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id", "layer_id", "status", "progress", "file_name", "message", "result_version", "created_at", "updated_at"))
            m.put(Rows.toCamel(k), job.get(k));
        return m;
    }

    Map<String, Object> job(UUID id) {
        List<Map<String, Object>> l = db.queryForList("""
            SELECT id, layer_id, status, progress, file_name, file_path, ogr_layer, source_info::text AS source_info, source_srid,
                   mapping::text AS mapping, title_field, make_valid, validation::text AS validation, staging_table, result_version,
                   message, created_by, created_at, updated_at
            FROM jobs.import_jobs WHERE id=?""", id);
        if (l.isEmpty()) throw ApiException.notFound("Задание импорта");
        return l.get(0);
    }

    private void progress(UUID jobId, String status, int p, String message) {
        db.update("UPDATE jobs.import_jobs SET status=?, progress=?, message=coalesce(?, message), updated_at=now() WHERE id=?", status, p, message, jobId);
    }

    static String q(String ident) { return "\"" + ident.replace("\"", "\"\"") + "\""; }
    static String sqlStr(String s) { return s.replace("'", "''"); }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
    private static String csv(Object o) { return String.valueOf(o).replace(";", ",").replace("\n", " "); }

    /** Перевод типовых сообщений GEOS на русский (шаг 5 мастера). */
    static String translate(String msg) {
        if (msg == null) return null;
        return msg.replace("Ring Self-intersection", "самопересечение кольца")
                  .replace("Self-intersection", "самопересечение контура полигона")
                  .replace("Too few points in geometry component", "недостаточно точек в контуре")
                  .replace("Hole lies outside shell", "внутреннее кольцо вне внешнего контура")
                  .replace("Interior is disconnected", "внутренняя область несвязна")
                  .replace("Nested shells", "вложенные контуры")
                  .replace("Invalid Coordinate", "некорректная координата");
    }
}
