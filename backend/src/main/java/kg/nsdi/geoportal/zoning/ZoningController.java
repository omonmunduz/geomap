package kg.nsdi.geoportal.zoning;

import kg.nsdi.geoportal.audit.AuditService;
import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.config.AppProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/zoning")
public class ZoningController {
    private final ZoningService zoning;
    private final ReportService reports;
    private final AuditService audit;
    private final AppProperties props;
    private final JdbcTemplate db;

    public ZoningController(ZoningService zoning, ReportService reports, AuditService audit, AppProperties props, JdbcTemplate db) {
        this.zoning = zoning; this.reports = reports; this.audit = audit; this.props = props; this.db = db;
    }

    @GetMapping("/config")
    public Map<String, Object> config() { return zoning.config(); }

    @GetMapping("/territories")
    public List<Map<String, Object>> territories() { return zoning.territories(); }

    public record CheckRequest(UUID territoryId) {}

    @PostMapping("/checks")
    public Map<String, Object> run(@RequestBody CheckRequest req) { return zoning.runCheck(req.territoryId(), CurrentUser.require()); }

    @GetMapping("/checks")
    public List<Map<String, Object>> checks() { return zoning.checks(); }

    @GetMapping("/discrepancies")
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID territoryId, @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String status, @RequestParam(required = false) Boolean present,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return zoning.list(new ZoningService.Filter(territoryId, type, status, present, from, to));
    }

    @GetMapping("/discrepancies/{id}")
    public Map<String, Object> get(@PathVariable UUID id) { return zoning.get(id); }

    /** Смена статуса с комментарием и прикреплением акта (БП-4: «прикрепляет акт, назначает статус»). */
    @PostMapping(value = "/discrepancies/{id}/status", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> status(@PathVariable UUID id, @RequestParam String status, @RequestParam(required = false) String comment,
                                      @RequestParam(required = false) MultipartFile file) throws IOException {
        CurrentUser u = CurrentUser.require();
        String name = null, path = null;
        if (file != null && !file.isEmpty()) {
            name = Paths.get(Objects.requireNonNull(file.getOriginalFilename())).getFileName().toString();
            Path dir = Paths.get(props.storageDir(), "attachments", id.toString());
            Files.createDirectories(dir);
            Path p = dir.resolve(UUID.randomUUID() + "_" + name.replaceAll("[^\\p{L}\\p{N}._-]", "_"));
            file.transferTo(p.toAbsolutePath());
            path = p.toString();
        }
        return zoning.changeStatus(id, status, comment, name, path, u);
    }

    @GetMapping("/discrepancies/{id}/attachments/{historyId}")
    public ResponseEntity<byte[]> attachment(@PathVariable UUID id, @PathVariable long historyId) throws IOException {
        Map<String, Object> a = zoning.attachment(id, historyId);
        if (a.get("attachment_path") == null) throw ApiException.notFound("Файл");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename((String) a.get("attachment_name"), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(Files.readAllBytes(Paths.get((String) a.get("attachment_path"))));
    }

    @GetMapping("/report")
    public ResponseEntity<byte[]> report(@RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(required = false) UUID territoryId,
                                         @RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) throws IOException {
        CurrentUser u = CurrentUser.require();
        if (!"xlsx".equalsIgnoreCase(format) && !"pdf".equalsIgnoreCase(format))
            throw ApiException.bad("Неподдерживаемый формат отчёта «" + format + "». Доступны: xlsx, pdf");
        var f = new ZoningService.Filter(territoryId, type, status, null, from, to);
        String terr = territoryId == null ? null : db.query("SELECT title FROM spatial.features WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, territoryId);
        boolean pdf = "pdf".equalsIgnoreCase(format);
        byte[] body = pdf ? reports.pdf(f, terr, u) : reports.xlsx(f, terr, u);
        audit.log(u, "report_export", "zoning_report", territoryId == null ? "all" : territoryId.toString(), Map.of("format", pdf ? "pdf" : "xlsx"));
        String fname = "zoning-report-" + LocalDate.now() + (pdf ? ".pdf" : ".xlsx");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fname).build().toString())
                .contentType(pdf ? MediaType.APPLICATION_PDF
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
