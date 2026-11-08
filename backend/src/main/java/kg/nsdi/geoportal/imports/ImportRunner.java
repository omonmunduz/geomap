package kg.nsdi.geoportal.imports;

import kg.nsdi.geoportal.auth.CurrentUser;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Фоновое выполнение импорта («Импорт запущен» → статус в «Мои слои»). */
@Component
public class ImportRunner {
    private final ImportService service;

    public ImportRunner(ImportService service) { this.service = service; }

    @Async
    public void run(UUID jobId, CurrentUser u) {
        try { service.commit(jobId, u); } catch (RuntimeException ignored) { /* статус failed уже записан */ }
    }
}
