package kg.nsdi.geoportal.search;

import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.common.Json;
import kg.nsdi.geoportal.common.Rows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Подсистема поиска (ЧТЗ п. 5.1.1.3, БП-3): полнотекстовый (ru/ky) и пространственный «Поиск в области».
 * MVP: PostgreSQL tsvector ('simple') + pg_trgm. Целевая архитектура — OpenSearch с морфологией ru/ky.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final NamedParameterJdbcTemplate db;
    private final Json json;

    public SearchController(NamedParameterJdbcTemplate db, Json json) { this.db = db; this.json = json; }

    private static final String VISIBLE = """
        FROM spatial.features f
        JOIN catalog.layers l ON l.id = f.layer_id AND f.version = l.published_version AND NOT l.archived
        WHERE NOT f.deleted AND (l.access_level = 'open' OR :staff)
        """;

    /** Префиксный запрос из лексем, разобранных тем же парсером 'simple', что и индекс: коды с дефисами («1-02-03-0605-0433») индексируются как «1», «-02», … */
    private static final String TSQ = "(SELECT to_tsquery('simple', string_agg(quote_literal(lexeme) || ':*', ' & ')) FROM unnest(to_tsvector('simple', :q)))";

    private static final String COLUMNS = """
        SELECT f.id, f.title, f.layer_id, l.title_ru AS layer_title, l.code AS layer_code,
               ST_X(ST_PointOnSurface(f.geom)) AS lon, ST_Y(ST_PointOnSurface(f.geom)) AS lat,
               ARRAY[ST_XMin(f.geom), ST_YMin(f.geom), ST_XMax(f.geom), ST_YMax(f.geom)] AS bbox
        """;

    @GetMapping
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "10") int limit,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(required = false) List<UUID> layers) {
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) return Map.of("total", 0, "items", List.of());
        List<String> tokens = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(s -> !s.isBlank()).limit(8).toList();
        if (tokens.isEmpty()) return Map.of("total", 0, "items", List.of());

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("staff", CurrentUser.staff(CurrentUser.get()))
                .addValue("q", query).addValue("like", "%" + query.replace("%", "") + "%")
                .addValue("limit", Math.min(Math.max(limit, 1), 100)).addValue("offset", Math.max(offset, 0));
        String filter = " AND (f.search @@ " + TSQ + " OR f.title ILIKE :like)";
        if (layers != null && !layers.isEmpty()) { filter += " AND f.layer_id IN (:layers)"; p.addValue("layers", layers); }

        Long total = db.queryForObject("SELECT count(*) " + VISIBLE + filter, p, Long.class);
        List<Map<String, Object>> items = db.queryForList(COLUMNS + VISIBLE + filter +
                " ORDER BY ts_rank(f.search, " + TSQ + ") + similarity(coalesce(f.title,''), :q) DESC, f.title LIMIT :limit OFFSET :offset", p)
                .stream().map(SearchController::dto).toList();
        return Map.of("total", total == null ? 0 : total, "items", items);
    }

    public record AreaRequest(Map<String, Object> geometry, List<UUID> layers, Integer limit, Integer offset) {}

    /** «Поиск в области»: объекты активных слоёв внутри нарисованного прямоугольника/полигона. */
    @PostMapping("/area")
    public Map<String, Object> area(@RequestBody AreaRequest req) {
        if (req.geometry() == null) throw ApiException.bad("Не задана область поиска");
        if (req.layers() == null || req.layers().isEmpty()) return Map.of("total", 0, "items", List.of());
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("staff", CurrentUser.staff(CurrentUser.get()))
                .addValue("g", json.write(req.geometry())).addValue("layers", req.layers())
                .addValue("limit", Math.min(req.limit() == null ? 50 : req.limit(), 500))
                .addValue("offset", req.offset() == null ? 0 : req.offset());
        String filter = " AND f.layer_id IN (:layers) AND ST_Intersects(f.geom, ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326))";
        Long total = db.queryForObject("SELECT count(*) " + VISIBLE + filter, p, Long.class);
        List<Map<String, Object>> items = db.queryForList(COLUMNS + VISIBLE + filter + " ORDER BY l.title_ru, f.title LIMIT :limit OFFSET :offset", p)
                .stream().map(SearchController::dto).toList();
        return Map.of("total", total == null ? 0 : total, "items", items);
    }

    private static Map<String, Object> dto(Map<String, Object> r) {
        Map<String, Object> m = Rows.camel(r);
        try {
            Object[] a = (Object[]) ((java.sql.Array) r.get("bbox")).getArray();
            m.put("bbox", Arrays.stream(a).map(o -> ((Number) o).doubleValue()).toList());
        } catch (Exception e) { m.put("bbox", null); }
        return m;
    }
}
