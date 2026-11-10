package kg.nsdi.geoportal.seed;

import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.catalog.LayerRepository;
import kg.nsdi.geoportal.config.AppProperties;
import kg.nsdi.geoportal.imports.ImportService;
import kg.nsdi.geoportal.zoning.ZoningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/**
 * Первичное наполнение демо-данными через тот же конвейер импорта, что и у пользователей
 * (GDAL → staging → валидация → версия → публикация). Выполняется один раз при пустой БД.
 */
@Component
public class DemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final AppProperties props;
    private final JdbcTemplate db;
    private final ImportService imports;
    private final LayerRepository layers;
    private final ZoningService zoning;

    public DemoSeeder(AppProperties props, JdbcTemplate db, ImportService imports, LayerRepository layers, ZoningService zoning) {
        this.props = props; this.db = db; this.imports = imports; this.layers = layers; this.zoning = zoning;
    }

    record Item(String layer, String file, Map<String, String> rename, String title) {}

    private static final List<Item> ITEMS = List.of(
        new Item("atd_oblasts", "atd_oblasts.geojson", Map.of("name", "name_en", "iso_3166_2", "iso_code", "type_en", "unit_type"), "name_ru"),
        new Item("atd_districts", "atd_districts.geojson", Map.of("shapeName", "name"), "shapeName"),
        new Item("settlements", "settlements.geojson", Map.of(), "name_ru"),
        new Item("roads", "roads.geojson", Map.of("type", "road_type"), "name"),
        new Item("hydrography", "hydrography.geojson", Map.of(), "name"),
        new Item("urban_zones", "urban_zones.geojson", Map.of(), "zone_code"),
        new Item("current_development", "current_development.geojson", Map.of(), "obj_no"));

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.seed().enabled()) return;
        Integer n = db.queryForObject("SELECT count(*) FROM catalog.layer_versions", Integer.class);
        if (n != null && n > 0) return;
        Path dir = Paths.get(props.seed().dir());
        if (!Files.isDirectory(dir)) { log.warn("Seed dir {} not found, skipping demo data", dir.toAbsolutePath()); return; }

        UUID orgId = db.queryForObject("SELECT id FROM core.organizations WHERE code='zemagency'", UUID.class);
        UUID sysId = db.query("SELECT id FROM core.users WHERE ext_subject='system:seed'", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
        if (sysId == null) sysId = db.queryForObject("INSERT INTO core.users(ext_subject,display_name,org_id,role,status) " +
                "VALUES ('system:seed','Система (первичное наполнение)',?,'admin','blocked') RETURNING id", UUID.class, orgId);
        CurrentUser sys = new CurrentUser(sysId, "Система (первичное наполнение)", "admin", orgId, "zemagency", "Земагентство");

        for (Item it : ITEMS) {
            Path f = dir.resolve(it.file());
            if (!Files.exists(f)) { log.warn("Seed file missing: {}", f); continue; }
            var layer = layers.byCode(it.layer());
            Map<String, Object> up;
            try (InputStream in = Files.newInputStream(f)) { up = imports.upload(layer.id(), it.file(), in, sys); }
            UUID jobId = (UUID) up.get("jobId");
            @SuppressWarnings("unchecked") Map<String, String> mapping = new LinkedHashMap<>((Map<String, String>) up.get("suggestedMapping"));
            it.rename().forEach(mapping::put);
            var v = imports.validate(jobId, new ImportService.ValidateRequest(4326, mapping, it.title(), true), sys);
            if (Boolean.TRUE.equals(v.get("blocking"))) {
                log.warn("Seed {} has blocking errors: {} {}", it.layer(), v.get("globalErrors"), v.get("errors"));
                continue;
            }
            imports.markQueued(jobId, sys);
            int version = imports.commit(jobId, sys);
            db.update("UPDATE catalog.layers SET published_version=? WHERE id=?", version, layer.id());
            layers.touch(layer.id());
            log.info("Seeded {} v{} ({} objects)", it.layer(), version, v.get("total"));
        }
        // начальная проверка зонирования по г. Бишкек, чтобы реестр расхождений не был пустым
        if (!props.seed().zoningCheck()) { log.info("Initial zoning check skipped (SEED_ZONING_CHECK=false)"); return; }
        UUID bishkek = db.query("""
            SELECT f.id FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id AND f.version=l.published_version
            WHERE l.code='atd_oblasts' AND f.title ILIKE 'Бишкек%' LIMIT 1""", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
        if (bishkek != null) log.info("Initial zoning check: {}", zoning.runCheck(bishkek, sys));
    }
}
