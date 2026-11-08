package kg.nsdi.geoportal.audit;

import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

/** Журнал событий и ИБ (ЧТЗ п. 5.6). В MVP — таблица audit.events в PostgreSQL. */
@Service
public class AuditService {
    private final JdbcTemplate db;
    private final Json json;

    public AuditService(JdbcTemplate db, Json json) { this.db = db; this.json = json; }

    public void log(UUID userId, String userName, String orgCode, String action, String objectType, String objectId,
                    String outcome, Map<String, ?> details, String ip) {
        db.update("INSERT INTO audit.events(user_id,user_name,org_code,action,object_type,object_id,outcome,details,ip) " +
                  "VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
                userId, userName, orgCode, action, objectType, objectId, outcome, json.write(details), ip);
    }

    /** Событие от имени текущего пользователя. */
    public void log(CurrentUser u, String action, String objectType, Object objectId, Map<String, ?> details) {
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) ip = a.getRequest().getRemoteAddr();
        log(u == null ? null : u.id(), u == null ? null : u.name(), u == null ? null : u.orgCode(),
            action, objectType, objectId == null ? null : objectId.toString(), "success", details, ip);
    }
}
