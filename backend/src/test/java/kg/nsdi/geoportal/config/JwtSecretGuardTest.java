package kg.nsdi.geoportal.config;

import kg.nsdi.geoportal.GeoportalApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.*;

/** Защита секрета подписи токенов: при DEV_LOGIN=false бэкенд не стартует с секретом по умолчанию или коротким. */
@ExtendWith(OutputCaptureExtension.class)
class JwtSecretGuardTest {

    private static final String STRONG = "3f9c1e7a5b2d4c6e8f0a1b3c5d7e9f1a2b4c6d8e0f1a3b5c";

    @ParameterizedTest(name = "DEV_LOGIN=false, секрет по умолчанию «{0}» → отказ")
    @ValueSource(strings = {"dev-only-secret-change-me-in-production-0123456789", "change-me-change-me-change-me-change-me-0123"})
    void defaultSecretRejectedInProduction(String secret) {
        assertThatIllegalStateException().isThrownBy(() -> SecurityConfig.checkJwtSecret(secret, false))
                .withMessageContaining("JWT_SECRET равен значению по умолчанию")
                .withMessageContaining("openssl rand -hex 32");
    }

    @Test
    @DisplayName("Секрет короче 32 байт → отказ (в любом режиме); 32 байта кириллицы считаются в байтах UTF-8")
    void shortSecretRejected() {
        assertThatIllegalStateException().isThrownBy(() -> SecurityConfig.checkJwtSecret("short-secret", false))
                .withMessageContaining("короче 32 байт (12)");
        assertThatIllegalStateException().isThrownBy(() -> SecurityConfig.checkJwtSecret("short-secret", true))
                .withMessageContaining("короче 32 байт");
        assertThatIllegalStateException().isThrownBy(() -> SecurityConfig.checkJwtSecret(null, false));
        assertThat(SecurityConfig.checkJwtSecret("секрет-секрет-секрет", false)).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    @DisplayName("Демо-режим (DEV_LOGIN=true) допускает секрет по умолчанию; собственный секрет допустим всегда")
    void allowedCombinations() {
        assertThatNoException().isThrownBy(() -> SecurityConfig.checkJwtSecret("dev-only-secret-change-me-in-production-0123456789", true));
        assertThatNoException().isThrownBy(() -> SecurityConfig.checkJwtSecret(STRONG, false));
    }

    @Test
    @DisplayName("Приложение целиком: DEV_LOGIN=false без JWT_SECRET не стартует, в логе понятная ошибка; с собственным секретом — стартует")
    void applicationStartup(CapturedOutput output) {
        PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
        try (db) {
            db.start();
            String[] common = {
                    "--spring.datasource.url=" + db.getJdbcUrl(), "--spring.datasource.username=" + db.getUsername(),
                    "--spring.datasource.password=" + db.getPassword(), "--server.port=0", "--app.seed.enabled=false",
                    "--app.dev-login=false"};

            // секрет не задан → значение по умолчанию из application.yml
            Throwable failure = catchThrowable(() -> run(common));
            assertThat(failure).as("приложение не должно стартовать").isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET равен значению по умолчанию, а DEV_LOGIN=false");
            assertThat(output.getOut() + output.getErr()).contains("JWT_SECRET равен значению по умолчанию, а DEV_LOGIN=false: запуск остановлен");

            String[] withSecret = java.util.Arrays.copyOf(common, common.length + 1);
            withSecret[common.length] = "--app.jwt-secret=" + STRONG;
            try (ConfigurableApplicationContext ctx = run(withSecret)) {
                assertThat(ctx.isRunning()).isTrue();
            }
        }
    }

    private static ConfigurableApplicationContext run(String... args) {
        return new SpringApplicationBuilder(GeoportalApplication.class).run(args);
    }
}
