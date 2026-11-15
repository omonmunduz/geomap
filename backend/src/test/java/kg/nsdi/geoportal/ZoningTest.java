package kg.nsdi.geoportal;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-TER-02 (проверка зонирования), FR-TER-03 (реестр и история статусов), FR-TER-05 (отчёт по территории).
 * Порядок важен: первая проверка по Бишкеку создаёт 29 расхождений, остальные тесты работают с ними.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ZoningTest extends IntegrationTest {

    private static String admin;
    private static UUID bishkek;

    @BeforeEach
    void setUp() {
        if (admin == null) admin = login("infocom:admin");
        if (bishkek == null) bishkek = bishkekId();
    }

    private JsonNode runCheck() {
        ResponseEntity<JsonNode> r = post(admin, "/api/zoning/checks", Map.of("territoryId", bishkek.toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test @Order(1)
    @DisplayName("FR-TER-02: первая проверка по Бишкеку — 117 объектов, 29 новых расхождений (17/3/9)")
    void firstCheck() {
        JsonNode r = runCheck();
        assertThat(r.path("objectsChecked").asInt()).isEqualTo(117);
        assertThat(r.path("discrepancies").asInt()).isEqualTo(29);
        assertThat(r.path("newDiscrepancies").asInt()).isEqualTo(29);
        assertThat(r.path("byType").path("USE_NOT_ALLOWED").asInt()).isEqualTo(17);
        assertThat(r.path("byType").path("NO_ZONE").asInt()).isEqualTo(3);
        assertThat(r.path("byType").path("CROSSES_ZONES").asInt()).isEqualTo(9);
    }

    @Test @Order(2)
    @DisplayName("FR-TER-02: повторная проверка — те же 29 расхождений, 0 новых")
    void repeatCheck() {
        JsonNode r = runCheck();
        assertThat(r.path("discrepancies").asInt()).isEqualTo(29);
        assertThat(r.path("newDiscrepancies").asInt()).isZero();
        assertThat(get(admin, "/api/zoning/discrepancies?territoryId=" + bishkek).getBody().size()).isEqualTo(29);
    }

    @Test @Order(3)
    @DisplayName("FR-TER-03: «Устранено» без комментария → 400; с комментарием → 200 и запись в истории")
    void statusChange() {
        String id = get(admin, "/api/zoning/discrepancies?territoryId=" + bishkek).getBody().get(0).path("id").asText();
        String path = "/api/zoning/discrepancies/" + id + "/status";

        MultiValueMap<String, Object> noComment = new LinkedMultiValueMap<>();
        noComment.add("status", "resolved");
        ResponseEntity<JsonNode> bad = multipart(admin, path, noComment);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().path("error").asText()).contains("обоснование");

        MultiValueMap<String, Object> withComment = new LinkedMultiValueMap<>();
        withComment.add("status", "resolved");
        withComment.add("comment", "Акт выездной проверки №12: объект приведён в соответствие");
        ResponseEntity<JsonNode> ok = multipart(admin, path, withComment);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().path("status").asText()).isEqualTo("resolved");

        JsonNode last = get(admin, "/api/zoning/discrepancies/" + id).getBody().path("history").get(0);
        assertThat(last.path("fromStatus").asText()).isEqualTo("new");
        assertThat(last.path("toStatus").asText()).isEqualTo("resolved");
        assertThat(last.path("comment").asText()).isEqualTo("Акт выездной проверки №12: объект приведён в соответствие");
        assertThat(last.path("byName").asText()).isEqualTo("Администратор Геопортала");
    }

    @Test @Order(4)
    @DisplayName("FR-TER-05: XLSX открывается Apache POI, на листе «Реестр расхождений» 29 строк")
    void xlsxReport() throws Exception {
        ResponseEntity<byte[]> r = getBytes(admin, "/api/zoning/report?format=xlsx&territoryId=" + bishkek);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getBody()))) {
            Sheet registry = wb.getSheetAt(1);
            assertThat(registry.getSheetName()).isEqualTo("Реестр расхождений");
            assertThat(registry.getPhysicalNumberOfRows() - 1).as("строк данных без заголовка").isEqualTo(29);
        }
    }

    @Test @Order(5)
    @DisplayName("FR-TER-05: PDF открывается PDFBox, 2 страницы, текст содержит «Бишкек» (кириллица)")
    void pdfReport() throws Exception {
        ResponseEntity<byte[]> r = getBytes(admin, "/api/zoning/report?format=pdf&territoryId=" + bishkek);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (PDDocument pdf = Loader.loadPDF(r.getBody())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            assertThat(new PDFTextStripper().getText(pdf)).contains("Бишкек");
        }
    }
}
