package kg.nsdi.geoportal.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Ролевая модель (ЧТЗ п. 5.1.1.6–5.1.1.7): anon / authorized / staff / admin.
 * Токен доступа — JWT (HS256), выдаётся после входа через Infocom ID / Түндүк
 * (в MVP — демонстрационный вход, см. AuthController).
 */
@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Значения по умолчанию из application.yml и docker-compose.yml — публично известны, в рабочем режиме недопустимы. */
    static final Set<String> DEFAULT_JWT_SECRETS = Set.of(
            "dev-only-secret-change-me-in-production-0123456789",
            "change-me-change-me-change-me-change-me-0123");
    static final int MIN_JWT_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtKey(AppProperties props) {
        return new SecretKeySpec(checkJwtSecret(props.jwtSecret(), props.devLogin()), "HmacSHA256");
    }

    /**
     * HS256 требует ключ не короче 32 байт. При выключенном демо-входе (DEV_LOGIN=false — рабочий режим)
     * секрет по умолчанию тоже запрещён: им можно подписать токен администратора.
     */
    static byte[] checkJwtSecret(String secret, boolean devLogin) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        String hint = " Задайте собственный JWT_SECRET не короче " + MIN_JWT_SECRET_BYTES + " байт, например: openssl rand -hex 32";
        String error = null;
        if (!devLogin && (secret == null || DEFAULT_JWT_SECRETS.contains(secret)))
            error = "JWT_SECRET равен значению по умолчанию, а DEV_LOGIN=false: запуск остановлен.";
        else if (bytes.length < MIN_JWT_SECRET_BYTES)
            error = "JWT_SECRET короче " + MIN_JWT_SECRET_BYTES + " байт (" + bytes.length + "): запуск остановлен.";
        if (error != null) {
            log.error(error + hint);
            throw new IllegalStateException(error + hint);
        }
        return bytes;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });

        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/catalog/**", "/api/tiles/**", "/api/features/**", "/api/search/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/search/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("admin")
                .requestMatchers("/api/zoning/**", "/api/imports/**", "/api/my/**", "/api/layers/**").hasAnyRole("staff", "admin")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
