package kg.nsdi.geoportal.auth;

import jakarta.servlet.http.HttpServletRequest;
import kg.nsdi.geoportal.audit.AuditService;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * БП-1 «Аутентификация и авторизация сотрудника» (ЧТЗ п. 4.4.1).
 * MVP: демонстрационный вход эмулирует ответ Infocom ID (subject) и запрос в Е-Кызмат.
 * Реальный OIDC Authorization Code Flow подключается заменой этого контроллера (см. документацию, раздел «Интеграции»).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate db;
    private final EmploymentRegistry registry;
    private final TokenService tokens;
    private final AuditService audit;
    private final AppProperties props;

    public AuthController(JdbcTemplate db, EmploymentRegistry registry, TokenService tokens, AuditService audit, AppProperties props) {
        this.db = db; this.registry = registry; this.tokens = tokens; this.audit = audit; this.props = props;
    }

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        List<Map<String, Object>> accounts = new ArrayList<>();
        if (props.devLogin()) {
            for (var a : registry.demoAccounts()) {
                String org = a.orgCode() == null ? null : db.query("SELECT short_name FROM core.organizations WHERE code=?",
                        rs -> rs.next() ? rs.getString(1) : null, a.orgCode());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subject", a.subject()); m.put("displayName", a.displayName()); m.put("position", a.position());
                m.put("org", org); m.put("provider", a.provider()); m.put("active", a.active());
                accounts.add(m);
            }
        }
        return Map.of("devLogin", props.devLogin(), "accounts", accounts);
    }

    public record LoginRequest(String subject) {}

    @PostMapping("/dev-login")
    public Map<String, Object> devLogin(@RequestBody LoginRequest req, HttpServletRequest http) {
        if (!props.devLogin()) throw ApiException.forbidden("Демонстрационный вход отключён");
        var emp = registry.lookup(req.subject())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Учётная запись не найдена"));
        String ip = http.getRemoteAddr();

        boolean citizen = "tunduk".equals(emp.provider());
        UUID orgId = null; String orgShort = null;
        if (!citizen) {
            if (!emp.active() || emp.orgCode() == null) {
                audit.log(null, emp.displayName(), emp.orgCode(), "login_denied", "user", emp.subject(), "denied",
                        Map.of("reason", "is_active=false в Е-Кызмат"), ip);
                throw ApiException.forbidden("Доступ к служебному контуру недоступен для вашей учётной записи. Обратитесь к администратору Геопортала");
            }
            Map<String, Object> org = db.queryForMap("SELECT id, short_name FROM core.organizations WHERE code=?", emp.orgCode());
            orgId = (UUID) org.get("id"); orgShort = (String) org.get("short_name");
        }

        // создать при первом входе / обновить при повторном
        List<Map<String, Object>> existing = db.queryForList("SELECT id, role, status FROM core.users WHERE ext_subject=?", emp.subject());
        UUID userId; String role;
        if (existing.isEmpty()) {
            role = citizen ? "authorized" : (emp.bootstrapAdmin() ? "admin" : "staff");
            userId = db.queryForObject("INSERT INTO core.users(ext_subject,email,display_name,position,org_id,role,last_login_at) " +
                    "VALUES (?,?,?,?,?,?,now()) RETURNING id", UUID.class,
                    emp.subject(), emp.email(), emp.displayName(), emp.position(), orgId, role);
        } else {
            var u = existing.get(0);
            if ("blocked".equals(u.get("status"))) {
                audit.log((UUID) u.get("id"), emp.displayName(), emp.orgCode(), "login_denied", "user", emp.subject(), "denied",
                        Map.of("reason", "учётная запись заблокирована"), ip);
                throw ApiException.forbidden("Учётная запись заблокирована администратором");
            }
            userId = (UUID) u.get("id"); role = (String) u.get("role");
            db.update("UPDATE core.users SET display_name=?, position=?, org_id=?, last_login_at=now() WHERE id=?",
                    emp.displayName(), emp.position(), orgId, userId);
        }
        CurrentUser cu = new CurrentUser(userId, emp.displayName(), role, orgId, emp.orgCode(), orgShort);
        audit.log(userId, cu.name(), cu.orgCode(), "login", "user", userId.toString(), "success", Map.of("provider", emp.provider()), ip);
        return Map.of("token", tokens.issue(cu), "user", cu);
    }

    @GetMapping("/me")
    public Object me() {
        CurrentUser u = CurrentUser.get();
        return u == null ? Map.of("anonymous", true) : u;
    }
}
