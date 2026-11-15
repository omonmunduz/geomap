package kg.nsdi.geoportal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-GIS-06 (векторные тайлы MVT) и FR-SRCH-01 (полнотекстовый поиск). */
class MapAndSearchTest extends IntegrationTest {

    @ParameterizedTest(name = "FR-GIS-06: тайл z12/2896/1507 слоя {0} непустой")
    @ValueSource(strings = {"current_development", "urban_zones"})
    void bishkekTileNotEmpty(String layerCode) {
        ResponseEntity<byte[]> r = getBytes(null, "/api/tiles/" + layerId(layerCode) + "/12/2896/1507.pbf");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType()).hasToString("application/vnd.mapbox-vector-tile");
        assertThat(r.getBody()).as("тайл %s", layerCode).isNotEmpty();
    }

    @Test
    @DisplayName("FR-SRCH-01: GET /api/search?q=бишк — первый результат «Бишкек» (слой «Населённые пункты»)")
    void searchBishkek() {
        JsonNode r = get(null, "/api/search?q=бишк").getBody();
        assertThat(r.path("total").asInt()).isPositive();
        JsonNode first = r.path("items").get(0);
        assertThat(first.path("title").asText()).isEqualTo("Бишкек");
        assertThat(first.path("layerTitle").asText()).isEqualTo("Населённые пункты");
    }
}
