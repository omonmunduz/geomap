package kg.nsdi.geoportal.tiles;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.catalog.Layer;
import kg.nsdi.geoportal.catalog.LayerRepository;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.common.Json;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Векторные тайлы MVT (Mapbox Vector Tile) из PostGIS: ST_AsMVT.
 * В целевой архитектуре публикацию по OGC (WMS/WMTS/WFS) и кэш тайлов выполняет GeoServer + GeoWebCache;
 * в MVP тайлы отдаются напрямую — интерфейс для клиента (XYZ .pbf) остаётся тем же.
 */
@RestController
public class TileController {
    private final JdbcTemplate db;
    private final LayerRepository layers;
    private final Json json;
    private final Cache<String, byte[]> cache = Caffeine.newBuilder().maximumWeight(256L * 1024 * 1024)
            .weigher((String k, byte[] v) -> v.length + 64).expireAfterAccess(Duration.ofHours(6)).build();

    private static final String SQL = """
        WITH b AS (SELECT ST_TileEnvelope(?, ?, ?) AS env),
             b4 AS (SELECT env, ST_Transform(env, 4326) AS env4326 FROM b),
             t AS (
               SELECT ST_AsMVTGeom(ST_Transform(CASE WHEN ? > 0 THEN ST_SimplifyPreserveTopology(f.geom, ?) ELSE f.geom END, 3857),
                                   b4.env, 4096, 64, true) AS geom,
                      f.id::text AS id, f.title AS title, f.attrs ->> ? AS cls
               FROM spatial.features f, b4
               WHERE f.layer_id = ? AND f.version = ? AND NOT f.deleted AND f.geom && b4.env4326)
        SELECT ST_AsMVT(t.*, 'features', 4096, 'geom') FROM t WHERE t.geom IS NOT NULL
        """;

    public TileController(JdbcTemplate db, LayerRepository layers, Json json) {
        this.db = db; this.layers = layers; this.json = json;
    }

    @GetMapping(value = "/api/tiles/{layerId}/{z}/{x}/{y}.pbf")
    public ResponseEntity<byte[]> tile(@PathVariable UUID layerId, @PathVariable int z, @PathVariable int x, @PathVariable int y,
                                       @RequestParam(defaultValue = "false") boolean draft) {
        if (z < 0 || z > 22) throw ApiException.bad("z out of range");
        CurrentUser u = CurrentUser.get();
        Layer l = layers.get(layerId);
        Integer version = l.resolveVersion(u, draft);
        if (version == null) throw ApiException.forbidden("Слой недоступен");

        String key = layerId + ":" + version + ":" + l.updatedAt().toInstant().toEpochMilli() + ":" + z + "/" + x + "/" + y;
        byte[] bytes = cache.get(key, k -> render(l, version, z, x, y));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.mapbox-vector-tile"))
                .cacheControl(l.operational() ? CacheControl.noCache() : CacheControl.maxAge(Duration.ofMinutes(5)))
                .body(bytes);
    }

    private byte[] render(Layer l, int version, int z, int x, int y) {
        Map<String, Object> style = json.map(l.styleJson());
        String field = style == null ? null : (String) style.get("field");
        // упрощение геометрии на мелких масштабах (градусы ~ половина пикселя)
        double tol = z >= 12 ? 0 : 360.0 / (256 * Math.pow(2, z)) / 2;
        byte[] b = db.queryForObject(SQL, byte[].class, z, x, y, tol, tol, field == null ? "__none__" : field, l.id(), version);
        return b == null ? new byte[0] : b;
    }
}
