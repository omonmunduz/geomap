package kg.nsdi.geoportal.imports;

import kg.nsdi.geoportal.auth.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private final ImportService service;
    private final ImportRunner runner;

    public ImportController(ImportService service, ImportRunner runner) { this.service = service; this.runner = runner; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam UUID layerId, @RequestParam("file") MultipartFile file) throws IOException {
        return service.upload(layerId, file.getOriginalFilename(), file.getInputStream(), CurrentUser.require());
    }

    @PostMapping("/{id}/validate")
    public Map<String, Object> validate(@PathVariable UUID id, @RequestBody ImportService.ValidateRequest req) {
        return service.validate(id, req, CurrentUser.require());
    }

    @GetMapping("/{id}/errors.csv")
    public ResponseEntity<byte[]> errors(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.require();
        service.status(id, u);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-errors-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(service.errorsCsv(id).getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/commit")
    public Map<String, Object> commit(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.require();
        service.markQueued(id, u);
        runner.run(id, u);
        return service.status(id, u);
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable UUID id) {
        return service.status(id, CurrentUser.require());
    }
}
