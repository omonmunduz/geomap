package kg.nsdi.geoportal.auth;

import kg.nsdi.geoportal.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/** Пользователь текущего запроса (из JWT). null — аноним. */
public record CurrentUser(UUID id, String name, String role, UUID orgId, String orgCode, String orgName) {

    public boolean isAdmin() { return "admin".equals(role); }
    public boolean isStaff() { return "staff".equals(role) || isAdmin(); }

    public static CurrentUser get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof JwtAuthenticationToken t) {
            Jwt j = t.getToken();
            String org = j.getClaimAsString("orgId");
            return new CurrentUser(UUID.fromString(j.getSubject()), j.getClaimAsString("name"), j.getClaimAsString("role"),
                    org == null ? null : UUID.fromString(org), j.getClaimAsString("orgCode"), j.getClaimAsString("orgName"));
        }
        return null;
    }

    public static CurrentUser require() {
        CurrentUser u = get();
        if (u == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Требуется вход в систему");
        return u;
    }

    public static boolean staff(CurrentUser u) { return u != null && u.isStaff(); }
}
