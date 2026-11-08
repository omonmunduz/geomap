package kg.nsdi.geoportal.catalog;

import kg.nsdi.geoportal.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class LayerRepository {
    private final JdbcTemplate db;

    static final RowMapper<Layer> MAPPER = (rs, i) -> new Layer(
            (UUID) rs.getObject("id"), rs.getString("code"), rs.getString("title_ru"), rs.getString("title_ky"),
            rs.getString("geom_type"), rs.getString("access_level"), (UUID) rs.getObject("owner_org_id"),
            rs.getBoolean("is_operational"), rs.getBoolean("archived"), rs.getString("title_attr"), rs.getString("style"),
            (Integer) rs.getObject("draft_version"), (Integer) rs.getObject("published_version"),
            rs.getObject("updated_at", OffsetDateTime.class));

    public LayerRepository(JdbcTemplate db) { this.db = db; }

    public Layer get(UUID id) {
        List<Layer> l = db.query("SELECT * FROM catalog.layers WHERE id=?", MAPPER, id);
        if (l.isEmpty()) throw ApiException.notFound("Слой");
        return l.get(0);
    }

    public Layer byCode(String code) {
        List<Layer> l = db.query("SELECT * FROM catalog.layers WHERE code=?", MAPPER, code);
        if (l.isEmpty()) throw ApiException.notFound("Слой " + code);
        return l.get(0);
    }

    public record Attr(String name, String labelRu, String type, boolean required, String optionsJson) {}

    public List<Attr> attributes(UUID layerId) {
        return db.query("SELECT name,label_ru,type,required,options::text FROM catalog.layer_attributes WHERE layer_id=? ORDER BY sort",
                (rs, i) -> new Attr(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5)), layerId);
    }

    public int nextVersion(UUID layerId) {
        Integer v = db.queryForObject("SELECT coalesce(max(version),0)+1 FROM catalog.layer_versions WHERE layer_id=?", Integer.class, layerId);
        return v == null ? 1 : v;
    }

    /** Сброс кэша тайлов слоя происходит через смену updated_at (входит в ключ кэша). */
    public void touch(UUID layerId) {
        db.update("UPDATE catalog.layers SET updated_at=clock_timestamp() WHERE id=?", layerId);
    }
}
