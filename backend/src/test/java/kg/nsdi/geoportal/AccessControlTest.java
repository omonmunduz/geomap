package kg.nsdi.geoportal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-AUTH-02 (проверка трудоустройства), FR-PUB-02 / FR-SRCH-05 (служебные слои, права на редактирование). */
class AccessControlTest extends IntegrationTest {

    @Test
    @DisplayName("FR-AUTH-02: dev-login Эрмека Бекмуратова (is_active=false) → 403 и login_denied в audit.events")
    void inactiveEmployeeDenied() {
        Integer before = deniedCount();
        ResponseEntity<JsonNode> r = post(null, "/api/auth/dev-login", Map.of("subject", "infocom:former"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().has("token")).isFalse();
        assertThat(deniedCount()).isEqualTo(before + 1);
        Map<String, Object> ev = db.queryForMap("SELECT outcome, object_id, org_code FROM audit.events " +
                "WHERE action='login_denied' AND user_name='Эрмек Бекмуратов' ORDER BY id DESC LIMIT 1");
        assertThat(ev.get("outcome")).isEqualTo("denied");
        assertThat(ev.get("object_id")).isEqualTo("infocom:former");
        assertThat(ev.get("org_code")).isEqualTo("mintrans");
    }

    private Integer deniedCount() {
        return db.queryForObject("SELECT count(*) FROM audit.events WHERE action='login_denied' AND user_name='Эрмек Бекмуратов'", Integer.class);
    }

    @Test
    @DisplayName("FR-PUB-02: слои ДСП не видны анониму в /api/catalog/tree и в тайлах (сотруднику — видны)")
    void restrictedLayersHiddenFromAnonymous() {
        String admin = login("infocom:admin");
        String staff = login("infocom:minzdrav");

        // служебный слой с опубликованными данными в Бишкеке
        String theme = get(null, "/api/catalog/themes").getBody().get(0).path("code").asText();
        UUID dsp = UUID.fromString(post(admin, "/api/layers", Map.of("titleRu", "Тест: служебный слой", "geomType", "point",
                "themeCode", theme, "accessLevel", "restricted")).getBody().path("id").asText());
        Map<String, Object> feature = Map.of("geometry", Map.of("type", "Point", "coordinates", List.of(74.6, 42.87)),
                "attrs", Map.of());
        assertThat(post(admin, "/api/layers/" + dsp + "/features", feature).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(admin, "/api/layers/" + dsp + "/publish", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<UUID> restricted = db.queryForList("SELECT id FROM catalog.layers WHERE access_level='restricted' AND NOT archived", UUID.class);
        assertThat(restricted).contains(dsp).hasSizeGreaterThanOrEqualTo(4);   // 3 из справочника + созданный

        Set<String> anonTree = treeLayerIds(null);
        Set<String> staffTree = treeLayerIds(staff);
        for (UUID id : restricted) {
            assertThat(anonTree).as("аноним видит служебный слой %s в каталоге", id).doesNotContain(id.toString());
            assertThat(staffTree).as("сотрудник видит служебный слой %s", id).contains(id.toString());
            assertThat(getBytes(null, "/api/tiles/" + id + "/14/11587/6028.pbf").getStatusCode())
                    .as("тайл служебного слоя %s для анонима", id).isEqualTo(HttpStatus.FORBIDDEN);
        }
        ResponseEntity<byte[]> staffTile = getBytes(staff, "/api/tiles/" + dsp + "/14/11587/6028.pbf");
        assertThat(staffTile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(staffTile.getBody()).isNotEmpty();
    }

    private Set<String> treeLayerIds(String token) {
        Set<String> ids = new HashSet<>();
        get(token, "/api/catalog/tree").getBody().forEach(t -> t.path("layers").forEach(l -> ids.add(l.path("id").asText())));
        return ids;
    }

    @Test
    @DisplayName("FR-PUB-02: сотрудник Минздрава не может редактировать слой Минархстроя (403)")
    void staffCannotEditForeignLayer() {
        String staff = login("infocom:minzdrav");
        UUID zones = layerId("urban_zones");                               // владелец — Минархстрой
        String featureId = db.queryForObject("SELECT f.id::text FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id " +
                "AND f.version=l.published_version WHERE l.code='urban_zones' LIMIT 1", String.class);

        Map<String, Object> polygon = Map.of("type", "Polygon", "coordinates",
                List.of(List.of(List.of(74.58, 42.86), List.of(74.585, 42.86), List.of(74.585, 42.864), List.of(74.58, 42.86))));
        Map<String, Object> attrs = Map.of("zone_code", "Т-1", "zone_type", "residential", "approval_doc", "тест");

        assertThat(post(staff, "/api/layers/" + zones + "/features", Map.of("geometry", polygon, "attrs", attrs)).getStatusCode())
                .as("создание объекта").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(staff, HttpMethod.PUT, "/api/features/" + featureId, Map.of("attrs", attrs)).getStatusCode())
                .as("изменение объекта").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(staff, HttpMethod.DELETE, "/api/features/" + featureId, null).getStatusCode())
                .as("удаление объекта").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(staff, HttpMethod.PATCH, "/api/layers/" + zones, Map.of("color", "#ff0000")).getStatusCode())
                .as("изменение стиля слоя").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(staff, "/api/layers/" + zones + "/publish", Map.of()).getStatusCode())
                .as("публикация слоя").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(staff, zones, DEMO_DATA.resolve("samples/green_zones_with_error.geojson")).getStatusCode())
                .as("импорт в слой").isEqualTo(HttpStatus.FORBIDDEN);
    }
}
