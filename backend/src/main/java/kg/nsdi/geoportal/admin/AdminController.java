package kg.nsdi.geoportal.admin;

import kg.nsdi.geoportal.audit.AuditService;
import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.common.Json;
import kg.nsdi.geoportal.common.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** UC-07 «Администрирование доступа», журнал событий, справочник правил зонирования (ЧТЗ п. 5.1.1.7, 5.1.1.9). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate db;
    private final AuditService audit;
    private final Json json;

    public AdminController(JdbcTemplate db, AuditService audit, Json json) { this.db = db; this.audit = audit; this.json = json; }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Rows.camel(db.queryForMap("""
            SELECT (SELECT count(*) FROM core.users) AS users,
                   (SELECT count(*) FROM catalog.layers) AS layers,
                   (SELECT count(*) FROM catalog.layers WHERE published_version IS NOT NULL) AS published_layers,
                   (SELECT count(*) FROM spatial.features f JOIN catalog.layers l ON l.id=f.layer_id AND f.version=l.published_version WHERE NOT f.deleted) AS published_features,
                   (SELECT count(*) FROM jobs.import_jobs) AS imports,
                   (SELECT count(*) FROM zoning.discrepancies) AS discrepancies,
                   (SELECT count(*) FROM audit.events WHERE at > now() - interval '24 hours') AS events_24h,
                   (SELECT count(*) FROM audit.events WHERE outcome <> 'success' AND at > now() - interval '24 hours') AS denied_24h"""));
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return db.queryForList("""
            SELECT u.id, u.ext_subject, u.display_name, u.email, u.position, u.role, u.status, u.created_at, u.last_login_at,
                   o.short_name AS org FROM core.users u LEFT JOIN core.organizations o ON o.id=u.org_id ORDER BY u.display_name""")
            .stream().map(Rows::camel).toList();
    }

    public record UserPatch(String role, String status) {}

    @PatchMapping("/users/{id}")
    @Transactional
    public Map<String, Object> patchUser(@PathVariable UUID id, @RequestBody UserPatch p) {
        CurrentUser me = CurrentUser.require();
        if (id.equals(me.id())) throw ApiException.bad("Нельзя изменить собственную роль или статус");
        Map<String, Object> u = db.queryForMap("SELECT role, status, org_id FROM core.users WHERE id=?", id);
        if (p.role() != null) {
            if (!List.of("authorized", "staff", "admin").contains(p.role())) throw ApiException.bad("Неизвестная роль");
            if (!"authorized".equals(p.role()) && u.get("org_id") == null)
                throw ApiException.bad("Служебная роль требует организации (по данным Е-Кызмат)");
            db.update("UPDATE core.users SET role=? WHERE id=?", p.role(), id);
        }
        if (p.status() != null) {
            if (!List.of("active", "blocked").contains(p.status())) throw ApiException.bad("Неизвестный статус");
            db.update("UPDATE core.users SET status=? WHERE id=?", p.status(), id);
        }
        audit.log(me, "user_update", "user", id, Map.of("from", u.get("role") + "/" + u.get("status"),
                "to", String.valueOf(p.role()) + "/" + p.status()));
        return Rows.camel(db.queryForMap("SELECT id, display_name, role, status FROM core.users WHERE id=?", id));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "200") int limit, @RequestParam(required = false) String action,
                                           @RequestParam(required = false) String outcome) {
        StringBuilder sql = new StringBuilder("SELECT id, at, user_name, org_code, action, object_type, object_id, outcome, details::text AS details, ip FROM audit.events WHERE true");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) { sql.append(" AND action=?"); args.add(action); }
        if (outcome != null && !outcome.isBlank()) { sql.append(" AND outcome=?"); args.add(outcome); }
        sql.append(" ORDER BY at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 1000));
        return db.queryForList(sql.toString(), args.toArray()).stream()
                .map(r -> { var m = Rows.camel(r); m.put("details", json.tree((String) r.get("details"))); return m; }).toList();
    }

    public record Rule(String zoneType, String useType, boolean allowed) {}

    @PutMapping("/zoning-rules")
    @Transactional
    public List<Map<String, Object>> rules(@RequestBody List<Rule> rules) {
        for (Rule r : rules)
            db.update("INSERT INTO zoning.rules(zone_type,use_type,allowed) VALUES (?,?,?) ON CONFLICT (zone_type,use_type) DO UPDATE SET allowed=EXCLUDED.allowed",
                    r.zoneType(), r.useType(), r.allowed());
        audit.log(CurrentUser.require(), "zoning_rules_update", "zoning_rules", null, Map.of("count", rules.size()));
        return db.queryForList("SELECT zone_type, use_type, allowed FROM zoning.rules").stream().map(Rows::camel).toList();
    }
}
