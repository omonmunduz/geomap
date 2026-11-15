package kg.nsdi.geoportal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-DAT-12, FR-GIS-07: мастер импорта — СК Kyrg-06, обязательные поля, самопересечение, публикация. */
class ImportPipelineTest extends IntegrationTest {

    private Map<String, Object> validateRequest(JsonNode upload, int srid, String titleField, boolean makeValid) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("sourceSrid", srid);
        req.put("mapping", mapper.convertValue(upload.path("suggestedMapping"), Map.class));
        req.put("titleField", titleField);
        req.put("makeValid", makeValid);
        return req;
    }

    @Test
    @DisplayName("FR-GIS-07: hospitals_kyrg06_zone3.zip — EPSG:7694, пересчёт в пределы КР, у объекта №3 не заполнено обязательное поле")
    void hospitalsKyrg06() {
        String staff = login("infocom:minzdrav");       // владелец слоя «Больницы» — Минздрав
        ResponseEntity<JsonNode> up = upload(staff, layerId("health"), DEMO_DATA.resolve("samples/hospitals_kyrg06_zone3.zip"));
        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode u = up.getBody();
        assertThat(u.path("detectedSrid").asInt()).as("определённая СК").isEqualTo(7694);

        JsonNode v = post(staff, "/api/imports/" + u.path("jobId").asText() + "/validate",
                validateRequest(u, 7694, u.path("suggestedTitleField").asText(null), false)).getBody();

        // координаты пересчитаны из Kyrg-06 зона 3 в WGS-84 и лежат в пределах КР (69–80.5° в.д., 39–43.5° с.ш.)
        JsonNode bbox = v.path("bbox");
        assertThat(bbox.size()).isEqualTo(4);
        assertThat(bbox.get(0).asDouble()).isBetween(69.0, 80.5);
        assertThat(bbox.get(2).asDouble()).isBetween(69.0, 80.5);
        assertThat(bbox.get(1).asDouble()).isBetween(39.0, 43.5);
        assertThat(bbox.get(3).asDouble()).isBetween(39.0, 43.5);
        assertThat(v.path("globalErrors").toString()).doesNotContain("вне территории КР");

        assertThat(v.path("blocking").asBoolean()).as("блокирующая ошибка").isTrue();
        List<JsonNode> errors = new ArrayList<>();
        v.path("errors").forEach(errors::add);
        assertThat(errors).anySatisfy(e -> {
            assertThat(e.path("fid").asInt()).isEqualTo(3);
            assertThat(e.path("message").asText()).isEqualTo("не заполнено обязательное поле «Наименование»");
        });

        // невалидный импорт нельзя подтвердить
        assertThat(post(staff, "/api/imports/" + u.path("jobId").asText() + "/commit", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("FR-DAT-12: green_zones_with_error.geojson — самопересечение; makeValid → 2 объекта; commit → publish → видно анониму")
    void greenZonesSelfIntersection() throws Exception {
        String admin = login("infocom:admin");
        String theme = get(null, "/api/catalog/themes").getBody().get(0).path("code").asText();
        JsonNode layer = post(admin, "/api/layers", Map.of("titleRu", "Тест: зелёные зоны", "geomType", "polygon",
                "themeCode", theme, "accessLevel", "open")).getBody();
        UUID layerId = UUID.fromString(layer.path("id").asText());

        JsonNode u = upload(admin, layerId, DEMO_DATA.resolve("samples/green_zones_with_error.geojson")).getBody();
        String job = u.path("jobId").asText();
        int srid = u.path("detectedSrid").isNumber() ? u.path("detectedSrid").asInt() : 4326;

        // без исправления — блокирующая ошибка самопересечения
        JsonNode bad = post(admin, "/api/imports/" + job + "/validate", validateRequest(u, srid, "park_name", false)).getBody();
        assertThat(bad.path("blocking").asBoolean()).isTrue();
        assertThat(bad.path("errors").toString()).contains("самопересечение");

        // с ST_MakeValid — 2 объекта без ошибок
        JsonNode ok = post(admin, "/api/imports/" + job + "/validate", validateRequest(u, srid, "park_name", true)).getBody();
        assertThat(ok.path("blocking").asBoolean()).isFalse();
        assertThat(ok.path("total").asInt()).isEqualTo(2);
        assertThat(ok.path("valid").asInt()).isEqualTo(2);
        assertThat(ok.path("errors").size()).isZero();

        assertThat(post(admin, "/api/imports/" + job + "/commit", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode done = awaitImport(admin, job);
        assertThat(done.path("status").asText()).as("статус импорта: %s", done).isEqualTo("done");

        // до публикации аноним слой не видит
        assertThat(anonPublishedVersion(layerId)).isNull();
        assertThat(anonSearch("Ала-Арча")).noneMatch(i -> i.path("layerId").asText().equals(layerId.toString()));

        JsonNode pub = post(admin, "/api/layers/" + layerId + "/publish", Map.of()).getBody();
        assertThat(pub.path("publishedVersion").asInt()).isEqualTo(1);

        // после публикации: каталог, поиск, карточка объекта и тайл доступны анониму
        assertThat(anonPublishedVersion(layerId)).isEqualTo(1);
        List<JsonNode> found = anonSearch("Ала-Арча").stream()
                .filter(i -> i.path("layerId").asText().equals(layerId.toString())).toList();
        assertThat(found).hasSize(1);
        assertThat(get(null, "/api/features/" + found.get(0).path("id").asText()).getStatusCode()).isEqualTo(HttpStatus.OK);
        Integer count = db.queryForObject("SELECT count(*) FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id " +
                "AND f.version=l.published_version WHERE l.id=?", Integer.class, layerId);
        assertThat(count).isEqualTo(2);
        ResponseEntity<byte[]> tile = getBytes(null, "/api/tiles/" + layerId + "/12/2896/1507.pbf");
        assertThat(tile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tile.getBody()).isNotEmpty();
    }

    private Integer anonPublishedVersion(UUID layerId) {
        for (JsonNode t : get(null, "/api/catalog/tree").getBody())
            for (JsonNode l : t.path("layers"))
                if (l.path("id").asText().equals(layerId.toString()))
                    return l.path("publishedVersion").isNull() ? null : l.path("publishedVersion").asInt();
        return null;
    }

    /** Строка запроса кодируется шаблонизатором URI TestRestTemplate. */
    private List<JsonNode> anonSearch(String q) {
        List<JsonNode> out = new ArrayList<>();
        get(null, "/api/search?limit=50&q=" + q).getBody().path("items").forEach(out::add);
        return out;
    }
}
