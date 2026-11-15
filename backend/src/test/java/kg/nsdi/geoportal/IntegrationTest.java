package kg.nsdi.geoportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Общая база интеграционных тестов: один контейнер PostGIS и один контекст Spring на весь прогон
 * (Flyway + демо-наполнение через настоящий конвейер GDAL выполняются один раз).
 * SEED_DEMO=true, SEED_ZONING_CHECK=false — первая проверка зонирования выполняется в тесте.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    public static final Path REPO_ROOT = locateRepoRoot();
    public static final Path DEMO_DATA = REPO_ROOT.resolve("demo-data");

    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("geoportal").withUsername("geo").withPassword("geo");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        requireGdal();
        if (!DB.isRunning()) DB.start();
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("app.gdal.pg", () -> "host=" + DB.getHost() + " port=" + DB.getMappedPort(5432) + " dbname=" + DB.getDatabaseName()
                + " user=" + DB.getUsername() + " password=" + DB.getPassword());
        r.add("app.seed.enabled", () -> "true");
        r.add("app.seed.dir", DEMO_DATA::toString);
        r.add("app.seed.zoning-check", () -> "false");
        r.add("app.dev-login", () -> "true");
        r.add("app.storage-dir", Files.createTempDirectory("geoportal-test-storage")::toString);
    }

    /** Без GDAL конвейер импорта не работает — падаем сразу с понятным сообщением, а не с ошибкой в середине наполнения. */
    static void requireGdal() {
        for (String tool : new String[]{"ogr2ogr", "ogrinfo"}) {
            String cmd = System.getenv().getOrDefault(tool.toUpperCase(), tool);
            boolean ok;
            try {
                Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (IOException e) {
                ok = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ok = false;
            }
            if (!ok) throw new IllegalStateException("Для тестов нужен GDAL: «" + cmd + "» не найден или не запускается. "
                    + "Установите GDAL >= 3.7 (Ubuntu/Debian: sudo apt-get install gdal-bin; macOS: brew install gdal; "
                    + "Windows: OSGeo4W или conda install gdal) и добавьте ogr2ogr/ogrinfo в PATH.");
        }
    }

    private static Path locateRepoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("demo-data"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("Не найден каталог demo-data (запускайте тесты из репозитория)");
        return p;
    }

    @Autowired protected TestRestTemplate http;
    @Autowired protected JdbcTemplate db;
    @Autowired protected ObjectMapper mapper;

    // ------------------------------------------------------------------ HTTP-помощники

    protected String login(String subject) {
        ResponseEntity<JsonNode> r = http.postForEntity("/api/auth/dev-login", Map.of("subject", subject), JsonNode.class);
        assertThat(r.getStatusCode()).as("dev-login %s", subject).isEqualTo(HttpStatus.OK);
        return r.getBody().path("token").asText();
    }

    protected HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    protected ResponseEntity<JsonNode> get(String token, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), JsonNode.class);
    }

    protected ResponseEntity<byte[]> getBytes(String token, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), byte[].class);
    }

    protected ResponseEntity<JsonNode> send(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = auth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> post(String token, String path, Object body) {
        return send(token, HttpMethod.POST, path, body);
    }

    protected ResponseEntity<JsonNode> multipart(String token, String path, MultiValueMap<String, Object> form) {
        HttpHeaders h = auth(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(form, h), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> upload(String token, UUID layerId, Path file) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new FileSystemResource(file));
        return multipart(token, "/api/imports?layerId=" + layerId, form);
    }

    /** Ожидание фоновой записи импорта (@Async) до статуса done/failed. */
    protected JsonNode awaitImport(String token, String jobId) throws InterruptedException {
        JsonNode s = null;
        for (int i = 0; i < 120; i++) {
            s = get(token, "/api/imports/" + jobId).getBody();
            String st = s.path("status").asText();
            if ("done".equals(st) || "failed".equals(st)) return s;
            Thread.sleep(250);
        }
        return s;
    }

    protected UUID layerId(String code) {
        return db.queryForObject("SELECT id FROM catalog.layers WHERE code=?", UUID.class, code);
    }

    protected UUID bishkekId() {
        return db.queryForObject("""
            SELECT f.id FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id AND f.version=l.published_version
            WHERE l.code='atd_oblasts' AND f.title ILIKE 'Бишкек%' LIMIT 1""", UUID.class);
    }
}
